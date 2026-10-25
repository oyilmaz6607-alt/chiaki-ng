// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.metallic.chiaki.BuildConfig
import com.metallic.chiaki.billing.ProStore
import com.metallic.chiaki.billing.QuotaNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * AltyazıTR abonelik istemcisi (PRO_API.md v1.1): abonelik jetonuyla oturum açar, altyazıyı sunucuya gönderir ve
 * Türkçeyi NDJSON akışı olarak okur. Akış sürerken kısmi metin ana iş parçacığında [translate]'in
 * `onPartial` geri çağrısına verilir (en sık ~80 ms'de bir veya noktalamada), sonunda tam metin döner.
 *
 * Süreler: bağlantı 2 sn, oturum yenileme dahil toplam 4 sn. Süre dolunca bağlantı başka bir iş
 * parçacığından kapatılır (HttpURLConnection okuması iptal edilemez), çağıran cihaz içi çeviriye döner.
 * Uygulama ömrü boyunca tek örnek: oturum jetonu bellekte saklanır, süresi dolmadan (2 dk kala) veya
 * sunucu 401 dönünce bir kez yenilenir.
 */
class ProTranslator private constructor(context: Context)
{
	companion object
	{
		@Volatile private var instance: ProTranslator? = null

		fun get(context: Context): ProTranslator =
			instance ?: synchronized(this) {
				instance ?: ProTranslator(context.applicationContext).also { instance = it }
			}

		/** BuildConfig'te sunucu adresi var mı? Yoksa abonelik "henüz hazır değil". */
		val isConfigured: Boolean get() = ProApi.normalizeBaseUrl(BuildConfig.PRO_API_BASE_URL) != null
	}

	class ProException(val error: ProError, message: String): IOException(message)

	/** Tek bir çeviri isteğinin (oturum + çeviri) bağlantısı ve süre durumu. */
	private class Call
	{
		val connection = AtomicReference<HttpURLConnection?>(null)
		@Volatile var finished = false
		@Volatile var timedOut = false

		fun abort()
		{
			connection.get()?.let { c -> try { c.disconnect() } catch(_: Exception) {} }
		}
	}

	private val store = ProStore(context)
	private val baseUrl = ProApi.normalizeBaseUrl(BuildConfig.PRO_API_BASE_URL)
	private val mainHandler = Handler(Looper.getMainLooper())
	private val sessionLock = Any()
	private var session: ProSession? = null
	private var sessionOwner: String? = null

	/** Son bilinen kota (oturum veya çeviri yanıtından). */
	@Volatile var lastQuota: ProQuota? = store.quota
		private set

	private fun fail(code: String, httpCode: Int = 0, message: String? = null): Nothing
	{
		val e = ProError(code, httpCode, message)
		throw ProException(e, ProApi.userMessage(e, lastQuota))
	}

	/**
	 * Paketin bu dönemki satır hakkı dolu mu (sunucunun son bildirdiği kotaya göre)? Doluysa dönem sonuna
	 * ([ProQuota.resetAt]) kadar sunucuya hiç gidilmez; çağıran cihaz içi çeviriye döner.
	 * Paket değişince (yeni jeton) ProStore kotayı sildiği için kendiliğinden açılır.
	 */
	fun isQuotaExhausted(nowMs: Long = System.currentTimeMillis()): Boolean
	{
		val q = store.quota ?: return false
		return QuotaNotice.isExhausted(q.used, q.limit, q.resetAt, nowMs)
	}

	/**
	 * Altyazıyı abonelik (yapay zekâ) çevirisiyle çevirir. Ana iş parçacığından çağrılabilir (ağ işi Dispatchers.IO'da).
	 * [onPartial] ana iş parçacığında, son metin dönmeden önce çağrılır.
	 * Hata, zaman aşımı, kota dolması veya abonelik sorununda [ProException] fırlatır.
	 */
	suspend fun translate(english: String, context: List<SubtitlePair>, onPartial: ((String) -> Unit)? = null): String
	{
		val base = baseUrl ?: fail(ProApi.CODE_NOT_CONFIGURED)
		val purchaseToken = store.purchaseToken ?: fail(ProApi.CODE_NO_PURCHASE)
		if(isQuotaExhausted())
			fail(ProApi.CODE_QUOTA_EXCEEDED, 429)
		val call = Call()
		return coroutineScope {
			// Bekçi: toplam süre dolunca (veya istek iptal edilince) açık bağlantıyı kapatır.
			val watchdog = launch {
				try
				{
					delay(ProApi.TOTAL_TIMEOUT_MS)
					call.timedOut = true
				}
				finally
				{
					if(!call.finished)
						call.abort()
				}
			}
			try
			{
				withContext(Dispatchers.IO) { translateBlocking(base, purchaseToken, english, context, onPartial, call) }
			}
			finally
			{
				call.finished = true
				watchdog.cancel()
			}
		}
	}

	private fun translateBlocking(base: String, purchaseToken: String, english: String, context: List<SubtitlePair>,
		onPartial: ((String) -> Unit)?, call: Call): String
	{
		return try
		{
			translateWithSession(base, purchaseToken, english, context, onPartial, call)
		}
		catch(e: ProException)
		{
			if(e.error.code == ProApi.CODE_QUOTA_EXCEEDED)
				markQuotaExhausted()
			throw e
		}
	}

	/** Sunucu "hak doldu" dedi: kayıtlı kotayı dolu olarak işaretle (dönem sonuna kadar istek atılmaz). */
	private fun markQuotaExhausted()
	{
		val q = store.quota ?: lastQuota ?: return
		if(q.used < q.limit)
			rememberQuota(q.copy(used = q.limit))
	}

	private fun translateWithSession(base: String, purchaseToken: String, english: String, context: List<SubtitlePair>,
		onPartial: ((String) -> Unit)?, call: Call): String
	{
		val first = obtainSession(base, purchaseToken, call, force = false)
		return try
		{
			postTranslate(base, first.token, english, context, onPartial, call)
		}
		catch(e: ProException)
		{
			if(!ProApi.shouldRenewSession(e.error))
				throw e
			// Oturum geçersiz/süresi dolmuş: bir kez yenile ve tekrar dene.
			invalidateSession(first.token)
			val renewed = obtainSession(base, purchaseToken, call, force = true)
			postTranslate(base, renewed.token, english, context, onPartial, call)
		}
	}

	/** Geçerli oturumu döndürür, gerekirse /v1/session ile yenisini açar (engelleyici). */
	private fun obtainSession(base: String, purchaseToken: String, call: Call?, force: Boolean,
		connectTimeoutMs: Int = ProApi.CONNECT_TIMEOUT_MS, readTimeoutMs: Int = ProApi.TOTAL_TIMEOUT_MS.toInt()): ProSession
	{
		if(!force)
		{
			synchronized(sessionLock) {
				val s = session
				if(s != null && sessionOwner == purchaseToken && !s.needsRefresh(SystemClock.elapsedRealtime()))
					return s
			}
		}
		val (code, body) = request(base + ProApi.PATH_SESSION, "POST", ProApi.sessionBody(purchaseToken, BuildConfig.VERSION_NAME),
			null, "application/json", call, connectTimeoutMs, readTimeoutMs)
		return when(val r = ProApi.parseSession(code, body))
		{
			is ProApi.SessionResult.Ok ->
			{
				val s = ProSession.create(r.token, r.expiresAt, r.quota, System.currentTimeMillis(), SystemClock.elapsedRealtime())
				synchronized(sessionLock) {
					session = s
					sessionOwner = purchaseToken
				}
				r.quota?.let { rememberQuota(it) }
				r.plan?.let { store.savePlan(it) }
				s
			}
			is ProApi.SessionResult.Failure ->
			{
				invalidateSession(null)
				fail(r.error.code, r.error.httpCode, r.error.serverMessage)
			}
		}
	}

	private fun invalidateSession(token: String?)
	{
		synchronized(sessionLock) {
			if(token == null || session?.token == token)
			{
				session = null
				sessionOwner = null
			}
		}
	}

	private fun rememberQuota(q: ProQuota)
	{
		lastQuota = q
		store.saveQuota(q)
	}

	private fun postTranslate(base: String, sessionToken: String, english: String, context: List<SubtitlePair>,
		onPartial: ((String) -> Unit)?, call: Call): String
	{
		val conn = open(base + ProApi.PATH_TRANSLATE, call, ProApi.CONNECT_TIMEOUT_MS, ProApi.TOTAL_TIMEOUT_MS.toInt())
		try
		{
			conn.requestMethod = "POST"
			conn.doOutput = true
			conn.setRequestProperty("Authorization", "Bearer $sessionToken")
			conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
			conn.setRequestProperty("Accept", ProApi.NDJSON)
			val bytes = ProApi.translateBody(english, context).toByteArray(Charsets.UTF_8)
			conn.setFixedLengthStreamingMode(bytes.size)
			conn.outputStream.use { it.write(bytes) }
			val code = conn.responseCode
			if(code != 200)
			{
				val body = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
				val e = ProApi.parseError(code, body)
				fail(e.code, e.httpCode, e.serverMessage)
			}
			val contentType = conn.contentType.orEmpty()
			if(!contentType.contains("ndjson", ignoreCase = true))
			{
				// Sunucu akış yerine tek JSON döndürdü (ör. önbellekten).
				val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
				return when(val r = ProApi.parseTranslate(code, body))
				{
					is ProApi.TranslateResult.Ok ->
					{
						r.quota?.let { rememberQuota(it) }
						r.text
					}
					is ProApi.TranslateResult.Failure -> fail(r.error.code, r.error.httpCode, r.error.serverMessage)
				}
			}
			return readStream(conn, onPartial, call)
		}
		catch(e: ProException)
		{
			throw e
		}
		catch(e: IOException)
		{
			throw networkError(e, call)
		}
	}

	private fun readStream(conn: HttpURLConnection, onPartial: ((String) -> Unit)?, call: Call): String
	{
		val acc = StreamAccumulator(SystemClock.uptimeMillis() - ProApi.PARTIAL_INTERVAL_MS)
		BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
			while(true)
			{
				val line = reader.readLine() ?: break
				when(val ev = ProApi.parseStreamLine(line))
				{
					is ProApi.StreamEvent.Delta ->
					{
						val partial = acc.add(ev.text, SystemClock.uptimeMillis())
						if(partial != null && onPartial != null)
							mainHandler.post { if(!call.finished) onPartial(partial) }
					}
					is ProApi.StreamEvent.Done ->
					{
						ev.quota?.let { rememberQuota(it) }
						val text = ev.text.ifEmpty { GeminiRequest.cleanOutput(acc.text) }
						if(text.isEmpty())
							fail(ProApi.CODE_BAD_RESPONSE, 200)
						return text
					}
					is ProApi.StreamEvent.Error -> fail(ev.error.code, ev.error.httpCode, ev.error.serverMessage)
					ProApi.StreamEvent.Unknown, null -> {}
				}
			}
		}
		// Akış "done" satırı olmadan bitti: yarım metin son çeviri sayılmaz.
		fail(ProApi.CODE_BAD_RESPONSE, 200)
	}

	private fun networkError(e: IOException, call: Call?): ProException
	{
		val timeout = call?.timedOut == true || e is SocketTimeoutException
		val err = ProError(if(timeout) ProApi.CODE_TIMEOUT else ProApi.CODE_NETWORK, 0, null)
		return ProException(err, ProApi.userMessage(err) + " (${e.javaClass.simpleName})")
	}

	private fun open(url: String, call: Call?, connectTimeoutMs: Int, readTimeoutMs: Int): HttpURLConnection
	{
		if(call?.timedOut == true)
			throw ProException(ProError(ProApi.CODE_TIMEOUT), ProApi.userMessage(ProError(ProApi.CODE_TIMEOUT)))
		val conn = try
		{
			URL(url).openConnection() as HttpURLConnection
		}
		catch(e: IOException)
		{
			throw networkError(e, call)
		}
		conn.connectTimeout = connectTimeoutMs
		conn.readTimeout = readTimeoutMs
		conn.useCaches = false
		call?.connection?.set(conn)
		return conn
	}

	/** Basit JSON isteği: (HTTP kodu, gövde). Engelleyici. */
	private fun request(url: String, method: String, body: String?, bearer: String?, accept: String, call: Call?,
		connectTimeoutMs: Int, readTimeoutMs: Int): Pair<Int, String?>
	{
		val conn = open(url, call, connectTimeoutMs, readTimeoutMs)
		try
		{
			conn.requestMethod = method
			conn.setRequestProperty("Accept", accept)
			if(bearer != null)
				conn.setRequestProperty("Authorization", "Bearer $bearer")
			if(body != null)
			{
				conn.doOutput = true
				conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
				val bytes = body.toByteArray(Charsets.UTF_8)
				conn.setFixedLengthStreamingMode(bytes.size)
				conn.outputStream.use { it.write(bytes) }
			}
			val code = conn.responseCode
			val stream = if(code in 200..299) conn.inputStream else conn.errorStream
			return code to stream?.use { it.readBytes().toString(Charsets.UTF_8) }
		}
		catch(e: IOException)
		{
			throw networkError(e, call)
		}
	}

	/**
	 * Abonelik ekranı için: yeni oturum açar (paket ve kota güncel olsun; paket değişikliği, deneme bitişi)
	 * ve kotayı döndürür. Paket [ProStore.plan]'a yazılır. Daha uzun süreler; engelleyici,
	 * Dispatchers.IO'da çağrılmalı. Hata durumunda [ProException].
	 */
	fun refreshSessionBlocking(): ProQuota?
	{
		val base = baseUrl ?: fail(ProApi.CODE_NOT_CONFIGURED)
		val purchaseToken = store.purchaseToken ?: fail(ProApi.CODE_NO_PURCHASE)
		val s = obtainSession(base, purchaseToken, null, force = true, connectTimeoutMs = 5000, readTimeoutMs = 8000)
		return s.quota ?: lastQuota
	}

	/** Bağlantıyı önceden ısıtır (TLS + bağlantı havuzu). Engelleyici, hata yok sayılır. */
	fun warmUp()
	{
		val base = baseUrl ?: return
		try
		{
			request(base + ProApi.PATH_HEALTH, "GET", null, null, "application/json", null, 4000, 4000)
		}
		catch(_: Exception)
		{
		}
	}
}
