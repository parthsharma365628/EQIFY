package com.example.eqify

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Builds the QR module grid for a profile code. Pure ZXing core (no Android
 * types) so it is JVM-testable; the screen draws the grid itself.
 */
object ProfileQrCode {
    /** Codes are short (~300 chars); anything bigger is not a profile code. */
    const val MAX_CONTENT_CHARS = 1_000

    /** The spec's minimum quiet zone; the renderer adds it around [matrix]. */
    const val QUIET_ZONE_MODULES = 4

    /**
     * Returns the smallest matrix ZXing produces: one cell per module, no
     * margin (see [QUIET_ZONE_MODULES]). Medium error correction tolerates
     * screen glare without making the code dense.
     */
    fun matrix(content: String): BitMatrix {
        require(content.isNotEmpty() && content.length <= MAX_CONTENT_CHARS) {
            "Profile code is too long for a QR code."
        }
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        return QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    }
}
