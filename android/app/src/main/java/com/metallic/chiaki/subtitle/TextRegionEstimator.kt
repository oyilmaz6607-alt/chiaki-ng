// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Altyazı bandı içinde yazı olabilecek dikdörtgen (piksel, bandın sol üstüne göre; sağ/alt hariç).
 * [lineHeight]: tahmini satır yüksekliği (piksel); 0 = bilinmiyor (küçültme yapılmaz).
 */
data class TextRegion(val left: Int, val top: Int, val right: Int, val bottom: Int, val lineHeight: Int)
{
	val width get() = right - left
	val height get() = bottom - top
}

/**
 * OCR'a gidecek kırpma: bant içindeki kaynak dikdörtgen + OCR görüntüsünün boyutu (küçültülmüş olabilir).
 * ML Kit kutuları OCR görüntüsüne göredir; [toFrameX]/[toFrameY] onları tam kareye göre 0..1'e çevirir.
 */
data class OcrCrop(
	val srcLeft: Int,
	val srcTop: Int,
	val srcWidth: Int,
	val srcHeight: Int,
	val dstWidth: Int,
	val dstHeight: Int
)
{
	/** OCR görüntüsündeki x (piksel) → tam karede 0..1. */
	fun toFrameX(ocrX: Float, frameW: Int): Float = (srcLeft + ocrX * srcWidth / dstWidth) / frameW

	/** OCR görüntüsündeki y (piksel) → tam karede 0..1 ([regionTop]: bandın karedeki üst satırı). */
	fun toFrameY(ocrY: Float, regionTop: Int, frameH: Int): Float = (regionTop + srcTop + ocrY * srcHeight / dstHeight) / frameH

	val scale get() = dstWidth.toFloat() / srcWidth
}

/**
 * Saf Kotlin "yazı bölgesi tahmincisi" (Android bağımlılığı yok).
 *
 * Altyazılar koyu/karışık zemin üstünde yüksek karşıtlıklı harflerdir: harf kenarlarında yatay parlaklık
 * farkı büyüktür. Her satırda |Y[x+2] - Y[x]| ≥ [edgeThreshold] olan piksel sayısı (kenar enerjisi) sayılır;
 * yeterince kenarı olan ardışık satırlar "satır kümeleri" oluşturur. Her küme için kenarlı sütunlar
 * kelime aralıklarını kapatacak şekilde birleştirilir; köşelerdeki HUD gibi merkez dışı parçalar atılır.
 * Sonuç: yalnızca bu kutu (+ pay) OCR'a gider — tüm bant yerine. Emin olunamayan durumda kutu büyür
 * (yazı asla bilerek kesilmez); hiç yazı izi yoksa null döner.
 *
 * Satır yüksekliği: her kümede kenar enerjisinin tepe değerinin yarısını geçen "çekirdek" bölgeler
 * (küçük harf gövdesi) sayılır; küme yüksekliği / çekirdek sayısı ≈ satır aralığı. Böylece birbirine
 * değen iki satır tek satır sanılıp aşırı küçültülmez.
 */
class TextRegionEstimator(
	/** Kenar sayılacak en az parlaklık farkı (0..255). */
	private val edgeThreshold: Int = 48,
	/** Bir satırın "yazı satırı" sayılması için gereken en az kenar pikseli (genişliğe göre de büyür). */
	private val minRowEdges: Int = 6,
	/** Bu yükseklikten (piksel) kısa kümeler yazı sayılmaz (ince çizgiler, gürültü). */
	private val minRunHeight: Int = 5,
	/** Bir küme içinde izin verilen boş satır sayısı. */
	private val maxRowGap: Int = 2,
	/** Altyazılar yatayda ortalıdır: merkezi bu aralıkta olan parçalar tutulur (SubtitleFilter'dan biraz geniş). */
	private val centerMin: Float = 0.12f,
	private val centerMax: Float = 0.88f
)
{
	companion object
	{
		/** Bu satır yüksekliğinden büyük yazı küçültülür ... */
		const val SCALE_ABOVE_LINE_PX = 40
		/** ... satır yüksekliği bu değere inecek şekilde (ML Kit'in rahat okuduğu aralık 24..32 px). */
		const val TARGET_LINE_PX = 32
		/** Bundan uzun kümeler büyük olasılıkla dokulu zemindir: satır yüksekliği bilinmiyor sayılır. */
		const val MAX_LINE_PX = 90
		private const val MIN_SCALE = 0.4f
		/** Kutu bandın bu oranından büyükse kırpmanın faydası yok: tüm bant kullanılır. */
		const val FULL_BAND_AREA = 0.85f
		const val MIN_OCR_W = 64
		const val MIN_OCR_H = 32
	}

	fun estimate(luma: ByteArray, offset: Int, rowStride: Int, width: Int, height: Int): TextRegion?
	{
		if(width < 16 || height < minRunHeight)
			return null
		val t = edgeThreshold
		val end = width - 2
		val rowE = IntArray(height)
		for(y in 0 until height)
		{
			val base = offset + y * rowStride
			var n = 0
			var x = 0
			while(x < end)
			{
				val d = (luma[base + x + 2].toInt() and 0xFF) - (luma[base + x].toInt() and 0xFF)
				if(d >= t || d <= -t)
					n++
				x++
			}
			rowE[y] = n
		}

		val rowThr = max(minRowEdges, width / 200)
		// Satır kümeleri [başlangıç, bitiş)
		val runs = ArrayList<IntArray>()
		var start = -1
		var last = -1
		for(y in 0 until height)
		{
			if(rowE[y] < rowThr)
				continue
			if(start >= 0 && y - last - 1 > maxRowGap)
			{
				runs.add(intArrayOf(start, last + 1))
				start = y
			}
			else if(start < 0)
				start = y
			last = y
		}
		if(start >= 0)
			runs.add(intArrayOf(start, last + 1))

		var left = Int.MAX_VALUE
		var right = Int.MIN_VALUE
		var top = Int.MAX_VALUE
		var bottom = Int.MIN_VALUE
		val lineHeights = ArrayList<Int>()
		var unknownLineHeight = false
		val colE = IntArray(width)
		for(run in runs)
		{
			val r0 = run[0]
			val r1 = run[1]
			val runH = r1 - r0
			if(runH < minRunHeight)
				continue
			val span = columnSpan(luma, offset, rowStride, width, r0, r1, colE) ?: continue
			left = min(left, span.first)
			right = max(right, span.second)
			top = min(top, r0)
			bottom = max(bottom, r1)
			if(runH > MAX_LINE_PX * 2)
				unknownLineHeight = true
			else
			{
				val lines = countCores(rowE, r0, r1)
				val lh = runH / max(1, lines)
				if(lh > MAX_LINE_PX)
					unknownLineHeight = true
				else
					lineHeights.add(lh)
			}
		}
		if(left > right || top > bottom)
			return null

		val lineH = if(unknownLineHeight || lineHeights.isEmpty()) 0 else lineHeights.sorted()[lineHeights.size / 2]
		// Pay: harflerin kenar enerjisi düşük uçları (ascender/descender, ince harfler) kesilmesin.
		val ref = if(lineH > 0) lineH else 24
		val padX = max(12, ref)
		val padY = max(6, ref / 2)
		var l = max(0, left - padX)
		var r = min(width, right + padX)
		var tp = max(0, top - padY)
		var b = min(height, bottom + padY)
		// En küçük boyut (merkezden genişlet)
		if(r - l < MIN_OCR_W)
		{
			val c = (l + r) / 2
			l = max(0, c - MIN_OCR_W / 2)
			r = min(width, l + MIN_OCR_W)
			l = max(0, r - MIN_OCR_W)
		}
		if(b - tp < MIN_OCR_H)
		{
			val c = (tp + b) / 2
			tp = max(0, c - MIN_OCR_H / 2)
			b = min(height, tp + MIN_OCR_H)
			tp = max(0, b - MIN_OCR_H)
		}
		// NV21 için çift koordinat/boyut
		l = l and 1.inv()
		tp = tp and 1.inv()
		if((r - l) % 2 != 0) r = if(r < width) r + 1 else r - 1
		if((b - tp) % 2 != 0) b = if(b < height) b + 1 else b - 1
		return TextRegion(l, tp, r, b, lineH)
	}

	/**
	 * Kümedeki kenarlı sütunları kelime aralığını kapatacak boşlukla birleştirir, merkez dışındaki
	 * parçaları (HUD) atar; kalanların [sol, sağ) aralığı. Hiç parça kalmazsa null.
	 */
	private fun columnSpan(luma: ByteArray, offset: Int, rowStride: Int, width: Int, r0: Int, r1: Int, colE: IntArray): Pair<Int, Int>?
	{
		val t = edgeThreshold
		val end = width - 2
		colE.fill(0)
		for(y in r0 until r1)
		{
			val base = offset + y * rowStride
			var x = 0
			while(x < end)
			{
				val d = (luma[base + x + 2].toInt() and 0xFF) - (luma[base + x].toInt() and 0xFF)
				if(d >= t || d <= -t)
				{
					// Kenar x..x+2 arasında
					colE[x + 1]++
				}
				x++
			}
		}
		val runH = r1 - r0
		val mergeGap = max(12, runH * 2)
		val minSegWidth = max(3, runH / 3)
		val minSegHits = max(4, runH / 2)
		var spanL = Int.MAX_VALUE
		var spanR = Int.MIN_VALUE
		var segStart = -1
		var segEnd = -1
		var segHits = 0
		fun closeSegment()
		{
			if(segStart < 0)
				return
			val segR = segEnd + 1
			val center = (segStart + segR) / 2f / width
			if(segR - segStart >= minSegWidth && segHits >= minSegHits && center >= centerMin && center <= centerMax)
			{
				spanL = min(spanL, segStart)
				spanR = max(spanR, segR)
			}
		}
		for(x in 0 until width)
		{
			val h = colE[x]
			if(h == 0)
				continue
			if(segStart >= 0 && x - segEnd > mergeGap)
			{
				closeSegment()
				segStart = -1
			}
			if(segStart < 0)
			{
				segStart = x
				segHits = 0
			}
			segEnd = x
			segHits += h
		}
		closeSegment()
		return if(spanL < spanR) spanL to spanR else null
	}

	/** Kümedeki "çekirdek" (kenar enerjisi tepenin yarısını geçen, ≥2 satırlık) bölge sayısı ≈ yazı satırı sayısı. */
	private fun countCores(rowE: IntArray, r0: Int, r1: Int): Int
	{
		var peak = 0
		for(y in r0 until r1)
			peak = max(peak, rowE[y])
		val thr = peak / 2
		var cores = 0
		var len = 0
		var gap = 0
		for(y in r0 until r1)
		{
			if(rowE[y] >= thr)
			{
				if(len == 0 || gap > 1)
				{
					if(len >= 2)
						cores++
					len = 0
				}
				len++
				gap = 0
			}
			else if(len > 0)
				gap++
		}
		if(len >= 2)
			cores++
		return max(1, cores)
	}

	/**
	 * Bölgeyi OCR kırpmasına çevirir: kutu bandın büyük kısmını kaplıyorsa tüm bant; büyük yazı
	 * satır yüksekliği ~[TARGET_LINE_PX] olacak şekilde küçültülür (asla büyütülmez), genişlik
	 * [maxWidth]'i geçmez. [region] null ise tüm bant.
	 */
	fun toCrop(region: TextRegion?, bandW: Int, bandH: Int, maxWidth: Int): OcrCrop
	{
		val r = region?.takeIf { it.width.toLong() * it.height < FULL_BAND_AREA * bandW.toLong() * bandH }
		val sl = r?.left ?: 0
		val st = r?.top ?: 0
		val sw = r?.width ?: bandW
		val sh = r?.height ?: bandH
		var scale = 1f
		val lh = region?.lineHeight ?: 0
		if(lh > SCALE_ABOVE_LINE_PX)
			scale = max(MIN_SCALE, TARGET_LINE_PX.toFloat() / lh)
		scale = min(scale, maxWidth.toFloat() / sw)
		var dw = max(2, (sw * scale).roundToInt() and 1.inv())
		var dh = max(2, (sh * scale).roundToInt() and 1.inv())
		if(dw > sw) dw = sw and 1.inv()
		if(dh > sh) dh = sh and 1.inv()
		return OcrCrop(sl, st, sw, sh, dw, dh)
	}
}
