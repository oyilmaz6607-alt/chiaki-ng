// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.companion

/**
 * AltyazıTR telefon kumandası: işi yapan taraf (Android'de [CompanionApi]).
 * Her fonksiyon sunucunun bağlantı iş parçacığında çağrılır ve bekleyebilir (ör. eşleştirme).
 */
interface CompanionBackend
{
	fun page(): HttpResponse
	fun state(): HttpResponse
	fun consoles(): HttpResponse
	fun getSettings(): HttpResponse
	fun postSettings(body: String): HttpResponse
	fun captionStart(body: String): HttpResponse
	fun captionStop(): HttpResponse
	fun regist(body: String): HttpResponse
	/** Jetonu doğru bir istek geldi (TV'de "telefon bağlandı" göstermek için). */
	fun onClientSeen() {}
}

/**
 * Yönlendirme ve erişim denetimi. Android'e bağımlı değildir.
 *
 * Erişim kuralları:
 * - İstemci adresi yerel ağda olmalı (bağlantı kabul edilirken [LanAddressFilter] ile, sunucuda).
 * - Host başlığı sayısal IP, "localhost" veya ".local" olmalı (DNS rebinding saldırılarına karşı).
 * - Origin başlığı varsa Host ile aynı olmalı (başka sitelerden gelen POST'lara karşı).
 * - "/" (sayfa iskeleti, gizli bilgi içermez) ve "/api/pair" (kısa kodu jetonla takas eder) dışında
 *   her istek jeton taşımalı: API için X-Token başlığı, diğerleri için ?t=.
 */
class CompanionRouter(
	private val token: CompanionToken,
	private val backend: CompanionBackend,
	private val shortCodeGuard: ShortCodeGuard = ShortCodeGuard()
)
{
	companion object
	{
		const val TOKEN_HEADER = "x-token"
		const val TOKEN_QUERY = "t"

		/** "192.168.1.5:8642", "[fe80::1]:8642", "localhost", "tv.local" → true. */
		fun isAllowedHost(hostHeader: String?): Boolean
		{
			if(hostHeader.isNullOrBlank())
				return false
			val h = hostHeader.trim().lowercase()
			val host: String = if(h.startsWith("["))
			{
				val end = h.indexOf(']')
				if(end < 0)
					return false
				val rest = h.substring(end + 1)
				if(rest.isNotEmpty() && !isPortSuffix(rest))
					return false
				h.substring(1, end)
			}
			else
			{
				val colon = h.indexOf(':')
				if(colon >= 0)
				{
					if(!isPortSuffix(h.substring(colon)))
						return false
					h.substring(0, colon)
				}
				else
					h
			}
			if(host == "localhost")
				return true
			if(host.endsWith(".local") && host.length > 6 && host.all { it.isLetterOrDigit() || it == '-' || it == '.' })
				return true
			return LanAddressFilter.parseNumeric(host) != null
		}

		private fun isPortSuffix(s: String) = s.length in 2..6 && s[0] == ':' && s.substring(1).all { it in '0'..'9' }

		/** Origin yoksa (aynı kaynaklı GET'ler, bazı tarayıcılar) kabul; varsa "http://<Host>" olmalı. */
		fun isAllowedOrigin(origin: String?, hostHeader: String?): Boolean
		{
			if(origin == null)
				return true
			if(hostHeader == null)
				return false
			return origin.trim().equals("http://" + hostHeader.trim(), ignoreCase = true)
		}

		fun unauthorized() = HttpResponse.error(401, "Bağlantı kodu geçersiz veya süresi dolmuş. TV'deki QR kodunu yeniden okutun.")
	}

	fun route(request: HttpRequest): HttpResponse
	{
		if(!isAllowedHost(request.header("host")))
			return HttpResponse.error(403, "Forbidden host")
		if(!isAllowedOrigin(request.header("origin"), request.header("host")))
			return HttpResponse.error(403, "Forbidden origin")

		val path = request.path
		val method = request.method

		// Sayfa iskeleti: jeton gerekmez (sayfa, jeton yoksa kısa kodu sorar).
		if(path == "/" || path == "/index.html")
		{
			if(method != "GET" && method != "HEAD")
				return methodNotAllowed("GET")
			return backend.page()
		}

		if(path == "/api/pair")
		{
			if(method != "POST")
				return methodNotAllowed("POST")
			val code = extractCode(request.bodyText)
			if(shortCodeGuard.locked)
				return HttpResponse.error(429, "Çok fazla yanlış deneme. TV'deki QR kodunu okutun.")
			if(!shortCodeGuard.check(token, code))
				return HttpResponse.error(401, "Kod yanlış. TV ekranındaki 4 haneli kodu girin.")
			backend.onClientSeen()
			return HttpResponse.json(200, "{\"ok\":true,\"token\":${Json.quote(token.token)}}")
		}

		if(!path.startsWith("/api/"))
			return HttpResponse.error(404, "Not found")

		if(!token.matches(request.header(TOKEN_HEADER)))
			return unauthorized()
		backend.onClientSeen()

		return when(path)
		{
			"/api/state" -> if(method == "GET") backend.state() else methodNotAllowed("GET")
			"/api/consoles" -> if(method == "GET") backend.consoles() else methodNotAllowed("GET")
			"/api/settings" -> when(method)
			{
				"GET" -> backend.getSettings()
				"POST" -> requireJson(request) ?: backend.postSettings(request.bodyText)
				else -> methodNotAllowed("GET, POST")
			}
			"/api/caption/start" -> if(method == "POST") requireJson(request) ?: backend.captionStart(request.bodyText) else methodNotAllowed("POST")
			"/api/caption/stop" -> if(method == "POST") backend.captionStop() else methodNotAllowed("POST")
			"/api/regist" -> if(method == "POST") requireJson(request) ?: backend.regist(request.bodyText) else methodNotAllowed("POST")
			else -> HttpResponse.error(404, "Not found")
		}
	}

	/** Gövdeli POST'larda Content-Type JSON olmalı (form gönderimiyle CSRF denemelerine karşı ek önlem). */
	private fun requireJson(request: HttpRequest): HttpResponse?
	{
		if(request.body.isEmpty())
			return null
		val type = request.header("content-type")?.substringBefore(';')?.trim()?.lowercase()
		if(type != "application/json")
			return HttpResponse.error(415, "Content-Type must be application/json")
		return null
	}

	private fun methodNotAllowed(allow: String) =
		HttpResponse(405, HttpResponse.JSON, "{\"ok\":false,\"error\":\"Method not allowed\"}".toByteArray(), mapOf("Allow" to allow))

	/** {"code":"1234"} gövdesinden kodu çıkarır (org.json olmadan, yalnızca rakamlar). */
	private fun extractCode(body: String): String?
	{
		val m = Regex("\"code\"\\s*:\\s*\"?(\\d{1,8})\"?").find(body) ?: return null
		return m.groupValues[1]
	}
}
