package com.synheart.core.models

import com.synheart.core.config.SynheartMode

/** Represents an active SDK session. */
data class SessionHandle(
    val sessionId: String,
    val startedAtMs: Long,
    val mode: SynheartMode
)
