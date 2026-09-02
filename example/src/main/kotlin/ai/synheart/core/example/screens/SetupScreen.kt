package ai.synheart.core.example.screens

import ai.synheart.core.example.sdk.SynheartController
import ai.synheart.core.example.ui.CodeBlock
import ai.synheart.core.example.ui.ErrorBanner
import ai.synheart.core.example.ui.KeyValueRow
import ai.synheart.core.example.ui.PillTone
import ai.synheart.core.example.ui.SectionCard
import ai.synheart.core.example.ui.StatusPill
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Step 1 — build a config and initialize.
 *
 * The two required fields are `appId` and `subjectId`. Everything else has a
 * working default. [ai.synheart.core.config.SynheartConfig.validate] runs before
 * any native work, so a bad config fails here with an actionable message rather
 * than surfacing later as an unexplained empty org_id or a mis-bound device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(c: SynheartController, padding: PaddingValues) {
    val context = LocalContext.current

    // Seeded from the persisted subject id, then owned by the field so typing
    // is not overwritten on every recomposition.
    var subjectField by remember(c.subjectId) { mutableStateOf(c.subjectId.orEmpty()) }

    Column(Modifier.padding(padding)) {
        TopAppBar(
            title = { Text("Setup") },
            actions = {
                if (c.isInitialized) {
                    Box(Modifier.padding(end = 16.dp)) {
                        StatusPill("v${c.sdkVersion}", PillTone.NEUTRAL)
                    }
                }
            },
        )

        LazyColumn(Modifier.fillMaxWidth().padding(16.dp)) {
            c.initError?.let { item { ErrorBanner(it) } }

            item {
                SectionCard(
                    title = if (c.isInitialized) "SDK initialized" else "SDK not initialized",
                    subtitle = if (c.isInitialized) {
                        "The native runtime is loaded. Grant consent next."
                    } else {
                        "Nothing is collected until you initialize and start a session."
                    },
                    trailing = {
                        StatusPill(
                            if (c.isInitialized) "ready" else "inactive",
                            if (c.isInitialized) PillTone.GOOD else PillTone.NEUTRAL,
                        )
                    },
                ) {
                    if (c.isInitialized) {
                        OutlinedButton(
                            onClick = { c.shutdownAsync() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.PowerSettingsNew, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Dispose SDK")
                        }
                    } else {
                        FilledTonalButton(
                            onClick = { c.initialize(context) },
                            enabled = !c.isInitializing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (c.isInitializing) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                            }
                            Spacer(Modifier.size(8.dp))
                            Text(if (c.isInitializing) "Initializing…" else "Initialize SDK")
                        }
                    }
                }
            }

            item {
                SectionCard(
                    title = "Identity",
                    subtitle = "subjectId must stay the same across restarts. The runtime " +
                        "scopes storage, baselines, and device identity to it — a value " +
                        "that changes per launch looks like a new person every time, so " +
                        "baselines never mature.",
                ) {
                    Column {
                        OutlinedTextField(
                            value = subjectField,
                            onValueChange = { subjectField = it },
                            enabled = !c.isInitialized,
                            label = { Text("subjectId") },
                            supportingText = {
                                Text("Your account id in a real app. Persisted here.")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        if (!c.isInitialized) {
                            Row(Modifier.fillMaxWidth(), Arrangement.End) {
                                TextButton(onClick = { c.saveSubjectId(subjectField) }) {
                                    Text("Save")
                                }
                            }
                        }
                        KeyValueRow("appId", SynheartController.appId)
                        KeyValueRow("deviceId", c.deviceId ?: "—")
                    }
                }
            }

            item {
                val supplied = c.credentials.count { it.consumed && it.supplied }
                val consumed = c.credentials.count { it.consumed }
                SectionCard(
                    title = "Credentials",
                    subtitle = "Read from example/env/synheart.credentials.json at build " +
                        "time. The platform download carries the four ids only — " +
                        "base_url and package_name you add yourself, and base_url is " +
                        "the one that gates attestation and upload.",
                    trailing = {
                        StatusPill(
                            "$supplied/$consumed used",
                            if (supplied == consumed) PillTone.GOOD else PillTone.NEUTRAL,
                        )
                    },
                ) {
                    Column {
                        c.credentials.forEach { cred ->
                            CredentialRow(cred)
                        }
                    }
                }
            }

            item {
                SectionCard(
                    title = "Device attestation",
                    subtitle = if (SynheartController.attestationConfigured) {
                        "Registration is triggered by CLOUD-UPLOAD CONSENT, not by " +
                            "initialize(). Grant it on the Consent tab and watch the " +
                            "status change here — the SDK runs the flow in the " +
                            "background rather than blocking the consent screen."
                    } else {
                        "Not configured, so this build never attests. Put a base_url in " +
                            "example/env/synheart.credentials.json to enable it — an " +
                            "org id is only needed for upload, not attestation. " +
                            "See SETUP.md."
                    },
                    trailing = {
                        StatusPill(
                            when {
                                !SynheartController.attestationConfigured -> "off"
                                c.attestationRegistered -> "registered"
                                else -> "pending"
                            },
                            when {
                                !SynheartController.attestationConfigured -> PillTone.NEUTRAL
                                c.attestationRegistered -> PillTone.GOOD
                                else -> PillTone.WARN
                            },
                        )
                    },
                ) {
                    if (SynheartController.attestationConfigured) {
                        Column {
                            KeyValueRow("auth url", SynheartController.authBaseUrl)
                            KeyValueRow(
                                "upload",
                                if (SynheartController.uploadConfigured) {
                                    "enabled · ${SynheartController.orgId}"
                                } else {
                                    "off — no org_id"
                                },
                            )
                            // Both of these read through the native runtime, so
                            // before initialize() they are unknown rather than
                            // false. Printing a bare "false" here said "this
                            // runtime lacks the device-auth symbols" when the
                            // truth was "nothing has been loaded yet" — and
                            // that is the row the troubleshooting steps tell you
                            // to trust first.
                            KeyValueRow(
                                "ABI available",
                                if (c.isInitialized) {
                                    "${c.attestationAvailable}"
                                } else {
                                    "unknown — runtime not loaded"
                                },
                            )
                            KeyValueRow(
                                "status",
                                if (c.isInitialized) {
                                    c.attestationStatusWord ?: "—"
                                } else {
                                    "unknown — runtime not loaded"
                                },
                            )
                            KeyValueRow("device id", c.attestationDeviceId ?: "—")
                            // Shown separately from `status` on purpose. A
                            // device admitted through development mode
                            // registers successfully and signs every request
                            // with a real hardware key, but is recorded
                            // `unattested` — it carries no provenance claim.
                            // Collapsing that into "registered" is exactly the
                            // confusion worth avoiding.
                            KeyValueRow("attestation", c.attestationClaim)

                            if (SynheartController.allowsUnattestedDevRegistration) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Debug build: allowUnattestedDevRegistration is ON, so a " +
                                        "device that cannot produce Play Integrity material " +
                                        "still asks the server to admit it, carrying " +
                                        "format:\"none\" and an empty blob — nothing fake is " +
                                        "sent.\n\nThe flag alone does nothing: development " +
                                        "mode must also be enabled for this app id " +
                                        "server-side, and it must be a development app id, " +
                                        "never a production one. Release builds disable this " +
                                        "automatically.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { c.registerDevice() },
                                    enabled = c.isInitialized,
                                ) { Text("Register now") }
                                OutlinedButton(
                                    onClick = { c.reregisterDevice() },
                                    enabled = c.isInitialized,
                                ) { Text("Re-attest") }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Register now is idempotent — it no-ops when already " +
                                    "registered. Re-attest forces a fresh registration, for " +
                                    "when the server has lost or revoked the device record.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                SectionCard(
                    title = "Config being used",
                    subtitle = if (SynheartController.attestationConfigured) {
                        "DeviceAuthConfig is set from the credentials file, so attestation " +
                            "is active. Upload additionally needs org_id."
                    } else {
                        "Local-only: no CloudConfig and no DeviceAuthConfig, so nothing " +
                            "leaves the device and no attestation is attempted. See " +
                            "SETUP.md to enable cloud upload."
                    },
                ) { ConfigListing(c) }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * One credential, and what its absence costs.
 *
 * The distinction that matters is between *absent* and *absent and load-bearing*.
 * `tenant_id` being empty is fine — this SDK never reads it. `base_url` being
 * empty is why attestation says "off", and that is worth stating on the row
 * rather than leaving it to be inferred from a card three sections down.
 */
@Composable
private fun CredentialRow(cred: SynheartController.Credential) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                cred.key,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                when {
                    // Say WHY it is unused even when a value is present. A bare
                    // "unused" pill next to a real tenant id reads as a bug.
                    !cred.consumed && cred.supplied ->
                        "${cred.value}  ·  never read — the runtime config " +
                            "carries app_id and org_id only"
                    !cred.consumed -> "not supplied — this SDK never reads it"
                    cred.supplied -> cred.value
                    cred.gates != null -> "not supplied — no ${cred.gates}"
                    else -> "not supplied — using the built-in default"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(8.dp))
        StatusPill(
            when {
                !cred.consumed -> "unused"
                cred.supplied -> "set"
                cred.gates != null -> "missing"
                else -> "default"
            },
            when {
                !cred.consumed -> PillTone.NEUTRAL
                cred.supplied -> PillTone.GOOD
                cred.gates != null -> PillTone.WARN
                else -> PillTone.NEUTRAL
            },
        )
    }
}

/**
 * A literal listing of the config this example passes, so a developer can copy
 * it rather than reverse-engineer it from the controller.
 */
@Composable
private fun ConfigListing(c: SynheartController) {
    // Built line by line rather than as one trimIndent() template.
    //
    // Interpolating a multi-line block into a trimIndent() string breaks the
    // alignment of everything around it: trimIndent measures the literal's own
    // indentation before substitution, so the injected lines land at whatever
    // column they were written at. Assembling a list keeps one source of
    // indentation.
    //
    // Values are the ones actually in use, not placeholders. A literal
    // `appId = "ai.synheart.core.example"` here contradicted the Identity card
    // two sections up the moment a credentials file supplied a real `app_…` id,
    // which makes the listing worse than no listing.
    val subject = c.subjectId ?: "—"
    val device = c.deviceId ?: "—"

    val lines = buildList {
        add("SynheartConfig(")
        add("  appId = \"${SynheartController.appId}\",   // required")
        add("  subjectId = \"$subject\",   // required, stable")
        add("  appVersion = \"1.0.0\",")
        add("  deviceId = \"$device\",")
        add("  mode = SynheartMode.PERSONAL,")
        add("")
        add("  // Development only — production gates on a verified consent token.")
        add("  allowUnsignedCapabilities = true,")
        add("")
        add("  // Declaring a module config activates that feature.")
        add("  wearConfig = WearConfig(),")
        add("  phoneConfig = PhoneConfig(),")
        add("  behaviorConfig = BehaviorConfig(),")
        add("")
        add("  // Surfaces the runtime's own logs; without it the runtime logs nowhere.")
        add("  runtimeLogEnvFilter = \"info\",")
        add("")
        add("  // Required for the runtime consent-form flow.")
        add("  consentConfig = ConsentConfig(")
        add("    deviceId = \"$device\",")
        add("    platform = \"android\",")
        add("    userId = \"$subject\",")
        add("  ),")
        if (SynheartController.attestationConfigured) {
            add("")
            add("  deviceAuthConfig = DeviceAuthConfig(")
            add("    authBaseUrl = \"${SynheartController.authBaseUrl}\",")
            add("    packageName = \"${SynheartController.packageName}\",")
            // Show the resolved value AND where it came from. Printing just
            // `BuildConfig.DEBUG` left you unable to tell what the runtime was
            // actually told, which is the only thing that matters here.
            add(
                "    allowUnattestedDevRegistration = " +
                    "${SynheartController.allowsUnattestedDevRegistration},  " +
                    "// BuildConfig.DEBUG",
            )
            add("  ),")
        } else {
            add("")
            add("  // no deviceAuthConfig — no origin named, so never attests")
        }
        if (SynheartController.uploadConfigured) {
            add("")
            add("  cloudConfig = CloudConfig(")
            add("    subjectId = \"$subject\",")
            add("    instanceId = \"$device\",")
            add("    orgId = \"${SynheartController.orgId}\",")
            add("  ),")
        } else {
            add("")
            add("  // no cloudConfig — nothing is uploaded")
        }
        add(")")
    }

    CodeBlock(lines.joinToString("\n"))
}
