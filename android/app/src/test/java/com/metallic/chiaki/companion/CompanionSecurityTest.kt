// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.companion

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.security.SecureRandom

class CompanionSecurityTest
{
	@Test
	fun allowsPrivateIpv4()
	{
		listOf("10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.254", "192.168.1.20", "127.0.0.1", "169.254.10.3")
			.forEach { assertTrue(it, LanAddressFilter.isAllowed(it)) }
	}

	@Test
	fun rejectsPublicIpv4()
	{
		listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.1.1", "11.0.0.1", "100.64.0.1", "0.0.0.0", "255.255.255.255", "1.1.1.1")
			.forEach { assertFalse(it, LanAddressFilter.isAllowed(it)) }
	}

	@Test
	fun ipv6Ranges()
	{
		listOf("::1", "fe80::1", "fe80::1%wlan0", "[fe80::abcd:1]", "febf::1", "fc00::1", "fd12:3456::1", "::ffff:192.168.1.5")
			.forEach { assertTrue(it, LanAddressFilter.isAllowed(it)) }
		listOf("2001:db8::1", "fec0::1", "fe00::1", "ff02::1", "::", "::ffff:8.8.8.8", "2a00:1450::200e")
			.forEach { assertFalse(it, LanAddressFilter.isAllowed(it)) }
	}

	@Test
	fun rejectsInvalidText()
	{
		listOf("", "localhost", "192.168.1", "192.168.1.256", "192.168.01.1x", "1::2::3", "fe80:::1", "example.com", "192.168.1.1.1")
			.forEach { assertFalse(it, LanAddressFilter.isAllowed(it)) }
		assertFalse(LanAddressFilter.isAllowed(null as InetAddress?))
	}

	@Test
	fun inetAddressVariants()
	{
		// Sayısal adresler ad çözümlemesi yapmaz.
		assertTrue(LanAddressFilter.isAllowed(InetAddress.getByName("192.168.0.7")))
		assertTrue(LanAddressFilter.isAllowed(InetAddress.getByName("fe80::7")))
		assertTrue(LanAddressFilter.isAllowed(InetAddress.getByName("127.0.0.1")))
		assertFalse(LanAddressFilter.isAllowed(InetAddress.getByName("93.184.216.34")))
		assertFalse(LanAddressFilter.isAllowed(InetAddress.getByName("2001:4860::8888")))
	}

	@Test
	fun parseNumericIpv6()
	{
		val b = LanAddressFilter.parseNumeric("fe80::1:2")!!
		assertEquals(16, b.size)
		assertEquals(0xfe.toByte(), b[0])
		assertEquals(0x80.toByte(), b[1])
		assertEquals(1.toByte(), b[13])
		assertEquals(2.toByte(), b[15])
		val full = LanAddressFilter.parseNumeric("1:2:3:4:5:6:7:8")!!
		assertEquals(8.toByte(), full[15])
		assertNull(LanAddressFilter.parseNumeric("1:2:3:4:5:6:7:8:9"))
		assertNull(LanAddressFilter.parseNumeric("1:2:3:4:5:6:7::8"))
		assertNull(LanAddressFilter.parseNumeric("12345::"))
	}

	@Test
	fun tokenIsUrlSafeAndRandom()
	{
		val random = SecureRandom()
		val seen = HashSet<String>()
		repeat(200) {
			val t = CompanionToken.generate(random)
			assertEquals(CompanionToken.TOKEN_LENGTH, t.token.length)
			assertTrue(t.token, t.token.all { it.isLetterOrDigit() || it == '-' || it == '_' })
			assertEquals(4, t.shortCode.length)
			assertTrue(t.shortCode.all { it in '0'..'9' })
			seen.add(t.token)
		}
		assertTrue(seen.size > 195)
	}

	@Test
	fun tokenMatching()
	{
		val t = CompanionToken.of("AbCd-_12", "0042")
		assertTrue(t.matches("AbCd-_12"))
		assertTrue(t.matches(" AbCd-_12 "))
		assertFalse(t.matches("abcd-_12"))
		assertFalse(t.matches("AbCd-_1"))
		assertFalse(t.matches(""))
		assertFalse(t.matches(null))
		assertTrue(t.matchesShortCode("0042"))
		assertFalse(t.matchesShortCode("42"))
	}

	@Test
	fun shortCodeGuardLocksAfterFailures()
	{
		val t = CompanionToken.of("AbCdEfGh", "1234")
		val guard = ShortCodeGuard(maxFailures = 3)
		assertTrue(guard.check(t, "1234"))
		assertFalse(guard.check(t, "0000"))
		assertFalse(guard.check(t, "0001"))
		assertFalse(guard.locked)
		assertFalse(guard.check(t, "0002"))
		assertTrue(guard.locked)
		// Kilitliyken doğru kod da kabul edilmez.
		assertFalse(guard.check(t, "1234"))
	}
}
