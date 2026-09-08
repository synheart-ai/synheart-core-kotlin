package ai.synheart.core.example.screens

import ai.synheart.core.example.sdk.SynheartController
import ai.synheart.core.example.ui.ErrorBanner
import ai.synheart.core.example.ui.KeyValueRow
import ai.synheart.core.example.ui.PillTone
import ai.synheart.core.example.ui.SectionCard
import ai.synheart.core.example.ui.StatusPill
import ai.synheart.core.models.HSIAxisValue
import ai.synheart.core.models.HSIState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Step 3 — run a session and watch HSI arrive.
 *
 * `initialize()` configures the SDK; it does not collect. Collection starts here
 * and stops at [SynheartController.stopSession].
 *
 * The runtime closes an HSI window about every 60 seconds. It does so on that
 * cadence whether or not a biosignal arrived, emitting each axis at zero
 * confidence when it has no basis for one — so a steadily climbing window count
 * is not evidence that anything is being measured.
 *
 * What grounds those axes is physiological signal. Behavior and motion are
 * collected and do reach the runtime, but they feed the digital and kinematic
 * modalities, which the Live HSI card renders separately.
 *
 * On a bare phone with nothing attached, the Simulated cardiac source card will
 * stream fabricated beats through the real ingest path so that path can be seen
 * working. It is opt-in per session and tagged Tier 3 on the way in, and the
 * card states the cost in place: those samples reach the same longitudinal
 * baselines real ones do. Cardiac is the only thing simulated.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionScreen(c: SynheartController, padding: PaddingValues) {
    val running = c.isSessionRunning

    Column(Modifier.padding(padding)) {
        TopAppBar(
            title = { Text("Session") },
            actions = {
                Box(Modifier.padding(end = 16.dp)) {
                    StatusPill(
                        if (running) "collecting" else "stopped",
                        if (running) PillTone.GOOD else PillTone.NEUTRAL,
                    )
                }
            },
        )

        LazyColumn(Modifier.fillMaxWidth().padding(16.dp)) {
            // One banner, not two: the SDK's own error says the same thing as
            // the consent precondition, so showing both stacked was noise.
            item {
                val err = c.sessionError
                if (err != null) {
                    ErrorBanner(err)
                } else if (!c.hasCollectionConsent) {
                    ErrorBanner(
                        "No activated feature has matching consent. A session needs both " +
                            "halves of a pair — the feature activated in SynheartConfig " +
                            "and its consent granted. Grant biosignals, behavior, or " +
                            "phone context on the Consent tab; cloud upload, vendor sync, " +
                            "and research do not make any sensor readable on their own.",
                    )
                }
            }

            item {
                SectionCard(
                    title = if (running) "Session running" else "No active session",
                    subtitle = if (running) {
                        "The runtime closes a window about every 60 seconds."
                    } else {
                        "Start a session to begin collection."
                    },
                ) {
                    Column {
                        if (running) {
                            OutlinedButton(
                                onClick = { c.stopSession() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Filled.Stop, null, Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text("Stop session")
                            }
                        } else {
                            FilledTonalButton(
                                onClick = { c.startSession() },
                                // Disabled without collection consent: a
                                // session granted none of the collection
                                // channels would collect nothing, and offering
                                // a button that silently does nothing is worse
                                // than one that is visibly unavailable.
                                enabled = c.hasCollectionConsent,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text("Start session")
                            }
                        }
                        c.session?.let { s ->
                            Spacer(Modifier.height(12.dp))
                            KeyValueRow("session_id", s.sessionId)
                            KeyValueRow("mode", s.mode.name.lowercase())
                        }
                    }
                }
            }

            item {
                SectionCard(
                    title = "Live HSI",
                    subtitle = "Typed HSIState from Synheart.onStateUpdate. Each window is " +
                        "parsed once and shared across collectors.",
                    trailing = { StatusPill("${c.hsiWindowCount} windows") },
                ) {
                    val state = c.latestState
                    if (state == null) {
                        Text(
                            if (running) {
                                "Waiting for the first window…\n\nThe runtime closes one " +
                                    "about every 60 seconds, so the first can take a " +
                                    "minute to appear."
                            } else {
                                "Start a session to receive HSI."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        AxisTable(state, c)
                    }
                }
            }

            // Uploading needs no host code: the runtime subscribes to the
            // engine's HSI broadcast, enqueues each window itself, and POSTs on
            // CloudConfig.uploadIntervalMs. This card exists to make that
            // visible and to force a flush — not to drive it.
            if (SynheartController.uploadConfigured) {
                item {
                    SectionCard(
                        title = "Cloud ingest",
                        subtitle = "The runtime uploads on its own: it enqueues every " +
                            "window as it closes and POSTs on " +
                            "CloudConfig.uploadIntervalMs. No host code required. When " +
                            "nothing arrives, the cause is almost always the consent " +
                            "gate, not a missing call.",
                        trailing = {
                            // The runtime's own success outranks the manual
                            // counter: it is the one that proves the pipeline
                            // works, and it is what makes a queue of 0 mean
                            // "drained" rather than "nothing ever arrived".
                            val autoUploaded = c.lastIngestSuccessAt != null
                            StatusPill(
                                when {
                                    c.uploadError != null -> "error"
                                    autoUploaded -> "uploading"
                                    c.uploadedCount > 0 -> "uploaded ${c.uploadedCount}"
                                    else -> "nothing sent"
                                },
                                when {
                                    c.uploadError != null -> PillTone.WARN
                                    autoUploaded || c.uploadedCount > 0 -> PillTone.GOOD
                                    else -> PillTone.NEUTRAL
                                },
                            )
                        },
                    ) {
                        Column {
                            KeyValueRow("queued, not yet sent", "${c.uploadQueueLength}")
                            KeyValueRow("uploaded by Flush now", "${c.uploadedCount}")
                            KeyValueRow(
                                "runtime last POST",
                                c.lastIngestSuccessAt.timeOrDash(),
                            )
                            KeyValueRow("last manual flush", c.lastFlushAt.timeOrDash())
                            c.uploadError?.let {
                                Spacer(Modifier.height(8.dp))
                                ErrorBanner(it)
                            }
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { c.flushUploads() },
                                enabled = !c.isFlushing,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Outlined.CloudUpload, null, Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(if (c.isFlushing) "Flushing…" else "Flush now")
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "The runtime flushes on its own schedule; this only forces " +
                                    "one early. The counter above tracks what these manual " +
                                    "flushes sent, so it can read 0 while automatic uploads " +
                                    "are succeeding — watch the queue depth for the real " +
                                    "signal.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item { CardiacSimulatorCard(c) }
            item { SignalSources(c, running) }
            item { WatchCard(c) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * Companion-watch session.
 *
 * Separate from the phone session above: the watch runs its own engine and
 * computes its own metrics. This only relays a command out and events back, so
 * a watch session can run whether or not the phone is collecting.
 */
@Composable
private fun WatchCard(c: SynheartController) {
    val status = c.watchStatus
    val running = c.isWatchSessionRunning
    SectionCard(
        title = "Companion watch",
        subtitle = "Runs a session on a paired Wear OS watch over the Wearable Data " +
            "Layer. Needs the companion app installed on the watch — it owns the " +
            "listener that answers the start command.",
        trailing = {
            StatusPill(
                when {
                    running -> "running"
                    status == null -> "unknown"
                    // Told apart on purpose: no transport at all is a different
                    // problem from a transport with no watch on the end.
                    !status.supported -> "unsupported"
                    !status.reachable -> "no watch"
                    else -> "ready"
                },
                when {
                    running || status?.canStartSession == true -> PillTone.GOOD
                    status == null -> PillTone.NEUTRAL
                    else -> PillTone.WARN
                },
            )
        },
    ) {
        Column {
            if (status == null) {
                Text(
                    "Not queried yet — initialize the SDK first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                KeyValueRow("supported", "${status.supported}")
                KeyValueRow("reachable", "${status.reachable}")
                KeyValueRow("events received", "${c.watchEventCount}")
                KeyValueRow("hr samples → engine", "${c.watchHrSampleCount}")
                KeyValueRow("last event", c.lastWatchEvent ?: "—")
            }

            c.watchError?.let {
                Spacer(Modifier.height(8.dp))
                ErrorBanner(it)
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { c.refreshWatchStatus() }) { Text("Refresh") }
                if (running) {
                    OutlinedButton(onClick = { c.stopWatchSession() }) { Text("Stop watch") }
                } else {
                    FilledTonalButton(
                        onClick = { c.startWatchSession() },
                        enabled = status?.canStartSession == true,
                    ) { Text("Start on watch") }
                }
            }

            if (status?.supported == true && status.reachable != true) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Play Services is present but no Wear OS node is connected. Pair " +
                        "the watch and make sure the companion app is installed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SignalSources(c: SynheartController, running: Boolean) {
    SectionCard(
        title = "Signal sources",
        subtitle = "What is actually feeding the runtime. Anything the simulator above " +
            "contributes arrives here as a Tier-3 provider, alongside whatever real sources " +
            "are available.",
        trailing = {
            StatusPill(
                when {
                    c.hasBiosignalSource && c.usesSyntheticBiosignals -> "SYNTHETIC"
                    c.hasBiosignalSource -> "receiving"
                    c.consentState?.biosignals != true -> "not consented"
                    !c.hasWearSource -> "no source"
                    // Attached but with nothing behind it. Reporting "no source"
                    // here sent you looking for a wiring bug when the source is
                    // wired and the platform store is simply absent.
                    !c.isWearPlatformAvailable -> "no health store"
                    !c.hasWearPermissions -> "not permitted"
                    c.wearEmittingButEmpty -> "empty samples"
                    else -> "no data yet"
                },
                when {
                    // Loud, not green: invented numbers must never read as a
                    // healthy measurement.
                    c.usesSyntheticBiosignals && c.hasBiosignalSource -> PillTone.BAD
                    c.hasBiosignalSource -> PillTone.GOOD
                    else -> PillTone.WARN
                },
            )
        },
    ) {
        Column {
            SourceRow(
                label = "Wear",
                // Honest: WearModule caches and streams samples, but nothing in
                // the SDK pushes them into the runtime — the session adapter
                // forwards them to synheart-session, not to the engine. A real
                // strap stops at wearSampleStream today.
                detail = "Heart rate, RR → wearSampleStream only; not pushed to the runtime",
                active = c.isWearCollecting,
                reachesRuntime = false,
            )
            SourceRow(
                label = "Behavior",
                detail = "Taps, scrolls → digital; accelerometer at 50 Hz → kinematic",
                active = c.isBehaviorCollecting,
                reachesRuntime = true,
            )
            SourceRow(
                label = "Phone",
                // Not enabled, on purpose. PhoneModule's four collectors are
                // Random() generators, and cardiac is the only thing this
                // example simulates. They never reached the runtime either.
                detail = "Not enabled — its collectors emit Random() values",
                active = c.isPhoneCollecting,
                reachesRuntime = false,
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // Behavior needs no sensor, so on a phone with no wearable this
            // counter is the only live proof that collection is running.
            Text("behavior events", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            KeyValueRow("captured", "${c.behaviorEventCount}")
            if (c.behaviorBreakdown.isNotEmpty()) {
                KeyValueRow(
                    "by type",
                    c.behaviorBreakdown.joinToString(", ") {
                        "${it.first.name.lowercase()} ${it.second}"
                    },
                )
            }
            if (c.behaviorEventCount == 0 && running) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Scroll or tap anywhere in this app to generate some. Events are " +
                        "captured by the activity's dispatchTouchEvent override — the " +
                        "Kotlin SDK has no view-tree hook of its own, so a host that " +
                        "records nothing collects no behavior.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            KeyValueRow("samples emitted", "${c.wearSampleCount}")
            KeyValueRow("carrying data", "${c.wearDataSampleCount}")

            val sample = c.lastWearSample
            if (!c.hasBiosignalSource || sample == null) {
                Spacer(Modifier.height(8.dp))
                // The specific reason, not a generic "no signal" — each of these
                // has a different fix.
                c.biosignalBlocker?.let { why ->
                    Text(
                        why,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                // Offered whenever the grant is missing. Health Connect gates
                // reads behind a runtime prompt, so a manifest declaration alone
                // leaves the source polling an empty store forever.
                // Only when Health Connect is present: prompting on a device
                // without it opens nothing and teaches the wrong fix.
                if (c.isWearPlatformAvailable && !c.hasWearPermissions) {
                    OutlinedButton(
                        onClick = { c.requestWearPermissions() },
                        enabled = !c.isRequestingWearPermissions,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.HealthAndSafety, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(
                            if (c.isRequestingWearPermissions) {
                                "Requesting…"
                            } else {
                                "Grant health access"
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "Whatever the cause, all five axes above stay at zero confidence " +
                        "until a real heart rate or HRV reading arrives — including " +
                        "focus and capacity. Behavior and motion do reach the runtime, " +
                        "but they feed the digital and kinematic modalities instead. " +
                        "Watch \"carrying data\", not \"samples emitted\": the latter " +
                        "climbs on every poll tick whether or not anything resolved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (c.usesSyntheticBiosignals) {
                    ErrorBanner(
                        "These values are INVENTED by the SDK's synthetic generator, not " +
                            "measured. They enter the runtime's longitudinal baselines " +
                            "exactly like real readings would. Set " +
                            "allowSyntheticBiosignals = false before pointing this at a " +
                            "real subject.",
                    )
                }
                KeyValueRow("latest hr", sample.hr?.let { "%.1f".format(it) } ?: "—")
                KeyValueRow("latest rmssd", sample.hrvRmssd?.let { "%.1f".format(it) } ?: "—")
                KeyValueRow("rr intervals", "${sample.rrIntervals?.size ?: 0}")
                KeyValueRow("received", Instant.ofEpochMilli(sample.timestamp).isoOrDash())
            }
        }
    }
}

/**
 * The five HSI 1.3 axes, with confidence.
 *
 * A null axis means the engine has not produced a value yet — usually not enough
 * signal. That is distinct from a parse failure, which [HSIState.hasParseError]
 * reports separately.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AxisTable(state: HSIState, c: SynheartController) {
    Column {
        if (state.hasParseError) {
            ErrorBanner("HSI parse failed: ${state.parseError}")
        }

        AxisRow("focus", state.hsi.focus)
        AxisRow("capacity", state.hsi.capacity)
        AxisRow("arousal", state.hsi.arousal)
        AxisRow("stress", state.hsi.stress)
        AxisRow("sleep", state.hsi.sleep)

        // The digital domain — derived from interaction alone, so these are the
        // axes that resolve on a phone with no wearable attached. Shown
        // separately because they are scored differently: interruption pressure
        // is lower_is_more and interaction mode is bidirectional, so neither
        // reads like the five above.
        if (state.hsi.hasDigital) {
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(
                "digital axes — from interaction, no wearable needed",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(8.dp))
            AxisRow("focus quality", state.hsi.focusQuality)
            AxisRow("interruption ↓", state.hsi.interruptionPressure)
            AxisRow("interaction mode", state.hsi.interactionMode)
            Spacer(Modifier.height(6.dp))
            Text(
                "interruption ↓ is lower_is_more: a low score means MORE interruption. " +
                    "interaction mode is bidirectional — neither end is better.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // §9.1 — an axis with no contributing modality is OMITTED, with its
        // reason in meta.synheart.state_withheld. On mobile that is the common
        // case, not an error. A canonical member is complete over axes.<domain>
        // ∪ this map, so a UI that reads only the axes cannot tell "withheld,
        // and here is why" from "this build does not produce that axis". The
        // thing never to do is paint a default over it.
        if (state.stateWithheld.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text("withheld this window", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            for ((axis, reason) in state.stateWithheld) KeyValueRow(axis, reason)
            Spacer(Modifier.height(6.dp))
            Text(
                withheldExplanation(state.stateWithheld.values.toSet()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // §9.4 — meta.synheart.sensing is present only for a host that declared
        // a profile. Shown because it is the block a consumer must stratify on
        // rather than pool across.
        state.sensing?.let { sensing ->
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text("sensing declaration", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            for (key in sensing.keys()) KeyValueRow(key, sensing.opt(key).toString())
        }

        HorizontalDivider(Modifier.padding(vertical = 10.dp))

        // Which modalities the runtime saw in this window, derived from
        // `meta.provenance.sources[*].signals`.
        //
        // This is the answer to "the SDK is collecting but every axis says no
        // basis". The five axes above are physiology-derived; behavior and
        // motion land here instead. Without this row a developer on a phone with
        // no wearable sees five empty axes and reasonably concludes nothing is
        // working, when digital signal is in fact arriving.
        Text("modalities in this window", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModalityChip("physiological", state.modalities.physiological, state.tiers.physiological)
            ModalityChip("kinematic", state.modalities.kinematic, state.tiers.kinematic)
            ModalityChip("digital", state.modalities.digital, state.tiers.digital)
        }

        // Do NOT read an empty modality set as "nothing was collected".
        // Modality is derived from meta.provenance.sources[*].signals, and in
        // core-runtime only the ingest_batch path registers a source at all:
        // push_rr_batch, push_behavior_event, push_behavior and push_accel all
        // feed the engine without ever appearing in provenance. So a window can
        // be built from plenty of signal — with grounded axes above to prove
        // it — and still report every modality absent.
        if (state.modalities.isEmpty) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (c.behaviorEventCount > 0) {
                    "The runtime listed no source in this window's provenance, though " +
                        "${c.behaviorEventCount} behavior events were captured and pushed — " +
                        "and the axes above may well be grounded.\n\nThat is not a " +
                        "contradiction. Only the ingest_batch path registers a source: the " +
                        "direct FFI pushes (push_rr_batch, push_behavior_event, push_behavior, " +
                        "push_accel) reach the engine without appearing in provenance. Trust " +
                        "the axis confidences above over these chips."
                } else {
                    "No modality is present. Either the runtime closed this window without a " +
                        "source it recognised, or every contributing push arrived by a direct " +
                        "FFI call, which registers no source. Read the axis confidences above."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (!state.modalities.physiological) {
            Spacer(Modifier.height(8.dp))
            Text(
                "No PHYSIOLOGICAL source is listed in this window's provenance. If the five " +
                    "axes above are at zero confidence, that is the reason — connect a " +
                    "wearable or start the simulator. If they are grounded, the source simply " +
                    "was not registered: only ingest_batch registers one, so RR pushed by " +
                    "push_rr_batch counts toward the axes without showing up here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        KeyValueRow("timestamp", Instant.ofEpochMilli(state.timestampMs).isoOrDash())
        KeyValueRow("subject", state.subjectId.ifEmpty { "—" })
    }
}

/**
 * One modality's presence in the current window, with its fidelity tier when the
 * runtime reported one. Lower tier number = higher fidelity.
 */
@Composable
private fun ModalityChip(label: String, present: Boolean, tier: Int?) {
    val scheme = MaterialTheme.colorScheme
    val fg = if (present) scheme.onSecondaryContainer else scheme.onSurfaceVariant
    Row(
        Modifier
            .background(
                if (present) scheme.secondaryContainer else scheme.surfaceContainerHighest,
                RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (present) Icons.Filled.CheckCircle else Icons.Outlined.RemoveCircleOutline,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = fg,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            // Pair the tier with the label only when the modality is actually
            // present. The runtime reports a tier for modalities it did not
            // observe in the window, and "digital · tier 2" beside an absent
            // marker reads as a contradiction rather than as two separate facts.
            if (present && tier != null) "$label · tier $tier" else label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
        )
    }
}

@Composable
private fun AxisRow(name: String, value: HSIAxisValue?) {
    // Confidence 0.0 means the engine produced a number with nothing behind it.
    val grounded = value != null && value.confidence > 0
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, Modifier.width(120.dp), style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(
            // Draw nothing at zero confidence. The engine emits a value
            // alongside a confidence of 0.0 to say it has no basis for it;
            // rendering that as a half-filled bar reads as a real measurement,
            // which it is not.
            progress = {
                if (grounded) value.value.coerceIn(0.0, 1.0).toFloat() else 0f
            },
            modifier = Modifier.weight(1f).height(8.dp),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            // Material 3 paints a trailing stop indicator by default, which at
            // zero progress is the only mark on the track — it reads as a small
            // measured value where the point is that there is none.
            drawStopIndicator = {},
        )
        Spacer(Modifier.width(12.dp))
        Text(
            when {
                value == null -> "no data"
                grounded -> "%.2f  conf %.2f".format(value.value, value.confidence)
                else -> "no basis"
            },
            modifier = Modifier.width(118.dp),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (grounded) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** One collection module and whether it is currently running. */
@Composable
private fun SourceRow(
    label: String,
    detail: String,
    active: Boolean,
    /**
     * Whether this module actually pushes into the native runtime.
     *
     * Not every collecting module does. Wear and behavior are wired through the
     * event processor and `pushBehaviorToRuntime`; phone context is collected
     * into a Kotlin-side cache with no consumer, so it never influences HSI.
     * Showing all three as "collecting" under a heading that says "feeding the
     * runtime" implied otherwise.
     */
    reachesRuntime: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (active) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusPill(
            when {
                !active -> "idle"
                reachesRuntime -> "feeding"
                else -> "local only"
            },
            when {
                !active -> PillTone.NEUTRAL
                reachesRuntime -> PillTone.GOOD
                else -> PillTone.NEUTRAL
            },
        )
    }
}

/**
 * The simulated cardiac source, and the one button that starts it.
 *
 * Deliberately the loudest card on the screen when it is running. A demo that
 * streams invented physiology and looks identical to one reading a real strap
 * is how fabricated numbers end up in a screenshot, a bug report, or a
 * baseline — so the state, the tier and the cost to the on-device baselines
 * are all stated where the button is.
 */
@Composable
private fun CardiacSimulatorCard(c: SynheartController) {
    val host = c.host
    val streaming = host.isStreamingCardiac

    SectionCard(
        title = "Simulated cardiac source",
        subtitle = "Fabricated beats through the real ingest path — push_rr_batch for the " +
            "intervals, push_wear_hr for the rate. The only simulated source in this " +
            "example; for exercising the integration when no wearable is attached.",
        trailing = {
            StatusPill(if (streaming) "streaming" else "off", if (streaming) PillTone.WARN else PillTone.NEUTRAL)
        },
    ) {
        Column {
            // The live readout is the point: a developer needs to see the rate
            // move, stay inside a plausible envelope, and HRV collapse when the
            // rate climbs. A single static number would prove none of that.
            Row(Modifier.fillMaxWidth()) {
                SimMetric("heart rate", host.latestSimBpm?.let { "%.0f".format(it) } ?: "—", "bpm", emphasis = true, modifier = Modifier.weight(1f))
                SimMetric("RMSSD", host.latestSimRmssd?.let { "%.1f".format(it) } ?: "—", "ms", modifier = Modifier.weight(1f))
                SimMetric("episode", host.simActivity, null, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            if (streaming) {
                OutlinedButton(onClick = { host.stopCardiacStream() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.StopCircle, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Stop HR stream")
                }
            } else {
                FilledTonalButton(
                    onClick = { host.startCardiacStream() },
                    // With no pipeline there is nothing to ingest into.
                    enabled = c.isSessionRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.FavoriteBorder, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Start HR stream")
                }
            }
            if (!c.isSessionRunning) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Start a session first — with no pipeline there is nothing to ingest into.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (streaming) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                KeyValueRow("RR packets pushed", "${host.rrPacketsPushed}")
                KeyValueRow("beats in those packets", "${host.beatsPushed}")
                Spacer(Modifier.height(8.dp))
                Text(
                    "One packet per notification, several intervals under a single arrival " +
                        "timestamp — the shape a BLE Heart Rate Measurement arrives in. " +
                        "push_rr_batch reconstructs a per-beat clock from the anchor; a loop of " +
                        "push_rr walked backwards has every beat after the first rejected by " +
                        "the ordering gate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WarningAmber, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text("These beats are not real", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "They reach the SRM, which builds this subject's longitudinal reference " +
                        "ranges on this device — so run the simulator under a throwaway " +
                        "subject_id and wipe local data afterwards (Setup tab).\n\nCardiac is " +
                        "the only thing simulated here — no motion, speed, screen state, app " +
                        "focus or notifications is fabricated alongside it.\n\nPushed as " +
                        "provider \"sdk_wear\" (Tier 3), never \"ble_hrm\": that label routes " +
                        "into the breathing detector's Tier-1 series. Withholding stops " +
                        "working the moment a host lies about where a number came from.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SimMetric(label: String, value: String, unit: String?, emphasis: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            // weight(fill = false) so a long episode label ("moderate activity")
            // wraps instead of overflowing a third of the card.
            Text(
                value,
                modifier = Modifier.weight(1f, fill = false),
                style = if (emphasis) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (unit != null) {
                Spacer(Modifier.width(3.dp))
                Text(unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Plain-language gloss for the withholding reasons a mobile host actually sees,
 * so a developer does not have to find the rule pack to learn that two missing
 * axes are a correct outcome rather than a bug.
 */
private fun withheldExplanation(reasons: Set<String>): String {
    val lines = mutableListOf<String>()
    if ("episodic_sensing" in reasons) {
        lines += "episodic_sensing: capacity and mental_fatigue are withheld on every frame of " +
            "an episodic host. Both integrate a trajectory, and an app that only runs in " +
            "foreground slices cannot supply an unbroken one — so the engine withholds them " +
            "with a reason rather than publishing a torn session clock."
    }
    if ("cold_start_confidence_exhausted" in reasons) {
        lines += "cold_start_confidence_exhausted: a real reading at confidence 0, produced when " +
            "the additive cold-start penalty consumed the whole multiplicative confidence " +
            "chain. Render it as unavailable, not as a score of zero."
    }
    if (lines.isEmpty()) {
        lines += "Each axis above was omitted for the stated reason. Show it as unavailable — " +
            "never substitute a neutral default, which reads as a measurement."
    }
    return lines.joinToString("\n\n")
}

private val ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME
private val WALL_CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun Instant.isoOrDash(): String =
    atZone(ZoneOffset.UTC).toLocalDateTime().format(ISO)

private fun Instant?.timeOrDash(): String =
    this?.atZone(ZoneOffset.UTC)?.toLocalTime()?.format(WALL_CLOCK) ?: "—"
