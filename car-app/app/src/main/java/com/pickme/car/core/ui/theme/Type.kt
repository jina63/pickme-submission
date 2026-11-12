package com.pickme.car.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun type(size:Int,line:Int,weight:FontWeight=FontWeight.Normal)=TextStyle(fontFamily=FontFamily.Default,fontSize=size.sp,lineHeight=line.sp,fontWeight=weight,letterSpacing=(-0.3).sp)
val Typography=Typography(
    headlineLarge=type(34,44,FontWeight.Bold),headlineMedium=type(30,40,FontWeight.Bold),headlineSmall=type(26,36,FontWeight.Bold),
    titleLarge=type(24,34,FontWeight.Bold),titleMedium=type(20,30,FontWeight.SemiBold),titleSmall=type(17,26,FontWeight.SemiBold),
    bodyLarge=type(18,28),bodyMedium=type(16,26),bodySmall=type(14,22),labelLarge=type(17,24,FontWeight.SemiBold),labelMedium=type(14,20,FontWeight.Medium)
)
