package ai.synheart.core.example.screens

import ai.synheart.core.example.sdk.SynheartController
import ai.synheart.core.example.ui.CodeBlock
import ai.synheart.core.example.ui.KeyValueRow
import ai.synheart.core.example.ui.PillTone
import ai.synheart.core.example.ui.SectionCard
import ai.synheart.core.example.ui.StatusPill
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
