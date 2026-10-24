// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

import kotlin.math.abs

/**
 * Ucuz değişiklik algılayıcı (Android bağımlılığı yok).
 *
 * Altyazı bölgesinin luma (Y) baytlarından [cols]×[rows] ızgaralık küçük bir "imza" (hücre ortalamaları,
 * 0..255) çıkarır. İki imza arasında belirgin şekilde değişen hücre sayısı [minChangedCells] veya
 * fazlaysa bölge "değişti" sayılır. OCR yalnızca bölge değiştiğinde (veya yedek olarak arada bir)
 * çalıştırılır; böylece pahalı OCR her karede değil, sadece altyazı değişince koşar.
 *
 * Sınıf yalnızca yapılandırma tutar (durumsuz), farklı iş parçacıklarından güvenle kullanılabilir.
 */
class FrameChangeDetector(
	val cols: Int = 64,
	val rows: Int = 16,
	/** Bir hücrenin "değişti" sayılması için ortalama parlaklık farkı (0..255). Video gürültüsünü yok sayar. */
	private val cellDelta: Int = 12,
	/** Bölgenin "değişti" sayılması için gereken en az değişen hücre sayısı. */
	private val minChangedCells: Int = 2,
	/** Örnekleme adımı (piksel): 2 = her 2. satır/sütun; hızlı ve yeterli. */
	private val step: Int = 2
)
{
	init
	{
		require(cols > 0 && rows > 0 && step > 0)
	}

	val size get() = cols * rows

	/**
	 * [luma] içindeki width×height bölgenin imzasını [out] dizisine yazar ve onu döndürür.
	 * [offset] ilk pikselin konumu, [rowStride] satırlar arası bayt sayısıdır (pixel stride = 1).
	 */
	fun computeSignature(
		luma: ByteArray,
		width: Int,
		height: Int,
		rowStride: Int = width,
		offset: Int = 0,
		out: IntArray = IntArray(size)
	): IntArray
	{
		require(out.size >= size)
		if(width <= 0 || height <= 0)
		{
			out.fill(0, 0, size)
			return out
		}
		val sums = LongArray(size)
		val counts = IntArray(size)
		val colOf = IntArray(width) { x -> x * cols / width }
		var y = 0
		while(y < height)
		{
			val rowBase = (y * rows / height) * cols
			val lineStart = offset + y * rowStride
			var x = 0
			while(x < width)
			{
				val cell = rowBase + colOf[x]
				sums[cell] += (luma[lineStart + x].toInt() and 0xFF).toLong()
				counts[cell]++
				x += step
			}
			y += step
		}
		for(i in 0 until size)
			out[i] = if(counts[i] > 0) (sums[i] / counts[i]).toInt() else 0
		return out
	}

	/** Belirgin şekilde değişen hücre sayısı. */
	fun changedCells(a: IntArray, b: IntArray): Int
	{
		var n = 0
		for(i in 0 until size)
			if(abs(a[i] - b[i]) > cellDelta)
				n++
		return n
	}

	/**
	 * Fraction (0..1) of cells inside the box [left]..[right] × [top]..[bottom] (normalized to the
	 * signature region) that changed noticeably. Used to tell "the subtitle itself disappeared"
	 * apart from motion elsewhere in the region.
	 */
	fun changedFraction(a: IntArray, b: IntArray, left: Float, top: Float, right: Float, bottom: Float): Float
	{
		val c0 = (left * cols).toInt().coerceIn(0, cols - 1)
		val c1 = (right * cols).toInt().coerceIn(c0, cols - 1)
		val r0 = (top * rows).toInt().coerceIn(0, rows - 1)
		val r1 = (bottom * rows).toInt().coerceIn(r0, rows - 1)
		var changed = 0
		var total = 0
		for(r in r0..r1)
			for(c in c0..c1)
			{
				total++
				val i = r * cols + c
				if(abs(a[i] - b[i]) > cellDelta)
					changed++
			}
		return if(total == 0) 0f else changed.toFloat() / total
	}

	fun isChanged(a: IntArray?, b: IntArray?): Boolean
	{
		if(a == null || b == null)
			return true
		return changedCells(a, b) >= minChangedCells
	}
}

/**
 * OCR'ın ne zaman çalışacağına karar verir (Android bağımlılığı yok, tek iş parçacığından kullanılmalı).
 *
 * - Bir OCR sürüyorsa yenisini başlatmaz (kuyruk yok, eski kareler atılır).
 * - Bölge, son OCR'a verilen kareye göre değiştiyse hemen OCR ister (yavaş değişimler de birikerek yakalanır).
 * - Hiç değişmese bile [fallbackMs] aralıkla bir kez OCR ister (kaçırılan durumlar için yedek).
 * - İki OCR başlangıcı arasında en az [minGapMs] bırakır (zayıf CPU'yu boğmamak için).
 */
class OcrScheduler(
	private val detector: FrameChangeDetector,
	private val fallbackMs: Long = 1000,
	private val minGapMs: Long = 0
)
{
	private var ocrRef: IntArray? = null
	private var lastOcrAt = 0L
	private var hasRun = false

	/** true dönerse çağıran OCR'ı başlatmalıdır; imza referans olarak kaydedilir. */
	fun shouldRun(signature: IntArray, nowMs: Long, ocrBusy: Boolean): Boolean
	{
		if(ocrBusy)
			return false
		val sinceLast = nowMs - lastOcrAt
		if(hasRun && sinceLast < minGapMs)
			return false
		val due = !hasRun || sinceLast >= fallbackMs || detector.isChanged(signature, ocrRef)
		if(!due)
			return false
		ocrRef = signature.copyOf()
		lastOcrAt = nowMs
		hasRun = true
		return true
	}

	fun reset()
	{
		ocrRef = null
		lastOcrAt = 0L
		hasRun = false
	}
}
