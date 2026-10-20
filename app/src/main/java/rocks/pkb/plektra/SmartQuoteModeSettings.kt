package rocks.pkb.plektra

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun SmartQuoteModeSettings(mode: String, style: String,
    onModeChanged: (String) -> Unit, onStyleChanged: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().settingRow(SettingLinkIds.TEXT_INPUT_SMART_QUOTES).padding(vertical = 8.dp)) {
        Text(stringResource(R.string.smart_quotes_title), style = MaterialTheme.typography.titleSmall)
        listOf(SettingsManager.SMART_QUOTES_OFF to R.string.quote_mode_off,
            SettingsManager.SMART_QUOTES_DEFERRED to R.string.quote_mode_deferred,
            SettingsManager.SMART_QUOTES_IMMEDIATE to R.string.quote_mode_immediate).forEach { (value, label) ->
            Row(Modifier.fillMaxWidth().clickable { onModeChanged(value) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == value, onClick = { onModeChanged(value) })
                Text(stringResource(label), Modifier.weight(1f))
            }
        }
        if (mode == SettingsManager.SMART_QUOTES_IMMEDIATE) {
            Text(stringResource(R.string.quote_immediate_help), style = MaterialTheme.typography.bodySmall)
        }
        if (mode == SettingsManager.SMART_QUOTES_DEFERRED) {
            val options = listOf(SettingsManager.SMART_QUOTES_STYLE_GERMAN_GUILLEMETS to "»…«",
                SettingsManager.SMART_QUOTES_STYLE_FRENCH_GUILLEMETS to "«…»",
                SettingsManager.SMART_QUOTES_STYLE_FRENCH_GUILLEMETS_NARROW_SPACED to "« … »",
                SettingsManager.SMART_QUOTES_STYLE_GERMAN_LOW_HIGH to "„…“",
                SettingsManager.SMART_QUOTES_STYLE_ENGLISH_CURLY to "“…”")
            Box {
                TextButton(onClick = { expanded = true }) { Text(options.firstOrNull { it.first == style }?.second ?: "»…«") }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { (value, label) -> DropdownMenuItem(text = { Text(label) }, onClick = {
                        onStyleChanged(value); expanded = false
                    }) }
                }
            }
        }
    }
}
