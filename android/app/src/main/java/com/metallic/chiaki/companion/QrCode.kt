// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.companion

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.max

/** AltyazıTR: ZXing BitMatrix'ini keskin (bulanıklaştırmasız) bir Bitmap'e çevirir. */
object QrCode
{
	/** QR standardının önerdiği sessiz bölge (modül cinsinden). */
	private const val QUIET_ZONE = 4

	/**
	 * [sizePx]: hedef kenar uzunluğu. Modül başına tam sayı piksel kullanılır; bu yüzden sonuç
	 * [sizePx]'ten biraz küçük olabilir (ImageView FIT_CENTER + filtrelemesiz çizim ile büyütülür).
	 */
	fun render(text: String, sizePx: Int): Bitmap?
	{
		val matrix = try
		{
			QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(
				EncodeHintType.MARGIN to 0,
				EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
				EncodeHintType.CHARACTER_SET to "UTF-8"
			))
		}
		catch(e: WriterException)
		{
			return null
		}
		catch(e: IllegalArgumentException)
		{
			return null
		}
		val modules = matrix.width + QUIET_ZONE * 2
		val scale = max(1, sizePx / modules)
		val side = modules * scale
		val pixels = IntArray(side * side) { Color.WHITE }
		for(y in 0 until matrix.height)
		{
			for(x in 0 until matrix.width)
			{
				if(!matrix.get(x, y))
					continue
				val px = (x + QUIET_ZONE) * scale
				val py = (y + QUIET_ZONE) * scale
				for(dy in 0 until scale)
				{
					val row = (py + dy) * side + px
					pixels.fill(Color.BLACK, row, row + scale)
				}
			}
		}
		return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
	}
}
