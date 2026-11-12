package com.pickme.car.pickme.ui

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import com.pickme.car.pickme.sharing.PickmeTripReader

/** Requires a native navigation destination already configured. Never changes the route. */
class PickmeTripAutomaticTest {
    @Test fun readsExistingRouteWithoutManualRefresh() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val received=CountDownLatch(1)
        val snapshot=AtomicReference<JSONObject>()
        lateinit var reader:PickmeTripReader
        instrumentation.runOnMainSync {
            reader=PickmeTripReader(instrumentation.targetContext,{snapshot.set(it);received.countDown()},{})
            reader.startAutomatic()
        }
        try {
            assertTrue("Automatic route read timed out; verify native navigation has a destination",received.await(30,TimeUnit.SECONDS))
            assertEquals("pleos",snapshot.get().getString("source"))
            assertTrue(snapshot.get().getJSONObject("destination").getString("label").isNotBlank())
            assertTrue(snapshot.get().has("origin"))
        } finally {instrumentation.runOnMainSync{reader.stopAutomatic();reader.release()}}
    }
}
