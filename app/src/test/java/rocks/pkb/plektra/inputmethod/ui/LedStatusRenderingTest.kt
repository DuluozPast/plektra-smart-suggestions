package rocks.pkb.plektra.inputmethod.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LedStatusRenderingTest {
    private fun render(curved: Boolean, segmentIndex: Int? = null): Bitmap {
        val led = LedStatusView(RuntimeEnvironment.getApplication()).apply {
            layout = ModifierLedLayouts.TITAN_2_ELITE
            bottomCornerRadiiPx = 100 to 100
            compactOnly = true
            followDisplayContour = curved
        }
        val view = led.ensureView()
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
            if (segmentIndex == null) view.draw(Canvas(it))
            else view.getChildAt(segmentIndex).draw(Canvas(it))
        }
    }

    @Test fun straightRailsBothRemainVisibleAndAvoidPhysicalCorners() {
        val bitmap = render(false)
        var bands = 0
        var previous = false
        for (y in 0 until bitmap.height) {
            val visible = (140..160).any { Color.alpha(bitmap.getPixel(it, y)) > 0 }
            if (visible && !previous) bands++
            previous = visible
            assertEquals(0, Color.alpha(bitmap.getPixel(0, y)))
        }
        assertEquals("Both separate LED rows must be drawn", 2, bands)
    }

    @Test fun curvedRailsRiseAlongTheSidesWithoutButtons() {
        val bitmap = render(true)
        assertTrue(bitmap.height >= 100)
        assertTrue((0 until bitmap.height / 2).any { y ->
            (0..40).any { x -> Color.alpha(bitmap.getPixel(x, y)) > 0 }
        })
        assertEquals(0, Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)))
    }

    @Test fun ledOnlyContoursStopAtTheDisplayCurveOnBothSides() {
        val bitmap = render(true)
        var leftEnd = -1
        var rightStart = bitmap.width
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width / 2) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) leftEnd = maxOf(leftEnd, x)
            }
            for (x in bitmap.width / 2 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) rightStart = minOf(rightStart, x)
            }
        }
        assertTrue("Left contour must be visible", leftEnd >= 0)
        assertTrue("Right contour must be visible", rightStart < bitmap.width)
        val leftExtent = leftEnd + 1
        val rightExtent = bitmap.width - rightStart
        val calibration = rocks.pkb.plektra.T2eCornerCalibration.read(RuntimeEnvironment.getApplication())
        assertEquals("Left rail stops at the calibrated 100px display corner",
            100f * calibration.size, leftExtent.toFloat(), 3f)
        assertEquals("Right rail stops at the display corner, without a straight extension",
            100f * calibration.size, rightExtent.toFloat(), 3f)
        assertEquals("Equal display corners have equal rail extents", leftExtent.toFloat(), rightExtent.toFloat(), 3f)
    }

    @Test fun splitAndContinuousRightRailsEndInAlignment() {
        val inner = render(true, 1)
        val outerTop = render(true, 4)
        val outerBottom = render(true, 3)
        fun limits(bitmap: Bitmap): Pair<Int, Int> {
            var minX = bitmap.width
            var minY = bitmap.height
            for (y in 0 until bitmap.height) for (x in bitmap.width / 2 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) {
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                }
            }
            return minX to minY
        }
        assertEquals("Upper ends must share a y coordinate", limits(inner).second, limits(outerTop).second)
        assertEquals("Lower ends must share an x coordinate", limits(inner).first, limits(outerBottom).first)
    }

    @Test @Config(qualifiers = "xhdpi")
    fun innerRailLeavesVisibleSpaceAroundTheButtonIncludingItsLowerCurve() {
        val geometry = rocks.pkb.plektra.inputmethod.KeyboardBarGeometry(RuntimeEnvironment.getApplication()).apply {
            radii = 100 to 100
            ledRadii = 100 to 100
            ledsVisible = true
            ledLayout = ModifierLedLayouts.TITAN_2_ELITE
            leftSurface = rocks.pkb.plektra.inputmethod.KeyboardBarGeometry.OuterSurface(
                android.graphics.RectF(0f, 0f, 180f, 140f), 12f)
        }
        val button = geometry.preparedSurface(1000f, 140f,
            android.graphics.RectF(0f, 0f, 180f, 140f), 12f, true, false).region
        val rail = android.graphics.PathMeasure(geometry.ledRailPath(1000f, 140f,
            ModifierLedLayouts.TITAN_2_ELITE.segments.first()), false)
        val point = FloatArray(2)
        var lowerSamples = 0
        for (sample in 1 until 40) {
            assertTrue(rail.getPosTan(rail.length * sample / 40f, point, null))
            if (point[1] > 100f) lowerSamples++
            for (dx in -3..3) for (dy in -3..3) {
                if (dx * dx + dy * dy <= 9) assertFalse(
                    "Inner rail must leave space to the button at ${point[0]},${point[1]}",
                    button.contains(kotlin.math.round(point[0]).toInt() + dx,
                        kotlin.math.round(point[1]).toInt() + dy))
            }
        }
        assertTrue("The lower curve must be checked as well", lowerSamples > 5)
    }
}
