package rocks.pkb.plektra.backup

import android.content.Context
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.dictionaries.DictionaryRepositoryManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Repository packs remain references; modified and privately imported packs travel with the backup. */
internal object DictionaryPackageBackup {
    const val MANIFEST = "dictionary_packages.json"
    const val DIRECTORY = "dictionaries_serialized/custom"
    private val filename = Regex("[a-z]{2,3}_base\\.dict")
    private val digest = Regex("[0-9a-f]{64}")

    fun hash(file: File): String = file.inputStream().use { input ->
        val sha = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            sha.update(buffer, 0, count)
        }
        sha.digest().joinToString("") { "%02x".format(it) }
    }

    fun snapshot(context: Context, destination: File): List<String> {
        val catalog = DictionaryRepositoryManager.offlineCatalog(context).items.associateBy { it.filename }
        val refs = linkedMapOf<String, JSONObject>()
        val paths = mutableListOf<String>()
        val local = File(context.filesDir, DIRECTORY)
        val imported = (local.listFiles().orEmpty().toList() +
            File(context.filesDir, "dictionaries_serialized").listFiles().orEmpty().toList())
            .filter { it.isFile && it.extension.equals("dict", ignoreCase = true) }.distinctBy { it.name }
        imported.forEach { file ->
            val sha = hash(file)
            val pack = catalog[file.name]
            if (pack != null && pack.sha256 == sha) {
                refs[file.name] = JSONObject().put("filename", file.name).put("id", pack.id).put("sha256", sha)
            } else {
                validatePackage(file)
                val path = "$DIRECTORY/${file.name}"
                File(destination, path).apply { parentFile?.mkdirs() }.also { file.copyTo(it, overwrite = true) }
                paths.add(path)
            }
        }
        val required = linkedSetOf<String>()
        SettingsManager.getCustomInputStyles(context).split(';').forEach {
            required.add(it.substringBefore(':').replace('_', '-').substringBefore('-').lowercase())
        }
        val extras = JSONObject(SettingsManager.getPreferences(context).getString("input_style_suggestion_locales", "{}") ?: "{}")
        extras.keys().forEach { key ->
            val values = extras.optJSONArray(key) ?: return@forEach
            for (i in 0 until values.length()) required.add(values.getString(i).replace('_', '-').substringBefore('-').lowercase())
        }
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.currentInputMethodSubtype?.locale?.replace('_', '-')?.substringBefore('-')?.let(required::add)
        required.forEach { language ->
            val name = "${language}_base.dict"
            val pack = catalog[name]
            if (pack != null && name !in refs && !File(local, name).exists()) {
                refs[name] = JSONObject().put("filename", name).put("id", pack.id).put("sha256", pack.sha256)
            }
        }
        if (refs.isNotEmpty()) {
            File(destination, MANIFEST).writeText(JSONObject().put("schemaVersion", 1)
                .put("packs", JSONArray(refs.values.toList())).toString(2))
            paths.add(MANIFEST)
        }
        return paths
    }

    private fun validatePackage(file: File) {
        val magic = file.inputStream().use { stream -> ByteArray(4).also { stream.read(it) } }.decodeToString()
        if (magic == "P31D") {
            rocks.pkb.plektra.core.suggestions.CompactDictionaryV31Reader(file).use { reader ->
                require(reader.wordCount >= 0)
            }
        } else file.inputStream().use(DictionaryRepositoryManager::validateDictionaryStream)
    }

    fun validate(root: File) {
        File(root, DIRECTORY).walkTopDown().filter { it.isFile }.forEach { file ->
            require(file.parentFile == File(root, DIRECTORY) && file.extension.equals("dict", ignoreCase = true)) { "Invalid dictionary backup path" }
            validatePackage(file)
        }
        references(root)
    }

    private fun references(root: File): List<JSONObject> {
        val file = File(root, MANIFEST)
        if (!file.exists()) return emptyList()
        val manifest = JSONObject(file.readText())
        require(manifest.getInt("schemaVersion") == 1) { "Unsupported dictionary backup version" }
        val packs = manifest.getJSONArray("packs")
        require(packs.length() <= 500) { "Too many dictionary references" }
        val names = mutableSetOf<String>()
        return (0 until packs.length()).map { index ->
            packs.getJSONObject(index).also {
                require(filename.matches(it.getString("filename")) && names.add(it.getString("filename")) &&
                    digest.matches(it.getString("sha256")) && it.getString("id").isNotBlank()) { "Invalid dictionary reference" }
            }
        }
    }

    fun missing(context: Context, root: File): List<String> = references(root).filter { reference ->
        val name = reference.getString("filename")
        val expected = reference.getString("sha256")
        val local = File(context.filesDir, "$DIRECTORY/$name")
        if (local.exists()) hash(local) != expected else {
            val bundled = context.assets.list("common/dictionaries_serialized").orEmpty().contains(name)
            if (!bundled) true else context.assets.open("common/dictionaries_serialized/$name").use { input ->
                val sha = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    sha.update(buffer, 0, count)
                }
                sha.digest().joinToString("") { "%02x".format(it) } != expected
            }
        }
    }.map { it.getString("filename").removeSuffix("_base.dict") }
}
