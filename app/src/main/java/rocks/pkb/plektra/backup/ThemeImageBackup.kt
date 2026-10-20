package rocks.pkb.plektra.backup

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.ThemeTextureModel
import rocks.pkb.plektra.ThemeTextureStore

/** Versioned assets beside prefs/files/learning, never embedded in theme JSON. */
internal object ThemeImageBackup {
    const val DIRECTORY = "theme-images"
    private const val MANIFEST = "manifest.json"
    private const val REFERENCE = "reference.png"
    private const val MAX_ASSETS = 512
    private const val MAX_TOTAL_BYTES = 72L * 1024 * 1024

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun dimensions(file: File, reference: Boolean): Pair<Int, Int> {
        val limit = if (reference) 8L * 1024 * 1024 else 2L * 1024 * 1024
        val edge = if (reference) 1024 else ThemeTextureModel.MAX_EDGE
        require(file.length() in 1..limit) { "Theme image size is invalid" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().buffered().use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outMimeType == "image/png" && bounds.outWidth in 1..edge && bounds.outHeight in 1..edge) {
            "Theme image format or dimensions are invalid"
        }
        return bounds.outWidth to bounds.outHeight
    }

    /** Export referenced textures only; abandoned crops and cached originals stay private. */
    fun snapshot(context: Context, destination: File): List<String> {
        val source = File(context.filesDir, DIRECTORY)
        require(destination.canonicalFile != source.canonicalFile)
        destination.mkdirs()
        destination.listFiles().orEmpty().forEach {
            require(it.isFile && (it.name == MANIFEST || it.name == REFERENCE || ThemeTextureModel.assetPattern.matches(it.name)))
            check(it.delete())
        }
        val assets = ThemeTextureModel.referencedAssets(SettingsManager.getPreferences(context).all.values).sorted()
        require(assets.size <= MAX_ASSETS) { "Too many theme textures" }
        val names = (if (File(source, REFERENCE).isFile) listOf(REFERENCE) else emptyList()) + assets
        val entries = JSONArray(); var total = 0L
        for (name in names) {
            val image = File(source, name)
            require(image.isFile) { "A referenced theme texture is missing" }
            val (width, height) = dimensions(image, name == REFERENCE)
            val digest = hash(image)
            if (name != REFERENCE) require(name == "texture-$digest.png") { "Theme texture hash is invalid" }
            total += image.length(); require(total <= MAX_TOTAL_BYTES)
            image.copyTo(File(destination, name), overwrite = true)
            entries.put(JSONObject().put("name", name).put("bytes", image.length()).put("width", width)
                .put("height", height).put("sha256", digest))
        }
        File(destination, MANIFEST).writeText(JSONObject().put("version", 2).put("images", entries).toString(2))
        return listOf("$DIRECTORY/$MANIFEST") + names.map { "$DIRECTORY/$it" }
    }

    /** Validate all files before changing any setting or image. */
    fun validate(directory: File): List<String> {
        if (!directory.exists()) return emptyList()
        require(directory.isDirectory) { "Theme image folder is invalid" }
        val manifest = File(directory, MANIFEST)
        require(manifest.isFile && manifest.length() in 1..262_144L) { "Theme image manifest is missing or too large" }
        val json = JSONObject(manifest.readText())
        val version = json.getInt("version")
        require(version in 1..2) { "Unsupported theme image manifest" }
        val images = json.getJSONArray("images")
        require(images.length() <= if (version == 1) 1 else MAX_ASSETS + 1) { "Too many theme images" }
        val names = mutableListOf<String>(); var total = 0L
        for (index in 0 until images.length()) {
            val entry = images.getJSONObject(index)
            val name = entry.getString("name")
            require((name == REFERENCE || version == 2 && ThemeTextureModel.assetPattern.matches(name)) && name !in names) {
                "Unsupported theme image name"
            }
            val image = File(directory, name)
            require(image.isFile && image.length() == entry.getLong("bytes")) { "Theme image is missing or truncated" }
            total += image.length(); require(total <= MAX_TOTAL_BYTES)
            val (width, height) = dimensions(image, name == REFERENCE)
            val digest = hash(image)
            require(width == entry.getInt("width") && height == entry.getInt("height") && digest == entry.getString("sha256")) {
                "Theme image integrity check failed"
            }
            if (name != REFERENCE) require(name == "texture-$digest.png") { "Theme texture hash is invalid" }
            names.add(name)
        }
        require(directory.listFiles().orEmpty().all { it.isFile && (it.name == MANIFEST || it.name in names) }) {
            "Unexpected theme image content"
        }
        return names
    }

    fun restore(context: Context, source: File): List<String> {
        if (!source.exists()) return emptyList()
        val images = validate(source)
        val target = File(context.filesDir, DIRECTORY)
        require(!target.exists() || target.isDirectory)
        val staged = Files.createTempDirectory(context.filesDir.toPath(), "theme-images-restoring-").toFile()
        val previous = File(staged.parentFile, staged.name + "-previous")
        var oldMoved = false
        try {
            for (name in images) File(source, name).copyTo(File(staged, name))
            if (target.exists()) {
                Files.move(target.toPath(), previous.toPath(), StandardCopyOption.ATOMIC_MOVE)
                oldMoved = true
            }
            try { Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            catch (failure: Exception) {
                if (oldMoved) Files.move(previous.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                throw failure
            }
            ThemeTextureStore.invalidate()
        } finally {
            staged.deleteRecursively()
            if (target.isDirectory) previous.deleteRecursively()
        }
        return images.map { "$DIRECTORY/$it" }
    }
}
