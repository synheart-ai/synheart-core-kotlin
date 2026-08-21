package ai.synheart.core.modules.behavior

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the buffer on [BehaviorEventStream].
 *
 * The bug these exist for: the flow was `MutableSharedFlow()` — replay 0, no
 * extra capacity, i.e. a rendezvous flow — while every `record*` reaches it
 * through `tryEmit`, whose Boolean result was discarded. `tryEmit` on a
 * rendezvous SharedFlow fails unless a subscriber is suspended awaiting a value
 * at that exact instant, and recording happens from `dispatchTouchEvent`, a
 * synchronous UI callback. So every tap and scroll was dropped in silence: the
 * host-facing stream stayed empty, the aggregator saw nothing, nothing reached
 * `synheart_core_push_behavior`, and the HSI digital modality never appeared.
 *
 * Behavior is the only source that needs no sensor, so this made the SDK look
 * inert on any phone without a wearable while reporting itself healthy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BehaviorEventStreamTest {

    @Test
    fun `a tap recorded from a non-suspending caller reaches a subscriber`() = runTest {
        val stream = BehaviorEventStream()
        val seen = mutableListOf<BehaviorEvent>()

        val job = launch { stream.events.collect { seen += it } }
        yield() // let the collector subscribe

        // Exactly how the host calls it: plain, synchronous, no coroutine.
        stream.recordTap(x = 12.0, y = 34.0)
        yield()

        assertEquals(1, seen.size)
        assertEquals(BehaviorEventType.TAP, seen.single().type)
        job.cancel()
    }

    @Test
    fun `a burst recorded between collector turns is not dropped`() = runTest {
        // The rendezvous flow lost all but at most one of these. A scroll
        // gesture produces exactly this shape: many events, one collector turn.
        val stream = BehaviorEventStream()
        val seen = mutableListOf<BehaviorEvent>()

        val job = launch { stream.events.collect { seen += it } }
        yield()

        repeat(20) { stream.recordTap(x = it.toDouble(), y = it.toDouble()) }
        yield()

        assertEquals(20, seen.size)
        job.cancel()
    }

    @Test
    fun `every event type reaches the subscriber`() = runTest {
        // The aggregator distinguishes these, and only some map to a runtime
        // behavior code — a type lost here is a modality lost downstream.
        val stream = BehaviorEventStream()
        val seen = mutableListOf<BehaviorEvent>()

        val job = launch { stream.events.collect { seen += it } }
        yield()

        stream.recordTap(1.0, 2.0)
        stream.recordScroll(delta = 9.0)
        stream.recordKeyDown()
        stream.recordKeyUp()
        stream.recordAppSwitch()
        stream.recordNotificationReceived()
        stream.recordNotificationOpened()
        yield()

        assertEquals(
            listOf(
                BehaviorEventType.TAP,
                BehaviorEventType.SCROLL,
                BehaviorEventType.KEY_DOWN,
                BehaviorEventType.KEY_UP,
                BehaviorEventType.APP_SWITCH,
                BehaviorEventType.NOTIFICATION_RECEIVED,
                BehaviorEventType.NOTIFICATION_OPENED,
            ),
            seen.map { it.type },
        )
        job.cancel()
    }

    @Test
    fun `recording with no subscriber does not throw or block`() = runTest {
        // Recording starts before a session subscribes. It must be a no-op, not
        // a failure, and must never suspend the UI thread it is called from.
        val stream = BehaviorEventStream()
        repeat(200) { stream.recordTap(0.0, 0.0) }

        val seen = mutableListOf<BehaviorEvent>()
        val job = launch { stream.events.collect { seen += it } }
        yield()

        // replay = 0, so a late subscriber gets nothing retroactively — the
        // point is only that the earlier calls neither threw nor blocked.
        assertTrue(seen.isEmpty())
        job.cancel()
    }
}
