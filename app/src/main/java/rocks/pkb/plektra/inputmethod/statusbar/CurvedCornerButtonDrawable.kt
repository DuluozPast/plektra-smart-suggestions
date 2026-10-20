package rocks.pkb.plektra.inputmethod.statusbar

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import rocks.pkb.plektra.inputmethod.ImeChromeLayout
import rocks.pkb.plektra.inputmethod.KeyboardBarGeometry

/** Draws the prepared surface; color changes do not rebuild its geometry. */
internal class CurvedCornerButtonDrawable(
    private val view: View,
    private var normalColor: Int,
    private var pressedColor: Int,
    private val cornerRadius: Float,
    private var borderColor: Int?,
    private val borderWidth: Int,
    val leftEdge: Boolean,
    val rightEdge: Boolean = !leftEdge,
    private var texture: rocks.pkb.plektra.KeyboardThemeTexture? = null
) : Drawable() {
    private var drawableAlpha = 255
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val texturePainter = rocks.pkb.plektra.ThemeTexturePainter(view.context, owner = { view }) { invalidateSelf() }
    private val textureBounds = RectF()
    fun updateTexture(value: rocks.pkb.plektra.KeyboardThemeTexture?) {
        if (texture != value) { texture = value; invalidateSelf() }
    }
    private val standaloneGeometry = KeyboardBarGeometry(view.context)
    private var geometry = standaloneGeometry
    private var surface: KeyboardBarGeometry.PreparedSurface? = null
    private var surfaceRevision = -1L
    private var frameKey: List<Any>? = null
    private val buttonPath = Path()
    private var originX = 0f
    private var originY = 0f
    private var lastPlacementKey: List<Any>? = null
    private var lastPlacement = ContentPlacement(0f, 0f, 1f)
    data class ContentPlacement(val x: Float, val y: Float, val scale: Float, val fits: Boolean = true)

    fun matchesStyle(radius: Float, stroke: Int) = radius == cornerRadius && stroke == borderWidth
    fun updateColors(normal: Int, pressed: Int, border: Int?) {
        if (normal == normalColor && pressed == pressedColor && border == borderColor) return
        normalColor = normal; pressedColor = pressed; borderColor = border
        invalidateSelf()
    }

    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean { invalidateSelf(); return true }

    private fun prepareSurface() {
        var ancestor = view.parent
        while (ancestor != null && ancestor !is ImeChromeLayout) ancestor = ancestor.parent
        val chrome = ancestor as? ImeChromeLayout
        geometry = chrome?.geometry ?: standaloneGeometry
        val width = chrome?.width?.toFloat() ?: view.width.toFloat()
        val height = chrome?.height?.toFloat() ?: view.height.toFloat()
        geometry.prepare(width, height)
        val location = IntArray(2).also(view::getLocationInWindow)
        val origin = IntArray(2).also { chrome?.getLocationInWindow(it) }
        val x = if (chrome != null) (location[0] - origin[0]).toFloat() else 0f
        val y = if (chrome != null) (location[1] - origin[1]).toFloat() else 0f
        val key = listOf<Any>(geometry, geometry.revision, x, y, view.width, view.height)
        if (key == frameKey) return
        frameKey = key; originX = x; originY = y
        val halfStroke = borderWidth / 2f
        val rect = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()).apply {
            inset(halfStroke, halfStroke); offset(x, y)
        }
        surface = geometry.preparedSurface(width, height, rect, cornerRadius, leftEdge, rightEdge, halfStroke)
        surfaceRevision = geometry.revision
        buttonPath.set(surface!!.path)
        buttonPath.offset(-x, -y)
        lastPlacementKey = null
    }

    fun placementRevision(): Pair<Long, Float> {
        prepareSurface()
        return surfaceRevision to geometry.minimumContentScale
    }

    fun contentCenter(contentWidth: Float, contentHeight: Float, preferredX: Float,
                      preferredY: Float, leftEdge: Boolean): ContentPlacement {
        prepareSurface()
        val key = listOf<Any>(surfaceRevision, originX, originY, contentWidth, contentHeight,
            geometry.minimumContentScale, preferredX, preferredY)
        if (key == lastPlacementKey) return lastPlacement
        lastPlacement = fitContentAt(contentWidth, contentHeight, visibleCenter())
        lastPlacementKey = key
        return lastPlacement
    }

    fun fitContentAt(contentWidth: Float, contentHeight: Float, preferred: PointF,
                     obstacles: List<RectF> = emptyList()): ContentPlacement {
        prepareSurface()
        val globalObstacles = obstacles.map { RectF(it).apply { offset(originX, originY) } }
        val placement = geometry.placeInk(surface!!, contentWidth, contentHeight,
            PointF(preferred.x + originX, preferred.y + originY),
            2f * view.resources.displayMetrics.density + borderWidth / 2f,
            obstacles = globalObstacles)
        return ContentPlacement(placement.x - originX, placement.y - originY, placement.scale, placement.fits)
    }

    override fun draw(canvas: Canvas) {
        prepareSurface()
        paint.style = Paint.Style.FILL
        paint.color = if (state.contains(android.R.attr.state_pressed)) pressedColor else normalColor
        paint.alpha = android.graphics.Color.alpha(paint.color) * drawableAlpha / 255
        canvas.drawPath(buttonPath, paint)
        if (texture != null) {
            textureBounds.set(bounds)
            texturePainter.draw(canvas, textureBounds, buttonPath, texture, drawableAlpha)
            if (state.contains(android.R.attr.state_pressed)) {
                paint.color = pressedColor; paint.alpha = 100 * drawableAlpha / 255
                canvas.drawPath(buttonPath, paint)
            }
        }
        if (borderColor != null && borderWidth > 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = borderWidth.toFloat()
            paint.color = borderColor!!
            paint.alpha = android.graphics.Color.alpha(paint.color) * drawableAlpha / 255
            canvas.drawPath(buttonPath, paint)
        }
    }

    fun visibleCenter(): PointF {
        prepareSurface()
        return PointF(surface!!.center.x - originX, surface!!.center.y - originY)
    }

    override fun setAlpha(alpha: Int) { drawableAlpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
