package rocks.pkb.plektra.inputmethod.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import rocks.pkb.plektra.R
import rocks.pkb.plektra.T2eCornerCalibration
import rocks.pkb.plektra.T2eCornerGeometry
import rocks.pkb.plektra.inputmethod.StatusBarController
import kotlin.math.roundToInt

/**
 * Compact controller around the LED strip at the bottom of the IME status bar.
 */
class LedStatusView(
    private val context: Context
) {
    companion object {
        internal const val LED_ZONE_HEIGHT_DP = 6.5f
        internal const val CONTOUR_LED_STROKE_DP = 1.4f
        internal const val CONTOUR_LED_GAP_DP = 2.2f
        internal const val CONTOUR_BUTTON_GAP_DP = 2.2f
        private const val CONTOUR_EDGE_PADDING_DP = 1f
        private const val SEGMENT_DIVIDER_DP = 1f
        private const val LED_CONTENT_HEIGHT_DP = 5.5f
        private const val LED_TOP_PADDING_DP = LED_ZONE_HEIGHT_DP - LED_CONTENT_HEIGHT_DP
        private val LED_COLOR_GRAY_OFF = Color.argb(100, 17, 17, 17)
        private val LED_COLOR_RED_LOCKED = Color.rgb(247, 99, 0)
        private val LED_COLOR_BLUE_ACTIVE = Color.rgb(100, 150, 255)
    }

    private val ledHeight: Int by lazy {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            LED_CONTENT_HEIGHT_DP,
            context.resources.displayMetrics
        ).toInt().coerceAtLeast(8)
    }
    private val topPadding: Int by lazy {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            LED_TOP_PADDING_DP,
            context.resources.displayMetrics
        ).toInt()
    }
    private val cornerRadius: Float by lazy {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            3f,
            context.resources.displayMetrics
        )
    }

    private var container: ModifierLedCanvas? = null
    var followDisplayContour: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            container?.requestLayout()
            invalidateGeometry()
        }
    var compactOnly: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            container?.compactOnly = value
        }
    private val ledsByState = mutableMapOf<ModifierLedState, MutableList<View>>()
    private val segmentsByView = mutableMapOf<View, ModifierLedSegment>()

    var bottomCornerRadiiPx: Pair<Int, Int>? = null
        set(value) {
            if (field == value) return
            field = value
            barGeometry.ledRadii = value
            container?.cornerRadiiPx = value
            container?.let { canvas ->
                for (index in 0 until canvas.childCount) canvas.getChildAt(index).invalidate()
            }
        }

    internal var layout: ModifierLedLayout = ModifierLedLayouts.DEFAULT
        set(value) {
            if (field == value) return
            field = value
            rebuildSegments()
        }

    var contourIntegrated: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            rebuildSegments()
            container?.invalidate()
        }

    internal var barGeometry = rocks.pkb.plektra.inputmethod.KeyboardBarGeometry(context)
        set(value) { field = value; invalidateGeometry() }

    private var geometryInvalidationKey: List<Any?>? = null

    internal fun invalidateGeometry() {
        barGeometry.ledRadii = bottomCornerRadiiPx
        barGeometry.ledLayout = layout
        barGeometry.ledContentHeight = ledHeight.toFloat()
        barGeometry.ledTopPadding = topPadding.toFloat()
        barGeometry.followDisplayContour = followDisplayContour
        val frame = geometryFrame()
        barGeometry.prepare(frame.width, frame.height)
        val key = listOf(barGeometry, barGeometry.revision, frame)
        if (key == geometryInvalidationKey) return
        geometryInvalidationKey = key
        container?.let { canvas ->
            for (index in 0 until canvas.childCount) canvas.getChildAt(index).invalidate()
            canvas.invalidate()
        }
    }

    private data class GeometryFrame(val width: Float, val height: Float, val x: Float, val y: Float)

    private fun geometryFrame(): GeometryFrame {
        val view = container ?: return GeometryFrame(0f, 0f, 0f, 0f)
        var ancestor = view.parent
        while (ancestor != null && ancestor !is rocks.pkb.plektra.inputmethod.ImeChromeLayout) ancestor = ancestor.parent
        val chrome = ancestor as? rocks.pkb.plektra.inputmethod.ImeChromeLayout
            ?: return GeometryFrame(view.width.toFloat(), view.height.toFloat(), 0f, 0f)
        val location = IntArray(2).also(view::getLocationInWindow)
        val origin = IntArray(2).also(chrome::getLocationInWindow)
        return GeometryFrame(chrome.width.toFloat(), chrome.height.toFloat(),
            (location[0] - origin[0]).toFloat(), (location[1] - origin[1]).toFloat())
    }

    var onLongPressListener: (() -> Unit)? = null
    var themeOverride: KeyboardThemeColors? = null

    fun ensureView(): ViewGroup {
        container?.let { return it }

        container = ModifierLedCanvas(context, ledHeight).apply {
            compactOnly = this@LedStatusView.compactOnly
            cornerRadiiPx = bottomCornerRadiiPx
            setPadding(0, topPadding, 0, 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnLongClickListener {
                onLongPressListener?.invoke()
                true
            }
        }

        rebuildSegments()
        invalidateGeometry()

        return container!!
    }

    fun getView(): ViewGroup? = container

    internal fun reservedStripHeightPx(): Int {
        val calibration = T2eCornerCalibration.read(context)
        val clearance = if (bottomCornerRadiiPx != null)
            kotlin.math.ceil((calibration.offsetPx - calibration.shiftYPx).coerceAtLeast(0f)).toInt()
            else 0
        return topPadding + ledHeight + clearance
    }

    fun update(snapshot: StatusBarController.StatusSnapshot) {
        val shiftLocked = snapshot.capsLockEnabled
        val shiftActive = (snapshot.shiftPhysicallyPressed || snapshot.shiftOneShot) && !shiftLocked
        updateLeds(ModifierLedState.SHIFT, shiftLocked, shiftActive)

        val ctrlLocked = snapshot.ctrlLatchActive
        val ctrlActive = (snapshot.ctrlPhysicallyPressed || snapshot.ctrlOneShot) && !ctrlLocked
        updateLeds(ModifierLedState.CTRL, ctrlLocked, ctrlActive)

        val altLocked = snapshot.altLatchActive
        val altActive = (snapshot.altPhysicallyPressed || snapshot.altOneShot) && !altLocked
        updateLeds(ModifierLedState.ALT, altLocked, altActive)

        updateSymLeds(snapshot.symPage, snapshot.symPhysicallyPressed)
    }

    private fun rebuildSegments() {
        invalidateGeometry()
        val canvas = container ?: return
        ledsByState.clear()
        segmentsByView.clear()
        canvas.replaceSegments(layout.segments) { segment ->
            createLedView(themeOverride?.ledInactive ?: LED_COLOR_GRAY_OFF, segment).also { led ->
                ledsByState.getOrPut(segment.state) { mutableListOf() }.add(led)
            }
        }
    }

    private fun createLedView(initialColor: Int, segment: ModifierLedSegment): View {
        return View(context).apply {
            segmentsByView[this] = segment
            background = createDrawable(initialColor, segment)
            setTag(R.id.led_previous_color, initialColor)
        }
    }

    private fun createDrawable(color: Int, segment: ModifierLedSegment): GradientDrawable {
        return object : GradientDrawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }

            override fun draw(canvas: Canvas) {
                val radii = bottomCornerRadiiPx
                if (radii == null) {
                    super.draw(canvas)
                    return
                }
                val density = context.resources.displayMetrics.density
                paint.strokeWidth = barGeometry.ledStrokeWidth(segment)
                val frame = geometryFrame()
                val section = barGeometry.ledSectionPath(frame.width, frame.height, segment)
                val save = canvas.save()
                canvas.translate(-frame.x, -frame.y)
                canvas.drawPath(section, paint)
                canvas.restoreToCount(save)
            }
        }.apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = this@LedStatusView.cornerRadius
        }
    }

    private fun updateLeds(state: ModifierLedState, isLocked: Boolean, isActive: Boolean = false) {
        val theme = themeOverride
        val targetColor = when {
            isLocked -> theme?.ledLocked ?: LED_COLOR_RED_LOCKED
            isActive -> theme?.ledActive ?: LED_COLOR_BLUE_ACTIVE
            else -> theme?.ledInactive ?: LED_COLOR_GRAY_OFF
        }
        ledsByState[state].orEmpty().forEach { led -> animateLedColor(led, targetColor) }
    }

    private fun updateSymLeds(symPage: Int, physicallyPressed: Boolean) {
        val theme = themeOverride
        val targetColor = when (symPage) {
            1 -> theme?.ledActive ?: LED_COLOR_BLUE_ACTIVE
            2 -> theme?.ledLocked ?: LED_COLOR_RED_LOCKED
            3 -> theme?.ledActive ?: LED_COLOR_BLUE_ACTIVE
            4 -> theme?.ledActive ?: LED_COLOR_BLUE_ACTIVE
            else -> if (physicallyPressed) {
                theme?.ledActive ?: LED_COLOR_BLUE_ACTIVE
            } else {
                theme?.ledInactive ?: LED_COLOR_GRAY_OFF
            }
        }
        ledsByState[ModifierLedState.SYM].orEmpty().forEach { led -> animateLedColor(led, targetColor) }
    }

    private fun animateLedColor(led: View?, targetColor: Int) {
        led ?: return
        val previousColor = (led.getTag(R.id.led_previous_color) as? Int) ?: LED_COLOR_GRAY_OFF
        led.setTag(R.id.led_previous_color, targetColor)

        if (previousColor == targetColor) {
            led.background = createDrawable(targetColor, segmentsByView.getValue(led))
            return
        }

        ValueAnimator.ofArgb(previousColor, targetColor).apply {
            duration = 200
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val color = animator.animatedValue as Int
                segmentsByView[led]?.let { led.background = createDrawable(color, it) }
            }
        }.start()
    }

    private inner class ModifierLedCanvas(
        context: Context,
        private val contentHeightPx: Int
    ) : ViewGroup(context) {
        private val segments = mutableListOf<ModifierLedSegment>()
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (cornerRadiiPx != null && event.actionMasked == MotionEvent.ACTION_DOWN) {
                val frame = geometryFrame()
                if (!barGeometry.ledContains(frame.width, frame.height, event.x + frame.x, event.y + frame.y)) return false
            }
            return super.dispatchTouchEvent(event)
        }

        var cornerRadiiPx: Pair<Int, Int>? = null
            set(value) {
                field = value
                requestLayout()
            }

        var compactOnly: Boolean = false
            set(value) {
                field = value
                requestLayout()
                invalidate()
            }

        fun replaceSegments(
            newSegments: List<ModifierLedSegment>,
            createView: (ModifierLedSegment) -> View
        ) {
            removeAllViews()
            segments.clear()
            newSegments.forEach { segment ->
                segments.add(segment)
                addView(createView(segment))
            }
            requestLayout()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val measuredWidth = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
            val calibration = T2eCornerCalibration.read(context)
            val curveHeight = if (followDisplayContour) cornerRadiiPx?.let {
                kotlin.math.ceil(maxOf(it.first, it.second) * calibration.size).toInt()
            } ?: 0 else 0
            val calibrationClearance = if (cornerRadiiPx != null)
                kotlin.math.ceil((calibration.offsetPx - calibration.shiftYPx).coerceAtLeast(0f)).toInt()
                else 0
            val desiredHeight = paddingTop + maxOf(contentHeightPx, curveHeight) + calibrationClearance + paddingBottom
            val measuredHeight = resolveSize(desiredHeight, heightMeasureSpec)
            val contentWidth = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0)
            val availableHeight = (measuredHeight - paddingTop - paddingBottom).coerceAtLeast(0)

            for (index in 0 until childCount) {
                val child = getChildAt(index)
                val segment = segments[index]
                if (cornerRadiiPx != null) {
                    child.measure(
                        MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
                    )
                    continue
                }
                child.measure(
                    MeasureSpec.makeMeasureSpec(
                        (contentWidth * segment.width).roundToInt().coerceAtLeast(1),
                        MeasureSpec.EXACTLY
                    ),
                    MeasureSpec.makeMeasureSpec(
                        (availableHeight * segment.height).roundToInt().coerceAtLeast(1),
                        MeasureSpec.EXACTLY
                    )
                )
            }
            setMeasuredDimension(measuredWidth, measuredHeight)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val contentWidth = (right - left - paddingLeft - paddingRight).coerceAtLeast(0)
            val availableHeight = (bottom - top - paddingTop - paddingBottom).coerceAtLeast(0)
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                val segment = segments[index]
                if (cornerRadiiPx != null) {
                    child.layout(0, 0, right - left, bottom - top)
                    continue
                }
                val childLeft = paddingLeft + (contentWidth * segment.x).roundToInt()
                val childTop = paddingTop + (availableHeight * segment.y).roundToInt()
                child.layout(
                    childLeft,
                    childTop,
                    childLeft + child.measuredWidth,
                    childTop + child.measuredHeight
                )
            }
        }

    }
}
