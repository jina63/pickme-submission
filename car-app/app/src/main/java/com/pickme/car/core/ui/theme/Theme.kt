package com.pickme.car.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColorScheme=lightColorScheme(
    primary=Color(0xFF2272EB),onPrimary=Color.White,primaryContainer=Color(0xFFEDF4FF),onPrimaryContainer=Color(0xFF1B57AA),
    secondary=Color(0xFF4E5968),onSecondary=Color.White,secondaryContainer=Color(0xFFF2F4F6),onSecondaryContainer=Color(0xFF333D4B),
    tertiary=Color(0xFF117A53),background=Color(0xFFF5F6F8),onBackground=Color(0xFF191F28),
    surface=Color.White,onSurface=Color(0xFF191F28),surfaceVariant=Color(0xFFF2F4F6),onSurfaceVariant=Color(0xFF6B7684),
    outline=Color(0xFFDDE2E8),outlineVariant=Color(0xFFEDF0F3),error=Color(0xFFD14343),surfaceTint=Color(0xFF2272EB)
)
private val DarkColorScheme=darkColorScheme(
    primary=Color(0xFF70AAFF),onPrimary=Color(0xFF102B51),primaryContainer=Color(0xFF16345C),onPrimaryContainer=Color(0xFFD5E5FF),
    secondary=Color(0xFFADB7C4),background=Color(0xFF15171B),onBackground=Color(0xFFF2F4F6),surface=Color(0xFF20242A),
    onSurface=Color(0xFFF2F4F6),surfaceVariant=Color(0xFF2B3038),onSurfaceVariant=Color(0xFFB0B8C1),outline=Color(0xFF515B68)
)
@Composable
@Suppress("UNUSED_PARAMETER")
fun PickMeTheme(darkTheme:Boolean=false,dynamicColor:Boolean=false,content:@Composable ()->Unit) {
    MaterialTheme(colorScheme=if(darkTheme)DarkColorScheme else LightColorScheme,typography=Typography,
        shapes=Shapes(extraSmall=RoundedCornerShape(8.dp),small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(16.dp),large=RoundedCornerShape(24.dp),extraLarge=RoundedCornerShape(28.dp)),content=content)
}
