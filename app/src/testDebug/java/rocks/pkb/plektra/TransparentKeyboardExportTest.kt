package rocks.pkb.plektra

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import rocks.pkb.plektra.inputmethod.ImeChromeLayout
import rocks.pkb.plektra.inputmethod.ui.LedStatusView
import rocks.pkb.plektra.inputmethod.ui.ModifierLedLayouts

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransparentKeyboardExportTest {
    @Test fun visualCardBackgroundIsPreserved() {
        val context = RuntimeEnvironment.getApplication()
        val chrome = ImeChromeLayout(context)
        val card = android.widget.FrameLayout(context).apply { setBackgroundColor(Color.BLUE) }
        chrome.addView(card, LinearLayout.LayoutParams(100, 80))
        chrome.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
        chrome.layout(0, 0, 100, 80)
        val bitmap = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888)
        chrome.drawTransparentArtwork(Canvas(bitmap))
        assertEquals(Color.BLUE, bitmap.getPixel(50, 40))
        bitmap.recycle()
    }

    @Test fun nativeExportRetainsBothRailsAndLeavesBackgroundTransparent() {
        val context = RuntimeEnvironment.getApplication()
        val chrome = ImeChromeLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.MAGENTA)
        }
        val led = LedStatusView(context).apply {
            layout = ModifierLedLayouts.TITAN_2_ELITE
            bottomCornerRadiiPx = 100 to 100
            compactOnly = true
            followDisplayContour = true
        }.ensureView()
        val row = LinearLayout(context).apply { setBackgroundColor(Color.BLACK); setTag(R.id.keyboard_artwork_container, true); addView(led) }
        val rowBackground = row.background
        chrome.addView(row)
        chrome.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST))
        chrome.layout(0, 0, 1080, chrome.measuredHeight)
        val beforeBackground = chrome.background
        val bitmap = Bitmap.createBitmap(1080, chrome.height, Bitmap.Config.ARGB_8888)
        chrome.drawTransparentArtwork(Canvas(bitmap))
        val reference = Bitmap.createBitmap(led.width, led.height, Bitmap.Config.ARGB_8888)
        led.draw(Canvas(reference))
        var foreground = 0
        for (y in 0 until led.height) for (x in 0 until led.width) {
            assertEquals("Native child pixels survive chrome export at $x,$y",
                reference.getPixel(x, y), bitmap.getPixel(x, y + led.top))
            if (Color.alpha(reference.getPixel(x, y)) > 0) foreground++
        }
        assertTrue(foreground > 100)
        for (y in 0 until bitmap.height) assertEquals(0, Color.alpha(bitmap.getPixel(540, y)))
        reference.recycle()
        assertSame(beforeBackground, chrome.background)
        assertSame(rowBackground, row.background)
        assertEquals(1080, chrome.width)
        bitmap.recycle()
    }
}
