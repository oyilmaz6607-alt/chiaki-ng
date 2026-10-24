// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionRegistTest
{
	@Test
	fun base64MatchesStandardDecoder()
	{
		val samples = listOf("", "AA", "AAE", "AAEC", "AAECAwQFBgc", "AAECAwQFBgc=", "_-_-", "+/+/")
		for(s in samples)
		{
			val expected = java.util.Base64.getDecoder().decode(
				s.trimEnd('=').replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) })
			assertTrue(s, expected.contentEquals(CompanionRegist.base64Decode(s.trimEnd('='))!!))
		}
		assertNull(CompanionRegist.base64Decode("A"))
		assertNull(CompanionRegist.base64Decode("AA*A"))
	}

	@Test
	fun accountIdMustBeEightBytes()
	{
		// 8 bayt → 12 karakter (bir '=' dolgusuyla)
		val id = CompanionRegist.decodeAccountId("AAECAwQFBgc=")!!
		assertTrue(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7).contentEquals(id))
		assertNotNull(CompanionRegist.decodeAccountId("AAECAwQFBgc"))
		assertNull(CompanionRegist.decodeAccountId("AAECAwQFBg=="))  // 7 bayt
		assertNull(CompanionRegist.decodeAccountId("AAECAwQFBgcI"))  // 9 bayt
		assertNull(CompanionRegist.decodeAccountId("not base64!"))
		assertNull(CompanionRegist.decodeAccountId(""))
	}

	@Test
	fun parsesValidForm()
	{
		val p = CompanionRegist.parse(" 192.168.1.20 ", "ps5", "AAECAwQFBgc=", "0123 4567")
		assertTrue(p is CompanionRegist.Parsed.Ok)
		val input = (p as CompanionRegist.Parsed.Ok).input
		assertEquals("192.168.1.20", input.host)
		assertEquals(CompanionRegist.Console.PS5, input.console)
		assertEquals(1234567, input.pin)
		assertNull(input.onlineId)

		val legacy = CompanionRegist.parse("ps4.local", "ps4_old", "my_psn", "11112222") as CompanionRegist.Parsed.Ok
		assertEquals("my_psn", legacy.input.onlineId)
		assertNull(legacy.input.accountId)
	}

	@Test
	fun reportsFieldErrors()
	{
		fun field(host: String?, console: String?, psn: String?, pin: String?) =
			(CompanionRegist.parse(host, console, psn, pin) as CompanionRegist.Parsed.Error).field
		assertEquals("host", field("", "ps5", "AAECAwQFBgc=", "12345678"))
		assertEquals("host", field("bad host", "ps5", "AAECAwQFBgc=", "12345678"))
		assertEquals("host", field("192.168.1.20/x", "ps5", "AAECAwQFBgc=", "12345678"))
		assertEquals("console", field("192.168.1.20", "xbox", "AAECAwQFBgc=", "12345678"))
		assertEquals("psnId", field("192.168.1.20", "ps5", "short", "12345678"))
		assertEquals("psnId", field("192.168.1.20", "ps4_old", "", "12345678"))
		assertEquals("pin", field("192.168.1.20", "ps5", "AAECAwQFBgc=", "1234567"))
		assertEquals("pin", field("192.168.1.20", "ps5", "AAECAwQFBgc=", "12345678a"))
		assertEquals("pin", field("192.168.1.20", "ps5", "AAECAwQFBgc=", null))
	}

	@Test
	fun failureMessages()
	{
		assertTrue(CompanionRegist.failureMessage("[E] Regist received HTTP code 403\n").contains("PIN süresi dolmuş veya hesap kimliği yanlış"))
		assertTrue(CompanionRegist.failureMessage("[E] Regist received HTTP code 403\n[E] Reported Application Reason: 0x80108b09 (Regist failed, probably invalid PIN)")
			.startsWith("PIN yanlış"))
		assertTrue(CompanionRegist.failureMessage("[E] Reported Application Reason: 0x80108b02 (Invalid PSN ID)").contains("Account ID"))
		assertTrue(CompanionRegist.failureMessage("[E] Regist received HTTP code 500").contains("HTTP 500"))
		assertTrue(CompanionRegist.failureMessage("[E] Regist timed out waiting for search response").contains("ulaşılamadı"))
		assertTrue(CompanionRegist.failureMessage("").startsWith("Eşleştirme başarısız"))
	}
}
