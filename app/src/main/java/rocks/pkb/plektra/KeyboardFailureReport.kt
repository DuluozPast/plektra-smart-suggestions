package rocks.pkb.plektra

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.io.File

/** Local diagnostic only: exception classes/frames and heap figures, never typed
 * text, exception messages, settings dumps or personal dictionary contents.
 * The file is excluded from Android backup and from the application's exports.
 */
internal object KeyboardFailureReport {
    private var reserve: ByteArray? = ByteArray(64 * 1024)
    private var installed = false
    private var visibleDialog = java.lang.ref.WeakReference<AlertDialog>(null)
    private fun file(context: Context) = File(context.noBackupFilesDir, "keyboard-failure.txt")

    @Synchronized fun install(context: Context) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(context, "uncaught", error)
            previous?.uncaughtException(thread, error)
        }
    }

    @Synchronized fun record(context: Context, stage: String, error: Throwable) {
        reserve = null // A small emergency reserve allows reporting a heap failure.
        try {
            val runtime = Runtime.getRuntime()
            file(context).writeText(format(stage, error, runtime.maxMemory(),
                runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())))
        } catch (_: Exception) {
            // Reporting must never replace the original failure.
        } catch (_: OutOfMemoryError) { }
    }

    internal fun format(stage: String, error: Throwable, maxHeap: Long, headroom: Long): String = buildString {
        appendLine("Keyboard diagnostic ${BuildConfig.VERSION_NAME}")
        appendLine("stage=$stage maxHeap=$maxHeap headroom=$headroom")
        var current: Throwable? = error
        repeat(4) {
            val item = current ?: return@buildString
            appendLine(item.javaClass.name)
            item.stackTrace.take(18).forEach { appendLine("  at $it") }
            val next = item.cause
            current = if (next === item) null else next
        }
    }.take(12_000)

    fun showIfPresent(activity: Activity) {
        if (visibleDialog.get()?.isShowing == true) return
        val reportFile = file(activity)
        val report = try { if (reportFile.exists()) reportFile.readText().take(12_000) else null }
            catch (_: Exception) { null } ?: return
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.keyboard_failure_report_title)
            .setMessage(report)
            .setPositiveButton(R.string.debug_recorder_copy) { _, _ ->
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Keyboard diagnostic", report))
            }
            .setNeutralButton(R.string.debug_recorder_clear) { _, _ -> reportFile.delete() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        visibleDialog = java.lang.ref.WeakReference(dialog)
    }
}
