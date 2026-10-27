package ai.synheart.core.modules.behavior

/**
 * Decides when to tell the runtime input capture is running.
 *
 * Capture counts as running only while the behaviour module tracks with
 * consent **and** the app is in the foreground: the interaction this SDK
 * sees is in-app only, so reporting it running in the background would
 * certify windows as input-free while the person types elsewhere — and a
 * window certified input-free can teach the resting heart rate and HRV.
 *
 * [report] fires only when the combined level changes, and is told the level
 * to report.
 */
class InputCaptureGate(private val report: (running: Boolean) -> Unit) {
    private var tracking = false
    private var foreground = false
    private var reported: Boolean? = null

    /** The level last reported, or null before the first report. */
    val lastReported: Boolean? get() = reported

    fun setTracking(running: Boolean) {
        tracking = running
        sync()
    }

    fun setForeground(inForeground: Boolean) {
        foreground = inForeground
        sync()
    }

    /** Forget everything without reporting, for teardown. */
    fun reset() {
        tracking = false
        foreground = false
        reported = null
    }

    private fun sync() {
        val running = tracking && foreground
        // Nothing to say until there is something running: the runtime's
        // default is already "not running".
        if (reported == null && !running) return
        if (reported == running) return
        reported = running
        report(running)
    }
}
