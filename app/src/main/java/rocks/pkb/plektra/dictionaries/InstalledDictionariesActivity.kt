package rocks.pkb.plektra.dictionaries
import rocks.pkb.plektra.core.suggestions.UserDictionaryStore
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import rocks.pkb.plektra.LocalizedComponentActivity
import rocks.pkb.plektra.R
import rocks.pkb.plektra.ui.theme.PlektraTheme
import java.io.File
import java.io.InputStream
import java.util.Locale
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.provider.OpenableColumns
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import rocks.pkb.plektra.core.suggestions.DictionaryIndex

/**
 * Activity that shows the list of serialized dictionaries bundled with the app
 * and available for download from the online repository.
 */
class InstalledDictionariesActivity : LocalizedComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PlektraTheme {
                InstalledDictionariesScreen(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    onBack = { finish() }
                )
            }
        }
    }
}

/**
 * Unified dictionary item that can represent both installed and available online dictionaries.
 */
internal data class UnifiedDictionaryItem(
    val languageCode: String,
    val displayName: String,
    val fileName: String,
    val isInstalled: Boolean,
    val isActive: Boolean = false,
    val source: DictionarySource,
    val variant: DictionaryVariant = DictionaryVariant.Basic,
    val wordCount: Int? = null,
    val onlineItem: DictionaryItem? = null,
    val downloadProgress: Pair<Long, Long>? = null,
    val isDownloading: Boolean = false
)

internal enum class DictionarySource {
    Asset,
    Imported,
    Online
}
/**
 * Dictionary content variant, independent from where the dictionary comes from.
 */
internal enum class DictionaryVariant {
    Basic,
    Extended
}

@Composable
fun InstalledDictionariesScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var installedDictionaries by remember { mutableStateOf(loadSerializedDictionaries(context)) }
    var onlineManifest by remember { mutableStateOf<DictionaryManifest?>(DictionaryRepositoryManager.offlineCatalog(context)) }
    var isLoadingManifest by remember { mutableStateOf(false) }
    var manifestError by remember { mutableStateOf<String?>(null) }
    var downloadingItems by remember { mutableStateOf<Set<String>>(emptySet()) }
    var downloadProgress by remember { mutableStateOf<Map<String, Pair<Long, Long>>>(emptyMap()) }
    var dictionaryToDelete by remember { mutableStateOf<UnifiedDictionaryItem?>(null) }
    
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    
    // Load online manifest on first launch
    LaunchedEffect(Unit) {
        Log.d("InstalledDictionaries", "Starting manifest load...")
        isLoadingManifest = true
        manifestError = null
        DictionaryRepositoryManager.fetchManifest()
            .onSuccess { manifest ->
                Log.d("InstalledDictionaries", "Manifest loaded successfully: ${manifest.items.size} items")
                onlineManifest = manifest
                isLoadingManifest = false
            }
            .onFailure { error ->
                manifestError = error.message
                isLoadingManifest = false
                Log.e("InstalledDictionaries", "Failed to load manifest: ${error.message}", error)
            }
    }
    
    // Merge installed and online dictionaries
    val unifiedDictionaries = remember(installedDictionaries, onlineManifest, downloadingItems, downloadProgress) {
        mergeDictionaries(installedDictionaries, onlineManifest, downloadingItems, downloadProgress)
    }
    
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val result = importDictionaryFromSaf(context, uri)
                val message = when (result) {
                    is ImportResult.Success -> {
                        DictionaryRepositoryManager.notifyDictionariesChanged(context)
                        installedDictionaries = loadSerializedDictionaries(context)
                        context.getString(
                            R.string.installed_dictionaries_import_success,
                            result.fileName
                        )
                    }
                    is ImportResult.InvalidName -> context.getString(R.string.installed_dictionaries_import_invalid_name)
                    is ImportResult.InvalidFormat -> context.getString(R.string.installed_dictionaries_import_invalid_format)
                    is ImportResult.CopyError -> context.getString(R.string.installed_dictionaries_import_failed)
                    ImportResult.UnsupportedUri -> context.getString(R.string.installed_dictionaries_import_failed)
                }
                snackbarHostState.showSnackbar(message)
            }
        }
    }
    
    fun refreshManifest() {
        Log.d("InstalledDictionaries", "Refreshing manifest...")
        coroutineScope.launch {
            isLoadingManifest = true
            manifestError = null
            DictionaryRepositoryManager.fetchManifest()
                .onSuccess { manifest ->
                    Log.d("InstalledDictionaries", "Manifest refreshed: ${manifest.items.size} items")
                    onlineManifest = manifest
                    isLoadingManifest = false
                }
                .onFailure { error ->
                    manifestError = error.message
                    isLoadingManifest = false
                    Log.e("InstalledDictionaries", "Failed to refresh manifest: ${error.message}", error)
                }
        }
    }
    
    fun downloadDictionary(item: DictionaryItem) {
        if (downloadingItems.contains(item.id)) return
        
        coroutineScope.launch {
            downloadingItems = downloadingItems + item.id
            downloadProgress = downloadProgress - item.id
            
            val result = DictionaryRepositoryManager.downloadDictionary(
                context = context,
                item = item,
                onProgress = { downloaded, total ->
                    downloadProgress = downloadProgress + (item.id to (downloaded to total))
                }
            )
            
            downloadingItems = downloadingItems - item.id
            downloadProgress = downloadProgress - item.id
            
            val message = when (result) {
                is DownloadResult.Success -> {
                    installedDictionaries = loadSerializedDictionaries(context)
                    context.getString(R.string.installed_dictionaries_download_success, item.name)
                }
                DownloadResult.NetworkError -> context.getString(R.string.installed_dictionaries_download_network_error)
                DownloadResult.InvalidFormat -> context.getString(R.string.installed_dictionaries_download_invalid_format)
                DownloadResult.HashMismatch -> context.getString(R.string.installed_dictionaries_download_hash_mismatch)
                DownloadResult.CopyError -> context.getString(R.string.installed_dictionaries_download_failed)
            }
            
            snackbarHostState.showSnackbar(message)
        }
    }

    fun activateDictionary(dictionary: UnifiedDictionaryItem) {
        val activeDictionaryPrefs =
            context.getSharedPreferences("active_dictionaries", Context.MODE_PRIVATE)

        Log.i(
            "InstalledDictionaries",
            "ACTIVATE_DEBUG language=${dictionary.languageCode} file=${dictionary.fileName}"
        )

        activeDictionaryPrefs.edit()
            .putString(dictionary.languageCode, dictionary.fileName)
            .apply()

        installedDictionaries = loadSerializedDictionaries(context)

        context.sendBroadcast(
            android.content.Intent(
                rocks.pkb.plektra.AppBroadcastActions.BASE_DICTIONARY_UPDATED
            ).setPackage(context.packageName)
        )
    }

    fun uninstallDictionary(dictionary: UnifiedDictionaryItem) {
        coroutineScope.launch {
            val result = deleteDictionaryFile(context, dictionary)
            val message = when (result) {
                is UninstallResult.Success -> {
                    if (dictionary.isActive) {
                        context.getSharedPreferences("active_dictionaries", Context.MODE_PRIVATE)
                            .edit()
                            .remove(dictionary.languageCode)
                            .apply()
                    }

                    DictionaryRepositoryManager.notifyDictionariesChanged(context)
                    installedDictionaries = loadSerializedDictionaries(context)

                    /*
                     * Restore personal entries that had been temporarily removed because
                     * they were already covered by this language's Extended dictionary.
                     */
                    val locale = Locale.forLanguageTag(dictionary.languageCode)

                    val restoredDuplicates =
                        UserDictionaryStore().restoreWordsRemovedForBase(
                            context = context,
                            locale = locale
                        )

                    Log.i(
                        "InstalledDictionaries",
                        "Uninstalled ${dictionary.fileName}. " +
                                "Restored $restoredDuplicates personal dictionary entries."
                    )

                    /*
                     * Notify the running IME that the Extended dictionary was removed.
                                     * The repository will be rebuilt and will fall back to the bundled
                                     * Basic dictionary for this language.
                                     */
                    context.sendBroadcast(
                        android.content.Intent(
                            rocks.pkb.plektra.AppBroadcastActions.BASE_DICTIONARY_UPDATED
                        ).setPackage(context.packageName)
                    )

                    context.getString(
                        R.string.installed_dictionaries_uninstall_success,
                        dictionary.displayName
                    )
                }
                UninstallResult.NotFound -> context.getString(R.string.installed_dictionaries_uninstall_not_found)
                UninstallResult.CannotDeleteAsset -> context.getString(R.string.installed_dictionaries_uninstall_cannot_delete_asset)
                UninstallResult.DeleteError -> context.getString(R.string.installed_dictionaries_uninstall_failed)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars),
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back_content_description)
                        )
                    }
                    Text(
                        text = stringResource(R.string.installed_dictionaries_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .weight(1f)
                    )
                    if (isLoadingManifest) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = { refreshManifest() }) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.installed_dictionaries_refresh)
                            )
                        }
                    }
                    IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.installed_dictionaries_import)
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        if (unifiedDictionaries.isEmpty() && !isLoadingManifest) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.installed_dictionaries_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (manifestError != null) {
                        Text(
                            text = stringResource(R.string.installed_dictionaries_manifest_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(
                    unifiedDictionaries,
                    key = { "${it.languageCode}_${it.fileName}_${it.source}" }
                ) { dictionary ->
                    UnifiedDictionaryItem(
                        dictionary = dictionary,
                        onDownload = { onlineItem ->
                            if (onlineItem != null) {
                                downloadDictionary(onlineItem)
                            }
                        },
                        onUninstall = { dict ->
                            dictionaryToDelete = dict
                        },
                        onActivate = { dict ->
                            activateDictionary(dict)
                        }
                    )
                }
            }
        }
    }
    
    // Uninstall confirmation dialog
    dictionaryToDelete?.let { dictionary ->
        AlertDialog(
            onDismissRequest = { dictionaryToDelete = null },
            title = {
                Text(stringResource(R.string.installed_dictionaries_uninstall_confirm_title))
            },
            text = {
                Text(stringResource(R.string.installed_dictionaries_uninstall_confirm_message, dictionary.displayName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        uninstallDictionary(dictionary)
                        dictionaryToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.installed_dictionaries_uninstall))
                }
            },
            dismissButton = {
                TextButton(onClick = { dictionaryToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun UnifiedDictionaryItem(
    dictionary: UnifiedDictionaryItem,
    onDownload: (DictionaryItem?) -> Unit,
    onUninstall: (UnifiedDictionaryItem) -> Unit,
    onActivate: (UnifiedDictionaryItem) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = dictionary.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )

                    Text(
                        text = dictionary.fileName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Text(
                        text = stringResource(
                            R.string.installed_dictionaries_language_code,
                            dictionary.languageCode.uppercase(Locale.getDefault())
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Text(
                        text = when (dictionary.variant) {
                            DictionaryVariant.Basic ->
                                stringResource(R.string.installed_dictionaries_variant_basic)

                            DictionaryVariant.Extended ->
                                stringResource(R.string.installed_dictionaries_variant_extended)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (dictionary.variant == DictionaryVariant.Extended) {
                            FontWeight.Medium
                        } else {
                            FontWeight.Normal
                        },
                        maxLines = 1
                    )

                    dictionary.wordCount?.let { wordCount ->
                        Text(
                            text = stringResource(
                                R.string.installed_dictionaries_word_count,
                                wordCount
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    if (dictionary.onlineItem != null) {
                        Text(
                            text = stringResource(
                                R.string.installed_dictionaries_size,
                                DictionaryRepositoryManager.formatFileSize(dictionary.onlineItem.bytes)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (dictionary.isInstalled) {
                            Text(
                                text = stringResource(R.string.installed_dictionaries_installed_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (dictionary.source == DictionarySource.Imported) {
                            Text(
                                text = stringResource(R.string.installed_dictionaries_imported_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        if (dictionary.onlineItem != null && !dictionary.isInstalled) {
                            Text(
                                text = stringResource(R.string.installed_dictionaries_available_online),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }

                if (dictionary.isDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    if (dictionary.isInstalled && !dictionary.isActive) {
                        TextButton(
                            onClick = { onActivate(dictionary) }
                        ) {
                            Text("Activer")
                        }
                    }

                    // Uninstall button (only for imported/downloaded dictionaries, not assets)
                    if (dictionary.isInstalled && dictionary.source != DictionarySource.Asset) {
                        IconButton(
                            onClick = { onUninstall(dictionary) }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.installed_dictionaries_uninstall),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    // Download button (only for online dictionaries not yet installed)
                    if (dictionary.onlineItem != null && !dictionary.isInstalled) {
                        IconButton(onClick = { onDownload(dictionary.onlineItem) }) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = stringResource(R.string.installed_dictionaries_download)
                            )
                        }
                    }
                }
            }

            if (dictionary.isDownloading && dictionary.downloadProgress != null) {
                val (downloaded, total) = dictionary.downloadProgress
                val progress = if (total > 0) downloaded.toFloat() / total.toFloat() else 0f
                LinearProgressIndicator(progress = { progress })
            }
        }
    }
}

/**
 * Merges installed dictionaries with online available dictionaries.
 */
/**
 * Merges installed Basic dictionaries with dictionaries available online.
 *
 * Dictionary source (Asset / Imported / Online) and dictionary variant
 * (Basic / Extended) are intentionally kept separate. This allows an
 * installed bundled Basic dictionary and an available Extended dictionary
 * for the same language to coexist in the UI.
 */
private fun mergeDictionaries(
    installed: List<InstalledDictionary>,
    onlineManifest: DictionaryManifest?,
    downloadingItems: Set<String>,
    downloadProgress: Map<String, Pair<Long, Long>>
): List<UnifiedDictionaryItem> {
    Log.d(
        "InstalledDictionaries",
        "Merging dictionaries - Installed: ${installed.size}, " +
                "Online: ${onlineManifest?.items?.size ?: 0}"
    )

    val installedItems = installed.map { dictionary ->
        UnifiedDictionaryItem(
            languageCode = dictionary.languageCode,
            displayName = dictionary.displayName,
            fileName = dictionary.fileName,
            isInstalled = true,
            isActive = dictionary.isActive,
            source = dictionary.source,
            variant = dictionary.variant,
            wordCount = dictionary.wordCount
        )
    }

    val onlineItems = onlineManifest?.items.orEmpty().map { dictionary ->
        val languageCode =
            DictionaryRepositoryManager.extractLanguageCode(dictionary.filename)

        val variant = when (dictionary.variant.lowercase(Locale.ROOT)) {
            "extended" -> DictionaryVariant.Extended
            else -> DictionaryVariant.Basic
        }

        /*
         * A bundled Basic dictionary satisfies an online Basic entry for the
         * same language. An Extended entry remains a separate downloadable
         * item even when its filename matches the bundled Basic filename.
         */
        val matchingInstalled = installed.firstOrNull { local ->
            local.fileName.equals(dictionary.filename, ignoreCase = true)
        }

        UnifiedDictionaryItem(
            languageCode = languageCode,
            displayName = dictionary.name,
            fileName = dictionary.filename,
            isInstalled = matchingInstalled != null,
            isActive = matchingInstalled?.isActive ?: false,
            source = matchingInstalled?.source ?: DictionarySource.Online,
            variant = variant,
            wordCount = dictionary.wordCount,
            onlineItem = dictionary,
            downloadProgress = downloadProgress[dictionary.id],
            isDownloading = dictionary.id in downloadingItems
        )
    }

    /*
     * When an online item matches an already installed dictionary with the same
     * language and variant, keep the richer online representation and remove the
     * duplicate local row. This applies equally to Basic and Extended variants.
     */
    val installedOnlineKeys = onlineItems
        .filter { it.isInstalled }
        .map {
            it.fileName.lowercase(Locale.ROOT)
        }
        .toSet()

    val localItemsToKeep = installedItems.filterNot { local ->
        local.fileName.lowercase(Locale.ROOT) in installedOnlineKeys
    }
    (localItemsToKeep + onlineItems).forEach {
        Log.i(
            "InstalledDictionaries",
            "LIST_DEBUG file=${it.fileName} installed=${it.isInstalled} active=${it.isActive} source=${it.source}"
        )
    }
    val result = (localItemsToKeep + onlineItems)
        .sortedWith(
            compareBy<UnifiedDictionaryItem>(
                { it.displayName.lowercase(Locale.getDefault()) },
                { it.variant.ordinal }
            )
        )

    Log.d(
        "InstalledDictionaries",
        "Final merged list: ${result.size} items"
    )

    result.forEach { item ->
        Log.d(
            "InstalledDictionaries",
            "  - ${item.displayName}: variant=${item.variant}, " +
                    "installed=${item.isInstalled}, " +
                    "hasOnline=${item.onlineItem != null}"
        )
    }

    return result
}

private data class InstalledDictionary(
    val languageCode: String,
    val displayName: String,
    val fileName: String,
    val isActive: Boolean = false,
    val source: DictionarySource,
    val variant: DictionaryVariant,
    val wordCount: Int? = null
)

/**
 * Loads the dictionaries currently available to the keyboard.
 *
 * Bundled asset dictionaries are the Basic variant. A dictionary stored in
 * the custom directory replaces the bundled dictionary for the same language
 * at runtime and is therefore represented as the installed Extended variant.
 */
private fun loadSerializedDictionaries(
    context: Context
): List<InstalledDictionary> {
    return try {
        Log.d("InstalledDictionaries", "Loading installed dictionaries...")

        val assetFiles = context.assets
            .list("common/dictionaries_serialized")
            ?.filter { it.endsWith("_base.dict") }
            ?: emptyList()

        Log.d(
            "InstalledDictionaries",
            "Found ${assetFiles.size} dictionaries in assets: " +
                    assetFiles.joinToString(", ")
        )

        val customDir =
            File(context.filesDir, "dictionaries_serialized/custom")

        val activeDictionaryPrefs =
            context.getSharedPreferences("active_dictionaries", Context.MODE_PRIVATE)

        fun activeFilenameFor(languageCode: String): String =
            activeDictionaryPrefs.getString(
                languageCode,
                "${languageCode}_base.dict"
            ) ?: "${languageCode}_base.dict"

        val customFiles = customDir
            .listFiles { file ->
                file.isFile && file.name.endsWith(".dict")
            }
            ?.map { it.name }
            ?: emptyList()

        Log.d(
            "InstalledDictionaries",
            "Found ${customFiles.size} custom dictionaries: " +
                    customFiles.joinToString(", ")
        )

        val assetDictionaries = assetFiles.map { fileName ->
            val languageCode =
                DictionaryRepositoryManager.extractLanguageCode(fileName)

            InstalledDictionary(
                languageCode = languageCode,
                displayName = getLanguageDisplayName(languageCode),
                fileName = fileName,
                isActive = activeFilenameFor(languageCode) == fileName,
                source = DictionarySource.Asset,
                variant = DictionaryVariant.Basic,
                wordCount = 50_000
            )
        }

        val customDictionaries = customFiles.map { fileName ->
            val languageCode =
                DictionaryRepositoryManager.extractLanguageCode(fileName)

            InstalledDictionary(
                languageCode = languageCode,
                displayName = getLanguageDisplayName(languageCode),
                fileName = fileName,
                isActive = fileName == activeFilenameFor(languageCode),
                source = DictionarySource.Imported,
                variant = DictionaryVariant.Extended
            )
        }

        /*
         * The runtime loader gives a custom dictionary priority over the
         * bundled asset for the same language. Mirror that behavior here so
         * the UI reports the dictionary that is actually active.
         */

        val result = (assetDictionaries + customDictionaries)
            .sortedBy {
                it.displayName.lowercase(Locale.getDefault())
            }

        Log.i(
            "InstalledDictionaries",
            "LOAD_DEBUG " +
                    result
                        .filter { it.languageCode == "fr" }
                        .joinToString(" | ") {
                            "${it.fileName}: active=${it.isActive}"
                        }
        )

        Log.d(
            "InstalledDictionaries",
            "Loaded ${result.size} active dictionaries: " +
                    result.joinToString(", ") {
                        "${it.displayName} (${it.variant}, ${it.source})"
                    }
        )

        result
    } catch (e: Exception) {
        Log.e(
            "InstalledDictionaries",
            "Error reading serialized dictionaries",
            e
        )
        emptyList()
    }
}

private sealed interface ImportResult {
    data class Success(val fileName: String) : ImportResult
    object InvalidName : ImportResult
    object InvalidFormat : ImportResult
    object CopyError : ImportResult
    object UnsupportedUri : ImportResult
}

private sealed interface UninstallResult {
    data class Success(val fileName: String) : UninstallResult
    object NotFound : UninstallResult
    object CannotDeleteAsset : UninstallResult
    object DeleteError : UninstallResult
}

@OptIn(ExperimentalSerializationApi::class)
private suspend fun importDictionaryFromSaf(context: Context, uri: android.net.Uri): ImportResult {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex != -1 && cursor.moveToFirst()) {
            cursor.getString(nameIndex)
        } else null
    } ?: return ImportResult.InvalidName
    if (!name.endsWith(".dict", ignoreCase = true)) {
        return ImportResult.InvalidName
    }

    val destDir = File(context.filesDir, "dictionaries_serialized/custom").apply { mkdirs() }
    val destFile = File(destDir, name)

    return try {
        require(destFile.canonicalFile.parentFile == destDir.canonicalFile) { "Invalid dictionary name" }
        val languageCode = DictionaryRepositoryManager.extractLanguageCode(name)
        val locale = Locale.forLanguageTag(languageCode)
        val normalizedWords = withContext(Dispatchers.IO) {
            val personalKeys = UserDictionaryStore().personalKeysForBase(context, locale)
            context.contentResolver.openInputStream(uri)?.use { input ->
                DictionaryFileInstaller.install(input, destFile, personalKeys)
            }
        } ?: return ImportResult.CopyError
        // Return to the UI/IME thread before updating personal entries, so an
        // import does not introduce concurrent read-modify-write operations.


        val removedDuplicates = UserDictionaryStore().removeWordsAlreadyInBase(
            context = context,
            baseNormalizedWords = normalizedWords,
            locale = locale
        )

        Log.i(
            "InstalledDictionaries",
            "Imported ${destFile.name}. Removed $removedDuplicates duplicate personal dictionary entries."
        )

        /*
         * Notify the running IME that the imported base dictionary has changed.
         * The repository cache will be invalidated and the new dictionary loaded.
         */
        context.sendBroadcast(
            android.content.Intent(
                rocks.pkb.plektra.AppBroadcastActions.BASE_DICTIONARY_UPDATED
            ).setPackage(context.packageName)
        )

        ImportResult.Success(destFile.name)
    } catch (oom: OutOfMemoryError) {
        // Validation expands legacy Extended files in memory; keep the app alive.
        rocks.pkb.plektra.KeyboardFailureReport.record(context, "dictionary-import-memory", oom)
        Log.e("InstalledDictionaries", "Not enough memory to validate dictionary", oom)
        ImportResult.CopyError
    } catch (e: SerializationException) {
        rocks.pkb.plektra.KeyboardFailureReport.record(context, "dictionary-import-format", e)
        Log.e("InstalledDictionaries", "Invalid dictionary format", e)
        ImportResult.InvalidFormat
    } catch (e: Exception) {
        rocks.pkb.plektra.KeyboardFailureReport.record(context, "dictionary-import-io", e)
        Log.e("InstalledDictionaries", "Error importing dictionary", e)
        ImportResult.CopyError
    }
}


private fun deleteDictionaryFile(
    context: Context,
    dictionary: UnifiedDictionaryItem
): UninstallResult {
    return try {
        // Bundled Basic dictionaries are part of the application and cannot be deleted.
        if (dictionary.source == DictionarySource.Asset) {
            Log.w(
                "InstalledDictionaries",
                "Cannot delete asset dictionary: ${dictionary.fileName}"
            )
            return UninstallResult.CannotDeleteAsset
        }

        /*
         * Remote Extended dictionaries use a distinct repository filename
         * (for example "fr_extended.dict"), but are installed locally using
         * the runtime-compatible "<language>_base.dict" filename.
         */
        val customDir = File(
            context.filesDir,
            "dictionaries_serialized/custom"
        )

        val installedFilename = dictionary.fileName

        val dictFile = File(customDir, installedFilename)

        if (!dictFile.exists()) {
            Log.w(
                "InstalledDictionaries",
                "Dictionary file not found: ${dictFile.absolutePath}"
            )
            return UninstallResult.NotFound
        }

        if (dictFile.delete()) {
            Log.d(
                "InstalledDictionaries",
                "Successfully deleted dictionary: $installedFilename"
            )
            UninstallResult.Success(installedFilename)
        } else {
            Log.e(
                "InstalledDictionaries",
                "Failed to delete dictionary file: ${dictFile.absolutePath}"
            )
            UninstallResult.DeleteError
        }
    } catch (e: Exception) {
        Log.e(
            "InstalledDictionaries",
            "Error deleting dictionary: ${dictionary.fileName}",
            e
        )
        UninstallResult.DeleteError
    }
}

private fun getLanguageDisplayName(languageCode: String): String {
    return try {
        val locale = Locale.forLanguageTag(languageCode)
        locale.getDisplayLanguage(locale).replaceFirstChar { char ->
            if (char.isLowerCase()) {
                char.titlecase(locale)
            } else {
                char.toString()
            }
        }
    } catch (e: Exception) {
        languageCode.uppercase(Locale.getDefault())
    }
}