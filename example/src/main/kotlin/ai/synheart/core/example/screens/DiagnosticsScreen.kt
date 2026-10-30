package ai.synheart.core.example.screens

import ai.synheart.core.example.sdk.SynheartController
import ai.synheart.core.example.ui.CodeBlock
import ai.synheart.core.example.ui.KeyValueRow
import ai.synheart.core.example.ui.PillTone
import ai.synheart.core.example.ui.SectionCard
import ai.synheart.core.example.ui.StatusPill
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Step 4 — native runtime health.
 *
 * The field worth understanding is `missingSymbols`. Optional native symbols are
 * resolved lazily and a miss degrades gracefully: the feature behind it returns
 * null, -1, or an empty list rather than throwing. That is good for robustness
 * and terrible for debugging, because a runtime one release behind silently
 * disables whole feature areas.
 *
 * The caveat is sharp here: the bridge has no `probeAll` equivalent, so the
 * list only ever names symbols something already tried to resolve. An empty list is therefore ambiguous — it means "nothing has failed
 * *yet*", not "the runtime exports everything". Treat a non-empty list as
 * actionable and an empty one as unproven.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(c: SynheartController, padding: PaddingValues) {
    // Diagnostics are a pull, not a stream. Bumping this re-reads them.
    var refreshToken by remember { mutableIntStateOf(0) }
    val diag = remember(refreshToken) { c.diagnostics }
    var confirmWipe by remember { mutableStateOf(false) }

    val available = diag?.optBoolean("isAvailable") == true
    val version = diag?.optString("version")?.takeIf { it.isNotEmpty() }
        ?: c.runtimeVersion
    val frameCount = diag?.optInt("frameCount", 0) ?: 0
    val missing = diag?.optJSONArray("missingSymbols")?.let { arr ->
        (0 until arr.length()).map { arr.optString(it) }
    } ?: emptyList()

    Column(Modifier.padding(padding)) {
        TopAppBar(
            title = { Text("Runtime") },
            actions = {
                IconButton(onClick = { refreshToken++; c.refreshConsent() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Re-read diagnostics")
                }
            },
        )

        LazyColumn(Modifier.fillMaxWidth().padding(16.dp)) {
            item {
                SectionCard(
                    title = "Native runtime",
                    subtitle = if (available) {
                        "The JNI bridge loaded and the runtime is answering."
                    } else {
                        "Not loaded. Run `synheart install runtime` in example/, then " +
                            "rebuild — AGP packages the .so files from " +
                            "example/synheart/vendor/runtime/android/jniLibs."
                    },
                    trailing = {
                        StatusPill(
                            if (available) "loaded" else "missing",
                            if (available) PillTone.GOOD else PillTone.BAD,
                        )
                    },
                ) {
                    Column {
                        KeyValueRow("runtime version", version ?: "—")
                        KeyValueRow("sdk version", c.sdkVersion)
                        KeyValueRow("frames this session", "$frameCount")
                        KeyValueRow("buffered windows", "${c.sessionWindows.size}")
                        KeyValueRow("lab ABI", if (c.isLabAvailable) "available" else "absent")
                    }
                }
            }

            item {
                SectionCard(
                    title = "Native symbols",
                    subtitle = if (missing.isEmpty()) {
                        "Nothing has failed to resolve. Not the same as \"all present\" — " +
                            "optional bindings resolve lazily and this SDK has no " +
                            "probe-everything call, so an untouched symbol is never listed."
                    } else {
                        "${missing.size} optional symbols failed to resolve against this " +
                            "runtime, so the features behind them are disabled. Run " +
                            "`synheart install runtime` to update the runtime."
                    },
                    trailing = {
                        StatusPill(
                            if (missing.isEmpty()) "none failed" else "${missing.size} missing",
                            if (missing.isEmpty()) PillTone.GOOD else PillTone.WARN,
                        )
                    },
                ) {
                    Column {
                        Text(
                            "Covers the symbols bound through the guarded path, and only " +
                                "those something has already tried to use. Some ABIs — the " +
                                "lab session calls in particular — are bound eagerly and " +
                                "are not counted here: an absent one throws on first " +
                                "access rather than degrading. Check \"lab ABI\" above " +
                                "before calling any lab API.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (missing.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            CodeBlock(missing.joinToString("\n"))
                        }
                    }
                }
            }

            item { DailyScoresCard(c) }

            item {
                SectionCard(
                    title = "Local data",
                    subtitle = "Everything this example stores lives on the device: the " +
                        "runtime SQLite store, the SRM snapshot, and consent records.",
                ) {
                    OutlinedButton(
                        onClick = { confirmWipe = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Wipe local data")
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Wipe local data?") },
            text = {
                Text(
                    "Deletes the runtime SQLite store, the SRM snapshot, and cached " +
                        "consent records for this subject. Baselines restart from cold. " +
                        "This cannot be undone.",
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(onClick = { confirmWipe = false; c.wipeLocalData() }) {
                    Text("Wipe")
                }
            },
        )
    }
}

/**
 * The daily scores on runtime 0.45+ (C ABI 4.2): report last night to score
 * it against the personal baselines the runtime keeps, and check Readiness
 * takes its input. Baselines read `value @ confidence`; `—` is withheld.
 *
 * The night's daily values only count while a session runs (the runtime
 * refuses them without a pipeline), so start one on the Session tab first.
 */
@Composable
private fun DailyScoresCard(c: SynheartController) {
    val context = LocalContext.current
    var bed by remember { mutableStateOf(LocalTime.of(23, 0)) }
    var wake by remember { mutableStateOf(LocalTime.of(7, 0)) }
    var recovery by remember { mutableStateOf("70") }
    LaunchedEffect(Unit) { c.refreshDailyBaselines() }

    fun pick(initial: LocalTime, onPicked: (LocalTime) -> Unit) {
        TimePickerDialog(
            context,
            { _, h, m -> onPicked(LocalTime.of(h, m)) },
            initial.hour,
            initial.minute,
            true,
        ).show()
    }

    val score = c.lastSleepScore
    val readiness = c.lastReadiness
    val sleep = c.sleepBaselines
    SectionCard(
        title = "Daily scores",
        subtitle = "Report last night to score it. Baselines show value @ confidence; " +
            "— is withheld. Start a session first so the night's values are stored.",
        trailing = {
            StatusPill(
                if (score?.score == null) "no score" else "sleep ${score.score}",
                if (score?.score == null) PillTone.NEUTRAL else PillTone.GOOD,
            )
        },
    ) {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pick(bed) { bed = it } }, modifier = Modifier.weight(1f)) {
                    Text("Bed $bed")
                }
                OutlinedButton(onClick = { pick(wake) { wake = it } }, modifier = Modifier.weight(1f)) {
                    Text("Wake $wake")
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val zone = ZoneId.systemDefault()
                    val today = LocalDate.now(zone)
                    val wakeAt = today.atTime(wake).atZone(zone)
                    var bedAt = today.atTime(bed).atZone(zone)
                    // A bedtime later in the day than the wake time was last night.
                    if (!bedAt.isBefore(wakeAt)) bedAt = bedAt.minusDays(1)
                    c.logSelfReportedSleep(bedAt.toInstant(), wakeAt.toInstant())
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Log last night") }
            Spacer(Modifier.height(12.dp))

            if (score != null) {
                KeyValueRow("sleep score", score.score?.toString() ?: "—")
                KeyValueRow("mode", score.mode.wire)
                KeyValueRow("confidence", "%.2f".format(score.confidence))
                KeyValueRow("pipeline", score.pipelineVersion)
                KeyValueRow("night values stored", c.lastNightPushes ?: "—")
            }
            KeyValueRow("midpoint baseline", c.describeRef(sleep?.midpointH))
            KeyValueRow("overnight HR baseline", c.describeRef(sleep?.overnightHrBpm))
            KeyValueRow("deep baseline", c.describeRef(sleep?.deepMin))
            KeyValueRow("REM baseline", c.describeRef(sleep?.remMin))
            KeyValueRow("recovery refs", c.baselineRefsSummary)

            Spacer(Modifier.height(12.dp))
            Text(
                "Readiness needs today's Recovery Score, which needs overnight HRV this " +
                    "example has no source for. Enter one to check the runtime takes the " +
                    "input; nothing is stored.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = recovery,
                    onValueChange = { v -> recovery = v.filter { it.isDigit() }.take(3) },
                    label = { Text("Recovery") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(120.dp),
                )
                OutlinedButton(
                    onClick = { recovery.toIntOrNull()?.let { c.computeReadiness(it) } },
                    enabled = score?.score != null && recovery.toIntOrNull() != null,
                    modifier = Modifier.weight(1f).padding(top = 8.dp),
                ) { Text("Compute readiness") }
            }
            if (readiness != null) {
                KeyValueRow("readiness", "${readiness.score} (${readiness.band.label})")
                KeyValueRow("confidence", "%.2f".format(readiness.confidence))
                KeyValueRow("model", readiness.modelVersion)
                KeyValueRow("explanation", readiness.explanation.joinToString { it.wire })
            }
        }
    }
}
