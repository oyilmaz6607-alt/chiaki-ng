// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/** Gemini'ye bağlam olarak giden önceki altyazı çifti. */
data class SubtitlePair(val english: String, val turkish: String)

/**
 * Gemini generateContent isteğini kurar ve yanıtını çözer (ağ yok, saf mantık).
 *
 * İstek: https://generativelanguage.googleapis.com/v1beta/models/<model>:generateContent
 * Başlık: x-goog-api-key. Yalnızca altyazı metni ve son birkaç altyazı çifti gönderilir, görüntü gönderilmez.
 */
object GeminiRequest
{
	const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/"
	const val MAX_CONTEXT = 4
	/** Çıktı sınırı. Belgeye göre maxOutputTokens düşünme jetonlarını da sayar; "minimal" ile bu yeterli. */
	const val MAX_OUTPUT_TOKENS = 300

	val SYSTEM_PROMPT = """
		You are a professional video game localizer translating English on-screen subtitles into Turkish.
		Translate ONLY the line given after "LINE:". Output only the Turkish translation: no quotes, notes, explanations or alternatives.
		Write natural, fluent, everyday Turkish as in a well-localized game; match the tone (casual, dramatic, sarcastic, formal) and keep it short enough to read quickly. Do not translate word by word; translate the meaning and idioms.
		Keep character names, place names, brand names and other proper nouns exactly as they are.
		If the line starts with a speaker label such as "NAME:", keep that exact label unchanged at the start and translate only the rest.
		Use the previous lines only as context (speaker, tone, terminology, consistency); never translate or repeat them.
		The text comes from OCR and may contain small recognition errors; silently correct obvious ones.
		If the line is only a name, a number or is not English, return it unchanged.
	""".trimIndent()

	fun url(model: String) = ENDPOINT + URLEncoder.encode(model, "UTF-8") + ":generateContent"

	/**
	 * Modele göre en düşük düşünme ayarı (https://ai.google.dev/gemini-api/docs/generate-content/thinking):
	 * Gemini 3.x Flash-Lite ve 3.6 Flash "minimal" destekler; 3.8 Flash "minimal"i reddeder → "low".
	 * 2.5 Flash/Flash-Lite thinkingLevel desteklemez → thinkingBudget 0 (kapalı). Diğerleri "low".
	 * null = thinkingConfig gönderme.
	 */
	fun thinkingConfigJson(model: String): String?
	{
		val m = model.lowercase()
		return when
		{
			m.startsWith("gemini-2.5-flash") -> "{\"thinkingBudget\":0}"
			m.startsWith("gemini-2.") || m.startsWith("gemini-1.") -> null
			m.contains("flash-lite") -> "{\"thinkingLevel\":\"minimal\"}"
			m.startsWith("gemini-3.6-flash") || m == "gemini-3-flash" || m.startsWith("gemini-3-flash-") -> "{\"thinkingLevel\":\"minimal\"}"
			else -> "{\"thinkingLevel\":\"low\"}"
		}
	}

	fun userPrompt(english: String, context: List<SubtitlePair>): String
	{
		val sb = StringBuilder()
		val ctx = context.takeLast(MAX_CONTEXT)
		if(ctx.isNotEmpty())
		{
			sb.append("Previous subtitles (context only, do not translate):\n")
			for(p in ctx)
				sb.append("EN: ").append(oneLine(p.english)).append("\nTR: ").append(oneLine(p.turkish)).append('\n')
			sb.append('\n')
		}
		sb.append("LINE: ").append(oneLine(english))
		return sb.toString()
	}

	private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()

	/** [withThinking] = false: thinkingConfig gönderilmez (model desteklemediğini söylerse ikinci deneme için). */
	fun body(model: String, english: String, context: List<SubtitlePair>, withThinking: Boolean = true): String
	{
		val sb = StringBuilder(1024)
		sb.append("{\"systemInstruction\":{\"parts\":[{\"text\":")
		MiniJson.quoteTo(sb, SYSTEM_PROMPT)
		sb.append("}]},\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":")
		MiniJson.quoteTo(sb, userPrompt(english, context))
		sb.append("}]}],\"generationConfig\":{")
		sb.append("\"maxOutputTokens\":").append(MAX_OUTPUT_TOKENS)
		sb.append(",\"candidateCount\":1")
		val thinking = if(withThinking) thinkingConfigJson(model) else null
		if(thinking != null)
			sb.append(",\"thinkingConfig\":").append(thinking)
		sb.append("}}")
		return sb.toString()
	}

	sealed class Parsed
	{
		data class Ok(val text: String): Parsed()
		data class Error(val message: String): Parsed()
	}

	/** 200 yanıtı: ilk adayın düşünce olmayan metin parçaları birleştirilir. */
	fun parseResponse(json: String): Parsed
	{
		val root = try { MiniJson.parse(json) as? Map<*, *> } catch(e: MiniJson.ParseException) { null }
			?: return Parsed.Error("geçersiz yanıt")
		(root["error"] as? Map<*, *>)?.let { return Parsed.Error((it["message"] as? String)?.take(160) ?: "hata") }
		val candidates = root["candidates"] as? List<*>
		val first = candidates?.firstOrNull() as? Map<*, *>
		if(first == null)
		{
			val block = (root["promptFeedback"] as? Map<*, *>)?.get("blockReason") as? String
			return Parsed.Error(if(block != null) "engellendi: $block" else "boş yanıt")
		}
		val parts = (first["content"] as? Map<*, *>)?.get("parts") as? List<*>
		val text = parts.orEmpty()
			.mapNotNull { it as? Map<*, *> }
			.filter { it["thought"] != true }
			.mapNotNull { it["text"] as? String }
			.joinToString("")
		val cleaned = cleanOutput(text)
		if(cleaned.isEmpty())
			return Parsed.Error("boş çeviri (${first["finishReason"] ?: "?"})")
		return Parsed.Ok(cleaned)
	}

	/** Hata yanıtından okunur mesaj. */
	fun parseError(code: Int, json: String?): String
	{
		val msg = try
		{
			((MiniJson.parse(json ?: "") as? Map<*, *>)?.get("error") as? Map<*, *>)?.get("message") as? String
		}
		catch(e: MiniJson.ParseException)
		{
			null
		}
		return "HTTP $code" + (msg?.let { ": " + it.take(140) } ?: "")
	}

	/** Modelin bazen eklediği tırnak, "TR:"/"Translation:" öneki, kod çiti ve fazla satırları temizler. */
	fun cleanOutput(raw: String): String
	{
		var s = raw.trim()
		if(s.startsWith("```"))
			s = s.removePrefix("```").substringAfter('\n', s).removeSuffix("```").trim()
		for(prefix in listOf("TR:", "Turkish:", "Translation:", "Türkçe:", "Çeviri:", "LINE:"))
			if(s.startsWith(prefix, ignoreCase = true))
				s = s.substring(prefix.length).trim()
		val quotes = listOf("\"" to "\"", "“" to "”", "«" to "»", "'" to "'")
		for((open, close) in quotes)
			if(s.length >= 2 && s.startsWith(open) && s.endsWith(close))
				s = s.substring(open.length, s.length - close.length).trim()
		// Altyazı tek blok: satır sonları boşluk olur.
		return s.replace(Regex("\\s*\\n\\s*"), " ").trim()
	}
}

/**
 * Gemini REST istemcisi: HttpURLConnection (ek bağımlılık yok). Engelleyici çağrıdır — Dispatchers.IO'da çağrılmalı.
 * Ağ hatası, zaman aşımı veya 4xx/5xx durumunda [GeminiException] fırlatır; çağıran cihaz içi çeviriye döner.
 */
class GeminiClient(
	private val connectTimeoutMs: Int = 2500,
	private val readTimeoutMs: Int = 2500
)
{
	class GeminiException(message: String, val httpCode: Int = 0): IOException(message)

	/** Bu modeller thinkingConfig'i reddettiyse bir daha gönderilmez. */
	private val noThinking = java.util.Collections.synchronizedSet(HashSet<String>())

	fun translate(apiKey: String, model: String, english: String, context: List<SubtitlePair>): String
	{
		val m = TranslationSettings.normalizeModel(model)
		val first = post(apiKey, m, GeminiRequest.body(m, english, context, withThinking = m !in noThinking))
		val response = if(first.first == 400 && m !in noThinking && first.second.contains("thinking", ignoreCase = true))
		{
			noThinking.add(m)
			post(apiKey, m, GeminiRequest.body(m, english, context, withThinking = false))
		}
		else
			first
		if(response.first != 200)
			throw GeminiException(GeminiRequest.parseError(response.first, response.second), response.first)
		return when(val parsed = GeminiRequest.parseResponse(response.second))
		{
			is GeminiRequest.Parsed.Ok -> parsed.text
			is GeminiRequest.Parsed.Error -> throw GeminiException(parsed.message)
		}
	}

	/**
	 * Bağlantıyı önceden ısıtır (TLS el sıkışması + bağlantı havuzu): ilk altyazıda ~300-800 ms kazanılır.
	 * Model bilgisini okur (çeviri kotası harcamaz). Hata yok sayılır.
	 */
	fun warmUp(apiKey: String, model: String)
	{
		try
		{
			val m = TranslationSettings.normalizeModel(model)
			val c = URL(GeminiRequest.ENDPOINT + URLEncoder.encode(m, "UTF-8")).openConnection() as HttpURLConnection
			c.connectTimeout = 4000
			c.readTimeout = 4000
			c.setRequestProperty("x-goog-api-key", apiKey)
			val code = c.responseCode
			(if(code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() }
		}
		catch(e: Exception)
		{
		}
	}

	private fun post(apiKey: String, model: String, body: String): Pair<Int, String>
	{
		val conn = try
		{
			URL(GeminiRequest.url(model)).openConnection() as HttpURLConnection
		}
		catch(e: Exception)
		{
			throw GeminiException("bağlantı: ${e.message}")
		}
		try
		{
			conn.requestMethod = "POST"
			conn.connectTimeout = connectTimeoutMs
			conn.readTimeout = readTimeoutMs
			conn.doOutput = true
			conn.useCaches = false
			conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
			conn.setRequestProperty("x-goog-api-key", apiKey)
			val bytes = body.toByteArray(Charsets.UTF_8)
			conn.setFixedLengthStreamingMode(bytes.size)
			conn.outputStream.use { it.write(bytes) }
			val code = conn.responseCode
			val stream = if(code in 200..299) conn.inputStream else conn.errorStream
			val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
			return code to text
		}
		catch(e: SocketTimeoutException)
		{
			conn.disconnect()
			throw GeminiException("zaman aşımı")
		}
		catch(e: GeminiException)
		{
			throw e
		}
		catch(e: IOException)
		{
			conn.disconnect()
			throw GeminiException("ağ: ${e.javaClass.simpleName}: ${e.message}")
		}
	}
}

/**
 * Gemini'nin art arda hata vermesi (kota 429, ağ yok) durumunda bir süre hiç denememek için
 * (her altyazıda 2,5 sn beklememek için). Tek iş parçacığından (ana) kullanılmalı.
 */
class GeminiBackoff(private val baseMs: Long = 15_000, private val maxMs: Long = 120_000)
{
	private var failures = 0
	private var until = 0L

	fun canTry(nowMs: Long) = nowMs >= until

	fun onSuccess()
	{
		failures = 0
		until = 0L
	}

	/** [httpCode] 400/401/403 (anahtar/model hatası) → daha uzun bekleme. */
	fun onFailure(nowMs: Long, httpCode: Int)
	{
		failures++
		val wait = when
		{
			httpCode == 400 || httpCode == 401 || httpCode == 403 || httpCode == 404 -> maxMs
			httpCode == 429 -> baseMs * 2
			failures < 2 -> 0L // tek bir zaman aşımı: hemen sonraki altyazıda yine dene
			else -> baseMs
		}
		until = nowMs + kotlin.math.min(maxMs, wait * kotlin.math.max(1, failures - 1))
	}

	fun remainingMs(nowMs: Long) = kotlin.math.max(0L, until - nowMs)
}

/**
 * "Önce hızlı çeviri, sonra Gemini/Pro" kararı (saf mantık).
 * Her altyazı gösterimi bir nesil (gen) numarası alır; bir sonuç yalnızca hâlâ güncel nesle aitse gösterilir,
 * ve son (Gemini/Pro) sonuç ekrandayken aynı neslin cihaz içi taslağı onu ezmez.
 * Pro akışının kısmi metni (PRO_PARTIAL) yalnızca ekranda henüz bir şey yokken veya önceki kısmi metnin
 * yerine yazılır: tam bir taslağı yarım cümleyle değiştirmez; geç gelen taslak da kısmi metni ezmez.
 */
class RefineState
{
	enum class Source { MLKIT, GEMINI, CACHE_GEMINI, CACHE_MLKIT, PRO, CACHE_PRO, PRO_PARTIAL }

	var gen = 0
		private set
	/** Bu nesilde ekranda olan sonucun kaynağı (null = henüz yok). */
	var shown: Source? = null
		private set

	fun newGeneration(): Int
	{
		gen++
		shown = null
		return gen
	}

	fun isFinal(s: Source?) = s == Source.GEMINI || s == Source.CACHE_GEMINI || s == Source.PRO || s == Source.CACHE_PRO

	fun isDraft(s: Source?) = s == Source.MLKIT || s == Source.CACHE_MLKIT

	/** true dönerse çağıran sonucu göstermelidir (ve kaynak kaydedilir). */
	fun offer(forGen: Int, source: Source): Boolean
	{
		if(forGen != gen)
			return false
		if(!isFinal(source) && isFinal(shown))
			return false
		if(source == Source.PRO_PARTIAL && isDraft(shown))
			return false
		if(isDraft(source) && shown == Source.PRO_PARTIAL)
			return false
		shown = source
		return true
	}

	/** Akış yarıda kesildi: ekrandaki yarım metin yok sayılır, böylece cihaz içi çeviri yerine yazılabilir. */
	fun dropPartial(forGen: Int)
	{
		if(forGen == gen && shown == Source.PRO_PARTIAL)
			shown = null
	}
}
