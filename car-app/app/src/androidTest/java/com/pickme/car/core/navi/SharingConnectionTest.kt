package com.pickme.car.core.navi

import android.content.Intent
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import androidx.test.platform.app.InstrumentationRegistry
import com.pickme.car.home.HomeActivity
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Read-only regression: opening/closing order must preserve the sharing SDK callback. */
class SharingConnectionTest {
    @Test fun closingOrderKeepsLiveConnection() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val ready=CountDownLatch(1)
        val before=CountDownLatch(1)
        val after=CountDownLatch(1)
        val main=Handler(Looper.getMainLooper())
        var secondRead=false
        var helper:NaviHelper?=null
        val listener=object:NaviHelperEventListener {
            override fun onDestinationInfo(destinationInfo:DestinationInfo) {if(secondRead)after.countDown()else before.countDown()}
        }
        val poll=object:Runnable {override fun run(){helper?.getDestinationInfo();main.postDelayed(this,1000)}}
        instrumentation.runOnMainSync {
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext,HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            NaviRemote.acquireForSharing(instrumentation.targetContext,onReady={helper=it;it.addListener(listener);main.post(poll);ready.countDown()},onFailed={ready.countDown()})
        }
        try {
            assertTrue("SDK connection timeout",ready.await(15,TimeUnit.SECONDS))
            assertNotNull("SDK connection failed",helper)
            assertTrue("Native SDK does not answer even before order screen opens",before.await(15,TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                NaviRemote.init(instrumentation.targetContext)
                NaviRemote.release()
                secondRead=true
                helper!!.getDestinationInfo()
            }
            assertTrue("Order release removed live-sharing callback",after.await(15,TimeUnit.SECONDS))
        } finally {instrumentation.runOnMainSync {main.removeCallbacks(poll);helper?.removeListener(listener);NaviRemote.releaseForSharing()}}
    }
}
