package ai.synheart.core.artifacts

import org.junit.Assert.*
import org.junit.Test

class ArtifactIdTest {

    // Golden vectors — must match Dart, Swift, and reference vectors

    @Test
    fun `vector 1 - HSIWindow`() {
        val id = computeArtifactId(
            type = "hsi_window",
            subjectId = "usr_abc123",
            sessionId = "sess_def456",
            startMs = 1709251200000,
            endMs = 1709251230000,
            schemaName = "hsi_window",
            schemaVersion = "1"
        )
        assertEquals("5e9f3c5a3c3279397da3fcd9361dd87b865b84843354a97bdedf8d8925190470", id)
    }

    @Test
    fun `vector 2 - BaselineSnapshot no session`() {
        val id = computeArtifactId(
            type = "baseline_snapshot",
            subjectId = "usr_abc123",
            sessionId = null,
            startMs = 1709164800000,
            endMs = 1709251200000,
            schemaName = "baseline_snapshot",
            schemaVersion = "1"
        )
        assertEquals("3eb16bd8bfffa0314bf1c62f101ac3c1d118bdfe0080c10109db8dc2bdeeed87", id)
    }

    @Test
    fun `vector 3 - Tombstone`() {
        val id = computeArtifactId(
            type = "tombstone",
            subjectId = "usr_abc123",
            sessionId = null,
            startMs = 1709251230000,
            endMs = 1709251230000,
            schemaName = "tombstone",
            schemaVersion = "1"
        )
        assertEquals("6cb42834a752f7cc9dd4c435a00500480754b5f271711fc34f7dc75596428807", id)
    }

    @Test
    fun `vector 4 - SessionSummary`() {
        val id = computeArtifactId(
            type = "session_summary",
            subjectId = "usr_abc123",
            sessionId = "sess_def456",
            startMs = 1709251200000,
            endMs = 1709251260000,
            schemaName = "session_summary",
            schemaVersion = "1"
        )
        assertEquals("78325014084c858ca8e38b568c68cfe011e10d5d720a0acb89fddaf1bd5d00ff", id)
    }

    @Test
    fun `same input produces same id`() {
        val id1 = computeArtifactId("hsi_window", "usr_x", "sess_1", 100, 200, "hsi_window", "1")
        val id2 = computeArtifactId("hsi_window", "usr_x", "sess_1", 100, 200, "hsi_window", "1")
        assertEquals(id1, id2)
    }

    @Test
    fun `different input produces different id`() {
        val id1 = computeArtifactId("hsi_window", "usr_x", "sess_1", 100, 200, "hsi_window", "1")
        val id2 = computeArtifactId("hsi_window", "usr_y", "sess_1", 100, 200, "hsi_window", "1")
        assertNotEquals(id1, id2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects empty type`() {
        computeArtifactId("", "usr_x", null, 0, 0, "test", "1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects pipe in subjectId`() {
        computeArtifactId("hsi_window", "usr|bad", null, 0, 0, "test", "1")
    }

    @Test
    fun `nil session uses tilde`() {
        val idNil = computeArtifactId("baseline_snapshot", "usr_x", null, 0, 1, "baseline_snapshot", "1")
        val idSess = computeArtifactId("baseline_snapshot", "usr_x", "some", 0, 1, "baseline_snapshot", "1")
        assertNotEquals(idNil, idSess)
    }
}
