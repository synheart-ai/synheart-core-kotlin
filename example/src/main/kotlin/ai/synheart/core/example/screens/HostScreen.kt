package ai.synheart.core.example.screens

import ai.synheart.core.example.sdk.SynheartController
import ai.synheart.core.example.ui.ErrorBanner
import ai.synheart.core.example.ui.KeyValueRow
import ai.synheart.core.example.ui.PillTone
import ai.synheart.core.example.ui.SectionCard
import ai.synheart.core.example.ui.StatusPill
import ai.synheart.core.models.AccelPlacement
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Backspace
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Step 5 — the host-driven half of the integration.
 *
 * Everything on the other tabs is configuration: build a config, grant
 * consent, start a session. This tab is what a mobile host has to keep *doing*
 * once that is done, and it is the half that was missing. Section headings map
 * onto the mobile host implementation guide, because the point of the screen
 * is to make each item observable rather than described.
 *
 * The first card is the one to read before the others. Every call below is
 * looked up optionally against the vendored runtime, which is a pinned
 * artifact that lags this SDK — so on any given device some of this is running
 * and some is a logged no-op, and guessing which is not possible from outside.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostScreen(c: SynheartController, padding: PaddingValues) {
    val host = c.host

    Column(Modifier.padding(padding)) {
        TopAppBar(
            title = { Text("Mobile host") },
            actions = {
                Box(Modifier.padding(end = 16.dp)) {
                    StatusPill(
                        if (host.isRunning) "driving" else "idle",
                        if (host.isRunning) PillTone.GOOD else PillTone.NEUTRAL,
                    )
                }
            },
        )

        LazyColumn(Modifier.fillMaxWidth().padding(16.dp)) {
            host.lastError?.let { item { ErrorBanner(it) } }
            item { AbiSupportCard(c) }
            item { TickLoopCard(c) }
            item { RestCard(c) }
            item { PlacementCard(c) }
            item { TypingProbeCard(c) }
            item { ContextCard(c) }
            item { SnapshotsCard(c) }
            item { DailyLoopCard(c) }
            item { NotWiredCard() }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Muted(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** §1 — what the loaded runtime can actually do. */
@Composable
private fun AbiSupportCard(c: SynheartController) {
    val host = c.host
    val support = host.abiSupport
    val missing = host.unsupportedCalls

    SectionCard(
        title = "Runtime ABI",
        subtitle = "Which mobile-host calls the vendored native runtime exports. A binding " +
            "existing in the Kotlin SDK says nothing about whether the call does anything " +
            "on this device.",
        trailing = {
            StatusPill(
                when {
                    support.isEmpty() -> "no runtime"
                    missing.isEmpty() -> "complete"
                    else -> "${missing.size} missing"
                },
                when {
                    support.isEmpty() -> PillTone.BAD
                    missing.isEmpty() -> PillTone.GOOD
                    else -> PillTone.WARN
                },
            )
        },
    ) {
        Column {
            if (support.isEmpty()) {
                Muted("The native runtime is not loaded, so none of this tab does anything. " +
                    "Initialize on the Setup tab first.")
            } else {
                for ((name, ok) in support) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (ok) Icons.Filled.CheckCircle else Icons.Outlined.Cancel,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                // Which cargo features the runtime was built with. Without
                // `app-context`, push_context_event is an inert stub returning 1 —
                // indistinguishable from a rejected payload unless this is read.
                Text("runtime build", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                val info = c.buildInfo
                if (info == null) {
                    Muted("build_info unavailable")
                } else {
                    for (key in info.keys()) KeyValueRow(key, info.opt(key).toString())
                }
                if (missing.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Muted(
                        "Each missing call degrades to a no-op, or to a null return for the " +
                            "ones that report status. That is deliberate — a hard symbol lookup " +
                            "would fail library load outright for a host that has not " +
                            "re-vendored — but it means the feature behind it is silently off. " +
                            "Run `synheart install runtime` to update the pinned artifact.\n\n" +
                            "push_context_event needs more than a recent artifact: without the " +
                            "`app-context` cargo feature it compiles to an inert stub that " +
                            "always returns 1.",
                    )
                }
            }
        }
    }
}

/** §6.1 / §6.2 / §6.4 — the tick loop and the keep-alive. */
@Composable
private fun TickLoopCard(c: SynheartController) {
    val host = c.host
    val usingTickAll = host.abiSupport["tickAll"] == true

    SectionCard(
        title = "Tick loop",
        subtitle = "The engine runs its own 1 Hz ticker from start_session — but with tick, " +
            "not tick_all, so after any gap it drains one window and skips the rest. This " +
            "loop calls tick_all so nothing is left behind.",
        trailing = {
            StatusPill(if (host.isRunning) "1 Hz" else "stopped", if (host.isRunning) PillTone.GOOD else PillTone.NEUTRAL)
        },
    ) {
        Column {
            KeyValueRow("ticks", "${host.ticks}")
            // Undercounts by design: windows the engine's own loop drains never
            // pass through this counter. A low number is not evidence that
            // emission is broken — watch the Live HSI window count instead.
            KeyValueRow("windows drained here", "${host.windowsDrained}")
            KeyValueRow("symbol", if (usingTickAll) "tick_all" else "tick (fallback)")
            KeyValueRow(
                "keep-alive",
                when {
                    host.foregroundServiceRunning -> "dataSync foreground service running"
                    host.isRunning -> "NOT running — collection stops on background"
                    else -> "stopped with the session"
                },
            )
            Spacer(Modifier.height(8.dp))
            Muted(
                if (usingTickAll) {
                    "tick_all drains every completed window, oldest first. After a background " +
                        "gap tick would poll one window and silently skip the rest, which is " +
                        "why the loop prefers this and only falls back when the symbol is absent."
                } else {
                    "This runtime has no tick_all, so the loop is falling back to tick — one " +
                        "window per call. A gap longer than one window loses the windows it " +
                        "spanned, and nothing counts them."
                },
            )
            Spacer(Modifier.height(8.dp))
            Muted(
                "Without the foreground service the tick loop dies the moment Android stops " +
                    "scheduling this process: the session emits nothing from then on, " +
                    "silently, while the UI still says \"collecting\". Bound to the session " +
                    "here rather than started from an IME, so it covers the whole thing and " +
                    "not just typing.\n\n" +
                    "Ordering, per §6.4: the loop drains its own retroactive buffers (the " +
                    "typing aggregator below) BEFORE each tick.",
            )
        }
    }
}

/** §6.5 — rest declaration. */
@Composable
private fun RestCard(c: SynheartController) {
    val host = c.host
    val supported = host.abiSupport["declareRestWindow"] == true

    SectionCard(
        title = "Rest windows",
        subtitle = "Without a rest declaration Focus is never zeroed on a break and Capacity " +
            "never takes the recovery path — break windows score as engaged.",
        trailing = {
            StatusPill(
                "${host.restDeclarations} declared",
                if (host.restDeclarations > 0) PillTone.GOOD else PillTone.NEUTRAL,
            )
        },
    ) {
        Column {
            KeyValueRow("condition", host.restDeclineReason ?: "satisfied / not evaluated")
            KeyValueRow("engine accel_rms", host.latestAccelRms?.let { "%.3f".format(it) } ?: "no reading")
            Spacer(Modifier.height(8.dp))
            Muted(
                "Composite: screen off ≥ 2 min AND no interaction AND low motion, with a " +
                    "wall-clock sleep window as an override. Screen off alone is wrong in both " +
                    "directions.\n\nA null motion reading is not treated as low motion: with no " +
                    "accelerometer forwarding the clause is unverifiable, and declaring on two " +
                    "of three conditions would call a walk with the phone pocketed a rest window.",
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { host.declareRestNow() },
                enabled = supported && c.isSessionRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Bedtime, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Declare rest for this window")
            }
            Spacer(Modifier.height(6.dp))
            Muted(
                if (supported) {
                    "Manual, because the composite needs two minutes of screen-off and nobody " +
                        "demos that. One-shot by design: a sticky flag would pin Focus at " +
                        "exactly 0.0 for the rest of the session, silently. Once per rest " +
                        "WINDOW, not once when a break begins."
                } else {
                    "declare_rest_window is absent from this runtime, so every break window " +
                        "on this device scores as engaged."
                },
            )
        }
    }
}

/** §4.3 — accelerometer placement. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlacementCard(c: SynheartController) {
    val host = c.host
    val supported = host.abiSupport["setAccelPlacement"] == true
    val placement = host.placement

    SectionCard(
        title = "Accelerometer placement",
        subtitle = "The four kinematic heads are requested in the config, but they withhold " +
            "until a body-worn mount is declared. UNKNOWN — the default — withholds all of them.",
        trailing = {
            StatusPill(
                if (placement.isValidatedEnvelope) "in envelope" else "out of envelope",
                if (placement.isValidatedEnvelope) PillTone.GOOD else PillTone.WARN,
            )
        },
    ) {
        Column {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in AccelPlacement.entries) {
                    FilterChip(
                        selected = placement == p,
                        onClick = { host.setAccelPlacement(p) },
                        enabled = supported,
                        label = { Text(p.name.lowercase()) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Muted(
                "Only pocket and waist are inside the validated envelope. Wrist and chest are " +
                    "body-worn but outside it; desk is not body-worn at all.\n\nThere is no " +
                    "hand-held placement, and during exactly the interaction the digital axes " +
                    "measure — typing, scrolling — the phone is in the hand. So placement is " +
                    "genuinely dynamic and a fixed compile-time pocket is wrong the moment the " +
                    "person picks the device up.\n\nAccelerometer samples reach the runtime " +
                    "through the SDK's AccelForwarder at 50 Hz (emitRawMotionSamples). Until a " +
                    "labelled dataset validates pocket geometry on phones, treat mobile " +
                    "kinematics as a validation target rather than a shipped capability.",
            )
            if (!supported) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "set_accel_placement is absent from this runtime — the kinematic heads stay dark.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** §5.3 — the typing micro-window probe. */
@Composable
private fun TypingProbeCard(c: SynheartController) {
    val host = c.host
    val supported = host.abiSupport["pushBehaviorEvent"] == true
    var text by remember { mutableStateOf("") }

    SectionCard(
        title = "Typing micro-windows",
        subtitle = "The richest input the engine takes, and mobile produced none of it. Desktop " +
            "aggregates key events into 10 s micro-windows and emits one Typing event per " +
            "window at ts = window_start_ms; this mirrors that shape.",
        trailing = {
            StatusPill(
                "${host.typingWindowsPushed} pushed",
                if (host.typingWindowsPushed > 0) PillTone.GOOD else PillTone.NEUTRAL,
            )
        },
    ) {
        Column {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    host.onTypingChanged(it)
                },
                enabled = supported && c.isSessionRunning,
                label = { Text("Type here for ten seconds") },
                supportingText = {
                    Text(
                        if (supported) "Backspaces count — they produce typing.correction_rate"
                        else "push_behavior_event is absent from this runtime",
                    )
                },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            KeyValueRow("buffered taps", "${host.pendingTypingTaps}")
            KeyValueRow("windows pushed", "${host.typingWindowsPushed}")
            KeyValueRow("taps in those windows", "${host.typingTapsPushed}")
            Spacer(Modifier.height(8.dp))
            Muted(
                "Only fields a text field can actually measure are populated — tap count, " +
                    "backspaces, the measured first→last span, speed, mean inter-tap interval, " +
                    "pauses over 500 ms, cadence stability and variability. Everything else is " +
                    "null, and that is a report rather than an omission: the engine withholds " +
                    "a null and renormalises it out, whereas 0.0 is a MEASURED zero that moves " +
                    "the score.\n\nNot double-counted: the raw keystrokes behind these summaries " +
                    "take the legacy int-coded path inside the SDK, and a failed rich push is " +
                    "deliberately NOT retried through it.",
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { text = ""; host.resetTypingProbe() }) {
                Icon(Icons.Outlined.Backspace, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Clear probe")
            }
        }
    }
}

/** §5.5 — app identity and the context channel. Two channels, two consumers. */
@Composable
private fun ContextCard(c: SynheartController) {
    val host = c.host
    val context = LocalContext.current
    val supported = host.abiSupport["pushContextEvent"] == true
    val richSupported = host.abiSupport["pushBehaviorEvent"] == true

    SectionCard(
        title = "App context",
        subtitle = "Two separate channels. App identity types the window; context events " +
            "supply the friction evidence CFI reads.",
        trailing = { StatusPill(if (supported) "bound" else "absent", if (supported) PillTone.NEUTRAL else PillTone.WARN) },
    ) {
        Column {
            KeyValueRow("app_foreground pushes", "${host.appForegroundPushes}")
            KeyValueRow("context accepted", "${host.contextEventsAccepted}")
            KeyValueRow("context rejected", "${host.contextEventsRejected}")
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { host.declareSelfForeground(context) },
                enabled = richSupported && c.isSessionRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Apps, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Declare this app as foreground")
            }
            Spacer(Modifier.height(8.dp))
            Muted(
                "APP IDENTITY rides the behaviour channel as app_foreground and is what gives " +
                    "the engine an app to type each window against. With none, current_app stays " +
                    "None, None resolves to UNKNOWN, and UNKNOWN's interpretation-mask row is all " +
                    "zeros — CFI / Cognitive Load, Stress B, Mental Fatigue B and Focus deviation " +
                    "terms all read 0 for someone who was working the whole time. The SDK runs a " +
                    "30 s heartbeat of this; the button makes it observable.\n\n" +
                    "CONTEXT EVENTS are a different channel: privacy-preserving keyboard / pointer / " +
                    "shortcut events feeding the context window, the only source of " +
                    "context.deviation.* and so of CFI. There is NO app-category variant — an " +
                    "{app_id, category} payload never parses. The SDK derives LeftClick and Scroll " +
                    "from REAL touches; the keyboard half comes from the typing probe, one event " +
                    "per real keystroke. There is no button to push a hand-made one: cardiac is " +
                    "the only thing this example simulates. Type or scroll and watch " +
                    "\"context accepted\".\n\n" +
                    "This app reports ITSELF, true while the person is in it and wrong the moment " +
                    "they leave — which is why the SDK stops on background. A real host implements " +
                    "ForegroundAppSource over UsageStatsManager (PACKAGE_USAGE_STATS).",
            )
        }
    }
}

/** §7 / §9.5 — the three snapshots and the comparability key. */
@Composable
private fun SnapshotsCard(c: SynheartController) {
    val host = c.host
    val sizes = host.storedSnapshotSizes

    SectionCard(
        title = "Persisted snapshots",
        subtitle = "Three, not one. The runtime's own SQLite covers session state only; " +
            "everything the engine accumulates about the person lives in snapshots the host " +
            "exports and re-loads.",
        trailing = {
            StatusPill(
                "${host.sessionStateSaves} state saves",
                if (host.sessionStateSaves > 0) PillTone.GOOD else PillTone.NEUTRAL,
            )
        },
    ) {
        Column {
            for ((k, v) in sizes) KeyValueRow(k, if (v == 0) "not stored" else "$v bytes")
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            KeyValueRow("config_id", host.configId ?: "unavailable")
            KeyValueRow("device_class key", c.hostDeviceClass)
            if (host.configIdChanged) {
                Spacer(Modifier.height(8.dp))
                ErrorBanner(
                    "config_id changed since the stored snapshots were written. Any score cached " +
                        "under the old key is not comparable to a new one — config_id moves " +
                        "whenever anything value-affecting changes, including the sensing and " +
                        "mask_profile declarations.",
                )
            }
            Spacer(Modifier.height(8.dp))
            Muted(
                "session state carries Capacity, Mental Fatigue, Stress, Valence and the " +
                    "context engine, and is loaded at initialize() — before the first tick, " +
                    "because window 1 writes each head's state slot and a later restore is " +
                    "overwritten by a cold window.\n\nSRM carries the personal baseline and is " +
                    "exported at session end; without it the baselines report Warming forever. " +
                    "Keyed per device_class: a cross-class load is rejected with " +
                    "ERR_SRM_CONFIG_MISMATCH, which is the baseline partition working.\n\n" +
                    "longitudinal carries the wearable reference, the 7-night sleep ring and " +
                    "today's partial daily accumulator, and is written after every recompute.",
            )
        }
    }
}

/** §8 — the daily loop. */
@Composable
private fun DailyLoopCard(c: SynheartController) {
    val host = c.host
    val supported = host.abiSupport["rollDay"] == true

    SectionCard(
        title = "Daily loop",
        subtitle = "Recovery, Readiness, Strain and Sleep are DAILY scores with no live " +
            "per-window head. Desktop implements none of this, so there is no reference to copy.",
        trailing = {
            StatusPill("${host.dailyPushes} dimensions", if (host.dailyPushes > 0) PillTone.GOOD else PillTone.NEUTRAL)
        },
    ) {
        Column {
            KeyValueRow("roll_day", if (supported) "bound" else "absent")
            KeyValueRow("strain attach", if (host.abiSupport["attachStrainScore"] == true) "bound" else "absent")
            KeyValueRow("strain scores attached", "${host.strainScoresAttached}")
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { host.pushSimulatedDailyBaselines() },
                enabled = c.isSessionRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.CalendarToday, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Push a day of wearable baselines")
            }
            Spacer(Modifier.height(8.dp))
            Muted(
                "srm_push_wearable_daily is the ONLY baseline path for a phone with no live " +
                    "wearable session. It was bound and never called.\n\nThe values here come " +
                    "from the simulator rather than Health Connect, because this example holds " +
                    "no health permission — a real host reads them from the platform store and " +
                    "sends fidelity: 1 for a provider summary. Both dimensions are heart-derived. " +
                    "Nothing is padded to zero.\n\nStrain is scored BEFORE roll_day (§8.4): rolling " +
                    "clears the values it is computed from, so a host that rolls first never emits " +
                    "a Strain score while the load still reaches the baselines. Null on a fresh " +
                    "install's first roll is normal.\n\nroll_day runs at session start and at LOCAL " +
                    "midnight; skip it and the engine adopts a provisional UTC day. The index " +
                    "must strictly advance, so the last one is persisted and checked.",
            )
        }
    }
}

/** What this example does not do, and why. */
@Composable
private fun NotWiredCard() {
    val items = listOf(
        "subject_age_years / hr_max_bpm / hr_rest_bpm" to
            "The engine's PipelineConfig has these fields, but core-runtime never reads them " +
            "out of the config JSON — so passing them changes nothing. Heart-rate-reserve " +
            "features run on fallbacks and daily hr_load stays withheld until core-runtime " +
            "plumbs them through.",
        "UsageStatsManager → AppSwitch identity" to
            "Android-native work in synheart-behavior-kotlin, plus PACKAGE_USAGE_STATS. Without " +
            "it AppSwitch carries no app ids and Android has no context layer.",
        "Real foreground-app identity" to
            "The SDK reports this app's own package as app_foreground, which is true while the " +
            "person is here. Naming the app actually in front needs a ForegroundAppSource over " +
            "UsageStatsManager (PACKAGE_USAGE_STATS, granted through Settings) — not built here.",
        "Real GPS speed" to
            "push_speed is bound and never called. The only speed this example could supply is " +
            "one the simulator invented, and cardiac is the only thing it is allowed to " +
            "simulate. locomotion_state therefore runs on its accel-only fallback until a real " +
            "location stream is wired.",
        "Scheduled daily job" to
            "WorkManager. The midnight roll here is a coroutine, so it only fires if the app is " +
            "alive across midnight; otherwise the day rolls at the next session start.",
        "PhoneModule's collectors" to
            "Not enabled here — buildConfig declares no phoneConfig. Its four collectors are " +
            "Random() generators for motion, screen state, app focus and notifications, and " +
            "they never reached the runtime (no bridge reference). The module needs replacing " +
            "with real platform collectors or deleting.",
        "Live wear samples → runtime" to
            "WearModule caches and streams samples but never pushes them into the runtime; " +
            "the session adapter forwards them to synheart-session, not to the engine. A real " +
            "strap or Health Connect feed stops at wearSampleStream. Each host reimplements " +
            "the push today.",
    )
    SectionCard(title = "Still not wired", subtitle = "Gaps this example does not close, and what each one needs.") {
        Column {
            for ((title, body) in items) {
                Column(Modifier.padding(bottom = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Muted(body)
                }
            }
        }
    }
}
