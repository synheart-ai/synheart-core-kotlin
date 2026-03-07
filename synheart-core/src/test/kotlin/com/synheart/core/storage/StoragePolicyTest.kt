package com.synheart.core.storage

import com.synheart.core.config.SynheartMode
import org.junit.Assert.*
import org.junit.Test

class StoragePolicyTest {

    // Personal mode

    @Test
    fun `personal allows core artifact types`() {
        val policy = storagePolicyForMode(SynheartMode.PERSONAL)
        assertTrue(policy.canPersistArtifact("hsi_window"))
        assertTrue(policy.canPersistArtifact("session_summary"))
        assertTrue(policy.canPersistArtifact("baseline_snapshot"))
        assertTrue(policy.canPersistArtifact("tombstone"))
    }

    @Test
    fun `personal rejects bio streams`() {
        val policy = storagePolicyForMode(SynheartMode.PERSONAL)
        assertFalse(policy.canPersistStream("hrv_raw"))
        assertFalse(policy.canPersistStream("ppg_raw"))
        assertFalse(policy.canPersistStream("accel_raw"))
    }

    @Test
    fun `personal rejects metrics`() {
        val policy = storagePolicyForMode(SynheartMode.PERSONAL)
        assertFalse(policy.canIncludeMetrics())
    }

    // Insight mode

    @Test
    fun `insight allows all artifact types`() {
        val policy = storagePolicyForMode(SynheartMode.INSIGHT)
        assertTrue(policy.canPersistArtifact("hsi_window"))
        assertTrue(policy.canPersistArtifact("session_summary"))
        assertTrue(policy.canPersistArtifact("baseline_snapshot"))
        assertTrue(policy.canPersistArtifact("tombstone"))
    }

    @Test
    fun `insight rejects bio streams`() {
        val policy = storagePolicyForMode(SynheartMode.INSIGHT)
        assertFalse(policy.canPersistStream("hrv_raw"))
    }

    @Test
    fun `insight allows metrics`() {
        val policy = storagePolicyForMode(SynheartMode.INSIGHT)
        assertTrue(policy.canIncludeMetrics())
    }

    // Research mode

    @Test
    fun `research allows everything`() {
        val policy = storagePolicyForMode(SynheartMode.RESEARCH)
        assertTrue(policy.canPersistArtifact("hsi_window"))
        assertTrue(policy.canPersistArtifact("session_summary"))
        assertTrue(policy.canPersistArtifact("baseline_snapshot"))
        assertTrue(policy.canPersistArtifact("tombstone"))
        assertTrue(policy.canPersistStream("hrv_raw"))
        assertTrue(policy.canPersistStream("ppg_raw"))
        assertTrue(policy.canIncludeMetrics())
    }

    @Test
    fun `unknown artifact type rejected in personal`() {
        val policy = storagePolicyForMode(SynheartMode.PERSONAL)
        assertFalse(policy.canPersistArtifact("unknown_type"))
    }
}
