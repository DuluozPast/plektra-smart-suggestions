package rocks.pkb.plektra

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThemePhotoImageStoreTest {
    @Test fun decodeTargetIsBoundedAndNeverUpscalesOrLosesAspectRatio() {
        assertEquals(1024 to 768, ThemePhotoImageStore.boundedSize(8000,6000))
        assertEquals(512 to 1024, ThemePhotoImageStore.boundedSize(4000,8000))
        assertEquals(16 to 8, ThemePhotoImageStore.boundedSize(16,8))
        assertEquals(1 to 1024, ThemePhotoImageStore.boundedSize(1,Int.MAX_VALUE))
    }
    @Test fun eyedropperAveragesANeighbourhoodInsteadOfOneNoisyPixel() {
        val image=Bitmap.createBitmap(5,5,Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.BLACK); image.setPixel(2,2,Color.WHITE)
        assertEquals(Color.rgb(10,10,10), ThemePhotoImageStore.sample(image,2,2))
        assertEquals(Color.WHITE,ThemePhotoImageStore.sample(image,2,2,0))
    }
    @Test fun edgesAreClippedAndTransparentSamplesDoNotInventBlack() {
        val image=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
        assertNull(ThemePhotoImageStore.sample(image,0,0))
        image.setPixel(1,1,Color.RED)
        assertEquals(Color.RED,ThemePhotoImageStore.sample(image,0,0))
        assertNull(ThemePhotoImageStore.sample(image,-1,0))
    }
    @Test fun zoomAndPanMapTheReticleBackToTheSampledImageRegion() {
        val image=IntSize(1000,500); val viewport=IntSize(400,200)
        val fit=ThemePhotoViewport.fit(image,viewport,2f,Offset(80f,0f))
        assertEquals(.8f,fit.scale,.0001f)
        assertEquals(400 to 250,fit.samplePosition(image))
        val corner=ThemePhotoViewport.fit(image,viewport,2f,Offset(9999f,9999f))
        assertEquals(399.6f,corner.pan.x,.0001f)
        assertEquals(0 to 0,corner.samplePosition(image))
        val opposite=ThemePhotoViewport.fit(image,viewport,1f,Offset(-9999f,-9999f))
        assertEquals(999 to 499,opposite.samplePosition(image))
    }
    @Test fun cameraUsesOnlyTheDedicatedPrivateProviderPath() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val capture=ThemePhotoImageStore.capture(context)
        try {
            assertEquals(ThemePhotoImageStore.directory(context).canonicalFile,capture.parentFile.canonicalFile)
            val paths=mutableListOf<Pair<String?,String?>>()
            context.resources.getXml(R.xml.file_provider_paths).use { xml ->
                while (xml.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "cache-path") {
                        paths.add(xml.getAttributeValue(null,"name") to xml.getAttributeValue(null,"path"))
                    }
                    xml.next()
                }
            }
            assertTrue(paths.contains("theme_photos" to "theme-photos/"))
            assertTrue(paths.none { it.second == "." || it.second == "/" })
            ThemePhotoImageStore.deleteCapture(context,"../"+capture.name)
            assertTrue(capture.exists())
        } finally { ThemePhotoImageStore.deleteCapture(context,capture.name) }
        assertFalse(capture.exists())
    }
}
