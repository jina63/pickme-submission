package com.pickme.car.pickme.ui

import android.graphics.Bitmap
import android.content.pm.PackageManager
import org.junit.Assert.assertFalse
import com.pickme.car.pickme.sharing.VehicleSharingState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.pickme.car.home.HomeActivity
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Checks real home -> contacts -> confirmation navigation without sending mail. */
class DesignNavigationTest {
    @get:Rule val compose=createAndroidComposeRule<HomeActivity>()
    private fun screenshot(name:String) {
        compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val file=File(instrumentation.targetContext.filesDir,name)
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
        println("DESIGN_SCREENSHOT ${file.absolutePath}")
    }
    @Test fun homeContactAndSingleConfirmationRemainUsable() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val permissions=context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse("The app must not require overlay setup",permissions.contains("android.permission.SYSTEM_ALERT_WINDOW"))
        compose.onNodeWithText("픽미").assertIsDisplayed()
        compose.onNodeWithText("딱픽업").assertIsDisplayed()
        screenshot("design-home.png")
        compose.onNodeWithText("픽미").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithText("친구 2").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("초대할 친구").assertExists()
        compose.onNodeWithText("내비 도착 알림 켜기").assertDoesNotExist()
        screenshot("design-invitation.png")
        compose.onNodeWithText("친구 2").performScrollTo().performClick()
        compose.onNodeWithText("친구 2님에게 초대 메일을 보내시겠습니까?").assertExists()
        compose.onAllNodesWithText("메일 보내기").assertCountEquals(1)
        screenshot("design-confirmation.png")
        compose.onNodeWithText("취소").performClick()
    }
    @Test fun ongoingPickupCanBeReopenedFromHomeWithoutCreatingAnotherInvite() {
        val original=invitationSessions.restore()
        val wasRunning=VehicleSharingState.running
        val originalId=VehicleSharingState.invitationId
        try {
            compose.runOnIdle {
                invitationSessions.save("fixture-owner",InvitationState(id="resume-ui-fixture",link="https://example.com/friend#fixture",status="accepted",passengerName="시연 친구",pickupLabel="만날 장소"),true,true)
                VehicleSharingState.invitationId="resume-ui-fixture"
                VehicleSharingState.running=true
            }
            compose.onNodeWithText("진행 중인 픽업 보기").assertIsDisplayed().performClick()
            compose.waitUntil(10000){compose.onAllNodesWithText("시연 친구님 픽업").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("탑승 장소  ·  만날 장소").assertExists()
            compose.onNodeWithText("다른 친구 선택하기").assertDoesNotExist()
            compose.onNodeWithText("← 돌아가기").performClick()
            compose.onNodeWithText("진행 중인 픽업 보기").assertIsDisplayed().performClick()
            compose.waitUntil(10000){compose.onAllNodesWithText("시연 친구님 픽업").fetchSemanticsNodes().isNotEmpty()}
            compose.runOnIdle {VehicleSharingState.running=false}
            compose.onNodeWithText("다른 친구 선택하기").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithText("초대할 친구").assertExists()
            compose.onNodeWithText("시연 친구님 픽업").assertDoesNotExist()
            compose.onNodeWithText("← 돌아가기").performClick()
        } finally {
            compose.runOnIdle {
                invitationSessions.clear()
                if(original!=null)invitationSessions.save(original.cookie,original.state,original.mailRequested,original.navigationApplied)
                VehicleSharingState.running=wasRunning
                VehicleSharingState.invitationId=originalId
            }
        }
    }
}
