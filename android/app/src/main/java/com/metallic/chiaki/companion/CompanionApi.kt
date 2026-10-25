// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.companion

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.metallic.chiaki.BuildConfig
import com.metallic.chiaki.R
import com.metallic.chiaki.common.AppDatabase
import com.metallic.chiaki.common.DiscoveredDisplayHost
import com.metallic.chiaki.common.DisplayHost
import com.metallic.chiaki.common.MacAddress
import com.metallic.chiaki.common.ManualDisplayHost
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.RegisteredHost
import com.metallic.chiaki.common.getDatabase
import com.metallic.chiaki.discovery.DiscoveryManager
import com.metallic.chiaki.discovery.serverMac
import com.metallic.chiaki.lib.ChiakiLog
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.CreateError
import com.metallic.chiaki.lib.Regist
import com.metallic.chiaki.lib.RegistEvent
import com.metallic.chiaki.lib.RegistEventCanceled
import com.metallic.chiaki.lib.RegistEventFailed
import com.metallic.chiaki.lib.RegistEventSuccess
import com.metallic.chiaki.lib.RegistHost
import com.metallic.chiaki.lib.RegistInfo
import com.metallic.chiaki.lib.Target
import com.metallic.chiaki.main.HomeConsole
import com.metallic.chiaki.main.HomePrefs
import com.metallic.chiaki.subtitle.CaptionService
import com.metallic.chiaki.subtitle.TranslationSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * AltyazıTR telefon kumandası: API uç noktalarının Android tarafı.
 * Sunucunun bağlantı iş parçacıklarında çalışır; arayüz/servis işlemleri ana iş parçacığına aktarılır.
 */
class CompanionApi(context: Context, private val clientSeen: () -> Unit): CompanionBackend
{
	companion object
	{
		private const val TAG = "CompanionApi"
		private const val PAGE_ASSET = "companion/index.html"
		/** Son API isteğinden bu kadar sonra telefon için açılan ağ taraması durdurulur. */
		private const val DISCOVERY_IDLE_MS = 45_000L
		private const val DISCOVERY_WAIT_MS = 3_000L
		private const val REGIST_TIMEOUT_S = 45L
		private const val MAIN_TIMEOUT_S = 5L

		private const val CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
			"connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
	}

	private val context = context.applicationContext
	private val mainHandler = Handler(Looper.getMainLooper())
	private val database: AppDatabase = getDatabase(this.context)
	private val preferences = Preferences(this.context)
	private val homePrefs = HomePrefs(this.context)
	private val registRunning = AtomicBoolean(false)
	@Volatile private var disposed = false

	/** Yalnızca ana iş parçacığında kullanılır. */
	private var discoveryManager: DiscoveryManager? = null
	private val discoveryIdleRunnable = Runnable { discoveryManager?.active = false }

	private val pageBytes: ByteArray? by lazy {
		try
		{
			this.context.assets.open(PAGE_ASSET).use { it.readBytes() }
		}
		catch(e: Exception)
		{
			Log.e(TAG, "Companion page asset missing", e)
			null
		}
	}

	fun dispose()
	{
		disposed = true
		mainHandler.post {
			mainHandler.removeCallbacks(discoveryIdleRunnable)
			discoveryManager?.dispose()
			discoveryManager = null
		}
	}

	override fun onClientSeen() = clientSeen()

	// region Sayfa

	override fun page(): HttpResponse
	{
		val bytes = pageBytes ?: return HttpResponse.text(500, "Sayfa bulunamadı")
		return HttpResponse(200, HttpResponse.HTML, bytes, mapOf("Content-Security-Policy" to CSP))
	}

	// endregion

	// region Durum ve konsollar

	private fun ok(json: JSONObject): HttpResponse
	{
		json.put("ok", true)
		return HttpResponse.json(200, json.toString())
	}

	private fun fail(status: Int, message: String, extra: (JSONObject) -> Unit = {}): HttpResponse
	{
		val json = JSONObject()
		json.put("ok", false)
		json.put("error", message)
		extra(json)
		return HttpResponse.json(status, json.toString())
	}

	/** Telefon açıkken ağ taraması çalışsın (ana ekran kapalıyken de konsolun durumu görünsün). */
	private fun touchDiscovery()
	{
		mainHandler.post {
			if(disposed)
				return@post
			val manager = discoveryManager ?: DiscoveryManager().also { discoveryManager = it }
			if(!manager.active)
				manager.active = true
			mainHandler.removeCallbacks(discoveryIdleRunnable)
			mainHandler.postDelayed(discoveryIdleRunnable, DISCOVERY_IDLE_MS)
		}
	}

	private fun discoveredHosts() = runOnMain { discoveryManager?.discoveredHosts?.value } ?: emptyList()

	private fun loadConsoles(): List<HomeConsole>
	{
		val (manualHosts, registeredHosts) = runBlocking {
			database.manualHostDao().getAll().first() to database.registeredHostDao().getAll().first()
		}
		val macRegisteredHosts = registeredHosts.associateBy { it.serverMac }
		val idRegisteredHosts = registeredHosts.associateBy { it.id }
		val hosts: List<DisplayHost> = discoveredHosts().map {
			DiscoveredDisplayHost(it.serverMac?.let { mac -> macRegisteredHosts[mac] }, it)
		} + manualHosts.map {
			ManualDisplayHost(it.registeredHost?.let { id -> idRegisteredHosts[id] }, it)
		}
		return HomeConsole.merge(hosts)
	}

	/** MainActivity.onHostsChanged ile aynı seçim kuralı. */
	private fun selectedConsole(consoles: List<HomeConsole>): HomeConsole?
	{
		val rememberedKey = homePrefs.lastConsoleKey
		return consoles.firstOrNull { it.key == rememberedKey }
			?: homePrefs.lastConsoleHost?.let { host -> consoles.firstOrNull { it.host == host && it.isRegistered } }
			?: consoles.firstOrNull { it.isRegistered }
			?: consoles.firstOrNull()
	}

	private fun consoleJson(console: HomeConsole, selected: HomeConsole?) = JSONObject().apply {
		put("key", console.key)
		put("name", console.name ?: context.getString(R.string.home_console_unnamed))
		put("host", console.host)
		put("state", when(console.state)
		{
			HomeConsole.State.READY -> "ready"
			HomeConsole.State.STANDBY -> "standby"
			HomeConsole.State.UNKNOWN -> "unknown"
		})
		put("ps5", console.isPS5)
		put("registered", console.isRegistered)
		put("manual", console.isManual)
		put("selected", console.key == selected?.key)
	}

	private fun consolesArray(consoles: List<HomeConsole>, selected: HomeConsole?) =
		JSONArray().also { array -> consoles.forEach { array.put(consoleJson(it, selected)) } }

	override fun state(): HttpResponse
	{
		touchDiscovery()
		val consoles = loadConsoles()
		val selected = selectedConsole(consoles)
		return ok(JSONObject().apply {
			put("version", BuildConfig.VERSION_NAME)
			put("caption", JSONObject().apply {
				put("running", CaptionService.isRunning)
				put("canDrawOverlays", CaptionService.canDrawOverlays(context))
			})
			put("selectedKey", selected?.key ?: JSONObject.NULL)
			put("consoles", consolesArray(consoles, selected))
		})
	}

	override fun consoles(): HttpResponse
	{
		touchDiscovery()
		val consoles = loadConsoles()
		val selected = selectedConsole(consoles)
		val suggested = consoles.firstOrNull { !it.isRegistered && it.host.isNotEmpty() && it.displayHost is DiscoveredDisplayHost }?.host
			?: homePrefs.lastDiscoveredHost
			?: selected?.host?.takeIf { it.isNotEmpty() }
		return ok(JSONObject().apply {
			put("consoles", consolesArray(consoles, selected))
			put("suggestedHost", suggested ?: "")
			put("suggestedPS5", consoles.firstOrNull { it.host == suggested }?.isPS5 ?: true)
		})
	}

	// endregion

	// region Altyazı

	override fun captionStart(body: String): HttpResponse
	{
		val key = parseObject(body)?.optString("key")?.takeIf { it.isNotEmpty() }
		touchDiscovery()
		var consoles = loadConsoles()
		// Tarama yeni başladıysa konsolun görünmesi için kısa süre bekle.
		val deadline = System.currentTimeMillis() + DISCOVERY_WAIT_MS
		while(System.currentTimeMillis() < deadline &&
			(if(key != null) consoles.none { it.key == key } else consoles.none { it.isRegistered && it.state != HomeConsole.State.UNKNOWN }))
		{
			Thread.sleep(300)
			consoles = loadConsoles()
		}
		val console = (if(key != null) consoles.firstOrNull { it.key == key } else selectedConsole(consoles))
			?: return fail(404, "Konsol bulunamadı. PS5 açık ve TV ile aynı ağda olmalı; gerekirse önce eşleştirin.")
		val registeredHost = console.displayHost.registeredHost
			?: return fail(409, "Bu konsol henüz eşleştirilmemiş. Önce \"Eşleştir\" bölümünden eşleştirin.")
		if(console.host.isEmpty())
			return fail(409, "Konsolun IP adresi bilinmiyor. Konsol açık ve aynı ağda olmalı.")
		if(!CaptionService.canDrawOverlays(context))
			return fail(409, "TV'de bir kez izin vermeniz gerekiyor: TV'de AltyazıTR Oyun'u açıp \"Altyazıyı Başlat\"a basın ve " +
				"\"Diğer uygulamaların üzerinde göster\" iznini açın. Sonra buradan başlatabilirsiniz.") { it.put("code", "overlay_permission") }
		if(CaptionService.isRunning)
			return ok(JSONObject().put("message", "Altyazı zaten çalışıyor."))

		homePrefs.lastConsoleKey = console.key
		homePrefs.lastConsoleHost = console.host

		if(console.state == HomeConsole.State.STANDBY)
		{
			wakeup(console.host, registeredHost)
			return fail(409, "Konsol uykudaydı, uyandırılıyor. 15-20 saniye sonra tekrar \"Altyazıyı Başlat\"a basın.") { it.put("code", "waking") }
		}

		val connectInfo = ConnectInfo(console.isPS5, console.host, registeredHost.rpRegistKey, registeredHost.rpKey, preferences.videoProfile)
		val error = runOnMain {
			try
			{
				CaptionService.start(context, connectInfo)
				null
			}
			catch(e: Exception)
			{
				// Android 12+: uygulama arka plandayken ön plan servisi başlatılamayabilir.
				Log.e(TAG, "CaptionService could not be started", e)
				e.javaClass.simpleName
			}
		}
		if(error != null)
			return fail(409, "TV altyazıyı arka plandan başlatmaya izin vermedi. TV'de AltyazıTR Oyun'u bir kez açıp tekrar deneyin.") { it.put("code", "background_start") }
		return ok(JSONObject()
			.put("message", "Altyazı başlatıldı (${console.name ?: console.host}). TV girişini PS5'in HDMI girişine geçirin.")
			.put("key", console.key))
	}

	override fun captionStop(): HttpResponse
	{
		if(!CaptionService.isRunning)
			return ok(JSONObject().put("message", "Altyazı zaten kapalı."))
		val error = runOnMain {
			try
			{
				CaptionService.stop(context)
				null
			}
			catch(e: Exception)
			{
				Log.e(TAG, "CaptionService could not be stopped", e)
				e.javaClass.simpleName
			}
		}
		if(error != null)
			return fail(500, "Altyazı durdurulamadı. TV'deki uygulamadan durdurun.")
		return ok(JSONObject().put("message", "Altyazı durduruluyor."))
	}

	private fun wakeup(host: String, registeredHost: RegisteredHost)
	{
		mainHandler.post {
			val manager = discoveryManager ?: DiscoveryManager().also { discoveryManager = it }
			Thread {
				try
				{
					manager.sendWakeup(host, registeredHost.rpRegistKey, registeredHost.target.isPS5)
				}
				catch(e: Exception)
				{
					Log.w(TAG, "Wakeup failed", e)
				}
			}.start()
		}
	}

	// endregion

	// region Ayarlar

	private fun titleRes(key: String): Int? = when(key)
	{
		CompanionSettings.KEY_FONT -> R.string.preferences_caption_font_title
		CompanionSettings.KEY_TEXT_SIZE -> R.string.preferences_subtitle_size_title
		CompanionSettings.KEY_BOLD -> R.string.preferences_caption_bold_title
		CompanionSettings.KEY_TEXT_COLOR -> R.string.preferences_caption_text_color_title
		CompanionSettings.KEY_BG_COLOR -> R.string.preferences_caption_bg_color_title
		CompanionSettings.KEY_BG_OPACITY -> R.string.preferences_caption_bg_opacity_title
		CompanionSettings.KEY_CORNER_RADIUS -> R.string.preferences_caption_corner_radius_title
		CompanionSettings.KEY_SHADOW -> R.string.preferences_caption_shadow_title
		CompanionSettings.KEY_POSITION -> R.string.preferences_caption_position_title
		CompanionSettings.KEY_MAX_WIDTH -> R.string.preferences_caption_max_width_title
		CompanionSettings.KEY_LINE_SPACING -> R.string.preferences_caption_line_spacing_title
		CompanionSettings.KEY_CAPTURE_PERCENT -> R.string.preferences_subtitle_area_title
		CompanionSettings.KEY_INTERVAL_MS -> R.string.preferences_subtitle_speed_title
		CompanionSettings.KEY_TRANSLATION_ENGINE -> R.string.preferences_translation_engine_title
		CompanionSettings.KEY_GEMINI_API_KEY -> R.string.preferences_gemini_api_key_title
		CompanionSettings.KEY_GEMINI_MODEL -> R.string.preferences_gemini_model_title
		CompanionSettings.KEY_DRAFT_FIRST -> R.string.preferences_translation_draft_first_title
		else -> null
	}

	/** (etiketler, değerler) dizi kaynakları. */
	private fun optionArrays(key: String): Pair<Int, Int>? = when(key)
	{
		CompanionSettings.KEY_FONT -> R.array.caption_font_entries to R.array.caption_font_values
		CompanionSettings.KEY_TEXT_COLOR -> R.array.caption_text_color_entries to R.array.caption_text_color_values
		CompanionSettings.KEY_BG_COLOR -> R.array.caption_bg_color_entries to R.array.caption_bg_color_values
		CompanionSettings.KEY_SHADOW -> R.array.caption_shadow_entries to R.array.caption_shadow_values
		CompanionSettings.KEY_POSITION -> R.array.caption_position_entries to R.array.caption_position_values
		CompanionSettings.KEY_LINE_SPACING -> R.array.caption_line_spacing_entries to R.array.caption_line_spacing_values
		CompanionSettings.KEY_TRANSLATION_ENGINE -> R.array.translation_engine_entries to R.array.translation_engine_values
		CompanionSettings.KEY_GEMINI_MODEL -> R.array.gemini_model_entries to R.array.gemini_model_values
		else -> null
	}

	private fun optionLabel(key: String, value: String): String
	{
		val (entriesRes, valuesRes) = optionArrays(key) ?: return value
		return try
		{
			val entries = context.resources.getStringArray(entriesRes)
			val values = context.resources.getStringArray(valuesRes)
			val i = values.indexOfFirst { it.equals(value, ignoreCase = true) }
			if(i in entries.indices) entries[i] else value
		}
		catch(e: Exception)
		{
			value
		}
	}

	/** Liste ayarının kayıtlı metin değeri; listede yoksa varsayılan. */
	private fun choiceValue(spec: CompanionSettings.Spec.Choice): String
	{
		val raw = try { preferences.sharedPreferences.getString(spec.key, null) } catch(e: ClassCastException) { null }
		return spec.options.firstOrNull { it.equals(raw?.trim(), ignoreCase = true) } ?: spec.default
	}

	/** Geçerli (etkin) değerler: Preferences'taki okuyucularla aynı sınırlar ve eski anahtar dönüşümleri. */
	private fun currentValues(): JSONObject
	{
		val p = preferences
		val values = JSONObject()
		for(spec in CompanionSettings.SPECS)
		{
			val value: Any = when(spec.key)
			{
				CompanionSettings.KEY_FONT -> p.subtitleCaptionFont
				CompanionSettings.KEY_TEXT_SIZE -> p.subtitleTextSize
				CompanionSettings.KEY_BOLD -> p.subtitleCaptionBold
				CompanionSettings.KEY_BG_OPACITY -> p.subtitleCaptionBackgroundOpacity
				CompanionSettings.KEY_CORNER_RADIUS -> p.subtitleCaptionCornerRadius
				CompanionSettings.KEY_SHADOW -> p.subtitleCaptionShadow
				CompanionSettings.KEY_POSITION -> p.subtitleCaptionPosition
				CompanionSettings.KEY_MAX_WIDTH -> p.subtitleCaptionMaxWidthPercent
				CompanionSettings.KEY_CAPTURE_PERCENT -> p.subtitleCapturePercent
				CompanionSettings.KEY_INTERVAL_MS -> p.subtitleIntervalMs
				CompanionSettings.KEY_TRANSLATION_ENGINE -> p.translationEngineChoice
				CompanionSettings.KEY_GEMINI_MODEL -> p.geminiModel
				CompanionSettings.KEY_DRAFT_FIRST -> p.translationDraftFirst
				// Gizli anahtar asla açık gönderilmez: yalnızca maskeli hâli ("AIza…x7Q2") veya "".
				CompanionSettings.KEY_GEMINI_API_KEY -> TranslationSettings.maskApiKey(p.geminiApiKey)
				else -> when(spec)
				{
					is CompanionSettings.Spec.Secret -> ""
					is CompanionSettings.Spec.Choice -> choiceValue(spec)
					is CompanionSettings.Spec.IntRange -> spec.default
					is CompanionSettings.Spec.Bool -> spec.default
				}
			}
			values.put(spec.key, value)
		}
		return values
	}

	private fun specsJson(): JSONArray
	{
		val array = JSONArray()
		for(spec in CompanionSettings.SPECS)
		{
			val o = JSONObject()
			o.put("key", spec.key)
			o.put("title", titleRes(spec.key)?.let { context.getString(it) } ?: spec.key)
			when(spec)
			{
				is CompanionSettings.Spec.IntRange ->
				{
					o.put("type", "int")
					o.put("min", spec.min)
					o.put("max", spec.max)
					o.put("step", spec.step)
					o.put("default", spec.default)
				}
				is CompanionSettings.Spec.Bool ->
				{
					o.put("type", "bool")
					o.put("default", spec.default)
				}
				is CompanionSettings.Spec.Secret ->
				{
					o.put("type", "secret")
					o.put("maxLength", spec.maxLength)
					if(spec.key == CompanionSettings.KEY_GEMINI_API_KEY)
						o.put("help", "Ücretsiz anahtar: aistudio.google.com/apikey · Gemini'ye yalnızca altyazı metni gider. Silmek için boş kaydedin.")
				}
				is CompanionSettings.Spec.Choice ->
				{
					o.put("type", "choice")
					o.put("default", spec.default)
					o.put("options", JSONArray().also { options ->
						spec.options.forEach { value ->
							options.put(JSONObject().put("value", value).put("label", optionLabel(spec.key, value)))
						}
					})
				}
			}
			array.put(o)
		}
		return array
	}

	override fun getSettings(): HttpResponse =
		ok(JSONObject().put("values", currentValues()).put("specs", specsJson()).put("captionRunning", CaptionService.isRunning))

	override fun postSettings(body: String): HttpResponse
	{
		val json = parseObject(body) ?: return fail(400, "Geçersiz JSON")
		val input = LinkedHashMap<String, Any?>()
		val names = json.keys()
		while(names.hasNext())
		{
			val name = names.next()
			input[name] = json.opt(name).let { if(it == JSONObject.NULL) null else it }
		}
		val result = CompanionSettings.validate(input)
		if(!result.isValid)
			return fail(400, "Geçersiz ayar: " + result.errors.entries.joinToString("; ") { "${it.key}: ${it.value}" }) { o ->
				o.put("fields", JSONObject().also { f -> result.errors.forEach { (k, v) -> f.put(k, v) } })
			}

		val editor = preferences.sharedPreferences.edit()
		if(result.reset)
		{
			CompanionSettings.STYLE_KEYS.forEach { editor.remove(it) }
			editor.putString(CompanionSettings.KEY_POSITION, CompanionSettings.POSITIONS.first())
		}
		for((key, value) in result.values)
		{
			when(value)
			{
				is CompanionSettings.Value.IntValue -> editor.putInt(key, value.value)
				is CompanionSettings.Value.BoolValue -> editor.putBoolean(key, value.value)
				is CompanionSettings.Value.StringValue -> editor.putString(key, value.value)
			}
		}
		// apply(): dinleyiciler (CaptionService, ayarlar ekranı) ana iş parçacığında haber alır.
		editor.apply()
		return ok(JSONObject().put("values", currentValues()).put("captionRunning", CaptionService.isRunning))
	}

	// endregion

	// region Eşleştirme

	override fun regist(body: String): HttpResponse
	{
		val json = parseObject(body) ?: return fail(400, "Geçersiz JSON")
		val parsed = CompanionRegist.parse(
			json.optString("host"),
			json.optString("console"),
			json.optString("psnId"),
			json.optString("pin"))
		val input = when(parsed)
		{
			is CompanionRegist.Parsed.Error -> return fail(400, parsed.message) { it.put("field", parsed.field) }
			is CompanionRegist.Parsed.Ok -> parsed.input
		}
		if(!registRunning.compareAndSet(false, true))
			return fail(409, "Şu an başka bir eşleştirme sürüyor. Birkaç saniye sonra tekrar deneyin.")
		try
		{
			return runRegist(input)
		}
		finally
		{
			registRunning.set(false)
		}
	}

	private fun runRegist(input: CompanionRegist.Input): HttpResponse
	{
		val target = when(input.console)
		{
			CompanionRegist.Console.PS5 -> Target.PS5_1
			CompanionRegist.Console.PS4_GE_8 -> Target.PS4_10
			CompanionRegist.Console.PS4_GE_7 -> Target.PS4_9
			CompanionRegist.Console.PS4_LT_7 -> Target.PS4_8
		}
		// RegistExecuteViewModel ile aynı akış; yayın (broadcast) kapalı: adres telefondan geliyor.
		val info = RegistInfo(target, input.host, false, input.onlineId, input.accountId, input.pin)

		val logText = StringBuilder()
		val log = ChiakiLog(ChiakiLog.Level.ALL.value and ChiakiLog.Level.VERBOSE.value.inv()) { level, text ->
			synchronized(logText) { logText.append(ChiakiLog.formatLog(level, text)).append('\n') }
			Log.i(TAG, "regist: $text")
		}
		val latch = CountDownLatch(1)
		val event = AtomicReference<RegistEvent?>(null)
		val regist = try
		{
			Regist(info, log) { e ->
				event.compareAndSet(null, e)
				latch.countDown()
			}
		}
		catch(e: CreateError)
		{
			Log.e(TAG, "Failed to create Regist: ${e.errorCode}")
			return fail(500, "Eşleştirme başlatılamadı (${e.errorCode}).")
		}

		try
		{
			if(!latch.await(REGIST_TIMEOUT_S, TimeUnit.SECONDS))
			{
				regist.stop()
				latch.await(5, TimeUnit.SECONDS)
			}
		}
		finally
		{
			// Yerel iş parçacığına katılır; geri çağrının içinden değil, burada çağrılmalı.
			try { regist.dispose() } catch(e: Exception) { Log.w(TAG, "Regist dispose failed", e) }
		}

		return when(val e = event.get())
		{
			is RegistEventSuccess -> saveRegisteredHost(e.host, input)
			is RegistEventFailed, null, RegistEventCanceled ->
			{
				val text = synchronized(logText) { logText.toString() }
				val message = if(e == null || e == RegistEventCanceled)
					"Konsol zamanında yanıt vermedi. IP adresini kontrol edip tekrar deneyin."
				else
					CompanionRegist.failureMessage(text)
				fail(502, message)
			}
		}
	}

	private fun saveRegisteredHost(host: RegistHost, input: CompanionRegist.Input): HttpResponse
	{
		val registeredHost = RegisteredHost(host)
		val overwritten = try
		{
			runBlocking {
				val dao = database.registeredHostDao()
				val existing = dao.getByMac(registeredHost.serverMac)
				// Telefondan bilerek yeniden eşleştirildi: var olan kaydın üzerine yazılır.
				dao.deleteByMac(registeredHost.serverMac)
				val id = dao.insert(registeredHost)
				// Aynı adresle elle eklenmiş ve eşleştirilmemiş bir konsol varsa bu kayda bağla.
				val manualHostDao = database.manualHostDao()
				manualHostDao.getAll().first()
					.filter { it.host == input.host && (it.registeredHost == null || it.registeredHost == existing?.id) }
					.forEach { manualHostDao.assignRegisteredHost(it.id, id) }
				existing != null
			}
		}
		catch(e: Exception)
		{
			Log.e(TAG, "Could not save registered host", e)
			return fail(500, "Eşleştirme başarılı oldu ama kaydedilemedi. Tekrar deneyin.")
		}
		homePrefs.lastConsoleKey = "mac:${registeredHost.serverMac}"
		homePrefs.lastConsoleHost = input.host
		val name = host.serverNickname.ifEmpty { input.host }
		val message = if(overwritten)
			"$name eşleştirildi. Bu konsol zaten eşleştirilmişti; eski kaydın yerine yenisi yazıldı."
		else
			"$name eşleştirildi. Artık \"Altyazıyı Başlat\"a basabilirsiniz."
		return ok(JSONObject()
			.put("message", message)
			.put("overwritten", overwritten)
			.put("name", name)
			.put("mac", MacAddress(host.serverMac).toString())
			.put("ps5", host.target.isPS5))
	}

	// endregion

	private fun parseObject(body: String): JSONObject? =
		try
		{
			if(body.isBlank()) JSONObject() else JSONObject(body)
		}
		catch(e: JSONException)
		{
			null
		}

	/** [block]'u ana iş parçacığında çalıştırıp sonucunu bekler (en fazla [MAIN_TIMEOUT_S] sn). */
	private fun <T> runOnMain(block: () -> T): T?
	{
		if(Looper.myLooper() == Looper.getMainLooper())
			return block()
		val latch = CountDownLatch(1)
		val result = AtomicReference<T?>(null)
		mainHandler.post {
			try
			{
				result.set(block())
			}
			finally
			{
				latch.countDown()
			}
		}
		latch.await(MAIN_TIMEOUT_S, TimeUnit.SECONDS)
		return result.get()
	}
}
