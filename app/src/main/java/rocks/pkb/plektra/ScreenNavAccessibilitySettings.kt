package rocks.pkb.plektra

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import rocks.pkb.plektra.inputmethod.ScreenNavAccessibilityService

@Composable internal fun ScreenNavAccessibilitySettingsCard() {
    val context=LocalContext.current
    val component=remember(context) { ComponentName(context,ScreenNavAccessibilityService::class.java) }
    val available=remember(component) { try { context.packageManager.getServiceInfo(component,0);true } catch(_: Exception) { false } }
    if(!available) return
    fun enabled(): Boolean {
        val manager=context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
            val service=info.resolveInfo.serviceInfo
            ComponentName(service.packageName,service.name)==component
        }
    }
    var active by remember { mutableStateOf(enabled()) }
    val owner=androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer=androidx.lifecycle.LifecycleEventObserver { _,event ->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME) active=enabled()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.screen_nav_title),style=MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.screen_nav_description),style=MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.screen_text_settings_description),style=MaterialTheme.typography.bodySmall)
            Text(stringResource(if(active) R.string.screen_nav_enabled else R.string.screen_nav_disabled),style=MaterialTheme.typography.labelMedium)
            OutlinedButton(onClick={
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }) { Text(stringResource(R.string.screen_nav_open_settings)) }
        }
    }
}
