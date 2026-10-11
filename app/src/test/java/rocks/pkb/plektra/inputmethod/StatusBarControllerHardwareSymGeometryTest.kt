package rocks.pkb.plektra.inputmethod

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.statusbar.KeyboardBarRowGeometry
import rocks.pkb.plektra.inputmethod.ui.ModifierLedLayouts
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w478dp-h850dp-360dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusBarControllerHardwareSymGeometryTest {
    @Test
    fun actualSymPagesOwnTheirCloseSlotAndKeepNeighborAndCompositeInkIntact() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        SettingsManager.setTitan2LayoutEnabled(activity, true)
        SettingsManager.setPhysicalKeyboardProfileOverride(activity, "titan2elite_qwerty")
        SettingsManager.setTitan2EliteRoundedCornerInsetsEnabled(activity, true)
        SettingsManager.setSoftwareKeyboardMode(activity, SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE)
        val controller = StatusBarController(activity, clipboardHistoryManager = rocks.pkb.plektra.clipboard.ClipboardHistoryManager(activity))
        var closes = 0
        controller.onSymCloseRequested = { closes++ }
        val mappings = mapOf(KeyEvent.KEYCODE_Q to "0", KeyEvent.KEYCODE_A to "*",
            KeyEvent.KEYCODE_Z to "7", KeyEvent.KEYCODE_M to ".")
        for (page in listOf(1, 2, 5, 1)) {
            controller.update(snapshot(page), symMappings = mappings)
            val chrome = controller.field("statusBarLayout") as ImeChromeLayout
            activity.setContentView(chrome)
            chrome.bottomCornerRadiiPx = 110 to 110
            chrome.contourIntegratedIndicators = true
            val keyboard = controller.field("emojiKeyboardContainer") as ViewGroup
            keyboard.translationY = 0f
            layout(chrome)
            val bottom = keyboard.getChildAt(2) as ViewGroup
            val first = bottom.getChildAt(0)
            val m = (0 until bottom.childCount).map(bottom::getChildAt).first { key ->
                val group = (key as? ViewGroup)?.getChildAt(0) as? ViewGroup
                (group?.getChildAt(1) as? TextView)?.text?.toString() == "M"
            }
            val slot = bottom.getChildAt(bottom.childCount - 1) as FrameLayout
            val close = controller.field("symSurfaceCloseButton") as View
            assertSame("Close belongs to the real trailing grid slot", slot, close.parent)
            assertEquals(first.width, close.width)
            assertEquals(first.height, close.height)
            assertEquals(first.width, m.width)
            val closeBounds = globalBounds(chrome, close)
            assertEquals(closeBounds, Rect().also { chrome.geometry.rightSurface!!.bounds.round(it) })
            val mBounds = globalBounds(chrome, m)
            assertTrue("Close does not overlap M", mBounds.right <= closeBounds.left)
            val mWithLeds = region(KeyboardBarRowGeometry.canvasSurface(m,
                android.graphics.RectF(0f, 0f, m.width.toFloat(), m.height.toFloat()), 6f, false, false).path, m)
            chrome.geometry.ledsVisible = false
            val mWithoutLeds = region(KeyboardBarRowGeometry.canvasSurface(m,
                android.graphics.RectF(0f, 0f, m.width.toFloat(), m.height.toFloat()), 6f, false, false).path, m)
            assertTrue("M's shape remains identical", Region(mWithLeds).apply { op(mWithoutLeds, Region.Op.XOR) }.isEmpty)
            chrome.geometry.ledsVisible = true
            for (segment in ModifierLedLayouts.TITAN_2_ELITE.segments.filter { it.x >= 0.5f }) {
                val bounds = android.graphics.RectF()
                chrome.geometry.ledRailPath(chrome.width.toFloat(), chrome.height.toFloat(), segment).computeBounds(bounds, true)
                assertTrue("Right LED cap remains in Close's slot", bounds.left - chrome.geometry.ledStrokeWidth(segment) / 2f >= closeBounds.left - 0.01f)
            }
            for (rowIndex in listOf(0, 1)) {
                val key = (keyboard.getChildAt(rowIndex) as ViewGroup).getChildAt(0)
                fun upper(multiplier: Int): Region {
                    SettingsManager.setTitan2EliteTopCornerMultiplier(activity, multiplier)
                    return region(KeyboardBarRowGeometry.canvasSurface(key,
                        android.graphics.RectF(0f, 0f, key.width.toFloat(), key.height.toFloat()), 6f, true, false).path, key)
                }
                assertTrue("Upper and middle row theme corners ignore bottom blending", upper(1).apply { op(upper(6), Region.Op.XOR) }.isEmpty)
            }
            chrome.viewTreeObserver.dispatchOnPreDraw()
            assertCompositeInkInside(first)
            assertTrue(close.performClick())
        }
        assertEquals(4, closes)
        val chrome = controller.field("statusBarLayout") as ImeChromeLayout
        val close = controller.field("symSurfaceCloseButton") as View
        for (page in listOf(3, 4)) {
            controller.update(snapshot(page))
            (controller.field("emojiKeyboardContainer") as View).translationY = 0f
            layout(chrome)
            assertSame("Non-grid panels return Close to the common surface", controller.field("symSurfaceContainer"), close.parent)
            if (page == 3) {
                assertEquals(View.VISIBLE, close.visibility)
                assertEquals(globalBounds(chrome, close), Rect().also { chrome.geometry.rightSurface!!.bounds.round(it) })
                assertTrue(close.performClick())
            } else {
                assertEquals(View.GONE, close.visibility)
            }
        }
        SettingsManager.setSoftwareKeyboardMode(activity, SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL)
        controller.update(snapshot(0))
        layout(chrome)
        assertEquals(View.GONE, close.visibility)
        SettingsManager.setSoftwareKeyboardMode(activity, SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE)
        controller.update(snapshot(1), symMappings = mappings)
        (controller.field("emojiKeyboardContainer") as View).translationY = 0f
        layout(chrome)
        assertNotSame(controller.field("symSurfaceContainer"), close.parent)
        assertEquals(globalBounds(chrome, close), Rect().also { chrome.geometry.rightSurface!!.bounds.round(it) })
        assertTrue(close.performClick())
        assertEquals(6, closes)
    }

    @Test
    fun blackberryGridKeepsZeroAndCurrencyVisibleAndClickableEvenWithTitanLayoutEnabled() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        SettingsManager.setTitan2LayoutEnabled(activity, true)
        SettingsManager.setSymAutoClose(activity, false)
        for (profile in listOf("Q25", "key2")) {
            SettingsManager.setPhysicalKeyboardProfileOverride(activity, profile)
            val commits = mutableListOf<String>()
            val connection = java.lang.reflect.Proxy.newProxyInstance(
                android.view.inputmethod.InputConnection::class.java.classLoader,
                arrayOf(android.view.inputmethod.InputConnection::class.java)
            ) { _, method, args ->
                if (method.name == "commitText") commits.add(args!![0].toString())
                when (method.returnType) { java.lang.Boolean.TYPE -> true; java.lang.Integer.TYPE -> 0; else -> null }
            } as android.view.inputmethod.InputConnection
            val controller = StatusBarController(activity, clipboardHistoryManager = rocks.pkb.plektra.clipboard.ClipboardHistoryManager(activity))
            var committed = 0
            controller.onSymTextCommitted = { committed++ }
            val mappings = (KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z).associateWith { "text" } +
                mapOf(KeyEvent.KEYCODE_0 to "zero", KeyEvent.KEYCODE_GRAVE to "currency")
            SettingsManager.setSoftwareKeyboardMode(activity, SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE)
            controller.update(snapshot(1), inputConnection = connection, symMappings = mappings)
            val chrome = controller.field("statusBarLayout") as ImeChromeLayout
            activity.setContentView(chrome)
            val keyboard = controller.field("emojiKeyboardContainer") as ViewGroup
            keyboard.translationY = 0f
            layout(chrome)
            val bottom = keyboard.getChildAt(2) as ViewGroup
            val keys = (0 until bottom.childCount).map(bottom::getChildAt).mapNotNull { child ->
                val content = (child as? ViewGroup)?.getChildAt(0) as? ViewGroup
                val label = (content?.getChildAt(1) as? TextView)?.text?.toString()
                label?.let { it to child }
            }
            assertEquals(listOf("0", "Z", "X", "C", "V", "B", "N", "M", "$"), keys.map { it.first })
            val close = controller.field("symSurfaceCloseButton") as View
            assertTrue(globalBounds(chrome, keys.last().second).right <= globalBounds(chrome, close).left)
            assertTrue(globalBounds(chrome, close).right <= chrome.width)
            assertTrue(keys.first().second.performClick())
            assertTrue(keys.last().second.performClick())
            assertEquals(listOf("zero", "currency"), commits)
            assertEquals(2, committed)
        }
    }

    private fun assertCompositeInkInside(key: View) {
        val group = (key as ViewGroup).getChildAt(0) as ViewGroup
        assertTrue((group.getChildAt(1) as TextView).text.isNotBlank())
        val shape = region(KeyboardBarRowGeometry.canvasSurface(key,
            android.graphics.RectF(0f, 0f, key.width.toFloat(), key.height.toFloat()),
            6f * key.resources.displayMetrics.density, true, false).path, key)
        val margin = maxOf(key.width, key.height)
        for (index in 0..1) {
            val child = group.getChildAt(index)
            val bitmap = Bitmap.createBitmap(key.width + 2 * margin, key.height + 2 * margin, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.translate(margin + group.left.toFloat(), margin + group.top.toFloat())
            canvas.concat(group.matrix)
            canvas.translate(child.left.toFloat(), child.top.toFloat())
            canvas.concat(child.matrix)
            child.draw(canvas)
            var ink = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 32) {
                    ink++
                    assertTrue("Content $index ink fits at ${x - margin},${y - margin}", shape.contains(x - margin, y - margin))
                }
            }
            assertTrue("Content $index draws visible ink", ink > 0)
        }
    }

    private fun region(path: Path, view: View) = Region().apply { setPath(path, Region(0, 0, view.width, view.height)) }
    private fun globalBounds(chrome: ImeChromeLayout, view: View) = Rect(0, 0, view.width, view.height).also { chrome.offsetDescendantRectToMyCoords(view, it) }
    private fun StatusBarController.field(name: String): Any? = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)
    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(view.resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        view.viewTreeObserver.dispatchOnPreDraw()
    }
    private fun snapshot(page: Int) = StatusBarController.StatusSnapshot(false, false, false,
        false, false, false, false, false, false, false, page)
}
