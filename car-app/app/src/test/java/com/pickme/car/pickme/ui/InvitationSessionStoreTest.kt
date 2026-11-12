package com.pickme.car.pickme.ui

import org.junit.Assert.*
import org.junit.Test

class InvitationSessionStoreTest {
    @Test fun reopeningKeepsTheExistingInvitationAndOwnerInsteadOfCreatingAnother() {
        val store=InvitationSessionStore()
        val state=InvitationState(id="existing",link="https://example.com/friend#fixture",status="accepted",passengerName="시연 친구",passengerReady=true,pickupLabel="만날 장소",busy=true)
        store.save("fixture-owner",state,true,true)
        val restored=store.restore()!!
        assertEquals("existing",restored.state.id)
        assertEquals(state.link,restored.state.link)
        assertEquals("fixture-owner",restored.cookie)
        assertTrue(restored.mailRequested)
        assertTrue(restored.navigationApplied)
        assertFalse(restored.state.busy)
    }
    @Test fun uncreatedInvitationCannotOverwriteAnActiveSessionAndExplicitNewPickupClearsIt() {
        val store=InvitationSessionStore()
        store.save("fixture-owner",InvitationState(id="existing"),false,false)
        store.save("",InvitationState(),false,false)
        assertEquals("existing",store.restore()!!.state.id)
        store.clear()
        assertNull(store.restore())
    }
}
