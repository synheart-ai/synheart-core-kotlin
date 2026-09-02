package ai.synheart.core.example.screens

import ai.synheart.core.example.sdk.SynheartController
import ai.synheart.core.example.ui.ConsentToggle
import ai.synheart.core.example.ui.ErrorBanner
import ai.synheart.core.example.ui.KeyValueRow
import ai.synheart.core.example.ui.PillTone
import ai.synheart.core.example.ui.SectionCard
import ai.synheart.core.example.ui.StatusPill
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Step 2 — consent, via the runtime's editable-form flow.
 *
 * This is the canonical path:
 *
 *   consentGetEditableFormTyped()  → what the user edits
 *   consentSubmitFormTyped(form)   → persist offline-first, then reconcile
 *   consentEffectiveStateTyped()   → what the runtime actually enforces
 *
 * The distinction between the form and the effective state matters. The runtime
 * intersects the submitted choice with the cloud default profile, so asking for
 * a channel does not guarantee getting it. Always gate features on the effective
 * state, never on the form.
 *
 * The older Kotlin-side helpers — `requestConsent`,
 * `getAvailableConsentProfiles`, `setConsentUIProvider`, `getConsentInfo` — are
 * legacy and are not used here. Neither is `grantConsent("biosignals")`, which
 * writes a single channel without going through the profile intersection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentScreen(c: SynheartController, padding: PaddingValues) {
    val form = c.consentForm
    val state = c.consentState

    Column(Modifier.padding(padding)) {
        TopAppBar(
            title = { Text("Consent") },
            actions = {
                IconButton(onClick = { c.refreshConsent() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Re-read from runtime")
                }
            },
        )

        if (form == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    "No consent form available.\n\n" +
                        "The runtime returns one only after initialize() succeeds.",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxWidth().padding(16.dp)) {
            c.consentError?.let { item { ErrorBanner(it) } }

            item {
                SectionCard(
                    title = "Collection",
                    subtitle = "Category-level, matching what the runtime exposes. " +
                        "Channel-level truth is kept inside the runtime.",
                ) {
                    Column {
                        ConsentToggle(
                            title = "Biosignals",
                            description = "Heart rate and HRV. Required for HSI — without " +
                                "it the runtime drops every window.",
                            value = form.biosignals,
                            enforced = state?.biosignals,
                            onChanged = { c.editConsent(biosignals = it) },
                        )
                        ConsentToggle(
                            title = "Phone context",
                            description = "Motion, screen state, and app context.",
                            value = form.phoneContext,
                            enforced = state?.phoneContext,
                            onChanged = { c.editConsent(phoneContext = it) },
                        )
                        ConsentToggle(
                            title = "Behavior",
                            description = "Taps, scrolls, gestures, and notifications.",
                            value = form.behavior,
                            enforced = state?.behavior,
                            onChanged = { c.editConsent(behavior = it) },
                        )
                    }
                }
            }

            item {
                SectionCard(
                    title = "Sharing",
                    subtitle = "This example ships without cloud credentials, so these " +
                        "persist locally but no upload path exists. Supplying an org_id " +
                        "activates them — see SETUP.md.",
                ) {
                    Column {
                        ConsentToggle(
                            title = "Cloud upload",
                            description = "Send derived HSI to the platform. Turning this " +
                                "on makes submit attempt a cloud profile fetch and token " +
                                "issue, which fails offline without losing the local save.",
                            value = form.allowCloud,
                            enforced = state?.cloudUpload,
                            onChanged = { c.editConsent(allowCloud = it) },
                        )
                        ConsentToggle(
                            title = "Vendor sync",
                            description = "Pull from Whoop, Garmin, Oura, or Fitbit.",
                            value = form.allowVendorSync,
                            enforced = state?.vendorSync,
                            onChanged = { c.editConsent(allowVendorSync = it) },
                        )
                        ConsentToggle(
                            title = "Research",
                            description = "Permit export to research studies. Independent " +
                                "of cloud upload.",
                            value = form.allowResearch,
                            enforced = state?.research,
                            onChanged = { c.editConsent(allowResearch = it) },
                        )
                    }
                }
            }

            item {
                FilledTonalButton(
                    onClick = { c.submitConsent() },
                    enabled = !c.isSubmittingConsent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (c.isSubmittingConsent) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(if (c.isSubmittingConsent) "Submitting…" else "Submit consent")
                }
                Spacer(Modifier.height(20.dp))
            }

            item {
                SectionCard(
                    title = "Effective state",
                    subtitle = "What the runtime enforces right now. Gate your features on " +
                        "this, not on the toggles above.",
                ) {
                    Column {
                        if (state == null) {
                            Text("Not available.")
                        } else {
                            KeyValueRow("biosignals", "${state.biosignals}")
                            KeyValueRow("phone_context", "${state.phoneContext}")
                            KeyValueRow("behavior", "${state.behavior}")
                            KeyValueRow("cloud_upload", "${state.cloudUpload}")
                            KeyValueRow("vendor_sync", "${state.vendorSync}")
                            KeyValueRow("research", "${state.research}")
                            HorizontalDivider(Modifier.padding(vertical = 10.dp))
                            KeyValueRow("profile_id", form.profileId)
                            KeyValueRow("tier", form.consentTier.name.lowercase())
                            KeyValueRow("version", state.version)
                        }
                    }
                }
            }

            item {
                // A distinction worth surfacing, and the usual cause of
                // "consent says granted but nothing collects": hasConsent() answers
                // whether a channel is ENFORCEABLE right now, which a
                // cloud-configured app reads as false until the consent service
                // issues a token, whatever the user chose.
                SectionCard(
                    title = "Ready to collect?",
                    subtitle = "An enabled feature paired with its granted consent. " +
                        "Cloud upload, vendor sync and research grant none of the pairs — " +
                        "they govern what happens to data once collected.",
                    trailing = {
                        StatusPill(
                            if (c.hasCollectionConsent) "yes" else "no",
                            if (c.hasCollectionConsent) PillTone.GOOD else PillTone.WARN,
                        )
                    },
                ) {
                    Text(
                        if (c.hasCollectionConsent) {
                            "At least one activated module has its consent granted, so a " +
                                "session will collect something."
                        } else {
                            "No activated module has matching consent. Grant biosignals, " +
                                "behavior, or phone context above, then submit."
                        },
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
