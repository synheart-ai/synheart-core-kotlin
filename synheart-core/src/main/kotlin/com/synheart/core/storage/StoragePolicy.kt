package com.synheart.core.storage

import com.synheart.core.config.SynheartMode

/** Controls what may be persisted based on the current operational mode (RFC-CORE-0003 §6). */
interface StoragePolicy {
    fun canPersistArtifact(type: String): Boolean
    fun canPersistStream(streamType: String): Boolean
    fun canIncludeMetrics(): Boolean
}

fun storagePolicyForMode(mode: SynheartMode): StoragePolicy = when (mode) {
    SynheartMode.PERSONAL -> PersonalPolicy
    SynheartMode.INSIGHT -> InsightPolicy
    SynheartMode.RESEARCH -> ResearchPolicy
}

private object PersonalPolicy : StoragePolicy {
    private val allowedArtifacts = setOf("hsi_window", "session_summary", "baseline_snapshot", "tombstone")
    override fun canPersistArtifact(type: String) = type in allowedArtifacts
    override fun canPersistStream(streamType: String) = streamType == "hsi.snapshot"
    override fun canIncludeMetrics() = false
}

private object InsightPolicy : StoragePolicy {
    private val allowedArtifacts = setOf("hsi_window", "session_summary", "baseline_snapshot", "tombstone")
    private val allowedStreams = setOf("hsi.snapshot", "app.metrics", "behavior.events")
    override fun canPersistArtifact(type: String) = type in allowedArtifacts
    override fun canPersistStream(streamType: String) = streamType in allowedStreams
    override fun canIncludeMetrics() = true
}

private object ResearchPolicy : StoragePolicy {
    override fun canPersistArtifact(type: String) = true
    override fun canPersistStream(streamType: String) = true
    override fun canIncludeMetrics() = true
}
