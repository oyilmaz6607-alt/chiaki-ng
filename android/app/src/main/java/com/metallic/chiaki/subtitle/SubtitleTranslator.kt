// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.LruCache
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.metallic.chiaki.R
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reads the game's English subtitles from the stream picture (OCR), translates them to Turkish
 * on-device and draws the translation on top of the original subtitle.
 *
 * Everything runs on the TV: no frames or text leave the device (after the one-time model download).
 */
class SubtitleTranslator(
	private val surfaceView: SurfaceView,
	private val output: TextView,
	private val scope: CoroutineScope,
	private val config: Config = Config(),
	/** Test sürümünde köşede durum bilgisi gösterir (null = kapalı). */
	private val debugView: TextView? = null
)
{
	data class Config(
		/** Bottom part of the picture that is scanned for subtitles (0.1 .. 1.0). */
		val captureFraction: Float = 0.35f,
		/** Time between two scans. Lower = faster reaction, more CPU. */
		val intervalMs: Long = 300L,
		/** OCR input is downscaled to this width to keep it fast on TV hardware. */
		val maxCaptureWidth: Int = 1280,
		/** Cover the English subtitle with the Turkish one instead of a fixed bottom bar. */
		val coverOriginal: Boolean = true
	)

	companion object
	{
		private const val TAG = "SubtitleTranslator"
	}

	private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	private val translator: Translator = Translation.getClient(
		TranslatorOptions.Builder()
			.setSourceLanguage(TranslateLanguage.ENGLISH)
			.setTargetLanguage(TranslateLanguage.TURKISH)
			.build()
	)
	private val cache = LruCache<String, String>(300)
	private val tracker = SubtitleTracker()

	private val copyThread = HandlerThread("SubtitlePixelCopy").also { it.start() }
	private val copyHandler = Handler(copyThread.looper)

	private var job: Job? = null
	private var bitmap: Bitmap? = null
	private var modelReady = false

	// Tanılama bilgisi
	private var frames = 0
	private var copyFails = 0
	private var lastOcr = ""
	private var lastError = ""
	private var modelState = "bekliyor"
	private var lastTranslation = ""

	private fun updateDebug()
	{
		val v = debugView ?: return
		v.visibility = View.VISIBLE
		v.text = buildString {
			append("Altyazı: model=").append(modelState)
			append(" | kare=").append(frames)
			append(" | kopya hatası=").append(copyFails)
			if(lastOcr.isNotEmpty()) append("\nOkunan: ").append(lastOcr.take(80))
			if(lastTranslation.isNotEmpty()) append("\nÇeviri: ").append(lastTranslation.take(80))
			if(lastError.isNotEmpty()) append("\nHata: ").append(lastError.take(120))
		}
	}

	val isRunning get() = job?.isActive == true

	fun start()
	{
		if(isRunning)
			return
		tracker.reset()
		job = scope.launch {
			// Model inene kadar tekrar dene (internet geç gelebilir)
			while(isActive && !modelReady)
			{
				ensureModel()
				if(!modelReady)
					delay(10_000)
			}
			while(isActive)
			{
				val started = System.currentTimeMillis()
				try
				{
					tick()
				}
				catch(e: CancellationException)
				{
					throw e
				}
				catch(e: Exception)
				{
					Log.w(TAG, "Subtitle frame failed", e)
					lastError = "${e.javaClass.simpleName}: ${e.message}"
				}
				frames++
				updateDebug()
				val elapsed = System.currentTimeMillis() - started
				delay(max(50L, config.intervalMs - elapsed))
			}
		}
	}

	fun stop()
	{
		job?.cancel()
		job = null
		tracker.reset()
		output.visibility = View.GONE
		debugView?.visibility = View.GONE
	}

	fun release()
	{
		stop()
		recognizer.close()
		translator.close()
		copyThread.quitSafely()
		bitmap?.recycle()
		bitmap = null
	}

	private suspend fun ensureModel()
	{
		if(modelReady)
			return
		showStatus(output.context.getString(R.string.subtitle_model_downloading))
		modelState = "iniyor"
		updateDebug()
		try
		{
			translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
			modelReady = true
			modelState = "hazır"
			updateDebug()
			output.visibility = View.GONE
		}
		catch(e: Exception)
		{
			Log.e(TAG, "Translation model download failed", e)
			modelState = "HATA"
			lastError = "Model: ${e.javaClass.simpleName}: ${e.message}"
			updateDebug()
			showStatus(output.context.getString(R.string.subtitle_model_failed))
			delay(4000)
			output.visibility = View.GONE
		}
	}

	private suspend fun tick()
	{
		val frame = surfaceView.holder.surfaceFrame
		if(!surfaceView.holder.surface.isValid || frame.width() <= 0 || frame.height() <= 0)
			return

		val fraction = config.captureFraction.coerceIn(0.1f, 1f)
		val src = Rect(0, (frame.height() * (1f - fraction)).roundToInt(), frame.width(), frame.height())
		if(src.height() <= 0)
			return

		val scale = min(1f, config.maxCaptureWidth.toFloat() / src.width())
		val dstW = max(1, (src.width() * scale).roundToInt())
		val dstH = max(1, (src.height() * scale).roundToInt())
		val bmp = bitmap?.takeIf { it.width == dstW && it.height == dstH }
			?: Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888).also {
				bitmap?.recycle()
				bitmap = it
			}

		if(!copyFrame(src, bmp))
		{
			copyFails++
			return
		}

		val result = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
		val lines = result.textBlocks.flatMap { block ->
			block.lines.mapNotNull { line ->
				val box = line.boundingBox ?: return@mapNotNull null
				OcrLine(
					text = line.text,
					left = box.left.toFloat() / dstW,
					top = box.top.toFloat() / dstH,
					right = box.right.toFloat() / dstW,
					bottom = box.bottom.toFloat() / dstH
				)
			}
		}

		val candidate = SubtitleFilter.pickSubtitle(lines)
		lastOcr = candidate?.text ?: (if(lines.isEmpty()) "(yazı yok)" else "(${lines.size} satır, altyazı değil)")
		when(val action = tracker.onFrame(candidate))
		{
			is SubtitleTracker.Action.Show ->
			{
				val turkish = translate(action.candidate.text)
				lastTranslation = turkish
				show(turkish, action.candidate, fraction)
			}
			is SubtitleTracker.Action.Keep -> place(action.candidate, fraction)
			SubtitleTracker.Action.Hide -> output.visibility = View.GONE
			SubtitleTracker.Action.None -> {}
		}
	}

	private suspend fun copyFrame(src: Rect, dest: Bitmap): Boolean =
		suspendCancellableCoroutine { cont ->
			try
			{
				PixelCopy.request(surfaceView, src, dest, { result ->
					if(cont.isActive)
						cont.resume(result == PixelCopy.SUCCESS)
				}, copyHandler)
			}
			catch(e: IllegalArgumentException)
			{
				// Surface went away between the validity check and the copy.
				if(cont.isActive)
					cont.resume(false)
			}
		}

	private suspend fun translate(english: String): String
	{
		val key = SubtitleFilter.normalize(english)
		cache.get(key)?.let { return it }
		val turkish = translator.translate(english).await()
		cache.put(key, turkish)
		return turkish
	}

	private fun showStatus(text: String)
	{
		output.text = text
		(output.layoutParams as? FrameLayout.LayoutParams)?.let {
			it.bottomMargin = defaultBottomMargin()
			output.layoutParams = it
		}
		output.minHeight = 0
		output.visibility = View.VISIBLE
	}

	private fun show(turkish: String, candidate: SubtitleCandidate, fraction: Float)
	{
		output.text = turkish
		output.visibility = View.VISIBLE
		place(candidate, fraction)
	}

	/**
	 * Positions the Turkish text so it sits over (and hides) the English subtitle.
	 */
	private fun place(candidate: SubtitleCandidate, fraction: Float)
	{
		val params = output.layoutParams as? FrameLayout.LayoutParams ?: return
		val parent = output.parent as? ViewGroup ?: return
		if(!config.coverOriginal || surfaceView.height == 0 || parent.height == 0)
		{
			params.bottomMargin = defaultBottomMargin()
			output.minHeight = 0
			output.layoutParams = params
			return
		}

		val surfaceLoc = IntArray(2).also { surfaceView.getLocationInWindow(it) }
		val parentLoc = IntArray(2).also { parent.getLocationInWindow(it) }
		val viewTop = surfaceLoc[1] - parentLoc[1]
		val captureTop = viewTop + surfaceView.height * (1f - fraction)
		val captureHeight = surfaceView.height * fraction

		val pad = (8 * output.resources.displayMetrics.density).roundToInt()
		val subtitleBottom = captureTop + candidate.bottom * captureHeight
		val subtitleHeight = (candidate.bottom - candidate.top) * captureHeight

		params.bottomMargin = max(0, (parent.height - subtitleBottom).roundToInt() - pad)
		output.minHeight = subtitleHeight.roundToInt() + pad * 2
		output.layoutParams = params
	}

	private fun defaultBottomMargin() = (48 * output.resources.displayMetrics.density).roundToInt()
}
