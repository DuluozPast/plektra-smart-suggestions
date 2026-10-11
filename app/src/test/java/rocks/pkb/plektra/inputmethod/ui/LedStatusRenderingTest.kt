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

    @Test fun ledOnlyContoursStopAtTheOuterPhysicalSlotsOnBothSides() {
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
        assertEquals("Left rail follows its 16.4% outer slot", bitmap.width * 0.164f, leftExtent.toFloat(), 3f)
        assertEquals("Right rail follows its 16.0% outer slot, not the interior Ctrl slot",
            bitmap.width * 0.160f, rightExtent.toFloat(), 3f)
        assertEquals("Only the physical slot width difference may remain",
            bitmap.width * 0.004f, (leftExtent - rightExtent).toFloat(), 3f)
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
}
