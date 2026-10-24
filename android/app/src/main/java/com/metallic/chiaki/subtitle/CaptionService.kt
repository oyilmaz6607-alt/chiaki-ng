// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.metallic.chiaki.BuildConfig
import com.metallic.chiaki.R
import com.metallic.chiaki.common.LogManager
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * "HDMI üstü altyazı" modu (Game Caption'daki gibi):
 * Oyuncu oyunu PS5'ten HDMI ile normal oynar. Bu servis arka planda PS5'e sessiz bir Remote Play
 * oturumu açar, görüntüyü ekrana basmadan sadece altyazıyı okumak için kullanır ve Türkçe çeviriyi
 * diğer uygulamaların (HDMI girişinin) üstünde gösterir.
 */
class CaptionService : Service()
{
	companion object
	{
		private const val TAG = "CaptionService"
		const val EXTRA_CONNECT_INFO = "connect_info"
		const val ACTION_STOP = "com.altyazitr.STOP"
		private const val CHANNEL_ID = "altyazi"
		private const val NOTIFICATION_ID = 6607

		/** Okuma için düşük ama yeterli çözünürlük: PS5 ve TV'yi yormaz. */
		private const val CAPTURE_W = 1280
		private const val CAPTURE_H = 720

		@Volatile var isRunning = false
			private set

		fun canDrawOverlays(context: Context) = Settings.canDrawOverlays(context)

		fun start(context: Context, connectInfo: ConnectInfo)
		{
			val intent = Intent(context, CaptionService::class.java).putExtra(EXTRA_CONNECT_INFO, connectInfo)
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
				context.startForegroundService(intent)
			else
				context.startService(intent)
		}

		fun stop(context: Context)
		{
			context.startService(Intent(context, CaptionService::class.java).setAction(ACTION_STOP))
		}
	}

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private var session: Session? = null
	private var imageReader: ImageReader? = null

	private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	private val translator = Translation.getClient(
		TranslatorOptions.Builder()
			.setSourceLanguage(TranslateLanguage.ENGLISH)
			.setTargetLanguage(TranslateLanguage.TURKISH)
			.build()
	)
	private val cache = LruCache<String, String>(300)
	private val tracker = SubtitleTracker()

	private lateinit var windowManager: WindowManager
	private var captionView: TextView? = null
	private var statusView: TextView? = null
	private val showDebug = BuildConfig.DEBUG

	private var loopJob: Job? = null
	private var modelReady = false
	private var connected = false
	private var frames = 0
	private var lastOcr = ""
	private var lastError = ""

	private lateinit var prefs: Preferences

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onCreate()
	{
		super.onCreate()
		windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
		prefs = Preferences(this)
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
	{
		startInForeground()
		if(intent?.action == ACTION_STOP)
		{
			stopSelf()
			return START_NOT_STICKY
		}
		val connectInfo = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_CONNECT_INFO, ConnectInfo::class.java) }
		if(connectInfo == null || session != null)
			return START_NOT_STICKY

		if(!canDrawOverlays(this))
		{
			Log.e(TAG, "No overlay permission")
			stopSelf()
			return START_NOT_STICKY
		}

		isRunning = true
		addOverlays()
		status(getString(R.string.caption_status_connecting))
		startSession(connectInfo)
		loopJob = scope.launch { captionLoop() }
		return START_NOT_STICKY
	}

	override fun onDestroy()
	{
		isRunning = false
		loopJob?.cancel()
		scope.cancel()
		session?.let {
			it.eventCallback = null
			it.stop()
			// join() bekler; ana iş parçacığını kilitlememek için ayrı yerde
			Thread { it.dispose() }.start()
		}
		session = null
		imageReader?.close()
		imageReader = null
		recognizer.close()
		translator.close()
		removeOverlays()
		super.onDestroy()
	}

	// ---------------------------------------------------------------- oturum

	private fun startSession(info: ConnectInfo)
	{
		val profile = ConnectVideoProfile.preset(VideoResolutionPreset.RES_720P, VideoFPSPreset.FPS_30, info.videoProfile.codec)
		val muted = info.copy(videoProfile = profile, muteAudio = true)
		val reader = ImageReader.newInstance(CAPTURE_W, CAPTURE_H, ImageFormat.YUV_420_888, 3)
		imageReader = reader
		try
		{
			val logFile = LogManager(this).createNewFile().file.absolutePath
			val s = Session(muted, logFile, prefs.logVerbose)
			s.eventCallback = { event -> scope.launch { onSessionEvent(event) } }
			s.start()
			s.setSurface(reader.surface)
			session = s
		}
		catch(e: CreateError)
		{
			lastError = "Oturum: ${e.errorCode}"
			status(getString(R.string.caption_status_failed, e.errorCode.toString()))
			scope.launch { delay(8000); stopSelf() }
		}
	}

	private fun onSessionEvent(event: Event)
	{
		when(event)
		{
			is ConnectedEvent ->
			{
				connected = true
				status(if(modelReady) null else getString(R.string.subtitle_model_downloading))
			}
			is QuitEvent ->
			{
				connected = false
				status(getString(R.string.caption_status_quit, event.reasonString ?: event.reason.toString()))
				scope.launch { delay(8000); stopSelf() }
			}
			is LoginPinRequestEvent ->
			{
				status(getString(R.string.caption_status_pin))
				scope.launch { delay(8000); stopSelf() }
			}
			else -> {}
		}
	}

	// ---------------------------------------------------------------- okuma + çeviri

	private suspend fun captionLoop()
	{
		while(currentCoroutineContext().isActive && !modelReady)
		{
			try
			{
				translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
				modelReady = true
				if(connected) status(null)
			}
			catch(e: Exception)
			{
				lastError = "Model: ${e.message}"
				status(getString(R.string.subtitle_model_failed))
				delay(10_000)
			}
		}

		val interval = prefs.subtitleIntervalMs.toLong()
		val fraction = (prefs.subtitleCapturePercent / 100f).coerceIn(0.1f, 1f)
		while(currentCoroutineContext().isActive)
		{
			val started = System.currentTimeMillis()
			try
			{
				processLatestFrame(fraction)
			}
			catch(e: CancellationException)
			{
				throw e
			}
			catch(e: Exception)
			{
				Log.w(TAG, "frame failed", e)
				lastError = "${e.javaClass.simpleName}: ${e.message}"
			}
			if(showDebug) debugStatus()
			delay(max(50L, interval - (System.currentTimeMillis() - started)))
		}
	}

	private suspend fun processLatestFrame(fraction: Float)
	{
		val reader = imageReader ?: return
		val image: Image = withContext(Dispatchers.IO) { reader.acquireLatestImage() } ?: return
		val w: Int
		val h: Int
		val lines: List<OcrLine>
		try
		{
			w = image.width
			h = image.height
			val result = recognizer.process(InputImage.fromMediaImage(image, 0)).await()
			lines = result.textBlocks.flatMap { block ->
				block.lines.mapNotNull { line ->
					val box = line.boundingBox ?: return@mapNotNull null
					OcrLine(line.text, box.left.toFloat() / w, box.top.toFloat() / h, box.right.toFloat() / w, box.bottom.toFloat() / h)
				}
			}.filter { it.top >= 1f - fraction }
		}
		finally
		{
			image.close()
		}
		frames++

		val candidate = SubtitleFilter.pickSubtitle(lines)
		lastOcr = candidate?.text ?: ""
		when(val action = tracker.onFrame(candidate))
		{
			is SubtitleTracker.Action.Show ->
			{
				val turkish = translate(action.candidate.text)
				showCaption(turkish, action.candidate)
			}
			is SubtitleTracker.Action.Keep -> placeCaption(action.candidate)
			SubtitleTracker.Action.Hide -> captionView?.visibility = View.GONE
			SubtitleTracker.Action.None -> {}
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

	// ---------------------------------------------------------------- ekran üstü katman

	@Suppress("DEPRECATION")
	private fun overlayType(): Int =
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
		else WindowManager.LayoutParams.TYPE_PHONE

	private fun overlayParams(gravity: Int) = WindowManager.LayoutParams(
		WindowManager.LayoutParams.WRAP_CONTENT,
		WindowManager.LayoutParams.WRAP_CONTENT,
		overlayType(),
		WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
				WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
				WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
		PixelFormat.TRANSLUCENT
	).also { it.gravity = gravity }

	private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

	@SuppressLint("SetTextI18n")
	private fun addOverlays()
	{
		val caption = TextView(this).apply {
			setTextColor(Color.WHITE)
			setBackgroundColor(Color.argb(235, 16, 16, 16))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.subtitleTextSize.toFloat())
			gravity = Gravity.CENTER
			setPadding(dp(16), dp(6), dp(16), dp(6))
			maxWidth = (resources.displayMetrics.widthPixels * 0.8f).roundToInt()
			setShadowLayer(4f, 1f, 1f, Color.BLACK)
			visibility = View.GONE
		}
		val status = TextView(this).apply {
			setTextColor(Color.rgb(255, 235, 59))
			setBackgroundColor(Color.argb(180, 0, 0, 0))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
			setPadding(dp(8), dp(4), dp(8), dp(4))
			maxWidth = (resources.displayMetrics.widthPixels * 0.5f).roundToInt()
			visibility = View.GONE
		}
		try
		{
			windowManager.addView(caption, overlayParams(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).also { it.y = dp(48) })
			windowManager.addView(status, overlayParams(Gravity.TOP or Gravity.START).also { it.x = dp(16); it.y = dp(16) })
			captionView = caption
			statusView = status
		}
		catch(e: Exception)
		{
			Log.e(TAG, "addView failed", e)
		}
	}

	private fun removeOverlays()
	{
		listOfNotNull(captionView, statusView).forEach {
			try { windowManager.removeView(it) } catch(_: Exception) {}
		}
		captionView = null
		statusView = null
	}

	private fun showCaption(text: String, candidate: SubtitleCandidate)
	{
		val v = captionView ?: return
		v.text = text
		v.visibility = View.VISIBLE
		placeCaption(candidate)
	}

	/** HDMI görüntüsü tam ekran: altyazının ekrandaki yeri = kare içindeki oranı × ekran yüksekliği. */
	private fun placeCaption(candidate: SubtitleCandidate)
	{
		val v = captionView ?: return
		val params = v.layoutParams as? WindowManager.LayoutParams ?: return
		val screenH = resources.displayMetrics.heightPixels
		if(prefs.subtitleCoverOriginal)
		{
			val pad = dp(8)
			params.y = max(0, (screenH * (1f - candidate.bottom)).roundToInt() - pad)
			v.minHeight = ((candidate.bottom - candidate.top) * screenH).roundToInt() + pad * 2
		}
		else
		{
			params.y = dp(48)
			v.minHeight = 0
		}
		try { windowManager.updateViewLayout(v, params) } catch(_: Exception) {}
	}

	/** null = durum yazısını gizle (test sürümünde tanılama bilgisi yine görünür). */
	private fun status(text: String?)
	{
		val v = statusView ?: return
		if(text == null && !showDebug)
		{
			v.visibility = View.GONE
			return
		}
		v.text = text ?: ""
		v.visibility = View.VISIBLE
		if(text != null && showDebug)
			v.append("\n" + debugLine())
	}

	private fun debugLine() =
		"bağlı=$connected model=${if(modelReady) "hazır" else "yok"} kare=$frames" +
				(if(lastOcr.isNotEmpty()) "\nOkunan: ${lastOcr.take(70)}" else "") +
				(if(lastError.isNotEmpty()) "\nHata: ${lastError.take(100)}" else "")

	private fun debugStatus()
	{
		val v = statusView ?: return
		v.visibility = View.VISIBLE
		v.text = "AltyazıTR (test)\n" + debugLine()
	}

	// ---------------------------------------------------------------- ön plan bildirimi

	private fun startInForeground()
	{
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
			getSystemService(NotificationManager::class.java).createNotificationChannel(
				NotificationChannel(CHANNEL_ID, getString(R.string.caption_channel), NotificationManager.IMPORTANCE_LOW))
		val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
			.setContentTitle(getString(R.string.caption_notification_title))
			.setSmallIcon(R.mipmap.ic_launcher)
			.setOngoing(true)
			.build()
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
			startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
		else
			startForeground(NOTIFICATION_ID, notification)
	}
}
