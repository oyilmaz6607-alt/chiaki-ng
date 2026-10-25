// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

import com.metallic.chiaki.billing.Plans
import com.metallic.chiaki.billing.PriceText
import com.metallic.chiaki.billing.ProProduct
import java.util.TimeZone

/** Satır kotası (PRO_API.md → quota): ücretli pakette aylık, denemede deneme boyunca. */
data class ProQuota(val used: Long, val limit: Long, val resetAt: Long)

/** Sunucunun bildirdiği paket (PRO_API.md v1.1 → /v1/session). */
data class ProPlan(
	/** "standart" | "pro" | "premium" | "deneme". */
	val plan: String,
	val planName: String?,
	/** Temel plan (denemede deneme bitince geçilecek paket). */
	val basePlan: String?,
	val trial: Boolean
)

/**
 * Sunucunun verdiği oturum jetonu. Jeton opaktır; süresi cihaz saatine güvenmeden, alındığı andaki
 * monoton saate göre tutulur (cihaz saati yanlışsa her istekte yeni oturum açılmasın diye).
 */
data class ProSession(
	val token: String,
	/** Sunucunun bildirdiği bitiş (unix ms). */
	val expiresAt: Long,
	/** Monoton saatte (ör. SystemClock.elapsedRealtime) tahmini bitiş. */
	val localExpiresAt: Long,
	val quota: ProQuota?
)
{
	companion object
	{
		/** Bitişe bu kadar kala yenilenir. */
		const val REFRESH_MARGIN_MS = 2 * 60_000L
		private const val MIN_TTL_MS = 5 * 60_000L
		private const val MAX_TTL_MS = 60 * 60_000L

		fun create(token: String, expiresAt: Long, quota: ProQuota?, nowWallMs: Long, nowMonoMs: Long): ProSession =
			ProSession(token, expiresAt, nowMonoMs + (expiresAt - nowWallMs).coerceIn(MIN_TTL_MS, MAX_TTL_MS), quota)
	}

	fun needsRefresh(nowMonoMs: Long) = localExpiresAt - nowMonoMs < REFRESH_MARGIN_MS
}

/** Sunucu veya ağ hatası. [code] PRO_API.md'deki kodlar ya da aşağıdaki yerel kodlar. */
data class ProError(val code: String, val httpCode: Int = 0, val serverMessage: String? = null)

/**
 * AltyazıTR abonelik sunucusu (PRO_API.md v1.1) için istek gövdeleri ve yanıt çözümleme (ağ yok, saf mantık).
 */
object ProApi
{
	const val PATH_HEALTH = "/v1/health"
	const val PATH_SESSION = "/v1/session"
	const val PATH_TRANSLATE = "/v1/translate"
	const val NDJSON = "application/x-ndjson"

	const val MAX_TEXT = 600
	const val MAX_CONTEXT = 4
	const val CONNECT_TIMEOUT_MS = 2000
	/** Bir altyazının çevirisi için toplam süre (oturum yenileme dahil). */
	const val TOTAL_TIMEOUT_MS = 4000L
	/** Akışta kısmi metnin ekrana en sık yazılma aralığı. */
	const val PARTIAL_INTERVAL_MS = 80L

	// Sunucu kodları
	const val CODE_NOT_SUBSCRIBED = "not_subscribed"
	const val CODE_INVALID_PURCHASE = "invalid_purchase"
	const val CODE_RATE_LIMITED = "rate_limited"
	const val CODE_SERVER_ERROR = "server_error"
	const val CODE_UNAUTHORIZED = "unauthorized"
	const val CODE_QUOTA_EXCEEDED = "quota_exceeded"
	const val CODE_BAD_REQUEST = "bad_request"
	const val CODE_UPSTREAM_ERROR = "upstream_error"
	// Yerel kodlar
	const val CODE_NOT_CONFIGURED = "not_configured"
	const val CODE_NO_PURCHASE = "no_purchase"
	const val CODE_TIMEOUT = "timeout"
	const val CODE_NETWORK = "network"
	const val CODE_BAD_RESPONSE = "bad_response"

	const val MSG_NOT_CONFIGURED = "Abonelik hizmeti henüz hazır değil"

	/** "https://x.example/" → "https://x.example"; boş/geçersiz → null (Pro yapılandırılmamış). */
	fun normalizeBaseUrl(raw: String?): String?
	{
		val s = raw?.trim()?.trimEnd('/') ?: return null
		if(!s.startsWith("https://") || s.length <= "https://".length)
			return null
		if(s.any { it.isWhitespace() || it == '"' || it == '?' || it == '#' })
			return null
		return s
	}

	fun sessionBody(purchaseToken: String, appVersion: String): String
	{
		val sb = StringBuilder(purchaseToken.length + 160)
		sb.append("{\"packageName\":")
		MiniJson.quoteTo(sb, ProProduct.PACKAGE_NAME)
		sb.append(",\"productId\":")
		MiniJson.quoteTo(sb, ProProduct.PRODUCT_ID)
		sb.append(",\"purchaseToken\":")
		MiniJson.quoteTo(sb, purchaseToken)
		sb.append(",\"appVersion\":")
		MiniJson.quoteTo(sb, appVersion)
		sb.append('}')
		return sb.toString()
	}

	/** Metin ve bağlam sınırları sözleşmedeki gibi kırpılır (≤ 600 karakter, ≤ 4 çift). */
	fun translateBody(english: String, context: List<SubtitlePair>): String
	{
		val sb = StringBuilder(1024)
		sb.append("{\"text\":")
		MiniJson.quoteTo(sb, english.trim().take(MAX_TEXT))
		sb.append(",\"context\":[")
		context.filter { it.english.isNotBlank() && it.turkish.isNotBlank() }.takeLast(MAX_CONTEXT).forEachIndexed { i, p ->
			if(i > 0) sb.append(',')
			sb.append("{\"en\":")
			MiniJson.quoteTo(sb, p.english.trim().take(MAX_TEXT))
			sb.append(",\"tr\":")
			MiniJson.quoteTo(sb, p.turkish.trim().take(MAX_TEXT))
			sb.append('}')
		}
		sb.append("],\"speaker\":null}")
		return sb.toString()
	}

	private fun parseObject(json: String?): Map<*, *>?
	{
		if(json.isNullOrBlank())
			return null
		return try { MiniJson.parse(json) as? Map<*, *> } catch(e: MiniJson.ParseException) { null }
	}

	private fun num(v: Any?): Long? = when(v)
	{
		is Long -> v
		is Double -> v.toLong()
		else -> null
	}

	fun quota(v: Any?): ProQuota?
	{
		val m = v as? Map<*, *> ?: return null
		val used = num(m["used"]) ?: return null
		val limit = num(m["limit"]) ?: return null
		return ProQuota(used, limit, num(m["resetAt"]) ?: 0L)
	}

	/** Hata gövdesi `{"ok":false,"code":..,"message":..}`; kod yoksa HTTP durumundan çıkarılır. */
	fun parseError(httpCode: Int, body: String?): ProError
	{
		val root = parseObject(body)
		val code = (root?.get("code") as? String)?.takeIf { it.isNotBlank() } ?: when(httpCode)
		{
			401 -> CODE_UNAUTHORIZED
			403 -> CODE_NOT_SUBSCRIBED
			429 -> CODE_RATE_LIMITED
			400 -> CODE_BAD_REQUEST
			in 500..599 -> CODE_SERVER_ERROR
			else -> CODE_BAD_RESPONSE
		}
		val message = (root?.get("message") as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(200)
		return ProError(code, httpCode, message)
	}

	sealed class SessionResult
	{
		data class Ok(val token: String, val expiresAt: Long, val quota: ProQuota?, val plan: ProPlan? = null): SessionResult()
		data class Failure(val error: ProError): SessionResult()
	}

	fun parseSession(httpCode: Int, body: String?): SessionResult
	{
		if(httpCode != 200)
			return SessionResult.Failure(parseError(httpCode, body))
		val root = parseObject(body) ?: return SessionResult.Failure(ProError(CODE_BAD_RESPONSE, httpCode))
		if(root["ok"] != true)
			return SessionResult.Failure(parseError(httpCode, body))
		val token = (root["sessionToken"] as? String)?.takeIf { it.isNotBlank() && it.length <= 4096 }
		val expiresAt = num(root["expiresAt"])
		if(token == null || expiresAt == null)
			return SessionResult.Failure(ProError(CODE_BAD_RESPONSE, httpCode))
		return SessionResult.Ok(token, expiresAt, quota(root["quota"]), plan(root))
	}

	/** Paket alanları; "plan" yoksa veya geçersizse null (v1 sunucu). */
	fun plan(root: Map<*, *>): ProPlan?
	{
		val id = (root["plan"] as? String)?.takeIf { PLAN_ID.matches(it) } ?: return null
		val name = (root["planName"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(40)
		val base = (root["basePlan"] as? String)?.takeIf { PLAN_ID.matches(it) }
		return ProPlan(id, name, base ?: id.takeIf { it != Plans.TRIAL_ID }, root["trial"] == true)
	}

	private val PLAN_ID = Regex("^[a-z0-9-]{1,40}$")

	sealed class TranslateResult
	{
		data class Ok(val text: String, val quota: ProQuota?, val cached: Boolean): TranslateResult()
		data class Failure(val error: ProError): TranslateResult()
	}

	/** Akışsız yanıt: `{"ok":true,"text":..,"quota":{..},"cached":false}`. */
	fun parseTranslate(httpCode: Int, body: String?): TranslateResult
	{
		if(httpCode != 200)
			return TranslateResult.Failure(parseError(httpCode, body))
		val root = parseObject(body) ?: return TranslateResult.Failure(ProError(CODE_BAD_RESPONSE, httpCode))
		if(root["ok"] != true)
			return TranslateResult.Failure(parseError(httpCode, body))
		val text = (root["text"] as? String)?.let { GeminiRequest.cleanOutput(it) }
		if(text.isNullOrEmpty())
			return TranslateResult.Failure(ProError(CODE_BAD_RESPONSE, httpCode))
		return TranslateResult.Ok(text, quota(root["quota"]), root["cached"] == true)
	}

	/** NDJSON akışının bir satırı. */
	sealed class StreamEvent
	{
		data class Delta(val text: String): StreamEvent()
		data class Done(val text: String, val quota: ProQuota?, val cached: Boolean): StreamEvent()
		data class Error(val error: ProError): StreamEvent()
		/** Tanınmayan/bozuk satır: yok sayılır. */
		object Unknown: StreamEvent()
	}

	/** Boş satır → null. */
	fun parseStreamLine(line: String): StreamEvent?
	{
		val s = line.trim()
		if(s.isEmpty())
			return null
		val root = parseObject(s) ?: return StreamEvent.Unknown
		(root["error"] as? String)?.let { code ->
			val message = (root["message"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(200)
			return StreamEvent.Error(ProError(code.ifBlank { CODE_SERVER_ERROR }, 200, message))
		}
		if(root["done"] == true)
		{
			val text = (root["text"] as? String)?.let { GeminiRequest.cleanOutput(it) } ?: ""
			return StreamEvent.Done(text, quota(root["quota"]), root["cached"] == true)
		}
		(root["d"] as? String)?.let { return StreamEvent.Delta(it) }
		return StreamEvent.Unknown
	}

	/** Bu hatadan sonra yeni oturumla bir kez daha denenmeli mi? */
	fun shouldRenewSession(e: ProError) = e.code == CODE_UNAUTHORIZED || e.httpCode == 401

	/**
	 * Kullanıcıya gösterilecek Türkçe mesaj. Kota aşımında sıfırlanma tarihi [lastQuota]'dan
	 * (son oturum/çeviri yanıtı) yazılır; bilinmiyorsa sunucunun mesajı kullanılır.
	 */
	fun userMessage(e: ProError, lastQuota: ProQuota? = null, nowMs: Long = System.currentTimeMillis(),
		tz: TimeZone = TimeZone.getDefault()): String = when(e.code)
	{
		CODE_NOT_CONFIGURED -> MSG_NOT_CONFIGURED
		CODE_NO_PURCHASE -> "AltyazıTR aboneliği bulunamadı."
		CODE_NOT_SUBSCRIBED -> "AltyazıTR aboneliğiniz etkin değil. Ayarlar → AltyazıTR Abonelik'ten kontrol edin."
		CODE_INVALID_PURCHASE -> "Abonelik doğrulanamadı. Google Play → Abonelikler'den kontrol edin."
		CODE_QUOTA_EXCEEDED ->
		{
			val reset = lastQuota?.resetAt?.takeIf { it > nowMs }
			when
			{
				reset != null -> "Paketinizin yapay zekâ çeviri hakkı doldu; ${PriceText.date(reset, nowMs, tz)} tarihinde yenilenir."
				e.serverMessage != null -> e.serverMessage
				else -> "Paketinizin yapay zekâ çeviri hakkı doldu; ay başında yenilenir."
			}
		}
		CODE_RATE_LIMITED -> "Çok fazla istek gönderildi; biraz sonra tekrar denenecek."
		CODE_UNAUTHORIZED -> "Abonelik oturumu açılamadı; tekrar denenecek."
		CODE_BAD_REQUEST -> "Çeviri isteği geçersiz."
		CODE_UPSTREAM_ERROR, CODE_SERVER_ERROR -> "Yapay zekâ çeviri sunucusu şu an yanıt vermiyor."
		CODE_TIMEOUT -> "Yapay zekâ çevirisi zaman aşımına uğradı."
		CODE_NETWORK -> "Çeviri sunucusuna bağlanılamadı. İnternet bağlantısını kontrol edin."
		CODE_BAD_RESPONSE -> "Çeviri sunucusundan geçersiz yanıt geldi."
		else -> e.serverMessage ?: "Abonelik hatası (${e.code})"
	}

	/**
	 * Bekleme (GeminiBackoff) için HTTP benzeri kod: abonelik/kota sorunlarında uzun, geçici hatalarda kısa bekleme.
	 */
	fun backoffCode(e: ProError): Int = when(e.code)
	{
		CODE_NOT_SUBSCRIBED, CODE_INVALID_PURCHASE, CODE_QUOTA_EXCEEDED, CODE_NOT_CONFIGURED, CODE_NO_PURCHASE -> 403
		CODE_RATE_LIMITED -> 429
		else -> e.httpCode.takeIf { it >= 500 } ?: 0
	}
}

/**
 * Akıştaki parçaları biriktirir ve ekrana ne zaman yazılacağına karar verir: en geç [minIntervalMs]'de bir
 * veya parça noktalama ile bittiğinde. Tek iş parçacığından kullanılmalı.
 */
class StreamAccumulator(startMs: Long, private val minIntervalMs: Long = ProApi.PARTIAL_INTERVAL_MS)
{
	private val sb = StringBuilder()
	private var lastEmitAt = startMs
	private var lastEmitted = ""

	val text: String get() = sb.toString()

	/** Yeni parça; ekrana yazılması gereken metin varsa döner (yoksa null). */
	fun add(delta: String, nowMs: Long): String?
	{
		if(delta.isEmpty())
			return null
		sb.append(delta)
		val current = sb.toString().replace(Regex("\\s+"), " ").trim()
		if(current.isEmpty() || current == lastEmitted)
			return null
		val punctuation = delta.trimEnd().lastOrNull()?.let { it in ".,!?;:…" } == true
		if(!punctuation && nowMs - lastEmitAt < minIntervalMs)
			return null
		lastEmitAt = nowMs
		lastEmitted = current
		return current
	}
}
