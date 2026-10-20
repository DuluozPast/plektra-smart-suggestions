package rocks.pkb.plektra

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One bounded reference is backed up; the optional original stays in the private disposable cache. */
internal object ThemePhotoImageStore {
    const val MAX_EDGE = 1024
    // Keep the reference/original pair consistent across a cancelled import or activity recreation.
    private val sourceMutex = Mutex()
    fun directory(context: Context) = File(context.cacheDir, "theme-photos").apply { mkdirs() }
    fun reference(context: Context) = File(File(context.filesDir, "theme-images").apply { mkdirs() }, "reference.png")

    fun capture(context: Context): File {
        val folder = directory(context)
        folder.listFiles().orEmpty().filter { it.name.startsWith("capture-") && System.currentTimeMillis() - it.lastModified() > 86_400_000L }
            .forEach { it.delete() }
        return File.createTempFile("capture-", ".jpg", folder)
    }

    fun deleteCapture(context: Context, name: String?) {
        if (name != null && name.matches(Regex("capture-[A-Za-z0-9-]+\\.jpg"))) File(directory(context), name).delete()
    }

    fun boundedSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = min(1.0, MAX_EDGE.toDouble() / max(width, height))
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    private fun decode(source: ImageDecoder.Source): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val (width, height) = boundedSize(info.size.width, info.size.height)
        decoder.setTargetSize(width, height)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
    }

    private fun digest(file: File): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun retainSource(context: Context, uri: Uri) {
        val temp = File.createTempFile("source-", ".tmp", directory(context))
        try {
            context.contentResolver.openInputStream(uri)!!.use { input -> temp.outputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0L
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    total += count; require(total <= 32L * 1024 * 1024)
                    output.write(buffer, 0, count)
                }
            } }
            Files.move(temp.toPath(), File(directory(context), "original-source").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            File(directory(context), "source-reference.sha256").writeText(digest(reference(context)))
        } catch (_: Exception) {
            // A restored reference or an oversized original remains usable at reference resolution.
            File(directory(context), "source-reference.sha256").delete()
        } finally { temp.delete() }
    }

    suspend fun texture(context: Context, region: RectF): KeyboardThemeTexture = withContext(Dispatchers.IO) {
        sourceMutex.withLock {
        val original = File(directory(context), "original-source")
        val marker = File(directory(context), "source-reference.sha256")
        val reference = reference(context)
        val source = if (original.isFile && original.length() <= 32L * 1024 * 1024 && marker.isFile &&
            marker.readText() == digest(reference)) original else reference
        val sample = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
            val plan = ThemeTextureModel.cropPlan(info.size.width, info.size.height, region)
            decoder.setTargetSize(plan.fullWidth, plan.fullHeight)
            decoder.crop = plan.crop
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
        coroutineContext.ensureActive()
        ThemeTextureStore.save(context, sample)
        }
    }

    suspend fun import(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        // ImageDecoder applies encoded orientation while decoding directly to the target size.
        val bitmap = decode(ImageDecoder.createSource(context.contentResolver, uri))
        val temporary = File.createTempFile("normalizing-", ".png", directory(context))
        try {
            temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            coroutineContext.ensureActive()
            sourceMutex.withLock {
                coroutineContext.ensureActive()
                Files.move(temporary.toPath(), reference(context).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                retainSource(context, uri)
            }
            bitmap
        } finally { temporary.delete() }
    }

    suspend fun load(context: Context): Bitmap? = withContext(Dispatchers.IO) {
        reference(context).takeIf { it.isFile }?.let { decode(ImageDecoder.createSource(it)) }
    }

    /** A 5×5 mean, weighted by alpha; a transparent sample has no selectable colour. */
    fun sample(bitmap: Bitmap, x: Int, y: Int, radius: Int = 2): Int? {
        require(radius in 0..8)
        if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) return null
        var alpha = 0L; var red = 0L; var green = 0L; var blue = 0L
        for (row in max(0, y-radius)..min(bitmap.height-1, y+radius)) {
            for (column in max(0, x-radius)..min(bitmap.width-1, x+radius)) {
                val color = bitmap.getPixel(column, row)
                val a = color ushr 24
                alpha += a; red += (color shr 16 and 255).toLong()*a
                green += (color shr 8 and 255).toLong()*a; blue += (color and 255).toLong()*a
            }
        }
        if (alpha == 0L) return null
        return android.graphics.Color.rgb(((red+alpha/2)/alpha).toInt(), ((green+alpha/2)/alpha).toInt(), ((blue+alpha/2)/alpha).toInt())
    }
}

internal data class ThemePhotoViewport(val scale: Float, val pan: Offset) {
    companion object {
        fun fit(image: IntSize, viewport: IntSize, zoom: Float, pan: Offset): ThemePhotoViewport {
            if (image.width <= 0 || image.height <= 0 || viewport.width <= 0 || viewport.height <= 0) return ThemePhotoViewport(1f, Offset.Zero)
            val scale = min(viewport.width.toFloat()/image.width, viewport.height.toFloat()/image.height) * zoom.coerceIn(1f, 12f)
            // A pipette must reach every pixel, including corners. Keep the reticle
            // inside the image, rather than forcing the whole viewport to stay filled.
            val maxX = max(0f, (image.width-1)*scale/2f)
            val maxY = max(0f, (image.height-1)*scale/2f)
            return ThemePhotoViewport(scale, Offset(pan.x.coerceIn(-maxX,maxX),pan.y.coerceIn(-maxY,maxY)))
        }
    }
    fun samplePosition(image: IntSize): Pair<Int, Int> =
        (image.width/2f-pan.x/scale).toInt().coerceIn(0,image.width-1) to
            (image.height/2f-pan.y/scale).toInt().coerceIn(0,image.height-1)

    fun region(image: IntSize, frame: IntSize): RectF = RectF(
        ((image.width/2f - frame.width/2f/scale - pan.x/scale)/image.width).coerceIn(0f,1f),
        ((image.height/2f - frame.height/2f/scale - pan.y/scale)/image.height).coerceIn(0f,1f),
        ((image.width/2f + frame.width/2f/scale - pan.x/scale)/image.width).coerceIn(0f,1f),
        ((image.height/2f + frame.height/2f/scale - pan.y/scale)/image.height).coerceIn(0f,1f)
    )
}

internal fun textureViewport(image: IntSize, viewport: IntSize, zoom: Float, pan: Offset, frame: IntSize): ThemePhotoViewport {
    if (viewport.width <= 0 || viewport.height <= 0) return ThemePhotoViewport(1f, Offset.Zero)
    val fitted = ThemePhotoViewport.fit(image, viewport, zoom, pan)
    val scale = max(fitted.scale, max(frame.width.toFloat()/image.width, frame.height.toFloat()/image.height))
    val maxX = max(0f, (image.width*scale-frame.width)/2f)
    val maxY = max(0f, (image.height*scale-frame.height)/2f)
    return ThemePhotoViewport(scale, Offset(pan.x.coerceIn(-maxX,maxX),pan.y.coerceIn(-maxY,maxY)))
}

@Composable
internal fun ThemePhotoColorButton(onColorSelected: (Int) -> Unit,
    theme: KeyboardThemePreset? = null, surfaceRole: String? = null, isSoftware: Boolean = false,
    coupled: Boolean = false, onTextureSelected: ((KeyboardThemeTexture?) -> Unit)? = null) {
    var open by rememberSaveable { mutableStateOf(false) }
    var sampled by rememberSaveable { mutableStateOf<Int?>(null) }
    var candidate by rememberSaveable { mutableStateOf<String?>(null) }
    val global = surfaceRole == ThemeTextureModel.ALL_SURFACES
    val launchPicker: () -> Unit = {
        val existing = theme?.textures?.get(if(global) "background" else surfaceRole)
        if (existing != null) candidate = existing.asset else open = true
    }
    if(global) OutlinedButton(onClick = launchPicker, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.PhotoCamera, null)
        Text(stringResource(R.string.theme_texture_global), Modifier.padding(start=8.dp))
    } else IconButton(onClick = launchPicker, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Filled.PhotoCamera, stringResource(if(surfaceRole in ThemeTextureModel.roles) R.string.theme_texture_photo_title else R.string.theme_photo_button))
    }
    if (open) ThemePhotoColorDialog(onDismiss = { open = false }, onColorSelected = { sampled = it; open = false },
        allowTexture = theme != null && (surfaceRole in ThemeTextureModel.roles || global) && onTextureSelected != null,
        hasTexture = if(global) theme?.textures?.isNotEmpty() == true else theme?.textures?.containsKey(surfaceRole) == true,
        textureOnly = global,
        onRemoveTexture = { onTextureSelected?.invoke(null); open = false },
        onEditTexture = {
            candidate = theme?.textures?.get(if(global) "background" else surfaceRole)?.asset
            open = false
        },
        onTextureSelected = { candidate = it.asset; open = false })
    val asset = candidate
    if (asset != null && theme != null && surfaceRole != null) ThemeTextureReviewDialog(
        texture = theme.textures[if(global) "background" else surfaceRole]?.takeIf { it.asset == asset } ?: KeyboardThemeTexture(asset, continuous = !surfaceRole.startsWith("key_popup")), theme = theme, role = surfaceRole, isSoftware = isSoftware, coupled = coupled,
        onDismiss = { candidate = null }, onChangePhoto = { candidate = null; open = true }, onApply = { candidate = null; onTextureSelected?.invoke(it) })
    sampled?.let { color ->
        KeyboardThemeColorPickerDialog(initialColor = color, onDismiss = { sampled = null },
            onColorSelected = { sampled = null; onColorSelected(it) }, onPreviewColorChanged = {})
    }
}

@Composable
private fun ThemePhotoColorDialog(onDismiss: () -> Unit, onColorSelected: (Int) -> Unit,
    allowTexture: Boolean = false, hasTexture: Boolean = false, onRemoveTexture: () -> Unit = {},
    onEditTexture: () -> Unit = {},
    onTextureSelected: (KeyboardThemeTexture) -> Unit = {}, textureOnly: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var cameraName by rememberSaveable { mutableStateOf<String?>(null) }
    var zoom by rememberSaveable { mutableStateOf(1f) }
    var panX by rememberSaveable { mutableStateOf(0f) }
    var panY by rememberSaveable { mutableStateOf(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var imageJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val generation = remember { java.util.concurrent.atomic.AtomicInteger() }
    var matter by rememberSaveable { mutableStateOf(textureOnly) }
    var frameWidth by rememberSaveable { mutableStateOf(0.65f) }
    var frameHeight by rememberSaveable { mutableStateOf(0.65f) }
    val resizeLabel = stringResource(R.string.theme_texture_resize)

    fun loadImage(uri: Uri?, captureName: String? = null) {
        if (uri == null && imageJob?.isActive == true) return
        val request = generation.incrementAndGet()
        imageJob?.cancel()
        imageJob = scope.launch {
            loading = true; error = false
            try {
                val loaded = if (uri == null) ThemePhotoImageStore.load(context) else ThemePhotoImageStore.import(context, uri)
                if (loaded != null) { bitmap = loaded; if (uri != null) { zoom = 1f; panX = 0f; panY = 0f } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = true }
            catch (_: OutOfMemoryError) { error = true }
            finally { if (generation.get() == request) loading = false; ThemePhotoImageStore.deleteCapture(context, captureName) }
        }
    }
    LaunchedEffect(Unit) { loadImage(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadImage(uri)
    }
    val cameraContract = remember { object : ActivityResultContracts.TakePicture() {
        override fun createIntent(context: Context, input: Uri): Intent = super.createIntent(context,input).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newRawUri("photo", input)
        }
    } }
    val camera = rememberLauncherForActivityResult(cameraContract) { success ->
        val name = cameraName; cameraName = null
        if (success && name != null) {
            val uri = FileProvider.getUriForFile(context, context.packageName+".fileprovider", File(ThemePhotoImageStore.directory(context),name))
            loadImage(uri, name)
        } else ThemePhotoImageStore.deleteCapture(context, name)
    }
    val image = bitmap
    val frame = IntSize(max(1,(viewport.width*frameWidth).roundToInt()),max(1,(viewport.height*frameHeight).roundToInt()))
    val geometry = image?.let { if (matter) textureViewport(IntSize(it.width,it.height),viewport,zoom,Offset(panX,panY),frame)
        else ThemePhotoViewport.fit(IntSize(it.width,it.height),viewport,zoom,Offset(panX,panY)) }
    val color = if (image != null && geometry != null) {
        val (x,y) = geometry.samplePosition(IntSize(image.width,image.height))
        ThemePhotoImageStore.sample(image,x,y)
    } else null

    AlertDialog(onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(stringResource(if (allowTexture) R.string.theme_texture_photo_title else R.string.theme_photo_title)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max=570.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if (allowTexture && !textureOnly) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    FilterChip(modifier=Modifier.weight(1f),selected=!matter,onClick={matter=false},label={Text(stringResource(R.string.theme_texture_color),maxLines=2)})
                    FilterChip(modifier=Modifier.weight(1f),selected=matter,onClick={matter=true},label={Text(stringResource(R.string.theme_texture_matter),maxLines=2)})
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(modifier=Modifier.weight(1f), contentPadding=PaddingValues(horizontal=8.dp,vertical=6.dp), enabled=!loading, onClick={
                        try { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        catch (_: Exception) { error=true }
                    }) { Text(stringResource(R.string.theme_photo_gallery)) }
                    OutlinedButton(modifier=Modifier.weight(1f), contentPadding=PaddingValues(horizontal=8.dp,vertical=6.dp), enabled=!loading, onClick={
                        try {
                            val file=ThemePhotoImageStore.capture(context); cameraName=file.name
                            camera.launch(FileProvider.getUriForFile(context,context.packageName+".fileprovider",file))
                        } catch (_: Exception) { ThemePhotoImageStore.deleteCapture(context,cameraName); cameraName=null; error=true }
                    }) { Text(stringResource(R.string.theme_photo_camera)) }
                }
                if (bitmap == null && ThemePhotoImageStore.reference(context).isFile) TextButton(enabled=!loading,onClick={loadImage(null)}) {
                    Text(stringResource(R.string.theme_photo_reuse))
                }
                if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.theme_photo_loading)) }
                if (error) Text(stringResource(R.string.theme_photo_error),color=MaterialTheme.colorScheme.error)
                if (image != null && geometry != null) {
                    Text(stringResource(if (matter) R.string.theme_texture_help else R.string.theme_photo_help),style=MaterialTheme.typography.bodySmall)
                    val displayed = remember(image) { image.asImageBitmap() }
                    Box(Modifier.fillMaxWidth().height(250.dp)) {
                    Canvas(Modifier.fillMaxSize().clipToBounds().background(Color.Black)
                        .onSizeChanged { viewport=it }
                        .pointerInput(image, matter) {
                            detectTransformGestures { centroid, pan, factor, _ ->
                                val oldZoom=zoom
                                zoom=(zoom*factor).coerceIn(1f,12f)
                                val ratio=zoom/oldZoom
                                val relative=centroid-Offset(size.width/2f,size.height/2f)
                                val next=relative-(relative-Offset(panX,panY))*ratio+pan
                                val fit=if (matter) textureViewport(IntSize(image.width,image.height),IntSize(size.width,size.height),zoom,next,
                                    IntSize(max(1,(size.width*frameWidth).roundToInt()),max(1,(size.height*frameHeight).roundToInt())))
                                    else ThemePhotoViewport.fit(IntSize(image.width,image.height),IntSize(size.width,size.height),zoom,next)
                                panX=fit.pan.x; panY=fit.pan.y
                            }
                        }) {
                        val fit=if(matter) textureViewport(IntSize(image.width,image.height),IntSize(size.width.toInt(),size.height.toInt()),zoom,Offset(panX,panY),frame)
                            else ThemePhotoViewport.fit(IntSize(image.width,image.height),IntSize(size.width.toInt(),size.height.toInt()),zoom,Offset(panX,panY))
                        val width=max(1,(image.width*fit.scale).roundToInt()); val height=max(1,(image.height*fit.scale).roundToInt())
                        drawImage(displayed, dstOffset=IntOffset(((size.width-width)/2+fit.pan.x).roundToInt(),((size.height-height)/2+fit.pan.y).roundToInt()),dstSize=IntSize(width,height),filterQuality=androidx.compose.ui.graphics.FilterQuality.None)
                        if (matter) {
                            val left=(size.width-frame.width)/2f; val top=(size.height-frame.height)/2f
                            val shade=Color.Black.copy(alpha=0.5f)
                            drawRect(shade,Offset.Zero,androidx.compose.ui.geometry.Size(size.width,top))
                            drawRect(shade,Offset(0f,top+frame.height),androidx.compose.ui.geometry.Size(size.width,top))
                            drawRect(shade,Offset(0f,top),androidx.compose.ui.geometry.Size(left,frame.height.toFloat()))
                            drawRect(shade,Offset(left+frame.width,top),androidx.compose.ui.geometry.Size(left,frame.height.toFloat()))
                            drawRect(Color.White,Offset(left,top),androidx.compose.ui.geometry.Size(frame.width.toFloat(),frame.height.toFloat()),style=Stroke(2.dp.toPx()))
                        } else {
                        drawCircle(Color.Black,11.dp.toPx(),style=Stroke(4.dp.toPx()))
                        drawCircle(Color.White,11.dp.toPx(),style=Stroke(2.dp.toPx()))
                        drawLine(Color.White,center-Offset(16.dp.toPx(),0f),center+Offset(16.dp.toPx(),0f),2.dp.toPx())
                        drawLine(Color.White,center-Offset(0f,16.dp.toPx()),center+Offset(0f,16.dp.toPx()),2.dp.toPx())
                        }
                    }
                    if (matter) Box(Modifier.offset { IntOffset((viewport.width+frame.width)/2-14.dp.roundToPx(),(viewport.height+frame.height)/2-14.dp.roundToPx()) }
                        .size(28.dp).background(Color.White,androidx.compose.foundation.shape.CircleShape).semantics { contentDescription=resizeLabel }
                        .pointerInput(image) { detectDragGestures { change, amount ->
                            change.consume()
                            frameWidth=(frameWidth+2*amount.x/viewport.width.coerceAtLeast(1)).coerceIn(0.12f,0.95f)
                            frameHeight=(frameHeight+2*amount.y/viewport.height.coerceAtLeast(1)).coerceIn(0.12f,0.95f)
                        } },contentAlignment=Alignment.Center) { Text("↘",color=Color.Black) }
                    }
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.theme_photo_zoom)+" ×"+String.format(java.util.Locale.ROOT,"%.1f",zoom),style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick={zoom=1f;panX=0f;panY=0f}) { Text(stringResource(R.string.theme_photo_reset_view),style=MaterialTheme.typography.labelSmall) }
                    }
                    Slider(value=zoom,onValueChange={zoom=it},valueRange=1f..12f)
                    if (!matter) color?.let { selected -> Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Surface(Modifier.size(32.dp),color=Color(selected),border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outline)) {}
                        Text(String.format(java.util.Locale.ROOT,"#%06X",selected and 0xffffff))
                    } }
                }
                if (allowTexture && hasTexture) OutlinedButton(enabled=!loading,onClick=onEditTexture) {
                    Text(stringResource(R.string.theme_texture_edit_existing))
                }
                if (allowTexture && hasTexture) TextButton(enabled=!loading,onClick=onRemoveTexture) { Text(stringResource(R.string.theme_texture_remove)) }
            }
        },
        confirmButton={ TextButton(enabled=image!=null && !loading && (matter || color!=null),onClick={
            if (!matter) color?.let(onColorSelected)
            else if (image!=null && geometry!=null) scope.launch {
                loading=true; error=false
                try { onTextureSelected(ThemePhotoImageStore.texture(context,geometry.region(IntSize(image.width,image.height),frame))) }
                catch(cancelled:CancellationException) { throw cancelled }
                catch(_:Exception) { error=true }
                catch(_:OutOfMemoryError) { error=true }
                finally { loading=false }
            }
        }) { Text(stringResource(if(matter) R.string.theme_texture_sample else R.string.theme_photo_use_color)) } },
        dismissButton={TextButton(enabled=!loading,onClick=onDismiss) { Text(stringResource(R.string.cancel)) }}
    )
}
