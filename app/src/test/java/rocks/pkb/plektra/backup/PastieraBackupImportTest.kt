package rocks.pkb.plektra.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.AppBroadcastActions
import rocks.pkb.plektra.SettingsManager
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PastieraBackupImportTest {
    private lateinit var context: Context
    private lateinit var root: File
    private val broadcasts = mutableListOf<Intent>()
    private val fixture get() = javaClass.getResource("/backup/pastiera_prefs.json")!!.readText()
    private val prefs get() = SettingsManager.getPreferences(context)

    @Before fun setUp() {
        org.robolectric.shadows.ShadowLog.stream = System.out
        val base = rocks.pkb.plektra.test.ShortPathContext.wrap(RuntimeEnvironment.getApplication())
        root = File.createTempFile("pastiera_import_", "", base.cacheDir).apply { delete(); mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("${root.name}_$name", mode)
            override fun sendBroadcast(intent: Intent) { broadcasts.add(intent); super.sendBroadcast(intent) }
        }
        prefs.edit().putString("keyboard_layout", "azerty")
            .putString("speech_provider", "OPENAI").putLong("speech_silence_ms", 3000)
            .putString("speech_model_OPENAI", "gpt-live-transcribe")
            .putBoolean("slim_mode_active", false)
            .putString("software_keyboard_mode_runtime_override", "force_hardware")
            .putString("physical_keyboard_profile_override", "auto")
            .putString("alt_modifier_binding", "device_sym:auto")
            .putString("target_only_setting", "keep").commit()
        context.getSharedPreferences("speech_credentials", Context.MODE_PRIVATE).edit()
            .putString("OPENAI", "opaque-device-encrypted-value").commit()
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun legacyZipImportsAndCanBeRepeatedWithoutChangingUnmentionedSettings() = runBlocking {
        File(context.filesDir, "variations.json").writeText("{\"targetOnlyVariations\":[\"keep\"]}")
        val zip = archive(mapOf("prefs/pastiera_prefs.json" to fixture,
            "files/user_defaults.json" to "[{\"w\":\"PastieraWord\",\"f\":30}]",
            "files/variations.json" to "{\"emailVariations\":[\"test@example.invalid\"]}",
            "files/keyboard_layouts/custom-pastiera.json" to
                """{"name":"custom-pastiera","mappings":{"KEYCODE_A":{"lowercase":"ä","uppercase":"Ä"}}}""",
            "prefs/speech_credentials.json" to "not imported",
            "files/unrelated.json" to "not imported"))
        val before = prefs.all.toMap()
        assertTrue(RestoreManager.inspect(context, Uri.fromFile(zip)) is RestoreInspectionResult.Success)
        assertEquals(before, prefs.all)
        repeat(2) {
            val result = RestoreManager.restore(context, Uri.fromFile(zip)) as RestoreResult.Success
            assertEquals("qwertz", SettingsManager.getKeyboardLayout(context))
            assertEquals(450L, prefs.getLong("long_press_threshold", 0))
            val layoutFile = File(context.filesDir, "keyboard_layouts/custom-pastiera.json")
            val layout = layoutFile.inputStream().use {
                rocks.pkb.plektra.data.layout.LayoutFileStore.loadLayoutFromStream(it)
            }!!
            assertEquals("ä", layout[android.view.KeyEvent.KEYCODE_A]!!.lowercase)
            val variations = JSONObject(File(context.filesDir, "variations.json").readText())
            assertEquals("keep", variations.getJSONArray("targetOnlyVariations").getString(0))
            assertEquals("test@example.invalid", variations.getJSONArray("emailVariations").getString(0))
            assertEquals("slim", prefs.getString("slim_mode_override", null))
            assertEquals(rocks.pkb.plektra.AltModifierBinding.Emoji, SettingsManager.getAltModifierBinding(context))
            assertFalse(prefs.contains("alt_character_layer_binding"))
            assertEquals(listOf("language"), SettingsManager.getSlimStatusBarSlotsLeft(context))
            assertEquals(listOf("hamburger"), SettingsManager.getSlimStatusBarSlotsRight(context))
            assertEquals("[\"PastieraWord\"]", prefs.getString("user_dictionary_entries", null))
            assertTrue(result.postActionsTriggered.containsAll(listOf(RestoreManager.PostRestoreAction.REFRESH_USER_DICTIONARY,
                RestoreManager.PostRestoreAction.REGISTER_CUSTOM_INPUT_STYLES)))
            assertTrue(result.preferencesSummary.appliedKeys.all { it.startsWith("plektra_prefs:") })
            assertFalse(prefs.getBoolean("slim_mode_active", true))
            assertEquals("force_hardware", prefs.getString("software_keyboard_mode_runtime_override", null))
            assertEquals("OPENAI", prefs.getString("speech_provider", null))
            assertEquals(3000L, prefs.getLong("speech_silence_ms", 0))
            assertEquals("gpt-live-transcribe", prefs.getString("speech_model_OPENAI", null))
            assertEquals("keep", prefs.getString("target_only_setting", null))
            assertEquals("opaque-device-encrypted-value", context.getSharedPreferences(
                "speech_credentials", Context.MODE_PRIVATE).getString("OPENAI", null))
            assertFalse(prefs.contains("pastierina_mode_override"))
            assertTrue(context.getSharedPreferences("pastiera_prefs", Context.MODE_PRIVATE).all.isEmpty())
            assertFalse(File(context.filesDir, "unrelated.json").exists())
        }
        assertEquals(2, broadcasts.count { it.action == AppBroadcastActions.USER_DICTIONARY_UPDATED })
        assertTrue(broadcasts.all { it.`package` == context.packageName })
    }

    @Test fun pastiera085PresentationValuesRestoreToCanonicalModes() = runBlocking {
        for ((legacy, expected) in mapOf("force_minimal" to "slim", "force_full" to "full_status_bar",
            "follow_system" to "full_status_bar")) {
            val content = JSONObject(fixture).apply {
                getJSONObject("entries").getJSONObject("pastierina_mode_override").put("value", legacy)
            }.toString()
            val result = RestoreManager.restore(context, Uri.fromFile(archive(
                mapOf("prefs/pastiera_prefs.json" to content))))
            assertTrue(result is RestoreResult.Success)
            assertEquals(expected, prefs.getString("slim_mode_override", null))
        }
    }

    @Test fun adaptiveLegacyImportPreservesTargetDeviceProfile() = runBlocking {
        val zip = archive(mapOf("prefs/pastiera_prefs.json" to fixture))
        val result = RestoreManager.restore(context, Uri.fromFile(zip),
            RestoreManager.ImportMode.ADAPT_TO_CURRENT_DEVICE) as RestoreResult.Success
        assertEquals("auto", prefs.getString("physical_keyboard_profile_override", null))
        assertTrue(result.preferencesSummary.skippedKeys.contains("plektra_prefs:physical_keyboard_profile_override"))
        assertEquals("qwertz", prefs.getString("keyboard_layout", null))
    }

    @Test fun canonicalNamesWinRegardlessOfZipOrder() = runBlocking {
        val legacy = JSONObject(fixture).apply {
            getJSONObject("entries").put("slim_mode_override",
                JSONObject().put("type", "string").put("value", "full_status_bar"))
        }.toString()
        val modern = """{"name":"plektra_prefs","entries":{"keyboard_layout":{"type":"string","value":"colemak"}}}"""
        val entries = linkedMapOf("prefs/pastiera_prefs.json" to legacy, "prefs/plektra_prefs.json" to modern)
        for (ordered in listOf(entries, entries.entries.reversed().associate { it.toPair() })) {
            val result = RestoreManager.restore(context, Uri.fromFile(archive(ordered)))
            assertTrue(result is RestoreResult.Success)
            assertEquals("colemak", prefs.getString("keyboard_layout", null))
            assertEquals("full_status_bar", prefs.getString("slim_mode_override", null))
        }
    }

    @Test fun legacyBackupWithoutMetadataStillImports() = runBlocking {
        assertTrue(RestoreManager.restore(context, Uri.fromFile(archive(
            mapOf("prefs/pastiera_prefs.json" to fixture), metadata = false))) is RestoreResult.Success)
    }

    @Test fun corruptRecognizedPreferencesFailBeforeAnyFileOrSettingChanges() = runBlocking {
        val corrupt = listOf("{broken", """{"entries":null}""",
            """{"name":"speech_credentials","entries":{}}""",
            """{"entries":{"keyboard_layout_auto_by_locale":{"type":"boolean","value":"false"}}}""",
            """{"entries":{"long_press_threshold":{"type":"long","value":1.5}}}""",
            """{"entries":{"long_press_threshold":{"type":"long","value":1e30}}}""")
        val existing = File(context.filesDir, "user_defaults.json").apply { writeText("{\"existing\":true}") }
        val before = prefs.all.toMap()
        for (content in corrupt) {
            val zip = archive(mapOf("prefs/pastiera_prefs.json" to content,
                "files/user_defaults.json" to "{\"replacement\":true}"))
            assertTrue(RestoreManager.inspect(context, Uri.fromFile(zip)) is RestoreInspectionResult.Failure)
            assertTrue(RestoreManager.restore(context, Uri.fromFile(zip)) is RestoreResult.Failure)
            assertEquals(before, prefs.all)
            assertEquals("{\"existing\":true}", existing.readText())
        }
        assertTrue(broadcasts.isEmpty())
    }

    @Test fun corruptUserFileAndMetadataFailBeforeSettingsAreChanged() = runBlocking {
        val before = prefs.all.toMap()
        for (extra in listOf("files/user_defaults.json" to "not json", "backup_meta.json" to "broken",
            "files/keyboard_layouts/broken.json" to "{}", "files/variations.json" to "[]")) {
            val zip = archive(mapOf("prefs/pastiera_prefs.json" to fixture, extra), metadata = false)
            assertTrue(RestoreManager.restore(context, Uri.fromFile(zip)) is RestoreResult.Failure)
            assertEquals(before, prefs.all)
            assertFalse(File(context.filesDir, "user_defaults.json").exists())
        }
    }

    @Test fun failedPreferenceWriteRollsBackExistingAndNewFilesAndSettings() = runBlocking {
        val existing = File(context.filesDir, "user_defaults.json").apply { writeText("{\"existing\":true}") }
        val before = prefs.all.toMap()
        var failNextCommit = true
        val failing = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val delegate = super.getSharedPreferences(name, mode)
                if (name != "plektra_prefs") return delegate
                return object : SharedPreferences by delegate {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = delegate.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun commit(): Boolean {
                                val result = editor.commit()
                                if (failNextCommit) { failNextCommit = false; return false }
                                return result
                            }
                        }
                    }
                }
            }
        }
        val zip = archive(mapOf("prefs/pastiera_prefs.json" to fixture,
            "files/user_defaults.json" to "{\"replacement\":true}",
            "files/ctrl_key_mappings.json" to "{}"))
        assertTrue(RestoreManager.restore(failing, Uri.fromFile(zip)) is RestoreResult.Failure)
        assertFalse(failNextCommit)
        assertEquals(before, prefs.all)
        assertEquals("{\"existing\":true}", existing.readText())
        assertFalse(File(context.filesDir, "ctrl_key_mappings.json").exists())
        assertEquals(1, broadcasts.count { it.action == AppBroadcastActions.LEARNING_RESTORED })
        assertTrue(broadcasts.all { it.action == AppBroadcastActions.LEARNING_RESTORED })
    }

    @Test fun unrelatedArchiveIsRejectedWithoutCreatingPreferenceFiles() = runBlocking {
        val before = prefs.all.toMap()
        val zip = archive(mapOf("prefs/arbitrary.json" to "{}", "files/arbitrary.json" to "{}"))
        assertTrue(RestoreManager.restore(context, Uri.fromFile(zip)) is RestoreResult.Failure)
        assertEquals(before, prefs.all)
        assertTrue(context.getSharedPreferences("arbitrary", Context.MODE_PRIVATE).all.isEmpty())
    }

    private fun archive(entries: Map<String, String>, metadata: Boolean = true): File {
        val zip = File.createTempFile("backup_", ".zip", context.cacheDir)
        ZipOutputStream(zip.outputStream()).use { output ->
            val contents = if (metadata) mapOf("backup_meta.json" to
                """{"versionCode":86,"versionName":"0.86","timestamp":"2026-02-25T00:00:00Z"}""") + entries else entries
            contents.forEach { (path, text) ->
                output.putNextEntry(ZipEntry(path))
                output.write(text.toByteArray())
                output.closeEntry()
            }
        }
        return zip
    }
}
