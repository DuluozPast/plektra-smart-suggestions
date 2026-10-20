package rocks.pkb.plektra.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThemeImageBackupTest {
    private lateinit var context: Context
    private lateinit var image: File
    private lateinit var archiveRoot: File

    @Before fun setup() {
        context = rocks.pkb.plektra.test.ShortPathContext.wrap(RuntimeEnvironment.getApplication())
        image = File(context.filesDir, "theme-images/reference.png")
        image.parentFile!!.mkdirs()
        image.delete()
        archiveRoot = File(context.cacheDir, "image-backup-test").apply { mkdirs() }
    }

    private fun writeImage(): ByteArray {
        // Real encoded 2x2 RGBA PNG; Bitmap.compress is only a stub in legacy Robolectric.
        image.writeBytes(java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEUlEQVR4nGNQCu34D8IMMAYAQjYH+ZGxrzoAAAAASUVORK5CYII="))
        return image.readBytes()
    }

    private fun snapshot(): File = File(archiveRoot, ThemeImageBackup.DIRECTORY).also {
        ThemeImageBackup.snapshot(context, it)
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Invalid image backup was accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun fullBackupZipRestoresReferenceAndQuoteMode() = runBlocking {
        val expected = writeImage()
        val prefs = rocks.pkb.plektra.SettingsManager.getPreferences(context)
        prefs.edit().putString("smart_quotes_mode", "immediate").commit()
        val zip = File(context.cacheDir, "full-image.zip")
        val exported = BackupManager.createBackup(context, Uri.fromFile(zip))
        assertTrue(exported.toString(), exported is BackupResult.Success)
        ZipFile(zip).use { archive ->
            assertNotNull(archive.getEntry("theme-images/manifest.json"))
            assertNotNull(archive.getEntry("theme-images/reference.png"))
            assertNull(archive.getEntry("files/theme-images/reference.png"))
        }
        image.writeBytes(byteArrayOf(1, 2, 3))
        prefs.edit().putString("smart_quotes_mode", "off").commit()
        val result = RestoreManager.restore(context, Uri.fromFile(zip))
        assertTrue(result.toString(), result is RestoreResult.Success)
        assertArrayEquals(expected, image.readBytes())
        assertEquals("immediate", prefs.getString("smart_quotes_mode", null))
    }

    @Test fun emptyImageFolderIsRepresentedInZipAndClearsOldReference() {
        val folder = snapshot()
        val zip = File(context.cacheDir, "empty-image.zip")
        zip.outputStream().use { ZipHelper.zip(archiveRoot, it) }
        ZipFile(zip).use { assertNotNull(it.getEntry("theme-images/manifest.json")) }
        writeImage()
        ThemeImageBackup.restore(context, folder)
        assertFalse(image.exists())
    }

    @Test fun legacyBackupWithoutImageFolderPreservesReference() {
        val expected = writeImage()
        ThemeImageBackup.restore(context, File(archiveRoot, "absent"))
        assertArrayEquals(expected, image.readBytes())
    }

    @Test fun alteredImageIsRejectedBeforeReferenceChanges() {
        val expected = writeImage()
        val folder = snapshot()
        val copied = File(folder, "reference.png")
        val bytes = copied.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        copied.writeBytes(bytes)
        rejects { ThemeImageBackup.restore(context, folder) }
        assertArrayEquals(expected, image.readBytes())
    }

    @Test fun truncatedImageIsRejectedBeforeReferenceChanges() {
        val expected = writeImage()
        val folder = snapshot()
        File(folder, "reference.png").writeBytes(byteArrayOf(1))
        rejects { ThemeImageBackup.restore(context, folder) }
        assertArrayEquals(expected, image.readBytes())
    }

    @Test fun pathTraversalAndUnexpectedFilesAreRejected() {
        writeImage()
        val folder = snapshot()
        File(folder, "unexpected.png").writeBytes(byteArrayOf(1))
        rejects { ThemeImageBackup.validate(folder) }
        File(folder, "unexpected.png").delete()
        val manifest = File(folder, "manifest.json")
        val json = JSONObject(manifest.readText())
        json.getJSONArray("images").getJSONObject(0).put("name", "../reference.png")
        manifest.writeText(json.toString())
        rejects { ThemeImageBackup.validate(folder) }
    }
}
