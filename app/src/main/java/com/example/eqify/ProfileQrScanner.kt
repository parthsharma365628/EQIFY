package com.example.eqify

import android.content.Context
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Google code scanner: Google Play services shows its own camera UI, so EQify
 * needs no CAMERA permission and never sees camera frames, only the decoded text.
 * Devices without Play services get a clear fallback message.
 */
object ProfileQrScanner {
    const val UNAVAILABLE_MESSAGE =
        "QR scanning isn't available on this device (it needs Google Play services). " +
            "Scan the code with your camera app, then paste the text here."

    fun scan(context: Context, onResult: (String) -> Unit, onError: (String) -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = try {
            GmsBarcodeScanning.getClient(context, options)
        } catch (e: Exception) {
            onError(UNAVAILABLE_MESSAGE)
            return
        }
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val text = barcode.rawValue
                if (text.isNullOrBlank()) onError("That QR code is empty or unreadable.")
                else onResult(text)
            }
            // Cancelled by the user: nothing to report.
            .addOnCanceledListener { }
            .addOnFailureListener { e ->
                val cancelled = (e as? MlKitException)?.errorCode == MlKitException.CODE_SCANNER_CANCELLED ||
                    (e as? com.google.android.gms.common.api.ApiException)?.statusCode == CommonStatusCodes.CANCELED
                if (!cancelled) {
                    val unavailable = (e as? MlKitException)?.errorCode.let {
                        it == MlKitException.UNAVAILABLE || it == MlKitException.CODE_SCANNER_UNAVAILABLE
                    } || e is com.google.android.gms.common.api.ApiException
                    onError(if (unavailable) UNAVAILABLE_MESSAGE
                        else "QR scanning failed: ${e.message ?: "unknown error"}")
                }
            }
    }
}
