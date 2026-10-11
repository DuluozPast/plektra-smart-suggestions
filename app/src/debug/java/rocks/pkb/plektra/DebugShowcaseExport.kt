package rocks.pkb.plektra

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import org.json.JSONObject
import rocks.pkb.plektra.inputmethod.ImeChromeLayout
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

internal object DebugShowcaseExport {
    const val ENABLED = "transparent_ime_export"
    private val pending = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    fun afterDraw(view: View) {
        if (!view.context.getSharedPreferences("showcase-export", Context.MODE_PRIVATE)
                .getBoolean(ENABLED, false) || !pending.add(view)) return
        view.postDelayed({
            try {
                if (view.isShown) {
                    view.context.getSharedPreferences("showcase-export", Context.MODE_PRIVATE)
                        .edit().putBoolean(ENABLED, false).apply()
                    export(view as ImeChromeLayout)
                }
            } catch (error: Exception) {
                android.util.Log.e("ShowcaseExport", "Transparent export failed", error)
            } finally {
                pending.remove(view)
            }
        }, 250)
    }

    fun exportVisible(): String {
        fun find(view: View): ImeChromeLayout? {
            if (!view.isShown) return null
            if (view is ImeChromeLayout) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                find(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        val view = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find)
            ?: error("No visible Plektra keyboard")
        return export(view).absolutePath
    }

    private fun export(view: ImeChromeLayout): File {
        require(view.width > 0 && view.height > 0) { "Keyboard has no measured content" }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.drawTransparentArtwork(Canvas(bitmap))
            val dir = File(view.context.cacheDir, "debug-reports").apply { mkdirs() }
            val target = File(dir, "keyboard-transparent.png")
            val temporary = File(dir, "keyboard-transparent.tmp")
            temporary.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            require(temporary.renameTo(target)) { "Could not save transparent PNG" }
            val screen = IntArray(2).also(view::getLocationOnScreen)
            File(dir, "keyboard-transparent.json").writeText(JSONObject()
                .put("width", view.width).put("height", view.height)
                .put("screen_x", screen[0]).put("screen_y", screen[1])
                .put("version", BuildConfig.VERSION_NAME).put("rendering", "native-view-alpha")
                .toString(2))
            return target
        } finally {
            bitmap.recycle()
        }
    }
}

@Composable
internal fun DebugShowcaseExportSetting() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("showcase-export", Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(prefs.getBoolean(DebugShowcaseExport.ENABLED, false)) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == DebugShowcaseExport.ENABLED) enabled = prefs.getBoolean(key, false)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Transparent keyboard export", style = MaterialTheme.typography.titleMedium)
                Text("Save the next displayed keyboard as a PNG with transparency. May include suggestions and clipboard text.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(enabled, {
                enabled = it
                prefs.edit().putBoolean(DebugShowcaseExport.ENABLED, it).apply()
            })
        }
        TextButton(onClick = {
            val file = File(context.cacheDir, "debug-reports/keyboard-transparent.png")
            if (!file.exists()) Toast.makeText(context, "Show the keyboard first", Toast.LENGTH_SHORT).show()
            else {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share transparent keyboard"))
            }
        }) { Text("Share PNG") }
    }
}
