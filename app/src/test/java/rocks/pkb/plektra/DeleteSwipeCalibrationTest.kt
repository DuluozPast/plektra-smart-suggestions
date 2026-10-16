package rocks.pkb.plektra
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk=[33])
class DeleteSwipeCalibrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun setup() { SettingsManager.getPreferences(context).edit().clear().commit(); DeleteSwipeCalibration.cancel(context) }
    @Test fun fiveLeftThumbGesturesProduceAWorkingShorterGesture() {
        DeleteSwipeCalibration.start(context)
        repeat(5) { DeleteSwipeCalibration.record(context, .35f + it * .005f, 120f, 10f, 300L) }
        assertTrue(DeleteSwipeCalibration.save(context))
        val p = DeleteSwipeCalibration.get(context)!!
        assertTrue(p.accepts(.35f, 100f, 5f, 300L))
        assertFalse(p.accepts(.9f, 100f, 5f, 300L))
        assertFalse(p.accepts(.35f, -100f, 5f, 300L))
        assertFalse(p.accepts(.35f, 100f, 80f, 300L))
        assertFalse(DeleteSwipeCalibration.active(context))
        assertEquals(SettingsManager.SWIPE_TO_DELETE_PROVIDER_NATIVE_IME, SettingsManager.getSwipeToDeleteProvider(context))
    }
    @Test fun incompleteAndInvalidSamplesCannotReplacePreviousProfile() {
        DeleteSwipeCalibration.start(context)
        DeleteSwipeCalibration.record(context, Float.NaN, 100f, 0f, 100L)
        DeleteSwipeCalibration.record(context, .3f, -100f, 0f, 100L)
        DeleteSwipeCalibration.record(context, .3f, 100f, 100f, 100L)
        assertEquals(0, DeleteSwipeCalibration.count(context)); assertFalse(DeleteSwipeCalibration.save(context))
        assertNull(DeleteSwipeCalibration.get(context))
    }
    @Test fun cancelPreservesSavedProfileAndResetRemovesIt() {
        DeleteSwipeCalibration.start(context)
        repeat(5) { DeleteSwipeCalibration.record(context, .3f, 100f, 0f, 100L) }
        DeleteSwipeCalibration.save(context)
        DeleteSwipeCalibration.start(context); DeleteSwipeCalibration.cancel(context)
        assertNotNull(DeleteSwipeCalibration.get(context)); DeleteSwipeCalibration.clear(context)
        assertNull(DeleteSwipeCalibration.get(context))
    }
}
