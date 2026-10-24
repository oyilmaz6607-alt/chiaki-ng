// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.companion

/**
 * AltyazıTR telefon kumandası: telefondan gelen eşleştirme formunun doğrulanması ve
 * eşleştirme günlüğünden okunur Türkçe hata mesajı çıkarılması. Android'e bağımlı değildir.
 */
object CompanionRegist
{
	const val PIN_LENGTH = 8
	const val ACCOUNT_ID_SIZE = 8

	enum class Console(val id: String)
	{
		PS5("ps5"),
		PS4_GE_8("ps4_8"),
		PS4_GE_7("ps4_7"),
		PS4_LT_7("ps4_old");

		val isPS5 get() = this == PS5

		companion object
		{
			fun fromId(id: String?) = values().firstOrNull { it.id == id }
		}
	}

	class Input(
		val host: String,
		val console: Console,
		/** PS4 < 7 dışında: 8 baytlık hesap kimliği. */
		val accountId: ByteArray?,
		/** Yalnızca PS4 < 7: PSN kullanıcı adı. */
		val onlineId: String?,
		val pin: Int
	)

	sealed class Parsed
	{
		class Ok(val input: Input): Parsed()
		class Error(val field: String, val message: String): Parsed()
	}

	/** IPv4, IPv6 veya basit makine adı (harf, rakam, '-', '.'), en fazla 253 karakter. */
	fun isValidHost(host: String): Boolean
	{
		if(host.isEmpty() || host.length > 253)
			return false
		if(LanAddressFilter.parseNumeric(host) != null)
			return true
		return host.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '.' } &&
			!host.startsWith('.') && !host.startsWith('-')
	}

	fun parse(host: String?, console: String?, psnId: String?, pin: String?): Parsed
	{
		val h = host?.trim() ?: ""
		if(!isValidHost(h))
			return Parsed.Error("host", "Konsolun IP adresini girin (ör. 192.168.1.20).")
		val c = Console.fromId(console?.trim()) ?: return Parsed.Error("console", "Konsol türünü seçin.")

		val id = psnId?.trim() ?: ""
		var accountId: ByteArray? = null
		var onlineId: String? = null
		if(c == Console.PS4_LT_7)
		{
			if(id.isEmpty() || id.length > 32)
				return Parsed.Error("psnId", "PSN kullanıcı adını girin.")
			onlineId = id
		}
		else
		{
			accountId = decodeAccountId(id)
				?: return Parsed.Error("psnId", "Geçerli bir Account ID girin (Encoded ID, ör. AbCdEfGh1234=).")
		}

		val p = pin?.trim()?.replace(" ", "") ?: ""
		if(p.length != PIN_LENGTH || !p.all { it in '0'..'9' })
			return Parsed.Error("pin", "PIN 8 haneli olmalı.")
		return Parsed.Ok(Input(h, c, accountId, onlineId, p.toInt()))
	}

	/** android.util.Base64.DEFAULT ile aynı davranış: standart alfabe, dolgu isteğe bağlı. URL-safe de kabul edilir. */
	fun decodeAccountId(id: String): ByteArray?
	{
		if(id.isEmpty() || id.length > 24)
			return null
		val bytes = base64Decode(id.trimEnd('=')) ?: return null
		return bytes.takeIf { it.size == ACCOUNT_ID_SIZE }
	}

	/**
	 * Dolgusuz Base64 çözücü (standart ve URL-safe alfabe). java.util.Base64 API 26 gerektirdiği için
	 * (minSdk 24) elle yazıldı. Geçersiz karakter veya uzunlukta null döner.
	 */
	fun base64Decode(s: String): ByteArray?
	{
		if(s.length % 4 == 1)
			return null
		val out = java.io.ByteArrayOutputStream(s.length * 3 / 4)
		var buffer = 0
		var bits = 0
		for(c in s)
		{
			val v = when(c)
			{
				in 'A'..'Z' -> c - 'A'
				in 'a'..'z' -> c - 'a' + 26
				in '0'..'9' -> c - '0' + 52
				'+', '-' -> 62
				'/', '_' -> 63
				else -> return null
			}
			buffer = (buffer shl 6) or v
			bits += 6
			if(bits >= 8)
			{
				bits -= 8
				out.write((buffer shr bits) and 0xff)
			}
		}
		return out.toByteArray()
	}

	/**
	 * Eşleştirme günlüğüne (lib/src/regist.c mesajları) bakarak kullanıcıya gösterilecek Türkçe hata.
	 * Örn. "Regist received HTTP code 403" → PIN süresi dolmuş veya hesap kimliği yanlış.
	 */
	fun failureMessage(log: String): String
	{
		val l = log.lowercase()
		val httpCode = Regex("regist received http code (\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull()
		return when
		{
			l.contains("0x80108b09") || l.contains("invalid pin") ->
				"PIN yanlış veya süresi dolmuş. Konsolda yeni bir PIN alıp tekrar deneyin."
			l.contains("0x80108b02") || l.contains("invalid psn id") ->
				"PSN hesap kimliği (Account ID) yanlış. psn.flipscreen.games'ten Encoded ID'yi yeniden kopyalayın."
			l.contains("0x80108b10") || l.contains("already in use") ->
				"Konsolda Remote Play şu an başka bir cihazda kullanılıyor. O bağlantıyı kapatıp tekrar deneyin."
			l.contains("0x80108b11") || l.contains("rp-version") ->
				"Konsol türü yanlış seçilmiş olabilir (PS5/PS4 sürümü). Doğru seçeneği işaretleyip tekrar deneyin."
			httpCode == 403 ->
				"PIN süresi dolmuş veya hesap kimliği yanlış. Konsolda yeni bir PIN alıp tekrar deneyin."
			httpCode != null ->
				"Konsol eşleştirmeyi reddetti (HTTP $httpCode). Remote Play'in açık olduğundan emin olup tekrar deneyin."
			l.contains("getaddrinfo") ->
				"Konsol adresi çözülemedi. IP adresini kontrol edin."
			l.contains("timed out waiting for search response") || l.contains("search failed") ||
				l.contains("eventually failed to connect") || l.contains("connect failed") ->
				"Konsola ulaşılamadı. IP adresini kontrol edin; konsol açık, Remote Play etkin ve TV ile aynı ağda olmalı."
			else ->
				"Eşleştirme başarısız oldu. IP adresini, Account ID'yi ve PIN'i kontrol edip tekrar deneyin."
		}
	}
}
