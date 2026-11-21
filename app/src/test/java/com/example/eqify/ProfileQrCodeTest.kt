package com.example.eqify

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileQrCodeTest {

    private val profile = SharedEqProfile(
        name = "Night Bass",
        tone = listOf(3f, 2f, 0f, -1f, 0f, 1f, 2f, 1.5f),
        correction = SharedCorrection(SharedCorrection.Kind.DATASET, "Sennheiser HD 600",
            listOf(-1.2f, 0.4f, 0f, 0.3f, 0f, -2.1f, 1f, 3.2f)),
        bass = 3f
    )

    /** Renders like the screen does (quiet zone, [scale] px per module) and decodes it. */
    private fun scan(matrix: BitMatrix, scale: Int = 4): String {
        val quiet = ProfileQrCode.QUIET_ZONE_MODULES
        val side = (matrix.width + 2 * quiet) * scale
        val pixels = IntArray(side * side) { i ->
            val x = (i % side) / scale - quiet
            val y = (i / side) / scale - quiet
            val dark = x in 0 until matrix.width && y in 0 until matrix.height && matrix.get(x, y)
            if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap).text
    }

    @Test fun qrRoundTripsToTheSameProfile() {
        val code = EqProfileCodec.encode(profile)
        val scanned = scan(ProfileQrCode.matrix(code))
        assertEquals(code, scanned)
        assertEquals(profile, EqProfileCodec.decode(scanned))
    }

    @Test fun matrixIsSquareWithoutMargin() {
        val m = ProfileQrCode.matrix(EqProfileCodec.encode(profile))
        assertEquals(m.width, m.height)
        // Version 1 is 21 modules; each version adds 4. No margin means a valid size.
        assertEquals(1, m.width % 4)
        assertTrue(m.width in 21..177)
        // Finder pattern corner module is dark when there is no margin.
        assertTrue(m.get(0, 0))
    }

    @Test fun typicalCodeStaysSmallEnoughToScanFromAScreen() {
        val long = profile.copy(name = "A".repeat(PresetNameRules.MAX_LENGTH))
        val m = ProfileQrCode.matrix(EqProfileCodec.encode(long))
        assertTrue("QR version too dense: ${m.width} modules", m.width <= 73) // ≤ version 14
    }

    @Test fun rejectsEmptyAndOversizedContent() {
        assertThrows(IllegalArgumentException::class.java) { ProfileQrCode.matrix("") }
        assertThrows(IllegalArgumentException::class.java) {
            ProfileQrCode.matrix("x".repeat(ProfileQrCode.MAX_CONTENT_CHARS + 1))
        }
    }

    @Test fun correctionOnlyShareRoundTrips() {
        val correctionOnly = SharedEqProfile("My IEM", List(8) { 0f },
            SharedCorrection(SharedCorrection.Kind.CUSTOM, "My IEM", listOf(1f, 0f, -2f, 0f, 0f, 3.5f, 0f, -1f)))
        val decoded = EqProfileCodec.decode(scan(ProfileQrCode.matrix(EqProfileCodec.encode(correctionOnly))))
        assertEquals(correctionOnly, decoded)
    }
}
