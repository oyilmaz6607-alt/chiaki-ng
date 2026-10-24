// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.companion

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * AltyazıTR telefon kumandası: erişim denetimi. Android'e bağımlı değildir.
 *
 * - Yalnızca yerel ağ adreslerinden gelen bağlantılar kabul edilir.
 * - Her sunucu başlangıcında rastgele bir jeton üretilir; sayfa iskeleti dışındaki her istek bu jetonu taşımalıdır.
 */
object LanAddressFilter
{
	/**
	 * 10/8, 172.16/12, 192.168/16, 127/8, 169.254/16 (link-local), ::1, fe80::/10, fc00::/7
	 * ve bunlara karşılık gelen IPv4-mapped IPv6 (::ffff:a.b.c.d) adresleri.
	 */
	fun isAllowed(address: InetAddress?): Boolean
	{
		if(address == null)
			return false
		val bytes = address.address
		return when(address)
		{
			is Inet4Address -> isAllowedV4(bytes)
			is Inet6Address -> isAllowedV6(bytes)
			else -> false
		}
	}

	/** Sayısal IP metni için (ör. "192.168.1.5", "fe80::1%wlan0"). Ad çözümlemesi yapılmaz. */
	fun isAllowed(numericAddress: String): Boolean
	{
		val bytes = parseNumeric(numericAddress) ?: return false
		return when(bytes.size)
		{
			4 -> isAllowedV4(bytes)
			16 -> isAllowedV6(bytes)
			else -> false
		}
	}

	private fun isAllowedV4(b: ByteArray): Boolean
	{
		if(b.size != 4)
			return false
		val a0 = b[0].toInt() and 0xff
		val a1 = b[1].toInt() and 0xff
		return a0 == 10 ||
			a0 == 127 ||
			(a0 == 172 && a1 in 16..31) ||
			(a0 == 192 && a1 == 168) ||
			(a0 == 169 && a1 == 254)
	}

	private fun isAllowedV6(b: ByteArray): Boolean
	{
		if(b.size != 16)
			return false
		// ::1
		if((0 until 15).all { b[it].toInt() == 0 } && b[15].toInt() == 1)
			return true
		// ::ffff:a.b.c.d
		if((0 until 10).all { b[it].toInt() == 0 } && (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff)
			return isAllowedV4(b.copyOfRange(12, 16))
		val b0 = b[0].toInt() and 0xff
		val b1 = b[1].toInt() and 0xff
		// fe80::/10
		if(b0 == 0xfe && (b1 and 0xc0) == 0x80)
			return true
		// fc00::/7
		if((b0 and 0xfe) == 0xfc)
			return true
		return false
	}

	/** Yalnızca sayısal IPv4/IPv6 metnini çözer (DNS sorgusu yapmaz); geçersizse null. */
	fun parseNumeric(text: String): ByteArray?
	{
		var s = text.trim()
		if(s.startsWith("[") && s.endsWith("]"))
			s = s.substring(1, s.length - 1)
		val zone = s.indexOf('%')
		if(zone >= 0)
			s = s.substring(0, zone)
		if(s.isEmpty())
			return null
		return if(s.contains(':')) parseV6(s) else parseV4(s)
	}

	private fun parseV4(s: String): ByteArray?
	{
		val parts = s.split('.')
		if(parts.size != 4)
			return null
		val out = ByteArray(4)
		for((i, p) in parts.withIndex())
		{
			if(p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' })
				return null
			val v = p.toInt()
			if(v > 255)
				return null
			out[i] = v.toByte()
		}
		return out
	}

	private fun parseV6(s: String): ByteArray?
	{
		var str = s
		var tail = ByteArray(0)
		// Sonda gömülü IPv4 (ör. ::ffff:192.168.1.2)
		val lastColon = str.lastIndexOf(':')
		if(str.indexOf('.', lastColon) >= 0)
		{
			tail = parseV4(str.substring(lastColon + 1)) ?: return null
			str = str.substring(0, lastColon + 1) + "0:0"
		}
		val doubleColon = str.indexOf("::")
		if(doubleColon >= 0 && str.indexOf("::", doubleColon + 1) >= 0)
			return null
		fun groups(part: String): List<Int>?
		{
			if(part.isEmpty())
				return emptyList()
			return part.split(':').map { g ->
				if(g.isEmpty() || g.length > 4 || !g.all { Character.digit(it, 16) >= 0 })
					return null
				g.toInt(16)
			}
		}
		val all: List<Int> = if(doubleColon >= 0)
		{
			val head = groups(str.substring(0, doubleColon)) ?: return null
			val rest = groups(str.substring(doubleColon + 2)) ?: return null
			val missing = 8 - head.size - rest.size
			if(missing < 1)
				return null
			head + List(missing) { 0 } + rest
		}
		else
			groups(str) ?: return null
		if(all.size != 8)
			return null
		val out = ByteArray(16)
		for((i, g) in all.withIndex())
		{
			out[i * 2] = (g shr 8).toByte()
			out[i * 2 + 1] = g.toByte()
		}
		if(tail.size == 4)
			System.arraycopy(tail, 0, out, 12, 4)
		return out
	}
}

/**
 * Oturum jetonu ve kısa kod.
 * Jeton: 8 karakter, URL'de kodlama gerektirmeyen alfabe ([A-Za-z0-9_-]), SecureRandom ile (48 bit).
 * Kısa kod: 4 haneli sayı; jeton olmadan açılan sayfada elle girilir ve jetonla takas edilir
 * (deneme sayısı sınırlıdır, bkz. [ShortCodeGuard]).
 */
class CompanionToken private constructor(val token: String, val shortCode: String)
{
	companion object
	{
		const val TOKEN_LENGTH = 8
		const val SHORT_CODE_LENGTH = 4
		private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

		fun generate(random: SecureRandom = SecureRandom()): CompanionToken
		{
			val token = CharArray(TOKEN_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }
			val code = (0 until SHORT_CODE_LENGTH).map { ('0' + random.nextInt(10)) }.joinToString("")
			return CompanionToken(String(token), code)
		}

		/** Testler için. */
		fun of(token: String, shortCode: String) = CompanionToken(token, shortCode)

		private fun constantTimeEquals(a: String?, b: String): Boolean
		{
			if(a == null)
				return false
			return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
		}
	}

	fun matches(candidate: String?): Boolean = constantTimeEquals(candidate?.trim(), token)

	fun matchesShortCode(candidate: String?): Boolean = constantTimeEquals(candidate?.trim(), shortCode)
}

/**
 * Kısa kod kaba kuvvetle denenmesin diye: belirli sayıda yanlış denemeden sonra kod geçersiz olur
 * (QR ile açmak veya sunucuyu yeniden başlatmak gerekir). İş parçacığı güvenlidir.
 */
class ShortCodeGuard(private val maxFailures: Int = 5)
{
	private var failures = 0

	@get:Synchronized
	val locked: Boolean get() = failures >= maxFailures

	/** true: kod doğru ve kilit yok. */
	@Synchronized
	fun check(token: CompanionToken, candidate: String?): Boolean
	{
		if(failures >= maxFailures)
			return false
		if(token.matchesShortCode(candidate))
			return true
		failures++
		return false
	}
}
