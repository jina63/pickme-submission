package com.pickme.car.core.navi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.WaypointInfo
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Manual emulator fixture: CGV 판교 + a synthetic 픽미 시연 탑승 장소. Read-only. */
@RunWith(AndroidJUnit4::class)
class FriendWaypointReadbackTest {
    @Test fun readSyntheticRouteFixture() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val destination=AtomicReference<DestinationInfo>()
        val stops=AtomicReference<List<WaypointInfo>>()
        val ready=CountDownLatch(1)
        val read=CountDownLatch(2)
        lateinit var helper:NaviHelper
        val listener=object:NaviHelperEventListener {
            override fun onDestinationInfo(destinationInfo:DestinationInfo) {if(destination.compareAndSet(null,destinationInfo))read.countDown()}
            override fun onWayPointInfo(waypoints:List<WaypointInfo>) {if(stops.compareAndSet(null,waypoints))read.countDown()}
        }
        instrumentation.runOnMainSync {
            helper=NaviHelper(instrumentation.targetContext)
            helper.initialize(onConnected={helper.addListener(listener);ready.countDown()},onFailed={ready.countDown()})
        }
        try {
            assertTrue("Navi connection timeout",ready.await(15,TimeUnit.SECONDS))
            instrumentation.runOnMainSync {helper.getDestinationInfo();helper.getWaypointInfo()}
            assertTrue("Navi read-back timeout",read.await(20,TimeUnit.SECONDS))
            val d=destination.get();val w=stops.get()
            // Show coordinates only for the named synthetic fixture, never for arbitrary user routes.
            assertEquals("CGV 판교",d.name)
            assertTrue("Synthetic fixture missing",w.any{it.name=="픽미 시연 탑승 장소"})
            val synthetic=w.first{it.name=="픽미 시연 탑승 장소"}
            println("SYNTHETIC etaRaw=${synthetic.remainTime}; distanceRaw=${synthetic.remainDistance}; destinationTimeRaw=${d.remainTime}")
            println("SYNTHETIC waypoint route=${synthetic.latitude},${synthetic.longitude}; display=${synthetic.displayLatitude},${synthetic.displayLongitude}")
        } finally {
            instrumentation.runOnMainSync {helper.removeListener(listener);helper.release()}
        }
    }
}
