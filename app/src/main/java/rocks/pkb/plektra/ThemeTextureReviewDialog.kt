package rocks.pkb.plektra

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Reuses the real renderers at the dialog's full phone width, rather than stretching a swatch. */
@Composable
internal fun ThemeTextureReviewDialog(texture: KeyboardThemeTexture, theme: KeyboardThemePreset,
    role: String, isSoftware: Boolean, coupled: Boolean, onDismiss: () -> Unit,
    onChangePhoto: () -> Unit, onApply: (KeyboardThemeTexture) -> Unit) {
    val global = role == ThemeTextureModel.ALL_SURFACES
    var mode by rememberSaveable(texture.asset) { mutableStateOf(texture.mode) }
    var intensity by rememberSaveable(texture.asset) { mutableStateOf(ThemeTextureModel.normalizedIntensity(texture)) }
    var continuous by rememberSaveable(texture.asset) { mutableStateOf(texture.continuous) }
    var software by rememberSaveable { mutableStateOf(isSoftware || role.startsWith("key_popup")) }
    var adjusted by remember(texture.asset) { mutableStateOf<KeyboardThemeTexture?>(texture.takeIf { it.foreground != null }) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val selected = (adjusted ?: texture.copy(foreground = null, halo = false)).copy(mode = mode, continuous = continuous || global, wholeKeyboard = texture.wholeKeyboard || global, intensity = intensity)
    val preview = theme.withSurfaceTexture(role, selected, coupled)
    val scale = ThemeTextureMetrics.scale(selected)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().heightIn(max = maxHeight), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.theme_texture_preview), Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("cover" to R.string.theme_texture_cover, "fit" to R.string.theme_texture_fit,
                        "tile" to R.string.theme_texture_tile).forEach { (value, label) ->
                        FilterChip(modifier = Modifier.weight(1f), selected = mode == value, enabled = !busy, onClick = { if(mode != value) { mode = value; adjusted = null } }, label = { Text(stringResource(label), maxLines = 2) })
                    }
                }
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(modifier = Modifier.weight(1f), selected = !software, onClick = { software = false }, label = { Text(stringResource(R.string.theme_texture_hardware), maxLines = 2) })
                    FilterChip(modifier = Modifier.weight(1f), selected = software, onClick = { software = true }, label = { Text(stringResource(R.string.theme_texture_software), maxLines = 2) })
                }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.theme_texture_intensity) + " · " + (intensity * 100).toInt() + " %")
                    Slider(value = intensity, onValueChange = { intensity = it; adjusted = null },
                        valueRange = 0f..1f, enabled = !busy)
                }
                if(!global) Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(checked = continuous, onCheckedChange = { continuous = it }, enabled = !busy)
                    Text(stringResource(if (continuous) R.string.theme_texture_continuous else R.string.theme_texture_per_key), Modifier.padding(start = 8.dp).weight(1f))
                }
                if(global) Text(stringResource(R.string.theme_texture_global_hint), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onChangePhoto, enabled = !busy, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.theme_texture_change_photo))
                }
                OutlinedButton(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(), enabled = !busy, onClick = {
                    if (adjusted != null) adjusted = null else scope.launch {
                        busy = true; failed = false
                        try {
                            val base = when(role) {
                                "background", ThemeTextureModel.ALL_SURFACES -> theme.background; "normal_key" -> theme.normalKey
                                "special_key" -> theme.specialKey; "suggestion" -> theme.suggestion
                                "status_bar_button" -> theme.statusBarButton; "key_popup" -> theme.keyPopup
                                else -> theme.keyPopupSelected
                            }
                            adjusted = ThemeTextureContrast.propose(context, selected, base)
                        } catch (_: Exception) { failed = true } finally { busy = false }
                    }
                }) { Text(stringResource(if (adjusted != null) R.string.theme_texture_manual_text else R.string.theme_texture_adapt_text)) }
                if (failed) Text(stringResource(R.string.theme_texture_contrast_failed), Modifier.padding(horizontal = 16.dp))
                if (software) VirtualKeyboardThemePreview(preview, SettingsManager.KEYBOARD_THEME_PREVIEW_VIEWPORT_SCALE_MIN)
                else HardwareKeyboardThemePreview(preview)
                if (scale > 4f) Text(stringResource(R.string.theme_texture_enlargement, scale),
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Button(enabled = !busy, onClick = { onApply(selected) }) { Text(stringResource(R.string.theme_texture_apply)) }
                }
            }
        }
    }
}
