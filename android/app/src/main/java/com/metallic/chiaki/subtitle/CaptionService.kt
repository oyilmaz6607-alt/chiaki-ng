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
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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

		/** Değişim algılayıcının bölgeye bakma sıklığı (ms). Ucuz: yalnızca Y baytları + 64x16 ortalama. */
		private const val SAMPLE_INTERVAL_MS = 60L
		/**
		 * OCR'a giden bölgenin en büyük genişliği. 720p'de küçültme yok (altyazı harfleri ~20 px; ML Kit
		 * ~16 px altında hata yapmaya başlıyor). Asıl kazanç zaten tam kare yerine yalnızca alt bölgeyi okumak.
		 */
		private const val OCR_MAX_WIDTH = 1280
		/** Gösterilen altyazının kutusundaki hücrelerin bu oranı değiştiyse boş OCR'da hemen gizle. */
		private const val HIDE_BOX_CHANGE = 0.2f
		private const val MOVE_EPS = 0.01f
		private const val COVER_SLACK_DP = 8
		private const val COVER_MAX_WIDTH = 0.92f
		private const val COVER_MAX_HEIGHT = 0.3f
		private const val BAND_MARGIN_DP = 48
		private const val MAX_LINES = 3
		private const val MIN_SHRINK = 0.6f

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

	/** OCR'a verilecek iş: altyazı bölgesinin gri NV21 hâli + kareye göre konumu. */
	private class OcrJob(
		val nv21: ByteArray,
		val width: Int,
		val height: Int,
		/** OCR'a giden karedeki bölgenin değişim imzası. */
		val signature: IntArray,
		val frameW: Int,
		val frameH: Int,
		/** Bölgenin kare içindeki üst satırı ve yüksekliği (piksel). */
		val regionTop: Int,
		val regionH: Int,
		/** Karenin ImageReader'dan alındığı an (uptime ms) — uçtan uca ölçüm için. */
		val frameAt: Long
	)
	{
		fun toRegionY(frameY: Float) = ((frameY * frameH - regionTop) / regionH).coerceIn(0f, 1f)
	}

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private var session: Session? = null
	private var imageReader: ImageReader? = null
	private var frameThread: HandlerThread? = null

	private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	private val translator = Translation.getClient(
		TranslatorOptions.Builder()
			.setSourceLanguage(TranslateLanguage.ENGLISH)
			.setTargetLanguage(TranslateLanguage.TURKISH)
			.build()
	)
	private val cache = LruCache<String, String>(300)
	private val tracker = SubtitleTracker()

	// ---- kare iş parçacığında kullanılanlar
	private val detector = FrameChangeDetector()
	private lateinit var scheduler: OcrScheduler
	private val ocrBusy = AtomicBoolean(false)
	@Volatile private var processingEnabled = false
	@Volatile private var captureFraction = 0.35f
	private var lastSampleAt = 0L
	private var regionY = ByteArray(0)
	private var nv21 = ByteArray(0)
	private val signature = IntArray(detector.size)

	// ---- ana iş parçacığında kullanılanlar
	/** Gösterilen altyazının bölge imzası ve kutusu (bölgeye göre 0..1): kaybolduğunu hızlı anlamak için. */
	private var shownSignature: IntArray? = null
	private var shownBox: FloatArray? = null
	/** Her Show/Hide'da artar; çeviri dönene kadar yenisi geldiyse eski çeviri gösterilmez. */
	private var captionGen = 0
	private var placedBox: SubtitleCandidate? = null

	private lateinit var windowManager: WindowManager
	private var captionView: TextView? = null
	private var statusView: TextView? = null
	private val showDebug = BuildConfig.DEBUG
	private var captionPosition = CaptionStyle.POSITION_COVER
	private var bandMaxWidthFraction = 0.8f
	private var baseTextPx = 0f
	private var screenW = 1920
	private var screenH = 1080

	private var loopJob: Job? = null
	private var modelReady = false
	private var connected = false
	@Volatile private var frames = 0
	@Volatile private var sampled = 0
	private var ocrRuns = 0
	private var ocrMs = 0L
	private var e2eMs = 0L
	private var lastOcr = ""
	@Volatile private var lastError = ""

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
		captureFraction = (prefs.subtitleCapturePercent / 100f).coerceIn(0.1f, 1f)
		// Ayar (150..1500 ms, varsayılan 300) artık sabit bekleme değil: iki OCR arasındaki en kısa boşluk
		// ve hiçbir değişiklik yokken yapılan yedek OCR sıklığı bundan türetilir.
		val interval = prefs.subtitleIntervalMs.toLong()
		scheduler = OcrScheduler(detector, fallbackMs = max(1000L, interval * 3), minGapMs = interval / 3)
		addOverlays()
		status(getString(R.string.caption_status_connecting))
		startSession(connectInfo)
		loopJob = scope.launch { captionLoop() }
		return START_NOT_STICKY
	}

	override fun onDestroy()
	{
		isRunning = false
		processingEnabled = false
		loopJob?.cancel()
		scope.cancel()
		session?.let {
			it.eventCallback = null
			it.stop()
			// join() bekler; ana iş parçacığını kilitlememek için ayrı yerde
			Thread { it.dispose() }.start()
		}
		session = null
		val reader = imageReader
		imageReader = null
		val thread = frameThread
		frameThread = null
		if(reader != null)
		{
			reader.setOnImageAvailableListener(null, null)
			// Kare iş parçacığında kapat: o sırada işlenen bir kareyle çakışmasın.
			if(thread != null)
				Handler(thread.looper).post { reader.close() }
			else
				reader.close()
		}
		thread?.quitSafely()
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
		// Her kare geldiği anda alınıp kapatılır: ImageReader kuyruğu dolarsa çözücünün çıkış iş parçacığı
		// bekleyebilir ve okunan kare birkaç kare eskiyebilir. Böylece her zaman en taze kare elde olur.
		val thread = HandlerThread("caption-frames").also { it.start() }
		frameThread = thread
		reader.setOnImageAvailableListener({ onImageAvailable(it) }, Handler(thread.looper))
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
				processingEnabled = true
				if(connected) status(null)
			}
			catch(e: CancellationException)
			{
				throw e
			}
			catch(e: Exception)
			{
				lastError = "Model: ${e.message}"
				status(getString(R.string.subtitle_model_failed))
				delay(10_000)
			}
		}
		// Kareler artık onImageAvailable'da işleniyor; burada yalnızca test sürümünün durum satırı yenilenir.
		while(showDebug && currentCoroutineContext().isActive)
		{
			debugStatus()
			delay(500)
		}
	}

	/**
	 * Kare iş parçacığı: her kare alınır ve hemen kapatılır. ~[SAMPLE_INTERVAL_MS]'de bir altyazı bölgesinin
	 * Y (parlaklık) baytları kopyalanır, küçük bir imza çıkarılır; bölge değiştiyse (veya yedek süre dolduysa)
	 * ve hiç OCR sürmüyorsa bölge küçültülüp OCR'a gönderilir. Kuyruk yok: eski kareler atılır.
	 */
	private fun onImageAvailable(reader: ImageReader)
	{
		val image = try { reader.acquireLatestImage() } catch(e: IllegalStateException) { null } ?: return
		frames++
		val now = SystemClock.uptimeMillis()
		val frameW: Int
		val frameH: Int
		val regionTop: Int
		val regionH: Int
		try
		{
			if(!processingEnabled || now - lastSampleAt < SAMPLE_INTERVAL_MS)
				return
			lastSampleAt = now
			val crop = image.cropRect
			val noCrop = crop.isEmpty()
			val cropLeft = if(noCrop) 0 else crop.left
			val cropTop = if(noCrop) 0 else crop.top
			frameW = if(noCrop) image.width else crop.width()
			frameH = if(noCrop) image.height else crop.height()
			// Çift yükseklik: NV21 için gerekli, böylece küçültmesiz düz kopyalama yolu kullanılır.
			regionTop = (frameH - ((frameH * captureFraction).toInt() and 1.inv())).coerceIn(0, frameH - 2)
			regionH = frameH - regionTop
			if(regionY.size != frameW * regionH)
				regionY = ByteArray(frameW * regionH)
			val plane = image.planes[0]
			val buf = plane.buffer
			val rowStride = plane.rowStride
			val pixelStride = plane.pixelStride
			for(row in 0 until regionH)
			{
				val src = (cropTop + regionTop + row) * rowStride + cropLeft * pixelStride
				val dst = row * frameW
				if(pixelStride == 1)
				{
					buf.position(src)
					buf.get(regionY, dst, frameW)
				}
				else
				{
					for(x in 0 until frameW)
						regionY[dst + x] = buf.get(src + x * pixelStride)
				}
			}
		}
		catch(e: Exception)
		{
			lastError = "Kare: ${e.javaClass.simpleName}: ${e.message}"
			return
		}
		finally
		{
			image.close()
		}
		sampled++

		detector.computeSignature(regionY, frameW, regionH, frameW, 0, signature)
		if(!scheduler.shouldRun(signature, now, ocrBusy.get()))
			return

		val (dstW, dstH) = LumaCrop.targetSize(frameW, regionH, OCR_MAX_WIDTH)
		val size = LumaCrop.nv21Size(dstW, dstH)
		// Tek OCR sürdüğü için (ocrBusy) bu tampon güvenle yeniden kullanılır.
		if(nv21.size != size)
			nv21 = ByteArray(size)
		LumaCrop.toGrayNv21(regionY, 0, frameW, frameW, regionH, dstW, dstH, nv21)
		val job = OcrJob(nv21, dstW, dstH, signature.copyOf(), frameW, frameH, regionTop, regionH, now)
		ocrBusy.set(true)
		scope.launch { runOcr(job) }.invokeOnCompletion { ocrBusy.set(false) }
	}

	/** Ana iş parçacığı. */
	private suspend fun runOcr(job: OcrJob)
	{
		val started = SystemClock.uptimeMillis()
		try
		{
			val input = InputImage.fromByteArray(job.nv21, job.width, job.height, 0, InputImage.IMAGE_FORMAT_NV21)
			val result = recognizer.process(input).await()
			ocrMs = SystemClock.uptimeMillis() - started
			ocrRuns++
			// Kutu koordinatları küçültülmüş bölgeye göre; yerleştirme için TAM kareye göre 0..1'e çevrilir.
			val lines = result.textBlocks.flatMap { block ->
				block.lines.mapNotNull { line ->
					val box = line.boundingBox ?: return@mapNotNull null
					OcrLine(
						line.text,
						box.left.toFloat() / job.width,
						(job.regionTop + box.top.toFloat() / job.height * job.regionH) / job.frameH,
						box.right.toFloat() / job.width,
						(job.regionTop + box.bottom.toFloat() / job.height * job.regionH) / job.frameH
					)
				}
			}
			onCandidate(SubtitleFilter.pickSubtitle(lines), job)
		}
		catch(e: CancellationException)
		{
			throw e
		}
		catch(e: Exception)
		{
			Log.w(TAG, "OCR failed", e)
			lastError = "${e.javaClass.simpleName}: ${e.message}"
		}
		finally
		{
			ocrBusy.set(false)
		}
	}

	private fun onCandidate(candidate: SubtitleCandidate?, job: OcrJob)
	{
		lastOcr = candidate?.text ?: ""
		// Gösterilen altyazının kutusundaki görüntü belirgin şekilde değiştiyse boş OCR'a hemen güven.
		val shownSig = shownSignature
		val box = shownBox
		val regionChanged = shownSig == null || box == null ||
				detector.changedFraction(job.signature, shownSig, box[0], box[1], box[2], box[3]) >= HIDE_BOX_CHANGE
		when(val action = tracker.onFrame(candidate, regionChanged))
		{
			is SubtitleTracker.Action.Show ->
			{
				val c = action.candidate
				rememberShown(c, job)
				val gen = ++captionGen
				// Tamamen yeni cümle ve çevirisi hazır değil: eski Türkçe yeni İngilizcenin üstünde yanlış
				// kalmasın. Daktilo büyümesinde (grown) eski çeviri yenisi gelene kadar kalır (titreme olmaz).
				if(!action.grown && cache.get(SubtitleFilter.normalize(c.text)) == null)
				{
					captionView?.visibility = View.GONE
					placedBox = null
				}
				scope.launch {
					val turkish = try
					{
						translate(c.text)
					}
					catch(e: CancellationException)
					{
						throw e
					}
					catch(e: Exception)
					{
						lastError = "Çeviri: ${e.message}"
						// Eski (artık yanlış) Türkçe ekranda kalmasın; bir sonraki değişimde yeniden denenir.
						if(gen == captionGen)
						{
							tracker.reset()
							shownSignature = null
							shownBox = null
							captionView?.visibility = View.GONE
						}
						return@launch
					}
					// Bu arada yeni altyazı geldi ya da gizlendi: eski çeviriyi gösterme.
					if(gen != captionGen)
						return@launch
					showCaption(turkish, c)
					e2eMs = SystemClock.uptimeMillis() - job.frameAt
				}
			}
			is SubtitleTracker.Action.Keep ->
			{
				rememberShown(action.candidate, job)
				if(captionView?.visibility == View.VISIBLE)
					placeCaption(action.candidate)
			}
			SubtitleTracker.Action.Hide ->
			{
				captionGen++
				shownSignature = null
				shownBox = null
				placedBox = null
				captionView?.visibility = View.GONE
				e2eMs = SystemClock.uptimeMillis() - job.frameAt
			}
			SubtitleTracker.Action.None -> {}
		}
	}

	private fun rememberShown(c: SubtitleCandidate, job: OcrJob)
	{
		shownSignature = job.signature
		shownBox = floatArrayOf(c.left, job.toRegionY(c.top), c.right, job.toRegionY(c.bottom))
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

	/** Gerçek ekran boyutu (HDMI görüntüsü tam ekran: kare içindeki oran × bu boyut = ekrandaki yer). */
	@Suppress("DEPRECATION")
	private fun readScreenSize()
	{
		val dm = DisplayMetrics()
		try
		{
			windowManager.defaultDisplay.getRealMetrics(dm)
		}
		catch(e: Exception)
		{
			dm.setTo(resources.displayMetrics)
		}
		screenW = if(dm.widthPixels > 0) dm.widthPixels else resources.displayMetrics.widthPixels
		screenH = if(dm.heightPixels > 0) dm.heightPixels else resources.displayMetrics.heightPixels
	}

	@SuppressLint("SetTextI18n")
	private fun addOverlays()
	{
		readScreenSize()
		val style = CaptionStyle.load(this)
		captionPosition = style.position
		bandMaxWidthFraction = style.maxWidthFraction
		val caption = TextView(this).apply {
			style.applyTo(this)
			maxWidth = (screenW * style.maxWidthFraction).roundToInt()
			visibility = View.GONE
		}
		baseTextPx = caption.textSize
		val status = TextView(this).apply {
			setTextColor(Color.rgb(255, 235, 59))
			setBackgroundColor(Color.argb(180, 0, 0, 0))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
			setPadding(dp(8), dp(4), dp(8), dp(4))
			maxWidth = (screenW * 0.5f).roundToInt()
			visibility = View.GONE
		}
		val captionGravity = if(captionPosition == CaptionStyle.POSITION_TOP) Gravity.TOP or Gravity.CENTER_HORIZONTAL
			else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
		try
		{
			windowManager.addView(caption, overlayParams(captionGravity).also { it.y = dp(BAND_MARGIN_DP) })
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
		placedBox = null
		placeCaption(candidate)
	}

	/**
	 * "cover": kutu İngilizce bloğun tamamını örter — en az onun genişliği/yüksekliği (+ küçük pay) kadar,
	 * yatayda aynı merkezde, alt kenarı İngilizcenin alt kenarının hemen altında (Türkçe uzunsa yukarı büyür).
	 * "bottom"/"top": ortalanmış sabit bant, kenardan 48dp, genişlik en fazla maxWidthFraction.
	 */
	private fun placeCaption(c: SubtitleCandidate)
	{
		val v = captionView ?: return
		val params = v.layoutParams as? WindowManager.LayoutParams ?: return
		val last = placedBox
		if(last != null && abs(last.left - c.left) < MOVE_EPS && abs(last.right - c.right) < MOVE_EPS &&
				abs(last.top - c.top) < MOVE_EPS && abs(last.bottom - c.bottom) < MOVE_EPS)
			return
		placedBox = c

		if(captionPosition == CaptionStyle.POSITION_COVER)
		{
			val slack = dp(COVER_SLACK_DP)
			val engW = ((c.right - c.left) * screenW).roundToInt()
			val engH = ((c.bottom - c.top) * screenH).roundToInt()
			val minW = min(screenW, engW + slack * 2)
			val minH = engH + slack * 2
			val maxW = max(minW, (screenW * COVER_MAX_WIDTH).roundToInt())
			v.minWidth = minW
			v.minHeight = minH
			v.maxWidth = maxW
			fitText(v, maxW, max(minH, (screenH * COVER_MAX_HEIGHT).roundToInt()))
			val half = v.measuredWidth / 2f
			val centerX = ((c.left + c.right) / 2f * screenW).coerceIn(half, max(half, screenW - half))
			params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
			params.x = (centerX - screenW / 2f).roundToInt()
			params.y = max(0, (screenH * (1f - c.bottom)).roundToInt() - slack)
		}
		else
		{
			val maxW = (screenW * bandMaxWidthFraction).roundToInt()
			v.minWidth = 0
			v.minHeight = 0
			v.maxWidth = maxW
			fitText(v, maxW, (screenH * COVER_MAX_HEIGHT).roundToInt())
			params.gravity = if(captionPosition == CaptionStyle.POSITION_TOP) Gravity.TOP or Gravity.CENTER_HORIZONTAL
				else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
			params.x = 0
			params.y = dp(BAND_MARGIN_DP)
		}
		try { windowManager.updateViewLayout(v, params) } catch(_: Exception) {}
	}

	/** Türkçe sığmazsa (en fazla [MAX_LINES] satır / [maxH] piksel) yazıyı adım adım %60'a kadar küçültür. */
	private fun fitText(v: TextView, maxW: Int, maxH: Int)
	{
		if(baseTextPx <= 0f)
			return
		var size = baseTextPx
		v.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
		val wSpec = View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST)
		val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
		v.measure(wSpec, hSpec)
		while((v.lineCount > MAX_LINES || v.measuredHeight > maxH) && size > baseTextPx * MIN_SHRINK)
		{
			size *= 0.9f
			v.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
			v.measure(wSpec, hSpec)
		}
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
		"bağlı=$connected model=${if(modelReady) "hazır" else "yok"} kare=$frames örnek=$sampled ocr=$ocrRuns" +
				"\nOCR=${ocrMs} ms  uçtan uca=${e2eMs} ms" +
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
