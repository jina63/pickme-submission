package com.pickme.car.home

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.pickme.car.pickme.sharing.VehicleSharingState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pickme.car.core.ui.theme.PickMeTheme
import com.pickme.car.pickup.ui.OrderActivity
import com.pickme.car.pickme.ui.InvitationActivity

// 픽미 화면과 같은 색
private val PageBackground = Color(0xFFF5F6F8)
private val Ink = Color(0xFF191F28)
private val MutedInk = Color(0xFF6B7684)
private val PickMeColor = Color(0xFF2272EB)   // 픽미: 브랜드 블루
private val PickupColor = Color(0xFF2272EB)   // 딱픽업: 브랜드 블루

/** 앱 첫 화면: 픽미 / 딱픽업 중 하나를 고른다. */
class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PickMeTheme {
                HomeScreen(
                    onPickMe = { startActivity(Intent(this, InvitationActivity::class.java)) },
                    onPickup = { startActivity(Intent(this, OrderActivity::class.java)) },
                )
            }
        }
    }
}

@Composable
fun HomeScreen(onPickMe: () -> Unit, onPickup: () -> Unit) {
    Scaffold(containerColor=PageBackground) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).padding(32.dp)) {
            val wide=maxWidth>=700.dp
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text("PICKME",fontSize=15.sp,fontWeight=FontWeight.Bold,color=PickMeColor,letterSpacing=2.sp)
                Text("가는 길을 함께,\n더 가볍게",fontSize=34.sp,lineHeight=45.sp,fontWeight=FontWeight.Bold,color=Ink)
                Text("친구를 만나고, 기다림 없이 픽업하세요.",fontSize=18.sp,color=MutedInk)
                if(VehicleSharingState.running) TextButton(onClick=onPickMe) { Text("진행 중인 픽업 보기",fontSize=20.sp) }
                Spacer(Modifier.height(12.dp))
                if(wide) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                        HomeCard("픽미","친구에게 초대를 보내고\n가는 길에 함께 만나요.",PickMeColor,onPickMe,Modifier.weight(1f))
                        HomeCard("딱픽업","가는 길에 미리 주문하고\n준비된 메뉴만 픽업해요.",PickupColor,onPickup,Modifier.weight(1f))
                    }
                } else {
                    HomeCard("픽미","친구에게 초대를 보내고 가는 길에 함께 만나요.",PickMeColor,onPickMe)
                    HomeCard("딱픽업","가는 길에 미리 주문하고 준비된 메뉴만 픽업해요.",PickupColor,onPickup)
                }
                Text("안전하게 정차한 상태에서 이용해 주세요.",fontSize=14.sp,color=MutedInk,modifier=Modifier.padding(top=8.dp))
            }
        }
    }
}

@Composable
private fun HomeCard(title:String,desc:String,color:Color,onClick:()->Unit,modifier:Modifier=Modifier) {
    Card(onClick=onClick,modifier=modifier.fillMaxWidth(),shape=RoundedCornerShape(26.dp),colors=CardDefaults.cardColors(containerColor=Color.White)) {
        Column(Modifier.fillMaxWidth().padding(28.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(54.dp).background(color.copy(alpha=.1f),RoundedCornerShape(18.dp)),contentAlignment=Alignment.Center) {Text(if(title=="픽미")"함께" else "픽업",fontSize=16.sp,fontWeight=FontWeight.Bold,color=color)}
            Text(title,fontSize=30.sp,fontWeight=FontWeight.Bold,color=Ink)
            Text(desc,fontSize=18.sp,lineHeight=29.sp,color=MutedInk)
            Text(if(title=="픽미")"친구 초대하기  ›" else "주문 시작하기  ›",fontSize=18.sp,fontWeight=FontWeight.SemiBold,color=color,modifier=Modifier.padding(top=10.dp))
        }
    }
}

@Preview(widthDp=960,heightDp=640)
@Composable
private fun HomePreview(){PickMeTheme{HomeScreen({}, {})}}
