// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
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
import com.metallic.chiaki.billing.BillingRepository
import com.metallic.chiaki.billing.ProStore
import com.metallic.chiaki.billing.QuotaNotice
import com.metallic.chiaki.common.LogManager
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.companion.CompanionServer
import com.metallic.chiaki.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File
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
		/**
		 * Kesilmiş bölge son OCR'dakiyle aynıysa (aynı kutu, imza değişmemiş) OCR tekrarlanmaz; yine de en geç
		 * bu süre sonra bir kez yeniden okunur.
		 */
		private const val SAME_CROP_RECHECK_MS = 3000L
		/** Bantta hiç yazı izi yokken OCR atlanır; ama her bu kadar ardışık "boş" kararda bir tüm bant okunur. */
		private const val EMPTY_VERIFY_EVERY = 4
		/** Daktilo büyümesinde Gemini'ye her harfte gitmemek için bekleme (ms). */
		private const val GROWN_GEMINI_DEBOUNCE_MS = 250L
		private const val CACHE_FILE = "translation_cache.json"
		private const val CACHE_SAVE_MS = 30_000L
		private const val MAX_CONTEXT_PAIRS = 4
		/** Gösterilen altyazının kutusundaki hücrelerin bu oranı değiştiyse boş OCR'da hemen gizle. */
		private const val HIDE_BOX_CHANGE = 0.2f
		private const val MOVE_EPS = 0.01f
		private const val COVER_SLACK_DP = 8
		private const val COVER_MAX_WIDTH = 0.92f
		private const val COVER_MAX_HEIGHT = 0.3f
		private const val BAND_MARGIN_DP = 48
		private const val MAX_LINES = 3
		private const val MIN_SHRINK = 0.6f
		private const val RESTYLE_DEBOUNCE_MS = 120L
		private const val PREVIEW_MS = 4000L
		/** Örnek cümlenin "cover" modunda yerleşeceği varsayılan kutu (karenin alt bölümü). */
		private val PREVIEW_BOX = SubtitleCandidate("", 0.2f, 0.82f, 0.8f, 0.9f)

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

	/** OCR'a verilecek iş: altyazı bölgesinden kesilen yazı kutusunun gri NV21 hâli + kareye göre konumu. */
	private class OcrJob(
		val nv21: ByteArray,
		/** Bant içindeki kesim ve OCR görüntüsünün boyutu; null = OCR yok (bantta yazı izi bulunmadı). */
		val crop: OcrCrop?,
		/** OCR'a giden karedeki bölgenin değişim imzası. */
		val signature: IntArray,
		val frameW: Int,
		val frameH: Int,
		/** Bölgenin kare içindeki üst satırı ve yüksekliği (piksel). */
		val regionTop: Int,
		val regionH: Int,
		/** Karenin ImageReader'dan alındığı an (uptime ms) — uçtan uca ölçüm için. */
		val frameAt: Long,
		/** Bu kesimi tüm bant okunarak "yazı yok" kararını doğrulamak için mi yapıldı? */
		val verifyEmpty: Boolean = false
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
	/** Motor başına çeviri önbelleği (bellek + diskte JSON). */
	private val cache = TranslationCache(2000)
	private val gemini = GeminiClient()
	private val geminiBackoff = GeminiBackoff()
	/** AltyazıTR Pro (abonelik) istemcisi: oturum jetonu uygulama genelinde paylaşılır. */
	private val pro by lazy { ProTranslator.get(this) }
	private val proBackoff = GeminiBackoff()
	/** Süren Pro isteği: yeni altyazı gelince iptal edilir (eski cümle için akış boşuna sürmesin). */
	private var proJob: Job? = null
	private val tracker = SubtitleTracker()
	private val refine = RefineState()
	/** Gemini'ye bağlam olarak gönderilen son altyazı çiftleri (ana iş parçacığı). */
	private val contextPairs = ArrayDeque<SubtitlePair>()
	private var cacheSaveJob: Job? = null

	// ---- çeviri ayarları (ana iş parçacığı; ayar değişince yeniden okunur)
	private var engine = TranslationSettings.ENGINE_MLKIT
	private var geminiKey = ""
	private var geminiModel = TranslationSettings.DEFAULT_MODEL
	private var draftFirst = TranslationSettings.DEFAULT_DRAFT_FIRST
	private var proSubscribed = false

	// ---- kare iş parçacığında kullanılanlar
	private val detector = FrameChangeDetector()
	/** Ayar değişince ana iş parçacığında yenisiyle değiştirilir (bkz. applyScanSettings). */
	@Volatile private lateinit var scheduler: OcrScheduler
	private var schedulerIntervalMs = -1L
	private val ocrBusy = AtomicBoolean(false)
	@Volatile private var processingEnabled = false
	@Volatile private var captureFraction = 0.35f
	private var lastSampleAt = 0L
	private var regionY = ByteArray(0)
	private var nv21 = ByteArray(0)
	private val signature = IntArray(detector.size)
	private val estimator = TextRegionEstimator()
	/** Son OCR'a giden kesim ve içeriğinin imzası: aynı içerik tekrar okunmasın. */
	private var lastCrop: OcrCrop? = null
	private var lastCropSig = IntArray(detector.size)
	private var cropSig = IntArray(detector.size)
	private var lastRealOcrAt = 0L
	private var emptyStreak = 0
	/** "Yazı izi yok → OCR atla" kararına güven. Tüm bant okunup yine de altyazı bulunursa kapanır. */
	@Volatile private var trustEmptyRegion = true

	// ---- ana iş parçacığında kullanılanlar
	/** Gösterilen altyazının bölge imzası ve kutusu (bölgeye göre 0..1): kaybolduğunu hızlı anlamak için. */
	private var shownSignature: IntArray? = null
	private var shownBox: FloatArray? = null
	private var placedBox: SubtitleCandidate? = null
	/** Ekrandaki (veya çevirisi beklenen) altyazının son bilinen kutusu: geç gelen çeviri buraya yerleşir. */
	private var currentCandidate: SubtitleCandidate? = null

	private lateinit var windowManager: WindowManager
	private var captionView: TextView? = null
	private var statusView: TextView? = null
	/** Sol üstteki sarı geliştirici bilgisi: Ayarlar'dan açılır, varsayılan kapalı. */
	private val showDebug by lazy { Preferences(this).subtitleDebugInfo }
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
	private var e2eFinalMs = 0L
	@Volatile private var cropMs = 0L
	@Volatile private var cropInfo = ""
	@Volatile private var ocrSkippedSame = 0
	@Volatile private var ocrSkippedEmpty = 0
	private var mlkitMs = 0L
	private var geminiMs = 0L
	private var proMs = 0L
	/** Pro akışında ilk parçanın gelme süresi. */
	private var proFirstMs = 0L
	private var shownEngine = ""
	private var geminiNote = ""
	private var lastOcr = ""
	@Volatile private var lastError = ""

	private lateinit var prefs: Preferences
	private lateinit var proStore: ProStore

	// ---- telefon kumandası / ayarlar ekranı: görünüm değişince canlı uygula (ana iş parçacığı)
	private var styleKeys: Set<String> = emptySet()
	private var scanKeys: Set<String> = emptySet()
	private var translationKeys: Set<String> = emptySet()
	private var prefsListenerRegistered = false
	private var companionHeld = false
	/** Ekranda gerçek altyazı yokken stil değişince kısa süre örnek cümle gösterilir. */
	private var previewing = false
	private var previewJob: Job? = null
	private var restyleJob: Job? = null
	// SharedPreferences dinleyicileri zayıf referansla tutar; alan olarak saklanmalı.
	private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		if(key == null || key in styleKeys)
		{
			// Telefondaki kaydırıcılar art arda yazabilir: kısa gecikmeyle tek seferde uygula.
			restyleJob?.cancel()
			restyleJob = scope.launch { delay(RESTYLE_DEBOUNCE_MS); restyle() }
		}
		if(key == null || key in scanKeys)
			applyScanSettings()
		if(key == null || key in translationKeys)
			applyTranslationSettings()
	}
	/** Abonelik başlayınca/bitince (BillingRepository → ProStore) motor yeniden seçilir. */
	private val proListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		if(key == null || key == ProStore.KEY_TOKEN)
			applyTranslationSettings()
	}

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onCreate()
	{
		super.onCreate()
		windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
		prefs = Preferences(this)
		proStore = ProStore(this)
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
		schedulerIntervalMs = interval
		scheduler = newScheduler(interval)
		applyTranslationSettings()
		// Abonelik durumunu Google Play'den tazele (sonuç ProStore'a yazılır, proListener motoru günceller).
		BillingRepository.get(this).start()
		loadCache()
		addOverlays()
		status(getString(R.string.caption_status_connecting))
		startSession(connectInfo)
		loopJob = scope.launch { captionLoop() }
		registerPrefsListener()
		if(!companionHeld)
		{
			companionHeld = true
			CompanionServer.acquire(this, CompanionServer.HOLDER_CAPTION)
		}
		return START_NOT_STICKY
	}

	override fun onDestroy()
	{
		isRunning = false
		processingEnabled = false
		if(prefsListenerRegistered)
		{
			prefs.sharedPreferences.unregisterOnSharedPreferenceChangeListener(prefsListener)
			proStore.sharedPreferences.unregisterOnSharedPreferenceChangeListener(proListener)
			prefsListenerRegistered = false
		}
		if(companionHeld)
		{
			companionHeld = false
			CompanionServer.release(CompanionServer.HOLDER_CAPTION)
		}
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
		if(cache.dirty)
		{
			val file = cacheFile()
			Thread { cache.saveTo(file) }.start()
		}
		recognizer.close()
		translator.close()
		removeOverlays()
		super.onDestroy()
	}

	// ---------------------------------------------------------------- canlı ayar değişiklikleri

	private fun registerPrefsListener()
	{
		if(prefsListenerRegistered)
			return
		styleKeys = prefs.subtitleCaptionStyleKeys
		scanKeys = setOf(getString(R.string.preferences_subtitle_area_key), getString(R.string.preferences_subtitle_speed_key))
		translationKeys = prefs.translationKeys
		prefs.sharedPreferences.registerOnSharedPreferenceChangeListener(prefsListener)
		proStore.sharedPreferences.registerOnSharedPreferenceChangeListener(proListener)
		prefsListenerRegistered = true
	}

	/** Taranan alan ve tarama aralığı: sonraki karelerde geçerli olur (kare iş parçacığı volatile alanları okur). */
	private fun applyScanSettings()
	{
		captureFraction = (prefs.subtitleCapturePercent / 100f).coerceIn(0.1f, 1f)
		val interval = prefs.subtitleIntervalMs.toLong()
		if(interval != schedulerIntervalMs)
		{
			schedulerIntervalMs = interval
			scheduler = newScheduler(interval)
		}
	}

	/**
	 * Değişim görülünce OCR hemen başlar (en fazla 50 ms boşluk; OCR sürerken zaten yenisi başlamaz).
	 * Ayardaki aralık artık yalnızca hiçbir değişiklik yokken yapılan yedek okumanın sıklığını belirler.
	 */
	private fun newScheduler(interval: Long) =
		OcrScheduler(detector, fallbackMs = max(1000L, interval * 3), minGapMs = min(50L, interval / 3))

	private fun applyTranslationSettings()
	{
		val before = Triple(engine, geminiKey, proSubscribed)
		proSubscribed = prefs.proSubscribed
		engine = prefs.translationEngine
		geminiKey = prefs.geminiApiKey
		geminiModel = prefs.geminiModel
		draftFirst = prefs.translationDraftFirst
		if(before != Triple(engine, geminiKey, proSubscribed))
		{
			geminiBackoff.onSuccess()
			proBackoff.onSuccess()
			geminiNote = ""
		}
		// Model inmemiş olsa da Gemini/Pro ile çalışılabilir.
		if(TranslationSettings.isRemote(engine))
			processingEnabled = processingEnabled || isRunning
		if(engine == TranslationSettings.ENGINE_GEMINI)
		{
			val key = geminiKey
			val model = geminiModel
			scope.launch(Dispatchers.IO) { gemini.warmUp(key, model) }
		}
		else if(engine == TranslationSettings.ENGINE_PRO)
		{
			val client = pro
			scope.launch(Dispatchers.IO) { client.warmUp() }
		}
	}

	private fun cacheFile() = File(filesDir, CACHE_FILE)

	private fun loadCache()
	{
		scope.launch(Dispatchers.IO) { cache.loadFrom(cacheFile()) }
	}

	/** Önbellek değiştiyse en geç [CACHE_SAVE_MS] sonra diske yazılır. */
	private fun scheduleCacheSave()
	{
		if(cacheSaveJob?.isActive == true)
			return
		cacheSaveJob = scope.launch {
			delay(CACHE_SAVE_MS)
			withContext(Dispatchers.IO) { if(cache.dirty) cache.saveTo(cacheFile()) }
		}
	}

	/** Ana iş parçacığı: görünüm ayarlarını yeniden yükleyip ekrandaki altyazıya uygular. */
	private fun restyle()
	{
		val v = captionView ?: return
		val style = CaptionStyle.load(this)
		captionPosition = style.position
		bandMaxWidthFraction = style.maxWidthFraction
		style.applyTo(v)
		baseTextPx = v.textSize
		val last = placedBox
		// placeCaption aynı kutuyu atlar; yeni görünümle yeniden ölçülsün.
		placedBox = null
		if(v.visibility == View.VISIBLE && !previewing)
		{
			// Gerçek altyazı ekranda: yalnızca yeniden yerleştir.
			if(last != null)
				placeCaption(last)
			return
		}
		// Ekranda altyazı yok: yeni görünüm birkaç saniye örnek cümleyle gösterilir.
		previewing = true
		v.text = getString(R.string.caption_preview_sample)
		v.visibility = View.VISIBLE
		placeCaption(last ?: PREVIEW_BOX)
		previewJob?.cancel()
		previewJob = scope.launch {
			delay(PREVIEW_MS)
			if(previewing)
			{
				previewing = false
				placedBox = null
				captionView?.visibility = View.GONE
			}
		}
	}

	private fun endPreview()
	{
		if(!previewing)
			return
		previewing = false
		previewJob?.cancel()
		previewJob = null
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
				status(if(modelReady || TranslationSettings.isRemote(engine)) null else getString(R.string.subtitle_model_downloading))
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
				// Gemini/Pro ile cihaz içi model olmadan da çalışılır; model arka planda yeniden denenir.
				if(TranslationSettings.isRemote(engine))
					processingEnabled = true
				else
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

		// 1) Yazı kutusu: tüm bant yerine yalnızca yazı izi olan bölge (+ pay) okunur.
		val cropStart = SystemClock.uptimeMillis()
		val region = estimator.estimate(regionY, 0, frameW, frameW, regionH)
		var verifyEmpty = false
		if(region == null)
		{
			if(trustEmptyRegion && emptyStreak < EMPTY_VERIFY_EVERY)
			{
				// Bantta yazı izi yok: OCR'sız "altyazı yok" sonucu (gizleme kararı yine çalışır).
				emptyStreak++
				ocrSkippedEmpty++
				lastCrop = null
				cropMs = SystemClock.uptimeMillis() - cropStart
				cropInfo = "yazı yok"
				val job = OcrJob(nv21, null, signature.copyOf(), frameW, frameH, regionTop, regionH, now)
				ocrBusy.set(true)
				scope.launch { runOcr(job) }.invokeOnCompletion { ocrBusy.set(false) }
				return
			}
			// Arada bir tüm bant okunur: tahminci bu oyunda yazıyı kaçırıyor olabilir.
			verifyEmpty = true
			emptyStreak = 0
		}
		else
			emptyStreak = 0
		val crop = estimator.toCrop(region, frameW, regionH, OCR_MAX_WIDTH)

		// 2) Aynı kutu ve içeriği değişmemişse (ör. yalnızca arka plan oynadı) OCR tekrarlanmaz.
		val cropOffset = crop.srcTop * frameW + crop.srcLeft
		detector.computeSignature(regionY, crop.srcWidth, crop.srcHeight, frameW, cropOffset, cropSig)
		val prev = lastCrop
		if(!verifyEmpty && prev != null && abs(prev.srcLeft - crop.srcLeft) <= 8 && abs(prev.srcTop - crop.srcTop) <= 8 &&
				abs(prev.srcWidth - crop.srcWidth) <= 16 && abs(prev.srcHeight - crop.srcHeight) <= 16 &&
				!detector.isChanged(cropSig, lastCropSig) && now - lastRealOcrAt < SAME_CROP_RECHECK_MS)
		{
			ocrSkippedSame++
			return
		}
		lastCrop = crop
		val tmp = lastCropSig
		lastCropSig = cropSig
		cropSig = tmp
		lastRealOcrAt = now

		// 3) Gri NV21 (gerekirse küçültülmüş; satır yüksekliği ~32 px) + ucuz karşıtlık germe.
		val size = LumaCrop.nv21Size(crop.dstWidth, crop.dstHeight)
		// Tek OCR sürdüğü için (ocrBusy) bu tampon güvenle yeniden kullanılır.
		if(nv21.size != size)
			nv21 = ByteArray(size)
		LumaCrop.toGrayNv21(regionY, cropOffset, frameW, crop.srcWidth, crop.srcHeight, crop.dstWidth, crop.dstHeight, nv21)
		LumaCrop.stretchContrast(nv21, crop.dstWidth * crop.dstHeight)
		cropMs = SystemClock.uptimeMillis() - cropStart
		cropInfo = "${crop.dstWidth}x${crop.dstHeight}" + (if(crop.scale < 0.99f) " ×%.2f".format(crop.scale) else "") +
				(if((region?.lineHeight ?: 0) > 0) " satır=${region?.lineHeight}px" else "") + (if(verifyEmpty) " (tam bant)" else "")
		val job = OcrJob(nv21, crop, signature.copyOf(), frameW, frameH, regionTop, regionH, now, verifyEmpty)
		ocrBusy.set(true)
		scope.launch { runOcr(job) }.invokeOnCompletion { ocrBusy.set(false) }
	}

	/** Ana iş parçacığı. */
	private suspend fun runOcr(job: OcrJob)
	{
		val crop = job.crop
		if(crop == null)
		{
			try { onCandidate(null, job) } finally { ocrBusy.set(false) }
			return
		}
		val started = SystemClock.uptimeMillis()
		try
		{
			val input = InputImage.fromByteArray(job.nv21, crop.dstWidth, crop.dstHeight, 0, InputImage.IMAGE_FORMAT_NV21)
			val result = recognizer.process(input).await()
			ocrMs = SystemClock.uptimeMillis() - started
			ocrRuns++
			// Kutu koordinatları kesilmiş (ve küçültülmüş olabilen) görüntüye göre; TAM kareye göre 0..1'e çevrilir.
			val lines = result.textBlocks.flatMap { block ->
				block.lines.mapNotNull { line ->
					val box = line.boundingBox ?: return@mapNotNull null
					OcrLine(
						line.text,
						crop.toFrameX(box.left.toFloat(), job.frameW),
						crop.toFrameY(box.top.toFloat(), job.regionTop, job.frameH),
						crop.toFrameX(box.right.toFloat(), job.frameW),
						crop.toFrameY(box.bottom.toFloat(), job.regionTop, job.frameH)
					)
				}
			}
			val candidate = SubtitleFilter.pickSubtitle(lines)
			// Tüm bant okununca altyazı çıktıysa "yazı izi yok" kararına artık güvenme.
			if(job.verifyEmpty && candidate != null)
				trustEmptyRegion = false
			onCandidate(candidate, job)
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
		if(job.crop != null || candidate != null)
			lastOcr = candidate?.text ?: ""
		// Gösterilen altyazının kutusundaki görüntü belirgin şekilde değiştiyse boş OCR'a hemen güven.
		val shownSig = shownSignature
		val box = shownBox
		val regionChanged = shownSig == null || box == null ||
				detector.changedFraction(job.signature, shownSig, box[0], box[1], box[2], box[3]) >= HIDE_BOX_CHANGE
		val action = tracker.onFrame(candidate, regionChanged)
		// Görünüm önizlemesi (örnek cümle) gerçek altyazı gelince ya da giderken hemen kalkar.
		if(previewing && (action is SubtitleTracker.Action.Show || action == SubtitleTracker.Action.Hide))
		{
			endPreview()
			captionView?.visibility = View.GONE
			placedBox = null
		}
		when(action)
		{
			is SubtitleTracker.Action.Show ->
			{
				rememberShown(action.candidate, job)
				startTranslation(action.candidate, action.grown, job.frameAt)
			}
			is SubtitleTracker.Action.Keep ->
			{
				rememberShown(action.candidate, job)
				currentCandidate = action.candidate
				if(captionView?.visibility == View.VISIBLE && !previewing)
					placeCaption(action.candidate)
			}
			SubtitleTracker.Action.Hide ->
			{
				refine.newGeneration()
				currentCandidate = null
				shownSignature = null
				shownBox = null
				placedBox = null
				captionView?.visibility = View.GONE
				e2eMs = SystemClock.uptimeMillis() - job.frameAt
			}
			SubtitleTracker.Action.None -> {}
		}
	}

	// ---------------------------------------------------------------- çeviri: önce hızlı, sonra Gemini/Pro

	/** Sunucuya giden motor (Gemini anahtarlı veya Pro); yoksa null = yalnızca cihaz içi. */
	private fun remoteEngine(): String? = when(engine)
	{
		TranslationSettings.ENGINE_GEMINI -> if(geminiKey.isNotEmpty()) TranslationSettings.ENGINE_GEMINI else null
		TranslationSettings.ENGINE_PRO -> TranslationSettings.ENGINE_PRO
		else -> null
	}

	/**
	 * Ana iş parçacığı. Cihaz içi (ML Kit) çeviri hemen gösterilir; Gemini/Pro seçiliyse onun sonucu gelince
	 * (altyazı hâlâ aynıysa) yerine yazılır. "Önce hızlı çeviri" kapalıysa yalnızca Gemini/Pro sonucu gösterilir
	 * (Pro'da akış sırasında Türkçe yazıldıkça görünür). Hata olursa cihaz içi çeviriye dönülür.
	 */
	private fun startTranslation(c: SubtitleCandidate, grown: Boolean, frameAt: Long)
	{
		currentCandidate = c
		val gen = refine.newGeneration()
		val remote = remoteEngine()
		val useRemote = remote != null
		if(remote != null)
		{
			cache.get(remote, c.text)?.let {
				val source = if(remote == TranslationSettings.ENGINE_PRO) RefineState.Source.CACHE_PRO else RefineState.Source.CACHE_GEMINI
				display(gen, it, source, frameAt, c.text)
				return
			}
		}
		val draft = cache.get(TranslationSettings.ENGINE_MLKIT, c.text)
		val wantDraft = !useRemote || draftFirst
		if(draft != null && wantDraft)
			display(gen, draft, RefineState.Source.CACHE_MLKIT, frameAt, c.text)
		else if(!grown)
		{
			// Tamamen yeni cümle ve çevirisi hazır değil: eski Türkçe yeni İngilizcenin üstünde yanlış
			// kalmasın. Daktilo büyümesinde (grown) eski çeviri yenisi gelene kadar kalır (titreme olmaz).
			captionView?.visibility = View.GONE
			placedBox = null
		}
		if(draft == null && wantDraft && modelReady)
			launchMlKit(gen, c.text, frameAt, isFallback = false)
		if(!useRemote)
		{
			if(!modelReady)
				lastError = "Çeviri modeli hazır değil"
			return
		}
		if(remote == TranslationSettings.ENGINE_PRO)
			launchPro(gen, c.text, grown, frameAt)
		else
			launchGemini(gen, c.text, grown, frameAt)
	}

	private fun launchMlKit(gen: Int, english: String, frameAt: Long, isFallback: Boolean)
	{
		scope.launch {
			val started = SystemClock.uptimeMillis()
			val turkish = try
			{
				translator.translate(english).await()
			}
			catch(e: CancellationException)
			{
				throw e
			}
			catch(e: Exception)
			{
				lastError = "Çeviri: ${e.message}"
				// Gemini/Pro isteği hâlâ yoldaysa taslağın hatası ekranı temizlemesin; sonucu o belirler.
				if(isFallback || !TranslationSettings.isRemote(engine))
					onTranslationFailed(gen)
				return@launch
			}
			mlkitMs = SystemClock.uptimeMillis() - started
			cache.put(TranslationSettings.ENGINE_MLKIT, english, turkish)
			if(display(gen, turkish, RefineState.Source.MLKIT, frameAt, english) && isFallback)
				shownEngine = if(engine == TranslationSettings.ENGINE_PRO) "cihaz içi (abonelik yerine)" else "cihaz içi (Gemini yerine)"
		}
	}

	private fun launchGemini(gen: Int, english: String, grown: Boolean, frameAt: Long)
	{
		scope.launch {
			if(grown)
			{
				// Daktilo efekti: harf harf büyüyen satır için her adımda istek atma.
				delay(GROWN_GEMINI_DEBOUNCE_MS)
				if(gen != refine.gen)
					return@launch
			}
			val now = SystemClock.uptimeMillis()
			if(!geminiBackoff.canTry(now))
			{
				geminiNote = "Gemini beklemede (${(geminiBackoff.remainingMs(now) + 999) / 1000} sn)"
				fallbackToMlKit(gen, english, frameAt)
				return@launch
			}
			val key = geminiKey
			val model = geminiModel
			val context = contextPairs.toList()
			val started = SystemClock.uptimeMillis()
			val turkish = try
			{
				withContext(Dispatchers.IO) { gemini.translate(key, model, english, context) }
			}
			catch(e: CancellationException)
			{
				throw e
			}
			catch(e: Exception)
			{
				geminiMs = SystemClock.uptimeMillis() - started
				geminiBackoff.onFailure(SystemClock.uptimeMillis(), (e as? GeminiClient.GeminiException)?.httpCode ?: 0)
				geminiNote = "Gemini hata: ${e.message?.take(80)} → cihaz içi"
				Log.w(TAG, "Gemini failed: ${e.message}")
				fallbackToMlKit(gen, english, frameAt)
				return@launch
			}
			geminiMs = SystemClock.uptimeMillis() - started
			geminiBackoff.onSuccess()
			geminiNote = ""
			cache.put(TranslationSettings.ENGINE_GEMINI, english, turkish)
			scheduleCacheSave()
			display(gen, turkish, RefineState.Source.GEMINI, frameAt, english)
		}
	}

	/**
	 * AltyazıTR Pro: Türkçe akış hâlinde gelir; kısmi metin ([RefineState.Source.PRO_PARTIAL]) ekranda henüz
	 * çeviri yokken gösterilir ve parça geldikçe güncellenir, sonra tam metin yerine yazılır.
	 * Hata/zaman aşımında yarım metin bırakılmaz, cihaz içi çeviriye dönülür.
	 */
	private fun launchPro(gen: Int, english: String, grown: Boolean, frameAt: Long)
	{
		proJob?.cancel()
		proJob = scope.launch {
			if(grown)
			{
				// Daktilo efekti: harf harf büyüyen satır için her adımda istek atma.
				delay(GROWN_GEMINI_DEBOUNCE_MS)
				if(gen != refine.gen)
					return@launch
			}
			// Paketin bu dönemki hakkı doldu: dönem sonuna kadar sunucuya gitmeden cihaz içi çeviri.
			if(pro.isQuotaExhausted())
			{
				geminiNote = "Abonelik: bu dönemin satır hakkı doldu → cihaz içi"
				maybeShowQuotaNotice()
				fallbackToMlKit(gen, english, frameAt)
				return@launch
			}
			val now = SystemClock.uptimeMillis()
			if(!proBackoff.canTry(now))
			{
				geminiNote = "Pro beklemede (${(proBackoff.remainingMs(now) + 999) / 1000} sn)" +
						(if(lastError.startsWith("Pro: ")) " — " + lastError.removePrefix("Pro: ") else "")
				fallbackToMlKit(gen, english, frameAt)
				return@launch
			}
			val context = contextPairs.toList()
			val started = SystemClock.uptimeMillis()
			var gotFirst = false
			val turkish = try
			{
				pro.translate(english, context) { partial ->
					if(!gotFirst)
					{
						gotFirst = true
						proFirstMs = SystemClock.uptimeMillis() - started
					}
					display(gen, partial, RefineState.Source.PRO_PARTIAL, frameAt, english)
				}
			}
			catch(e: CancellationException)
			{
				throw e
			}
			catch(e: Exception)
			{
				proMs = SystemClock.uptimeMillis() - started
				val error = (e as? ProTranslator.ProException)?.error
				proBackoff.onFailure(SystemClock.uptimeMillis(), error?.let { ProApi.backoffCode(it) } ?: 0)
				geminiNote = "Pro hata: ${e.message?.take(80)} → cihaz içi"
				lastError = "Pro: ${e.message?.take(100)}"
				Log.w(TAG, "Pro failed: ${error?.code} ${e.message}")
				// Sunucu aboneliği etkin görmüyorsa Google Play'deki durumu tazele (bitti/iptal olduysa motor değişir).
				if(error?.code == ProApi.CODE_NOT_SUBSCRIBED || error?.code == ProApi.CODE_INVALID_PURCHASE)
					BillingRepository.get(this@CaptionService).refreshPurchases()
				if(error?.code == ProApi.CODE_QUOTA_EXCEEDED)
					maybeShowQuotaNotice()
				refine.dropPartial(gen)
				fallbackToMlKit(gen, english, frameAt)
				return@launch
			}
			proMs = SystemClock.uptimeMillis() - started
			proBackoff.onSuccess()
			geminiNote = ""
			cache.put(TranslationSettings.ENGINE_PRO, english, turkish)
			scheduleCacheSave()
			display(gen, turkish, RefineState.Source.PRO, frameAt, english)
		}
	}

	/** Gemini/Pro sonuç vermedi: ekranda hiç çeviri yoksa cihaz içi çeviri gösterilir. */
	/**
	 * Kota doldu: dönem başına bir kez, ne olduğunu ve (en üst pakette değilse) bir üst paketi söyleyen
	 * Türkçe bildirim. Sonraki satırlar sessizce cihaz içi çeviriyle sürer.
	 */
	private fun maybeShowQuotaNotice()
	{
		val q = proStore.quota ?: return
		if(!proStore.claimQuotaNotice(q.resetAt))
			return
		val plan = proStore.plan
		Toast.makeText(applicationContext, QuotaNotice.message(plan?.plan, plan?.basePlan, q.resetAt), Toast.LENGTH_LONG).show()
	}

	private fun fallbackToMlKit(gen: Int, english: String, frameAt: Long)
	{
		if(gen != refine.gen || refine.shown != null)
			return
		val cached = cache.get(TranslationSettings.ENGINE_MLKIT, english)
		when
		{
			cached != null -> display(gen, cached, RefineState.Source.CACHE_MLKIT, frameAt, english)
			// Taslak zaten yolda (önce hızlı çeviri açık): o gelince gösterilir.
			draftFirst && modelReady -> {}
			modelReady -> launchMlKit(gen, english, frameAt, isFallback = true)
			else -> onTranslationFailed(gen)
		}
	}

	private fun onTranslationFailed(gen: Int)
	{
		// Eski (artık yanlış) Türkçe ekranda kalmasın; bir sonraki değişimde yeniden denenir.
		if(gen == refine.gen && refine.shown == null)
		{
			tracker.reset()
			shownSignature = null
			shownBox = null
			captionView?.visibility = View.GONE
		}
	}

	/** Sonuç hâlâ güncel altyazıya aitse gösterir; true = gösterildi. */
	private fun display(gen: Int, turkish: String, source: RefineState.Source, frameAt: Long, english: String): Boolean
	{
		val c = currentCandidate ?: return false
		val wasEmpty = refine.shown == null
		if(!refine.offer(gen, source))
			return false
		showCaption(turkish, c)
		val elapsed = SystemClock.uptimeMillis() - frameAt
		if(wasEmpty)
			e2eMs = elapsed
		e2eFinalMs = elapsed
		shownEngine = when(source)
		{
			RefineState.Source.MLKIT -> "cihaz içi"
			RefineState.Source.GEMINI -> "Gemini"
			RefineState.Source.CACHE_GEMINI -> "Gemini (önbellek)"
			RefineState.Source.CACHE_MLKIT -> "cihaz içi (önbellek)"
			RefineState.Source.PRO -> "Pro"
			RefineState.Source.CACHE_PRO -> "Pro (önbellek)"
			RefineState.Source.PRO_PARTIAL -> "Pro (akış)"
		}
		// Bağlama yalnızca son çeviri girer (Pro'nun yarım metni veya Gemini/Pro yerine taslak girmez).
		val isFinalResult = refine.isFinal(source) || (!TranslationSettings.isRemote(engine) && source != RefineState.Source.PRO_PARTIAL)
		if(isFinalResult)
			rememberContext(english, turkish)
		return true
	}

	/** Gemini'ye bağlam: son [MAX_CONTEXT_PAIRS] altyazı. Daktilo büyümesi önceki kaydın yerine geçer. */
	private fun rememberContext(english: String, turkish: String)
	{
		val last = contextPairs.lastOrNull()
		if(last != null && (SubtitleFilter.isSameSubtitle(last.english, english) ||
				SubtitleFilter.normalize(english).startsWith(SubtitleFilter.normalize(last.english))))
			contextPairs.removeLast()
		contextPairs.addLast(SubtitlePair(english, turkish))
		while(contextPairs.size > MAX_CONTEXT_PAIRS)
			contextPairs.removeFirst()
	}

	private fun rememberShown(c: SubtitleCandidate, job: OcrJob)
	{
		shownSignature = job.signature
		shownBox = floatArrayOf(c.left, job.toRegionY(c.top), c.right, job.toRegionY(c.bottom))
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
		endPreview()
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
		"bağlı=$connected model=${if(modelReady) "hazır" else "yok"} motor=$engine kare=$frames örnek=$sampled ocr=$ocrRuns" +
				" atlanan(aynı/boş)=$ocrSkippedSame/$ocrSkippedEmpty" +
				"\nkes=${cropMs} ms [$cropInfo]  OCR=${ocrMs} ms  mlkit=${mlkitMs} ms  gemini=${geminiMs} ms  pro=${proMs} ms (ilk parça ${proFirstMs} ms)" +
				"\nilk çeviri=${e2eMs} ms  son=${e2eFinalMs} ms  gösterilen: ${shownEngine.ifEmpty { "-" }}" +
				(if(geminiNote.isNotEmpty()) "\n$geminiNote" else "") +
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
