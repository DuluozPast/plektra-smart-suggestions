package rocks.pkb.plektra

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import rocks.pkb.plektra.inputmethod.speech.*
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechSettingsScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var futoAvailable by remember { mutableStateOf(ExternalSpeechProvider.isAvailable(context)) }
    var futoEnabled by remember { mutableStateOf(ExternalSpeechProvider.enabledVoiceInput(context) != null) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        futoAvailable = ExternalSpeechProvider.isAvailable(context)
        futoEnabled = ExternalSpeechProvider.enabledVoiceInput(context) != null
    }
    val client = remember { SpeechApiClient() }
    var provider by remember { mutableStateOf(SpeechSettings.provider(context)) }
    var silenceMs by remember { mutableStateOf(SpeechSettings.silenceMs(context)) }
    var catalog by remember { mutableStateOf(emptyList<SpeechModel>()) }
    var catalogRefresh by remember { mutableIntStateOf(0) }
    var selectedModel by remember(provider) { mutableStateOf(SpeechSettings.model(context, provider)) }
    var loading by remember { mutableStateOf(false) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var modelPicker by remember { mutableStateOf(false) }
    var silencePicker by remember { mutableStateOf(false) }
    var previewPicker by remember { mutableStateOf(false) }
    var continuousPreview by remember { mutableStateOf(SpeechSettings.continuousPreview(context)) }
    var showUsage by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showZdr by remember { mutableStateOf(false) }
    val usages = remember { SpeechUsageStore(context).all() }

    fun providerName(value: SpeechProvider) = when (value) {
        SpeechProvider.SYSTEM -> context.getString(R.string.speech_provider_system)
        SpeechProvider.FUTO -> "FUTO Voice Input"
        SpeechProvider.OPENROUTER -> "OpenRouter"
        SpeechProvider.OPENAI -> "OpenAI"
    }
    fun pauseName(value: Long) = when (value) {
        0L -> context.getString(R.string.speech_pause_manual)
        1500L -> context.getString(R.string.speech_pause_short)
        5000L -> context.getString(R.string.speech_pause_long)
        else -> context.getString(R.string.speech_pause_default)
    }

    fun refreshModels() {
        catalog = emptyList(); loading = true; catalogError = null
        catalogRefresh++
    }
    LaunchedEffect(provider, catalogRefresh) {
        if (provider == SpeechProvider.OPENROUTER) {
            catalog = emptyList(); loading = true; catalogError = null
            try {
                val models = client.models()
                val available = SpeechModels.selectZdrModel(models, selectedModel)
                catalog = models
                selectedModel = available
                SpeechSettings.setModel(context, provider, available)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { catalogError = speechErrorMessage(context, e) }
            finally { loading = false }
        } else {
            catalog = emptyList(); loading = false; catalogError = null
        }
    }

    Scaffold(topBar = {
        Surface(modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back_content_description)) }
                Text(stringResource(R.string.speech_settings_title), style = MaterialTheme.typography.headlineSmall)
            }
        }
    }) { padding ->
        Column(modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding()) {
            Text(stringResource(R.string.speech_language_description), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            SpeechSectionTitle(stringResource(R.string.speech_provider_title))
            Column(Modifier.settingRow("speech.provider").selectableGroup()) {
                SpeechProvider.entries.forEach { value ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = provider == value, role = Role.RadioButton) {
                            provider = value; SpeechSettings.setProvider(context, value)
                        }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = provider == value, onClick = null)
                        Text(providerName(value), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 12.dp))
            if (provider == SpeechProvider.FUTO) {
                SpeechNote(stringResource(if (futoAvailable) R.string.speech_external_installed else R.string.speech_external_missing))
                if (futoAvailable && !futoEnabled) {
                    TextButton(onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                        modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(stringResource(R.string.speech_external_enable))
                    }
                }
                SpeechNote(stringResource(R.string.speech_futo_description))
                TextButton(onClick = {
                    val settings = ExternalSpeechProvider.settingsIntent(context)
                    if (settings != null) {
                        try { context.startActivity(settings) }
                        catch (_: android.content.ActivityNotFoundException) { uriHandler.openUri(ExternalSpeechProvider.FUTO_DOWNLOAD_URL) }
                    } else uriHandler.openUri(ExternalSpeechProvider.FUTO_DOWNLOAD_URL)
                }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(if (futoAvailable) R.string.speech_external_settings else R.string.speech_external_install))
                }
            }
            if (provider.isCloud) {
                SpeechSettingRow(stringResource(R.string.speech_model_title), selectedModel.name, Icons.Default.Memory,
                    Modifier.settingRow("speech.model"), onClick = {
                        if (provider == SpeechProvider.OPENROUTER) refreshModels()
                        modelPicker = true
                    })
                if (provider == SpeechProvider.OPENROUTER) {
                    TextButton(enabled = !loading, onClick = { refreshModels() }, modifier = Modifier.padding(start = 48.dp)) {
                        Text(stringResource(if (loading) R.string.speech_models_loading else R.string.speech_models_refresh))
                    }
                    SpeechNote(stringResource(R.string.speech_zdr_filter))
                    catalogError?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
                }
                if (provider == SpeechProvider.OPENAI && selectedModel.protocol == SpeechProtocol.LIVE_TRANSCRIPTION) {
                    SpeechNote(stringResource(R.string.speech_live_description))
                }
                if (modelPicker) {
                    val defaults = if (provider == SpeechProvider.OPENAI) SpeechModels.openAiDefaults else SpeechModels.openRouterDefaults
                    val models = (if (provider == SpeechProvider.OPENAI) defaults + selectedModel else catalog).distinctBy { it.id }
                        .sortedWith(compareBy<SpeechModel> { defaults.indexOfFirst { default -> default.id == it.id }.let { index -> if (index < 0) Int.MAX_VALUE else index } }.thenBy { it.name })
                    AlertDialog(onDismissRequest = { modelPicker = false }, title = { Text(stringResource(R.string.speech_model_title)) },
                        text = {
                            if (provider == SpeechProvider.OPENROUTER && loading) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CircularProgressIndicator(Modifier.size(24.dp))
                                    Text(stringResource(R.string.speech_models_loading))
                                }
                            } else if (provider == SpeechProvider.OPENROUTER && catalogError != null) {
                                Column {
                                    Text(catalogError!!)
                                    TextButton(onClick = { refreshModels() }) { Text(stringResource(R.string.speech_models_refresh)) }
                                }
                            } else LazyColumn {
                                items(models, key = { it.id }) { model ->
                                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selectedModel.id == model.id, role = Role.RadioButton) {
                                        selectedModel = model; SpeechSettings.setModel(context, provider, model); modelPicker = false
                                    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selectedModel.id == model.id, onClick = null)
                                        Column(Modifier.padding(start = 8.dp)) {
                                            Text(model.name)
                                            Text(stringResource(when (model.protocol) {
                                                SpeechProtocol.AUDIO_CHAT -> R.string.speech_model_audio
                                                SpeechProtocol.LIVE_TRANSCRIPTION -> R.string.speech_model_live
                                                SpeechProtocol.TRANSCRIPTION -> R.string.speech_model_transcription
                                            }),
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }, confirmButton = { TextButton(onClick = { modelPicker = false }) { Text(stringResource(android.R.string.cancel)) } })
                }
            }
            if (provider != SpeechProvider.FUTO) {
                SpeechSettingRow(stringResource(R.string.speech_pause_title), pauseName(silenceMs), Icons.Default.Mic,
                    Modifier.settingRow("speech.pause"), onClick = { silencePicker = true })
            }
            SpeechSettingRow(stringResource(R.string.speech_preview_title),
                stringResource(if (continuousPreview) R.string.speech_preview_continuous else R.string.speech_preview_three_words),
                Icons.Default.Mic, Modifier.settingRow("speech.preview"), onClick = { previewPicker = true })
            if (provider == SpeechProvider.SYSTEM) {
                SpeechNote(stringResource(R.string.speech_system_pause_description))
            } else if (provider.isCloud) {
                SpeechStateLegend(silenceMs)
                if (silenceMs > 0) SpeechNote(stringResource(R.string.speech_cloud_pause_description))
                HorizontalDivider(Modifier.padding(top = 12.dp))
                SpeechSectionTitle(stringResource(R.string.speech_api_key_title))
                key(provider) { SpeechCredentialEditor(provider, client) }
                SpeechNote(stringResource(R.string.speech_cloud_description, providerName(provider)))
                SpeechNote(stringResource(R.string.speech_key_storage))
                if (provider == SpeechProvider.OPENROUTER) {
                    SpeechSettingRow(stringResource(R.string.speech_zdr_title),
                        stringResource(R.string.speech_zdr_summary), Icons.Default.PrivacyTip,
                        expanded = showZdr, onClick = { showZdr = !showZdr })
                    if (showZdr) {
                        SpeechNote(stringResource(R.string.speech_zdr_description))
                        SpeechNote(stringResource(R.string.speech_zdr_app_behavior))
                        TextButton(onClick = { uriHandler.openUri("https://openrouter.ai/settings/privacy") },
                            modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(stringResource(R.string.speech_zdr_settings))
                        }
                        TextButton(onClick = { uriHandler.openUri("https://openrouter.ai/docs/guides/features/zdr") },
                            modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(stringResource(R.string.speech_zdr_docs))
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 12.dp))
            SpeechSettingRow(stringResource(R.string.speech_usage_title),
                if (usages.isEmpty()) stringResource(R.string.speech_usage_empty)
                else stringResource(R.string.speech_usage_count, usages.sumOf { it.recordings }),
                Icons.Default.BarChart, Modifier.settingRow("speech.usage"), expanded = showUsage,
                onClick = { showUsage = !showUsage })
            if (showUsage) usages.forEach { usage ->
                val cost = if (usage.pricedRecordings == 0L) context.getString(R.string.speech_cost_unknown)
                    else context.getString(R.string.speech_cost_known, String.format(Locale.US, "%.6f", usage.costUsd), usage.pricedRecordings, usage.recordings)
                ListItem(headlineContent = { Text(usage.model) }, supportingContent = {
                    Text(context.getString(R.string.speech_usage_summary, usage.recordings, usage.words,
                        String.format(Locale.getDefault(), "%.1f", usage.durationMs / 60000.0),
                        String.format(Locale.getDefault(), "%.1f", usage.wordsPerMinute)) + "\n" + cost)
                })
            }
            HorizontalDivider()
            SpeechSettingRow(stringResource(R.string.speech_help_title), null, Icons.AutoMirrored.Filled.HelpOutline,
                expanded = showHelp, onClick = { showHelp = !showHelp })
            if (showHelp) SpeechNote(stringResource(R.string.speech_help_description))
            Spacer(Modifier.height(24.dp))
        }
    }
    if (previewPicker) AlertDialog(onDismissRequest = { previewPicker = false },
        title = { Text(stringResource(R.string.speech_preview_title)) },
        text = { Column {
            listOf(false, true).forEach { continuous ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                    selected = continuousPreview == continuous, role = Role.RadioButton) {
                    continuousPreview = continuous
                    SpeechSettings.setContinuousPreview(context, continuous)
                    previewPicker = false
                }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(continuousPreview == continuous, onClick = null)
                    Text(stringResource(if (continuous) R.string.speech_preview_continuous else R.string.speech_preview_three_words),
                        Modifier.padding(start = 8.dp))
                }
            }
            SpeechNote(stringResource(R.string.speech_preview_description))
        } }, confirmButton = { TextButton(onClick = { previewPicker = false }) { Text(stringResource(android.R.string.cancel)) } })

    if (silencePicker) AlertDialog(onDismissRequest = { silencePicker = false }, title = { Text(stringResource(R.string.speech_pause_title)) },
        text = { Column { listOf(1500L, 3000L, 5000L, 0L).forEach { value ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = silenceMs == value, role = Role.RadioButton) { silenceMs = value; SpeechSettings.setSilenceMs(context, value); silencePicker = false },
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(silenceMs == value, onClick = null); Text(pauseName(value), Modifier.padding(start = 8.dp))
            }
        } } }, confirmButton = { TextButton(onClick = { silencePicker = false }) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
private fun SpeechSectionTitle(title: String) {
    Text(title, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SpeechNote(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SpeechSettingRow(title: String, subtitle: String?, icon: ImageVector,
    modifier: Modifier = Modifier, expanded: Boolean = false, onClick: () -> Unit) {
    ListItem(modifier = modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ChevronRight, contentDescription = null) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeechStateLegend(silenceMs: Long) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Color(0xFFB3261E) to R.string.speech_legend_recording,

            Color(0xFF246AB4) to R.string.speech_legend_processing).toMutableList().apply {
                if (silenceMs > 0) add(1, Color(0xFFAE6700) to R.string.speech_legend_pause)
            }.forEach { (color, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Mic, contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(28.dp).background(color, CircleShape).padding(5.dp))
                Text(if (label == R.string.speech_legend_pause) stringResource(label, ((silenceMs + 999) / 1000).toInt())
                    else stringResource(label), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SpeechCredentialEditor(provider: SpeechProvider, client: SpeechApiClient) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var newKey by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(SpeechSettings.hasKey(context, provider)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var keyVisible by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().settingRow("speech.credentials").padding(horizontal = 16.dp)) {
        OutlinedTextField(value = newKey, onValueChange = { newKey = it; message = null }, enabled = !busy,
            label = { Text(stringResource(R.string.speech_provider_key, if (provider == SpeechProvider.OPENROUTER) "OpenRouter" else "OpenAI")) },
            placeholder = { Text(stringResource(R.string.speech_key_placeholder)) }, singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { keyVisible = !keyVisible }) {
                Icon(if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    stringResource(if (keyVisible) R.string.speech_key_hide else R.string.speech_key_show))
            } }, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text(stringResource(if (saved) R.string.speech_key_saved else R.string.speech_key_missing)) })
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), enabled = !busy && newKey.isNotBlank(), onClick = {
                try {
                    SpeechSettings.saveKey(context, provider, newKey)
                    newKey = ""; saved = true; message = context.getString(R.string.speech_key_saved)
                } catch (_: Exception) { message = context.getString(R.string.speech_key_save_error) }
            }) { Text(stringResource(R.string.speech_key_save)) }
            OutlinedButton(modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), enabled = !busy && (newKey.isNotBlank() || saved), onClick = {
                val candidate = newKey.trim()
                busy = true; message = null
                scope.launch {
                    try {
                        val key = candidate.ifBlank { SpeechSettings.readKey(context, provider) ?: "" }
                        client.validateKey(provider, key)
                        message = context.getString(R.string.speech_key_valid)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = speechErrorMessage(context, e) }
                    finally { busy = false }
                }
            }) { Text(stringResource(if (busy) R.string.speech_key_checking else R.string.speech_key_check)) }
        }
        if (saved) TextButton(enabled = !busy, onClick = {
            SpeechSettings.removeKey(context, provider)
            saved = false; message = null
        }) { Text(stringResource(R.string.speech_key_remove)) }
        message?.let { Text(it, Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodySmall) }
    }
}

internal fun speechErrorMessage(context: android.content.Context, error: Exception): String = when (error) {
    is SpeechHttpException -> when (error.status) {
        401, 403 -> context.getString(R.string.speech_error_auth)
        402 -> context.getString(R.string.speech_error_credits)
        429 -> context.getString(R.string.speech_error_rate_limit)
        else -> context.getString(R.string.speech_error_http, error.status)
    }
    is NoZdrModelException -> context.getString(R.string.speech_zdr_unavailable)
    is NoSpeechException -> context.getString(R.string.speech_recognition_error_no_match)
    is SpeechRealtimeException -> context.getString(R.string.speech_error_live)
    is java.io.IOException -> context.getString(R.string.speech_recognition_error_network)
    else -> context.getString(R.string.speech_recognition_error_generic)
}
