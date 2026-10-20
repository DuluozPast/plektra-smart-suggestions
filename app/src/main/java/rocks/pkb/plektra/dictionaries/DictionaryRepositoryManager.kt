package rocks.pkb.plektra.dictionaries

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import rocks.pkb.plektra.core.suggestions.DictionaryIndex
import rocks.pkb.plektra.core.suggestions.UserDictionaryStore

private const val TAG = "DictionaryRepositoryManager"
private const val MANIFEST_URL = "https://pkb.rocks/plektra-dict/dicts-manifest.json"
private val client = OkHttpClient()

@Serializable
data class DictionaryManifest(
    @SerialName("schemaVersion") val schemaVersion: Int,
    @SerialName("generatedAt") val generatedAt: String,
    @SerialName("releaseTag") val releaseTag: String,
    @SerialName("items") val items: List<DictionaryItem>
)

@Serializable
data class DictionaryItem(
    @SerialName("id") val id: String,
    @SerialName("filename") val filename: String,
    @SerialName("url") val url: String,
    @SerialName("bytes") val bytes: Long,
    @SerialName("sha256") val sha256: String,
    @SerialName("updatedAt") val updatedAt: String,
    @SerialName("name") val name: String,
    @SerialName("shortDescription") val shortDescription: String,
    @SerialName("languageTag") val languageTag: String,
    @SerialName("variant") val variant: String = "basic",
    @SerialName("wordCount") val wordCount: Int? = null
)

sealed class DownloadResult {
    data class Success(val fileName: String) : DownloadResult()
    object NetworkError : DownloadResult()
    object InvalidFormat : DownloadResult()
    object HashMismatch : DownloadResult()
    object CopyError : DownloadResult()
}

object DictionaryRepositoryManager {
    const val ACTION_DICTIONARIES_CHANGED = "rocks.pkb.plektra.ACTION_DICTIONARIES_CHANGED"

    fun notifyDictionariesChanged(context: Context) {
        context.sendBroadcast(android.content.Intent(ACTION_DICTIONARIES_CHANGED).setPackage(context.packageName))
    }

    fun offlineCatalog(context: Context): DictionaryManifest =
        context.assets.open("common/dictionary_catalog.json").bufferedReader().use {
            Json { ignoreUnknownKeys = true }.decodeFromString<DictionaryManifest>(it.readText())
        }

    
    /**
     * Fetches the dictionary manifest from the online repository.
     */
    suspend fun fetchManifest(): Result<DictionaryManifest> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching manifest from: $MANIFEST_URL")
            val request = Request.Builder()
                .url(MANIFEST_URL)
                .header("Accept", "application/json")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to fetch manifest: HTTP ${response.code}")
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }
            
            val body = response.body?.string()
            if (body.isNullOrBlank()) {
                Log.e(TAG, "Empty manifest response")
                return@withContext Result.failure(Exception("Empty response"))
            }
            
            Log.d(TAG, "Manifest response received, size: ${body.length} bytes")
            val manifest = Json { ignoreUnknownKeys = true }.decodeFromString<DictionaryManifest>(body)
            Log.d(TAG, "Manifest parsed successfully. Found ${manifest.items.size} dictionaries: ${manifest.items.map { it.filename }.joinToString(", ")}")
            Result.success(manifest)
        } catch (e: SerializationException) {
            Log.e(TAG, "Failed to parse manifest", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching manifest", e)
            Result.failure(e)
        }
    }
    
    /**
     * Downloads a dictionary from the repository and validates it.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun downloadDictionary(
        context: Context,
        item: DictionaryItem,
        onProgress: ((Long, Long) -> Unit)? = null
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(item.url)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to download ${item.filename}: ${response.code}")
                return@withContext DownloadResult.NetworkError
            }
            
            val body = response.body ?: return@withContext DownloadResult.NetworkError
            val contentLength = body.contentLength()
            
            // Download to temporary file first
            val tempDir = File(context.cacheDir, "dictionary_downloads").apply { mkdirs() }
            val tempFile = File(tempDir, "${item.filename}.tmp")
            
            body.byteStream().use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var totalBytesRead = 0L
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        onProgress?.invoke(totalBytesRead, contentLength)
                    }
                }
            }
            
            // Validate SHA256 hash
            val calculatedHash = calculateSHA256(tempFile)
            if (calculatedHash.lowercase() != item.sha256.lowercase()) {
                Log.e(TAG, "Hash mismatch for ${item.filename}. Expected: ${item.sha256}, Got: $calculatedHash")
                tempFile.delete()
                return@withContext DownloadResult.HashMismatch
            }
            
            // Move to final location
            /*
             * Extended dictionaries have their own filename in the remote repository
             * (for example "fr_extended.dict"), but the runtime loader expects custom
             * base dictionaries to use the standard "<language>_base.dict" filename.
             */
            val destDir = File(
                context.filesDir,
                "dictionaries_serialized/custom"
            ).apply { mkdirs() }

            val languageCode = extractLanguageCode(item.filename)
            val installedFilename = item.filename
            val destFile = File(destDir, installedFilename)
            val activeDictionaryPrefs =
                context.getSharedPreferences("active_dictionaries", Context.MODE_PRIVATE)
            
            require(destFile.canonicalFile.parentFile == destDir.canonicalFile) { "Invalid dictionary name" }
            val personalKeys = UserDictionaryStore().personalKeysForBase(
                context, java.util.Locale.forLanguageTag(item.languageTag))
            val normalizedWords = tempFile.inputStream().use { input ->
                DictionaryFileInstaller.install(input, destFile, personalKeys)
            }
            tempFile.delete()

            activeDictionaryPrefs.edit()
                .putString(languageCode, installedFilename)
                .apply()

            val locale = java.util.Locale.forLanguageTag(item.languageTag)

            val removedDuplicates = UserDictionaryStore().removeWordsAlreadyInBase(
                context = context,
                baseNormalizedWords = normalizedWords,
                locale = locale
            )

            Log.i(
                TAG,
                "Successfully downloaded and installed ${item.filename}. " +
                        "Removed $removedDuplicates duplicate personal dictionary entries."
            )
            /*
             * Notify the running IME that the active base dictionary has changed.
             * The receiver will invalidate its repository cache and load this dictionary.
             */
            context.sendBroadcast(
                android.content.Intent(
                    rocks.pkb.plektra.AppBroadcastActions.BASE_DICTIONARY_UPDATED
                ).setPackage(context.packageName)
            )

            
            notifyDictionariesChanged(context)
            Log.i(TAG, "Successfully downloaded and installed ${item.filename}")
            DownloadResult.Success(destFile.name)
        } catch (oom: OutOfMemoryError) {
            // Validation expands legacy Extended files in memory; keep the app alive.
            rocks.pkb.plektra.KeyboardFailureReport.record(context, "dictionary-download-memory", oom)
            Log.e(TAG, "Not enough memory to validate dictionary", oom)
            DownloadResult.CopyError
        } catch (e: SerializationException) {
            rocks.pkb.plektra.KeyboardFailureReport.record(context, "dictionary-download-format", e)
            Log.e(TAG, "Invalid dictionary format for ${item.filename}", e)
            DownloadResult.InvalidFormat
        } catch (e: Exception) {
            rocks.pkb.plektra.KeyboardFailureReport.record(context, "dictionary-download-io", e)
            Log.e(TAG, "Error downloading ${item.filename}", e)
            DownloadResult.CopyError
        }
    }
    
    /**
     * Calculates SHA256 hash of a file.
     */
    private fun calculateSHA256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Validates that the stream contains a valid dictionary format.
     */
    /**
     * Formats file size in human-readable format.
     */
    internal fun validateDictionaryStream(input: InputStream) {
        // Backup package validation uses the same bounded reader as installation.
        StreamingDictionaryValidator.validate(input, emptySet())
    }

    fun formatFileSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1 -> "%.1f MB".format(mb)
            kb >= 1 -> "%.1f KB".format(kb)
            else -> "$bytes B"
        }
    }
    
    /**
     * Extracts the language code from a Basic or Extended dictionary filename.
     *
     * Examples:
     * en_base.dict     -> en
     * en_extended.dict -> en
     */
    fun extractLanguageCode(filename: String): String {
        return filename
            .removeSuffix(".dict")
            .substringBefore("_")
    }
}
