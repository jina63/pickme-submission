package com.pickme.car.pickme.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class InvitationMailDialogTest {
    @get:Rule val compose=createComposeRule()

    @Test fun fixedDriverShownAndSendingNeedsNoNameEntry() {
        var sent=false
        compose.setContent {
            MaterialTheme {InvitationMailDialog("친구 2","김픽미",true,"CGV 판교",false,false,{}, {sent=true})}
        }
        compose.onNodeWithText("운전자: 김픽미").assertExists()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("메일 보내기").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(true,sent)
    }

    @Test fun noDestinationExplainsWhySendingIsBlocked() {
        compose.setContent { MaterialTheme {InvitationMailDialog("친구 2","김픽미",false,"",false,false,{}, {})} }
        compose.onNodeWithText("메일 보내기").assertIsNotEnabled()
        compose.onNodeWithText("내비 목적지가 연결되지 않았어요. 내비에서 목적지를 설정하면 자동으로 가져와요.").assertExists()
    }
}
