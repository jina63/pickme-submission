package com.pickme.car.pickme.ui

/** Process-local owner session. Never logged, exported or written to a file. */
internal class SavedInvitation(
    val cookie: String,
    val state: InvitationState,
    val mailRequested: Boolean,
    val navigationApplied: Boolean,
)

internal class InvitationSessionStore {
    private var saved: SavedInvitation? = null
    fun save(cookie: String, state: InvitationState, mailRequested: Boolean, navigationApplied: Boolean) {
        if (cookie.isBlank() || state.id.isBlank()) return
        saved = SavedInvitation(cookie, state.copy(busy = false), mailRequested, navigationApplied)
    }
    fun restore(): SavedInvitation? = saved
    fun clear() { saved = null }
}

internal val invitationSessions = InvitationSessionStore()
