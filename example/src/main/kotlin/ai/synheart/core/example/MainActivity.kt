package ai.synheart.core.example

import ai.synheart.core.example.screens.ConsentScreen
import ai.synheart.core.example.screens.DiagnosticsScreen
import ai.synheart.core.example.screens.HostScreen
import ai.synheart.core.example.screens.SessionScreen
import ai.synheart.core.example.screens.SetupScreen
import ai.synheart.core.example.sdk.SynheartController
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * Synheart Core SDK — reference example.
 *
 * Five tabs, one per step of the SDK lifecycle, in the order a host app
 * performs them:
 *
 *   Setup       → build a config and initialize
 *   Consent     → the runtime editable-form flow
 *   Session     → start collection, watch HSI arrive
 *   Host        → what the host has to keep DOING once a session is live:
 *                 the tick loop, rest declaration, snapshots, daily loop
 *   Runtime     → native runtime health
 *
 * The Host tab is the one that is easy to skip and shouldn't be. Everything
 * before it is configuration; a host that stops there gets a session that
 * emits no windows from interaction, scores every break as engaged, and
 * re-warms its baselines from cold on every launch.
 *
 * All SDK calls live in [SynheartController]. Screens only read state from it
 * and call its methods, so the integration is legible in one file.
 *
 * This example is local-only by default: no cloud credentials, no device
 * attestation. See SETUP.md to enable cloud upload.
 */
class MainActivity : ComponentActivity() {

    /**
     * Owned by the activity rather than created per-composition, so a rotation
     * does not tear down the native runtime and drop the session.
     */
    private val controller: SynheartController by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller.loadIdentity(this)
        setContent { SynheartExampleApp(controller) }
    }

    /**
     * Feed real interaction into the SDK.
     *
     * Has to live on the activity: the SDK ships no view-tree hook, so nothing
     * observes taps unless the host dispatches them itself. Declaring the
     * behavior feature and granting behavior consent is not enough on its own.
     *
     * Placed on `dispatchTouchEvent` rather than inside the composition so it
     * sees every gesture regardless of which tab is showing and which child
     * consumed the event — a `pointerInput` modifier on the content would miss
     * touches that a scrolling list claimed first.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        controller.recordTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Health Connect grants are made in another app, so the result arrives as a
     * resume rather than a callback — re-read them here or the screen keeps
     * showing "not permitted" after the user has just granted.
     */
    override fun onResume() {
        super.onResume()
        controller.onResumed()
    }

    /**
     * §6 — flush and persist on the way out. An Android process may not be
     * scheduled again before it is killed, so this cannot wait for onStop.
     */
    override fun onPause() {
        controller.onPaused()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only on a real teardown — isFinishing is false for a rotation, where
        // disposing would kill the runtime the recreated activity is about to
        // reuse.
        if (isFinishing) controller.shutdownAsync()
    }
}

private val SEED = Color(0xFF3E5C76)

@Composable
private fun SynheartExampleApp(controller: SynheartController) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme = remember(dark) {
        // Dynamic color where the platform offers it, so the example looks
        // native; the seed pair is the fallback.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (dark) darkColorScheme(primary = SEED) else lightColorScheme(primary = SEED)
        }
    }

    MaterialTheme(colorScheme = scheme) { HomeShell(controller) }
}

private data class Tab(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val TABS = listOf(
    Tab("Setup", Icons.Outlined.Tune, Icons.Filled.Tune),
    Tab("Consent", Icons.Outlined.PrivacyTip, Icons.Filled.PrivacyTip),
    Tab("Session", Icons.Outlined.PlayCircleOutline, Icons.Filled.PlayCircle),
    Tab("Host", Icons.Outlined.SettingsInputAntenna, Icons.Filled.SettingsInputAntenna),
    Tab("Runtime", Icons.Outlined.MonitorHeart, Icons.Filled.MonitorHeart),
)

@Composable
private fun HomeShell(c: SynheartController) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Gate the later tabs on initialization: consent, session, and diagnostics
    // all read through the native runtime, which does not exist until
    // initialize() has run. Showing them as tappable-but-broken would teach the
    // wrong lifecycle.
    val ready = c.isInitialized
    val shown = if (ready) index else 0

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { i, tab ->
                    NavigationBarItem(
                        selected = shown == i,
                        onClick = {
                            // Read the live flag at tap time rather than the
                            // `ready` captured during composition: a stale
                            // value left the later tabs unreachable behind an
                            // "initialize first" toast even though the SDK was
                            // ready.
                            if (!c.isInitialized && i != 0) {
                                scope.launch {
                                    snackbar.currentSnackbarData?.dismiss()
                                    snackbar.showSnackbar(
                                        "Initialize the SDK first (Setup tab).",
                                        duration = SnackbarDuration.Short,
                                    )
                                }
                                return@NavigationBarItem
                            }
                            index = i
                        },
                        icon = {
                            Icon(
                                if (shown == i) tab.selectedIcon else tab.icon,
                                contentDescription = tab.label,
                                tint = if (ready || i == 0) {
                                    androidx.compose.ui.graphics.Color.Unspecified
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                },
                            )
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (shown) {
            0 -> SetupScreen(c, padding)
            1 -> ConsentScreen(c, padding)
            2 -> SessionScreen(c, padding)
            3 -> HostScreen(c, padding)
            else -> DiagnosticsScreen(c, padding)
        }
    }
}
