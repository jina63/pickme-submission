package com.pickme.car.pickme.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.pickme.car.pickme.sharing.VehicleSharingState
import org.junit.Assert.*
import org.junit.Test

/** Recreates the screen's ViewModel without contacting the server or sending another invitation. */
class InvitationResumeTest {
    @Test fun reopeningRestoresSamePickupAndDoesNotResendMailOrDropActiveSharing() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val original=invitationSessions.restore()
            val wasRunning=VehicleSharingState.running
            val originalId=VehicleSharingState.invitationId
            val firstOwner=ViewModelStore()
            val nextOwner=ViewModelStore()
            try {
                invitationSessions.save("fixture-owner",InvitationState(id="existing-pickup",link="https://example.com/friend#fixture",status="accepted",passengerName="시연 친구",pickupLabel="만날 장소",passengerReady=true),true,true)
                VehicleSharingState.running=true
                VehicleSharingState.invitationId="existing-pickup"
                val first=ViewModelProvider(firstOwner,ViewModelProvider.NewInstanceFactory())[InvitationViewModel::class.java]
                assertEquals("existing-pickup",first.state.id)
                firstOwner.clear() // Leaving the screen clears its original ViewModel.
                val reopened=ViewModelProvider(nextOwner,ViewModelProvider.NewInstanceFactory())[InvitationViewModel::class.java]
                assertEquals("existing-pickup",reopened.state.id)
                assertEquals("시연 친구",reopened.state.passengerName)
                assertEquals("만날 장소",reopened.state.pickupLabel)
                assertTrue(reopened.mailRequested)
                assertTrue(reopened.navigationApplied)
                assertFalse("Ready state must be refreshed rather than trusted from an old screen",reopened.state.passengerReady)
                reopened.create(DemoContact("other","다른 친구","fixture"),"시연 운전자")
                assertEquals("existing-pickup",reopened.state.id)
                reopened.beginNewPickup()
                assertEquals("Active sharing must not be discarded by starting another pickup","existing-pickup",reopened.state.id)
                VehicleSharingState.running=false
                reopened.beginNewPickup()
                assertTrue(reopened.state.id.isBlank())
                assertNull(invitationSessions.restore())
            } finally {
                firstOwner.clear();nextOwner.clear();invitationSessions.clear()
                if(original!=null)invitationSessions.save(original.cookie,original.state,original.mailRequested,original.navigationApplied)
                VehicleSharingState.running=wasRunning;VehicleSharingState.invitationId=originalId
            }
        }
    }
}
