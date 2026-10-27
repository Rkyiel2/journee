package fr.journee.app

import android.graphics.Bitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Decodes a QR code from a bitmap (screenshot of the WebView or an imported image). */
object Qr {
    fun decode(bitmap: Bitmap): String? {
        val w = bitmap.width
        val h = bitmap.height
        if (w == 0 || h == 0) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(w, h, pixels)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
        for (bin in listOf(HybridBinarizer(source), GlobalHistogramBinarizer(source))) {
            try {
                return QRCodeReader().decode(BinaryBitmap(bin), hints).text
            } catch (_: Exception) {
                // try the next binarizer
            }
        }
        // Inverted colors (dark mode pages).
        return try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text
        } catch (_: Exception) {
            null
        }
    }
}
