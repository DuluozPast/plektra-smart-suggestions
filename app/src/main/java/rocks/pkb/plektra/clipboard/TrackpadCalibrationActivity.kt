package rocks.pkb.plektra

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rocks.pkb.plektra.ui.theme.PlektraTheme
import kotlinx.coroutines.delay

class TrackpadCalibrationActivity : LocalizedComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PlektraTheme {
                if (intent.getBooleanExtra("delete_swipe", false)) {
                    DeleteSwipeCalibrationScreen { finish() }
                } else TrackpadCalibrationScreen(onClose = { finish() })
            }
        }
    }

    override fun onStop() {
        if (intent.getBooleanExtra("delete_swipe", false)) DeleteSwipeCalibration.cancel(this)
        super.onStop()
    }

    override fun onDestroy() {
        SettingsManager.cancelTrackpadCalibrationSession(this)
        if (intent.getBooleanExtra("delete_swipe", false)) DeleteSwipeCalibration.cancel(this)
        super.onDestroy()
    }
}

@Composable
private fun TrackpadCalibrationScreen(
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }

    var text by remember { mutableStateOf("") }

    var session by remember {
        mutableStateOf(SettingsManager.getTrackpadCalibrationSession(context))
    }

    /*
     * The service writes calibration samples to SharedPreferences.
     * Polling keeps this Activity synchronized with the IME process/path
     * and automatically advances through the three calibration zones.
     */
    LaunchedEffect(Unit) {
        while (true) {
            val latestSession =
                SettingsManager.getTrackpadCalibrationSession(context)

            if (latestSession != null) {
                when (latestSession.target) {
                    SettingsManager.TrackpadCalibrationTarget.LEFT -> {
                        if (latestSession.leftSamples.size >= 10) {
                            SettingsManager.setTrackpadCalibrationTarget(
                                context,
                                SettingsManager.TrackpadCalibrationTarget.CENTER
                            )
                        }
                    }

                    SettingsManager.TrackpadCalibrationTarget.CENTER -> {
                        if (latestSession.centerSamples.size >= 10) {
                            SettingsManager.setTrackpadCalibrationTarget(
                                context,
                                SettingsManager.TrackpadCalibrationTarget.RIGHT
                            )
                        }
                    }

                    SettingsManager.TrackpadCalibrationTarget.RIGHT -> {
                        // Keep all 30 samples until Save is pressed.
                    }
                }
            }

            session =
                SettingsManager.getTrackpadCalibrationSession(context)

            delay(200)
        }
    }
    /*
     * A real focused text editor is essential here:
     * Pastiera must be running as the active IME while calibration gestures
     * are performed.
     */
    LaunchedEffect(Unit) {
        delay(300)
        focusRequester.requestFocus()
    }

    val currentSession = session

    val targetLabel = when (currentSession?.target) {
        SettingsManager.TrackpadCalibrationTarget.LEFT ->
            context.getString(R.string.trackpad_calibration_target_left)

        SettingsManager.TrackpadCalibrationTarget.CENTER ->
            context.getString(R.string.trackpad_calibration_target_center)

        SettingsManager.TrackpadCalibrationTarget.RIGHT ->
            context.getString(R.string.trackpad_calibration_target_right)

        null ->
            context.getString(R.string.trackpad_calibration_target_left)
    }

    val sampleCount = currentSession?.currentSamples?.size ?: 0

    val calibrationComplete =
        currentSession != null &&
                currentSession.leftSamples.size >= 10 &&
                currentSession.centerSamples.size >= 10 &&
                currentSession.rightSamples.size >= 10

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(
                start = 24.dp,
                end = 24.dp,
                top = 16.dp,
                bottom = 24.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "$targetLabel — ${sampleCount.coerceAtMost(10)}/10",
            style = MaterialTheme.typography.titleLarge
        )

        Text(
            text = context.getString(
                R.string.trackpad_calibration_instruction,
                targetLabel
            ),
            style = MaterialTheme.typography.bodyLarge
        )

        LinearProgressIndicator(
            progress = {
                (sampleCount / 10f).coerceIn(0f, 1f)
            },
            modifier = Modifier.fillMaxWidth()
        )

        /*
         * Keep a genuine text editor focused so Pastiera receives
         * the same input/trackpad path as during normal typing.
         */
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 2.dp
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .focusRequester(focusRequester),
                textStyle = LocalTextStyle.current.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp
                ),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopStart
                    ) {
                        if (text.isEmpty()) {
                            Text(
                                text = context.getString(
                                    R.string.trackpad_calibration_text_field_hint
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        innerTextField()
                    }
                }
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    SettingsManager.cancelTrackpadCalibrationSession(context)
                    onClose()
                }
            ) {
                Text(
                    context.getString(R.string.trackpad_calibration_cancel)
                )
            }

            Button(
                enabled = calibrationComplete,
                onClick = {
                    val finalSession =
                        SettingsManager.getTrackpadCalibrationSession(context)

                    if (finalSession != null) {
                        val leftMedian =
                            median(finalSession.leftSamples)

                        val centerMedian =
                            median(finalSession.centerSamples)

                        val rightMedian =
                            median(finalSession.rightSamples)

                        if (
                            leftMedian != null &&
                            centerMedian != null &&
                            rightMedian != null &&
                            leftMedian < centerMedian &&
                            centerMedian < rightMedian
                        ) {
                            val leftCenterBoundary =
                                (leftMedian + centerMedian) / 2f

                            val centerRightBoundary =
                                (centerMedian + rightMedian) / 2f

                            SettingsManager.setTrackpadSuggestionCalibration(
                                context = context,
                                leftCenterBoundary = leftCenterBoundary,
                                centerRightBoundary = centerRightBoundary
                            )

                            SettingsManager.cancelTrackpadCalibrationSession(context)

                            onClose()
                        }
                    }
                }
            ) {
                Text(
                    context.getString(R.string.trackpad_calibration_save)
                )
            }
        }
    }
}

private fun median(values: List<Float>): Float? {
    if (values.isEmpty()) {
        return null
    }

    val sorted = values.sorted()
    val middle = sorted.size / 2

    return if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2f
    } else {
        sorted[middle]
    }
}
/** A focused editor keeps the IME active while five natural leftward gestures are sampled. */
@Composable
private fun DeleteSwipeCalibrationScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    var count by remember { mutableStateOf(DeleteSwipeCalibration.count(context)) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        while (true) { count = DeleteSwipeCalibration.count(context); delay(200) }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(context.getString(R.string.delete_calibration_title), style = MaterialTheme.typography.headlineSmall)
        Text(context.getString(R.string.delete_calibration_help))
        Text("$count / 5", style = MaterialTheme.typography.headlineMedium)
        BasicTextField(value = text, onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().height(80.dp).focusRequester(focus))
        Button(enabled = count == 5, onClick = { if (DeleteSwipeCalibration.save(context)) onClose() }) {
            Text(context.getString(R.string.delete_calibration_save))
        }
        TextButton(onClick = { DeleteSwipeCalibration.start(context); count = 0; focus.requestFocus() }) {
            Text(context.getString(R.string.delete_calibration_restart))
        }
        TextButton(onClick = { DeleteSwipeCalibration.clear(context); onClose() }) {
            Text(context.getString(R.string.delete_calibration_reset))
        }
        TextButton(onClick = onClose) { Text(context.getString(android.R.string.cancel)) }
    }
}
