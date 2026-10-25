// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Luma (Y düzlemi) yardımcıları — Android bağımlılığı yok.
 *
 * OCR için kareden yalnızca altyazı bölgesini alır, küçültür ve gri tonlu NV21 olarak yazar
 * (Y = parlaklık, UV = 128 nötr). ML Kit NV21'i doğrudan okur; Bitmap dönüşümüne gerek kalmaz.
 */
object LumaCrop
{
	/** Hedef boyut: genişlik [maxWidth]'i geçmez, en-boy oranı korunur, ikisi de çift sayı (NV21 şartı). */
	fun targetSize(srcW: Int, srcH: Int, maxWidth: Int): Pair<Int, Int>
	{
		val scale = min(1f, maxWidth.toFloat() / srcW)
		val w = max(2, (srcW * scale).roundToInt() and 1.inv())
		val h = max(2, (srcH * scale).roundToInt() and 1.inv())
		return w to h
	}

	fun nv21Size(w: Int, h: Int) = w * h + w * h / 2

	/**
	 * [src] içindeki srcW×srcH bölgeyi (ilk piksel [srcOffset], satır adımı [rowStride], piksel adımı 1)
	 * dstW×dstH boyuta kutu filtresiyle küçültür ve [out]'a gri NV21 olarak yazar.
	 */
	fun toGrayNv21(
		src: ByteArray, srcOffset: Int, rowStride: Int, srcW: Int, srcH: Int,
		dstW: Int, dstH: Int, out: ByteArray
	)
	{
		require(out.size >= nv21Size(dstW, dstH))
		if(dstW == srcW && dstH == srcH)
		{
			for(y in 0 until srcH)
				System.arraycopy(src, srcOffset + y * rowStride, out, y * dstW, dstW)
		}
		else
		{
			val x0s = IntArray(dstW) { it * srcW / dstW }
			val x1s = IntArray(dstW) { max(x0s[it] + 1, (it + 1) * srcW / dstW) }
			for(dy in 0 until dstH)
			{
				val y0 = dy * srcH / dstH
				val y1 = max(y0 + 1, (dy + 1) * srcH / dstH)
				val outRow = dy * dstW
				for(dx in 0 until dstW)
				{
					val x0 = x0s[dx]
					val x1 = x1s[dx]
					var sum = 0
					for(y in y0 until y1)
					{
						val row = srcOffset + y * rowStride
						for(x in x0 until x1)
							sum += src[row + x].toInt() and 0xFF
					}
					out[outRow + dx] = (sum / ((y1 - y0) * (x1 - x0))).toByte()
				}
			}
		}
		out.fill(128.toByte(), dstW * dstH, nv21Size(dstW, dstH))
	}

	/**
	 * Ucuz karşıtlık germe: ilk [count] baytın %1 ve %99 yüzdelikleri 0..255'e yayılır (yalnızca aralık
	 * dar ise; zaten tam aralıktaysa dokunulmaz). Soluk/yarı saydam zemindeki altyazılarda ML Kit'e yardımcı olur.
	 */
	fun stretchContrast(buf: ByteArray, count: Int, minRangeToSkip: Int = 200)
	{
		if(count <= 0)
			return
		val hist = IntArray(256)
		for(i in 0 until count)
			hist[buf[i].toInt() and 0xFF]++
		val cut = count / 100
		var lo = 0
		var acc = 0
		while(lo < 255 && acc + hist[lo] <= cut)
		{
			acc += hist[lo]
			lo++
		}
		var hi = 255
		acc = 0
		while(hi > 0 && acc + hist[hi] <= cut)
		{
			acc += hist[hi]
			hi--
		}
		val range = hi - lo
		if(range >= minRangeToSkip || range < 16)
			return
		val lut = ByteArray(256) { v -> (((v - lo) * 255) / range).coerceIn(0, 255).toByte() }
		for(i in 0 until count)
			buf[i] = lut[buf[i].toInt() and 0xFF]
	}
}
