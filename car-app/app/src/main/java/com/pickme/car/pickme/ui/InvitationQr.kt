package com.pickme.car.pickme.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A QR must contain this invitation's complete public link, including its secret fragment. */
internal fun invitationQr(link: String, size: Int = 320): BitMatrix? {
    if (link.length > 2048 || size !in 160..2048) return null
    val uri = runCatching { URI(link) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    if (uri.scheme != "https" || uri.rawUserInfo != null || uri.path != "/friend" ||
        uri.rawQuery != null || !Regex("[A-Za-z0-9_-]{32}").matches(uri.rawFragment.orEmpty()) ||
        !host.contains('.') || host.endsWith(".local") || host.endsWith(".localhost") ||
        host.contains(':') || host.all { it.isDigit() || it == '.' }) return null
    return QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, size, size, mapOf(
        EncodeHintType.MARGIN to 4,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
    ))
}

@Composable
internal fun InvitationQr(link: String) {
    val size = with(LocalDensity.current) { 320.dp.roundToPx() }
    val result by produceState<Result<androidx.compose.ui.graphics.ImageBitmap>?>(initialValue = null, key1 = link, key2 = size) {
        value = null
        value = withContext(Dispatchers.Default) {
            runCatching {
                val matrix = requireNotNull(invitationQr(link, size))
                val pixels = IntArray(matrix.width * matrix.height) { i ->
                    if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
                Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
            }
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("휴대폰으로 초대 열기", style = MaterialTheme.typography.titleLarge)
        result?.getOrNull()?.let {
            Image(it, contentDescription = "이 초대의 친구 화면을 여는 QR 코드",
                modifier = Modifier.size(320.dp).background(Color.White), filterQuality = FilterQuality.None)
            Text("휴대폰 카메라로 QR을 스캔해 주세요.")
            Text("메일 초대와 같은 링크예요.",
                style = MaterialTheme.typography.bodyMedium)
            Text("초대받은 사람에게만 보여 주세요.", style = MaterialTheme.typography.bodySmall)
        } ?: Text(if(result==null) "QR을 준비하고 있어요." else "QR을 준비하지 못했어요. 휴대폰에서 열 수 있는 HTTPS 초대 링크가 필요해요. 링크 복사로도 공유할 수 있어요.")
    }
}
