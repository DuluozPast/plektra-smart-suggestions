package rocks.pkb.plektra

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object ThemeTextureBackground {
    fun set(view: android.view.View?, color: Int, texture: KeyboardThemeTexture?) {
        view ?: return
        view.background = if (texture == null) android.graphics.drawable.ColorDrawable(color)
            else ThemeTextureDrawable(view.context, color, texture)
    }
}

internal object ThemeTextureMetrics {
    private val scales = androidx.compose.runtime.mutableStateMapOf<String, Float>()
    private val times = mutableMapOf<String, Long>()
    private fun key(texture: KeyboardThemeTexture) = texture.asset + ":" + texture.mode
    fun scale(texture: KeyboardThemeTexture): Float = scales[key(texture)] ?: 1f
    fun record(texture: KeyboardThemeTexture, scale: Float) {
        val key = key(texture); val now = android.os.SystemClock.uptimeMillis()
        val next = if (now - (times[key] ?: 0L) > 1000) scale else maxOf(scale, scales[key] ?: scale)
        times[key] = now
        if (kotlin.math.abs(next - (scales[key] ?: -1f)) > 0.01f) scales[key] = next
        if (times.size > 128) { val old = times.minByOrNull { it.value }!!.key; times.remove(old); scales.remove(old) }
    }
}

internal object ThemeTextureStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val pending = mutableMapOf<String, MutableList<() -> Unit>>()
    private val failures = linkedSetOf<String>()
    private fun key(context: Context, asset: String) = context.filesDir.path + "/" + asset

    fun file(context: Context, asset: String): File {
        require(ThemeTextureModel.assetPattern.matches(asset))
        return File(File(context.filesDir, "theme-images").apply { mkdirs() }, asset)
    }

    fun invalidate() = synchronized(pending) { cache.evictAll(); failures.clear() }

    fun peek(context: Context, asset: String): Bitmap? = cache.get(key(context, asset))

    fun request(context: Context, asset: String, invalidate: () -> Unit) {
        if (!ThemeTextureModel.assetPattern.matches(asset)) return
        val key = key(context, asset)
        synchronized(pending) {
            if (cache.get(key) != null || key in failures) return
            pending[key]?.let { if (it.size < 96) it.add(invalidate); return }
            pending[key] = mutableListOf(invalidate)
        }
        val app = context.applicationContext
        scope.launch {
            val bitmap = try {
                val source = file(app, asset)
                require(source.length() in 1..2_097_152L)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                source.inputStream().buffered().use { BitmapFactory.decodeStream(it, null, bounds) }
                require(bounds.outMimeType == "image/png" && bounds.outWidth in 1..ThemeTextureModel.MAX_EDGE && bounds.outHeight in 1..ThemeTextureModel.MAX_EDGE)
                source.inputStream().buffered().use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
            val callbacks = synchronized(pending) {
                if (bitmap != null) cache.put(key, bitmap)
                else { failures.add(key); if (failures.size > 128) failures.remove(failures.first()) }
                pending.remove(key).orEmpty()
            }
            withContext(Dispatchers.Main) { callbacks.forEach { it() } }
        }
    }

    suspend fun save(context: Context, bitmap: Bitmap): KeyboardThemeTexture = withContext(Dispatchers.IO) {
        require(bitmap.width in 1..ThemeTextureModel.MAX_EDGE && bitmap.height in 1..ThemeTextureModel.MAX_EDGE)
        val directory = File(context.filesDir, "theme-images").apply { mkdirs() }
        val temporary = File.createTempFile("texture-saving-", ".png", directory)
        try {
            temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            require(temporary.length() in 1..2_097_152L)
            val digest = MessageDigest.getInstance("SHA-256")
            temporary.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            val asset = "texture-" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } + ".png"
            val target = file(context, asset)
            if (!target.exists()) {
                val total = directory.listFiles().orEmpty().filter { ThemeTextureModel.assetPattern.matches(it.name) }.sumOf { it.length() }
                require(total + temporary.length() <= 64L * 1024 * 1024) { "Texture storage limit reached" }
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            cache.put(key(context, asset), bitmap)
            synchronized(pending) { failures.remove(key(context, asset)) }
            KeyboardThemeTexture(asset)
        } finally { temporary.delete() }
    }
}

/** One painter per surface, no decoding or image resampling on the UI thread. */
internal class ThemeTexturePainter(private val context: Context, private val owner: () -> android.view.View? = { null }, private val invalidate: () -> Unit) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = false }
    private val matrix = Matrix()
    private var previous: Bitmap? = null
    private var previousMode: String? = null
    private var shader: BitmapShader? = null

    fun draw(canvas: Canvas, bounds: RectF, path: Path, texture: KeyboardThemeTexture?, alpha: Int = 255) {
        if (texture == null || bounds.isEmpty) return
        val bitmap = ThemeTextureStore.peek(context, texture.asset)
        if (bitmap == null) { ThemeTextureStore.request(context, texture.asset, invalidate); return }
        if (previous !== bitmap || previousMode != texture.mode) {
            val tile = if (texture.mode == "tile") Shader.TileMode.REPEAT else Shader.TileMode.CLAMP
            shader = BitmapShader(bitmap, tile, tile)
            previous = bitmap; previousMode = texture.mode
        }
        val frame = if(texture.continuous) ThemeTextureFrame.bounds(owner(), bounds, texture.wholeKeyboard) else bounds
        val scale = ThemeTextureModel.renderScale(bitmap.width, bitmap.height, frame.width(), frame.height(), texture.mode)
        ThemeTextureMetrics.record(texture, scale)
        val x = frame.left + (frame.width() - bitmap.width * scale) / 2f
        val y = frame.top + (frame.height() - bitmap.height * scale) / 2f
        matrix.setScale(scale, scale); matrix.postTranslate(x, y)
        shader!!.setLocalMatrix(matrix)
        paint.shader = shader; paint.alpha = ThemeTextureModel.renderAlpha(texture, alpha)
        val save = canvas.save()
        canvas.clipPath(path)
        if (texture.mode == "fit") canvas.clipRect(x, y, x + bitmap.width * scale, y + bitmap.height * scale)
        canvas.drawRect(bounds, paint)
        canvas.restoreToCount(save)
    }
}

internal class ThemeTextureDrawable(
    context: Context,
    private val color: Int,
    private val texture: KeyboardThemeTexture?,
    private val radius: Float = 0f,
    private val border: Int? = null,
    private val borderWidth: Float = 0f,
    private val pressedColor: Int? = null
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val painter = ThemeTexturePainter(context, owner = {
        var target: Drawable.Callback? = callback
        while(target is Drawable) target = target.callback
        target as? android.view.View
    }) { invalidateSelf() }
    private var opacity = 255
    override fun isStateful() = pressedColor != null
    override fun onStateChange(state: IntArray): Boolean { invalidateSelf(); return true }
    override fun draw(canvas: Canvas) {
        rect.set(bounds); rect.inset(borderWidth / 2, borderWidth / 2)
        path.reset(); path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        paint.style = Paint.Style.FILL; paint.color = color; paint.alpha = Color.alpha(color) * opacity / 255
        canvas.drawPath(path, paint)
        painter.draw(canvas, rect, path, texture, opacity)
        if (pressedColor != null && state.contains(android.R.attr.state_pressed)) {
            paint.color = pressedColor; paint.alpha = 100 * opacity / 255; canvas.drawPath(path, paint)
        }
        if (border != null && borderWidth > 0) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = borderWidth
            paint.color = border; paint.alpha = Color.alpha(border) * opacity / 255; canvas.drawPath(path, paint)
        }
    }
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

internal object ThemeTextureFrame {
    fun bounds(owner: android.view.View?, fallback: RectF, wholeKeyboard: Boolean = false): RectF {
        if(owner==null) return RectF(fallback)
        var current: android.view.View? = owner
        var frame: android.view.View? = null
        while(current!=null) {
            val chrome = current.getTag(R.id.theme_texture_frame)==true || current.javaClass.simpleName=="ImeChromeLayout"
            val keyboard = current.javaClass.simpleName=="AospKeyboardView"
            if(chrome || keyboard) {
                frame=current
                if(chrome || !wholeKeyboard) break
            }
            current=current.parent as? android.view.View
        }
        val root=frame ?: return RectF(fallback)
        if(root.width<=0 || root.height<=0) return RectF(fallback)
        val origin=IntArray(2); val local=IntArray(2)
        root.getLocationInWindow(origin);owner.getLocationInWindow(local)
        return RectF((origin[0]-local[0]).toFloat(),(origin[1]-local[1]).toFloat(),
            (origin[0]-local[0]+root.width).toFloat(),(origin[1]-local[1]+root.height).toFloat())
    }
}
