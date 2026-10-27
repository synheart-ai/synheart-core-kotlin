package ai.synheart.core.modules.srm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LongitudinalSrmModuleTest {

    private val tokyo = ZoneId.of("Asia/Tokyo")

    @Test
    fun `an instant files under the person's local date`() {
        // 22:30 UTC on the 14th is 07:30 on the 15th in Tokyo: the wake date.
        assertEquals(
            LocalDate.of(2026, 1, 15),
            LongitudinalSrmModule.localDateOf("2026-01-14T22:30:00Z", tokyo),
        )
    }

    @Test
    fun `a provider calendar date is already the date`() {
        assertEquals(
            LocalDate.of(2026, 3, 28),
            LongitudinalSrmModule.localDateOf("2026-03-28", tokyo),
        )
        assertNull(LongitudinalSrmModule.localDateOf("not a date", tokyo))
    }

    @Test
    fun `recovery is sent as a fraction`() {
        assertEquals(0.72, LongitudinalSrmModule.recoveryFraction(72.0)!!, 1e-9)
        assertEquals(0.72, LongitudinalSrmModule.recoveryFraction(0.72)!!, 1e-9)
        assertNull(LongitudinalSrmModule.recoveryFraction(140.0))
        assertNull(LongitudinalSrmModule.recoveryFraction(-1.0))
    }

    @Test
    fun `the sleep midpoint is hours after local midnight`() {
        // 18:30 UTC is 03:30 in Tokyo.
        assertEquals(3.5, LongitudinalSrmModule.midpointHours("2026-01-14T18:30:00Z", tokyo)!!, 1e-9)
    }
}
