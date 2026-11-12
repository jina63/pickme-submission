package com.pickme.car.pickme.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.*
import org.junit.Test

class InvitationQrTest {
    @Test fun scanningReturnsTheExactInvitationIncludingFragment() {
        for (token in listOf("a".repeat(32), "B_-9".repeat(8))) {
            val link="https://pickme-ca1cd-friend.web.app/friend#$token"
            // Encode at the exact display pixel size; avoid resampling uneven QR modules.
            for (size in listOf(320, 480, 640)) {
                val qr=invitationQr(link,size)!!
                val pixels=IntArray(size*size) { i ->
                    if(qr[i%size,i/size]) 0xff000000.toInt() else 0xffffffff.toInt()
                }
                val scanned=QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size,size,pixels))))
                assertEquals(link,scanned.text)
            }
        }
    }
    @Test fun localMissingAndMalformedInvitationsNeverProduceUnusableQr() {
        val token="a".repeat(32)
        for (link in listOf("", "not a url", "http://127.0.0.1:8787/friend#$token",
            "https://127.0.0.1/friend#$token", "https://10.0.2.2/friend#$token",
            "https://host.local/friend#$token", "https://example.com/friend",
            "https://example.com/other#$token", "https://user:password@example.com/friend#$token",
            "https://example.com/friend?token=$token", "https://example.com/friend#short")) {
            assertNull("Invalid invitation must not be shown as a usable QR",invitationQr(link))
        }
    }
}
