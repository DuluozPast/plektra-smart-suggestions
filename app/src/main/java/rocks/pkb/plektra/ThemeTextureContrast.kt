package rocks.pkb.plektra

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow

/** Contrast is calculated on demand in the editor, never while typing. */
internal object ThemeTextureContrast {
    data class Choice(val foreground: Int, val halo: Boolean)
    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double { val s=value/255.0; return if(s<=0.04045) s/12.92 else ((s+0.055)/1.055).pow(2.4) }
        return .2126*channel(Color.red(color))+.7152*channel(Color.green(color))+.0722*channel(Color.blue(color))
    }
    fun choose(colors: IntArray): Choice {
        require(colors.isNotEmpty())
        val levels=colors.map(::luminance).sorted()
        // A lower percentile penalises dark joints/light flecks rather than hiding them in a mean.
        fun score(white: Boolean): Double {
            val ratios=levels.map { if(white) 1.05/(it+.05) else (it+.05)/.05 }.sorted()
            return 100.0*ratios.count { it>=4.5 }/ratios.size+ratios[(ratios.lastIndex*.10).toInt()]+.05*ratios[ratios.size/2]
        }
        val white=score(true)>score(false)
        val ratios=levels.map { if(white) 1.05/(it+.05) else (it+.05)/.05 }.sorted()
        return Choice(if(white) Color.WHITE else Color.BLACK, ratios[(ratios.lastIndex*.10).toInt()]<4.5)
    }
    suspend fun propose(context: Context, texture: KeyboardThemeTexture, base: Int): KeyboardThemeTexture = withContext(Dispatchers.IO) {
        val file=ThemeTextureStore.file(context,texture.asset)
        require(file.length() in 1..2_097_152L)
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(file.path,bounds)
        require(bounds.outWidth in 1..512 && bounds.outHeight in 1..512)
        val bitmap=requireNotNull(BitmapFactory.decodeFile(file.path))
        try {
            val samples=ArrayList<Int>()
            val step=maxOf(1,minOf(bitmap.width,bitmap.height)/32)
            for(y in 0 until bitmap.height step step) for(x in 0 until bitmap.width step step) {
                val pixel=bitmap.getPixel(x,y); val a=ThemeTextureModel.renderAlpha(texture, Color.alpha(pixel))
                fun blend(p:Int,b:Int)=(p*a+b*(255-a))/255
                samples.add(Color.rgb(blend(Color.red(pixel),Color.red(base)),blend(Color.green(pixel),Color.green(base)),blend(Color.blue(pixel),Color.blue(base))))
            }
            if(texture.mode=="fit") repeat(maxOf(1,samples.size/3)) { samples.add(base) }
            val choice=choose(samples.toIntArray())
            texture.copy(foreground=choice.foreground,halo=choice.halo)
        } finally { bitmap.recycle() }
    }
    fun color(base: Int, texture: KeyboardThemeTexture?)=texture?.foreground ?: base
    fun haloColor(texture: KeyboardThemeTexture?): Int? = if(texture?.foreground!=null && texture.halo)
        (if(texture.foreground==Color.BLACK) Color.WHITE else Color.BLACK) else null
    fun text(view: TextView, base: Int, texture: KeyboardThemeTexture?) {
        view.setTextColor(color(base,texture))
        val halo=haloColor(texture)
        if(halo==null) view.setShadowLayer(0f,0f,0f,Color.TRANSPARENT)
        else view.setShadowLayer(view.resources.displayMetrics.density*1.3f,0f,0f,halo)
    }
    fun paint(paint: Paint, texture: KeyboardThemeTexture?, density: Float) {
        texture?.foreground?.let { paint.color=it }
        val halo=haloColor(texture)
        if(halo==null) paint.clearShadowLayer() else paint.setShadowLayer(density*1.3f,0f,0f,halo)
    }
    fun icon(canvas: Canvas, icon: Drawable, texture: KeyboardThemeTexture?, base: Int, density: Float) {
        haloColor(texture)?.let { color ->
            icon.setTint(color)
            val shift=density*.8f
            for((x,y) in listOf(-shift to 0f,shift to 0f,0f to -shift,0f to shift)) {
                val saved=canvas.save(); canvas.translate(x,y); icon.draw(canvas); canvas.restoreToCount(saved)
            }
        }
        icon.setTint(color(base,texture)); icon.draw(canvas)
    }
}

/** Keeps ImageView layout and hit targets unchanged; only its icon pixels gain a halo. */
internal class ThemeContrastIcon(val source: Drawable, private val texture: KeyboardThemeTexture, private val base: Int, private val density: Float): Drawable() {
    override fun draw(canvas: Canvas) { source.bounds=bounds; ThemeTextureContrast.icon(canvas,source,texture,base,density) }
    override fun getIntrinsicWidth()=source.intrinsicWidth
    override fun getIntrinsicHeight()=source.intrinsicHeight
    override fun setAlpha(alpha:Int) { source.alpha=alpha }
    override fun setColorFilter(filter:ColorFilter?) { source.colorFilter=filter }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
