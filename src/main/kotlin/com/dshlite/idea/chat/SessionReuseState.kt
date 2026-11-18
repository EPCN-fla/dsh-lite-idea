package com.dshlite.idea.chat

data class SessionReuseSnapshot(
    val lastSessionId: String?,
    val lastSessionClean: Boolean,
)

/**
 * Tracks durable last-session state plus the blank identities known in this
 * client. Switching away must not erase another session's blank identity.
 */
class SessionReuseState(
    initialLastSessionId: String? = null,
    initialLastSessionClean: Boolean = false,
) {
    private val blankSessionIds = HashSet<String>()
    private var lastSessionId: String? = initialLastSessionId
    private var lastSessionClean: Boolean = initialLastSessionClean && initialLastSessionId != null

    init {
        if (lastSessionClean) blankSessionIds += initialLastSessionId!!
    }

    @Synchronized
    fun snapshot() = SessionReuseSnapshot(lastSessionId, lastSessionClean)

    @Synchronized
    fun startupBlankSessionId(): String? = lastSessionId?.takeIf { lastSessionClean }

    @Synchronized
    fun created(sessionId: String): SessionReuseSnapshot {
        blankSessionIds += sessionId
        lastSessionId = sessionId
        lastSessionClean = true
        return snapshot()
    }

    @Synchronized
    fun resumed(sessionId: String): SessionReuseSnapshot {
        lastSessionId = sessionId
        lastSessionClean = sessionId in blankSessionIds
        return snapshot()
    }

    @Synchronized
    fun content(sessionId: String): SessionReuseSnapshot {
        blankSessionIds -= sessionId
        if (lastSessionId == sessionId) lastSessionClean = false
        return snapshot()
    }

    /** A successful empty history import can prove an unknown session is blank. */
    @Synchronized
    fun transcript(sessionId: String, importedEmpty: Boolean, hasDisplayedContent: Boolean): SessionReuseSnapshot {
        val blank = importedEmpty && !hasDisplayedContent
        if (blank) blankSessionIds += sessionId else blankSessionIds -= sessionId
        if (lastSessionId == sessionId) lastSessionClean = blank
        return snapshot()
    }

    @Synchronized
    fun deleted(sessionId: String): SessionReuseSnapshot {
        blankSessionIds -= sessionId
        if (lastSessionId == sessionId) lastSessionClean = false
        return snapshot()
    }
}
