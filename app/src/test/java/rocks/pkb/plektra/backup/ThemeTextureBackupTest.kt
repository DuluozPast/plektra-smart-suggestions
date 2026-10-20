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
import rocks.pkb.plektra.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class ThemeTextureBackupTest {
    private lateinit var context:Context
    private lateinit var texture:File
    private lateinit var folder:File
    private lateinit var bytes:ByteArray
    @Before fun setup() {
        context=rocks.pkb.plektra.test.ShortPathContext.wrap(RuntimeEnvironment.getApplication())
        SettingsManager.getPreferences(context).edit().clear().commit()
        folder=File(context.filesDir,"theme-images").apply { mkdirs() }
        bytes=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEUlEQVR4nGNQCu34D8IMMAYAQjYH+ZGxrzoAAAAASUVORK5CYII=")
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        texture=File(folder,"texture-$hash.png").apply { writeBytes(bytes) }
        val target=SettingsManager.KeyboardThemeTarget.HARDWARE
        val theme=SettingsManager.getKeyboardTheme(context,target).copy(textures=mapOf("background" to KeyboardThemeTexture(texture.name,"tile",intensity=.4f)))
        SettingsManager.setKeyboardTheme(context,target,theme)
    }
    @Test fun fullZipRestoresTextureBytesAndThemeReferenceButExcludesUnusedCrops()=runBlocking {
        File(folder,"texture-${"b".repeat(64)}.png").writeBytes(bytes)
        val zip=File(context.cacheDir,"texture-roundtrip.zip")
        val exported=BackupManager.createBackup(context,Uri.fromFile(zip))
        assertTrue(exported.toString(),exported is BackupResult.Success)
        ZipFile(zip).use { archive ->
            assertNotNull(archive.getEntry("theme-images/"+texture.name))
            assertNull(archive.getEntry("theme-images/texture-${"b".repeat(64)}.png"))
            assertNull(archive.getEntry("theme-images/original-source"))
        }
        texture.delete();SettingsManager.getPreferences(context).edit().clear().commit()
        val restored=RestoreManager.restore(context,Uri.fromFile(zip))
        assertTrue(restored.toString(),restored is RestoreResult.Success)
        assertArrayEquals(bytes,texture.readBytes())
        val actual=SettingsManager.getKeyboardTheme(context,SettingsManager.KeyboardThemeTarget.HARDWARE)
        assertEquals(KeyboardThemeTexture(texture.name,"tile",intensity=.4f),actual.textures["background"])
    }
    @Test fun wrongContentAddressIsRejectedBeforeExistingFilesChange() {
        val destination=File(context.cacheDir,"texture-snapshot")
        ThemeImageBackup.snapshot(context,destination)
        val manifest=File(destination,"manifest.json")
        val data=JSONObject(manifest.readText())
        val wrong="texture-${"c".repeat(64)}.png"
        File(destination,texture.name).renameTo(File(destination,wrong))
        data.getJSONArray("images").getJSONObject(0).put("name",wrong)
        manifest.writeText(data.toString())
        try { ThemeImageBackup.restore(context,destination);fail("Invalid address accepted") }
        catch(_:IllegalArgumentException) {}
        assertArrayEquals(bytes,texture.readBytes())
    }
    @Test fun versionOneReferenceOnlyBackupRemainsReadable() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        File(folder,"reference.png").writeBytes(bytes)
        val destination=File(context.cacheDir,"legacy-reference")
        ThemeImageBackup.snapshot(context,destination)
        val manifest=File(destination,"manifest.json")
        manifest.writeText(JSONObject(manifest.readText()).put("version",1).toString())
        assertEquals(listOf("reference.png"),ThemeImageBackup.validate(destination))
        ThemeImageBackup.restore(context,destination)
        assertArrayEquals(bytes,File(folder,"reference.png").readBytes())
    }
}
