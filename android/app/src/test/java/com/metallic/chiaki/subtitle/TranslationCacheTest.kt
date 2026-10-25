// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TranslationCacheTest
{
	@Test
	fun keyedByEngineAndNormalizedText()
	{
		val c = TranslationCache()
		c.put("gemini", "Stay close, BOY!", "Yakın dur, evlat!")
		assertEquals("Yakın dur, evlat!", c.get("gemini", "stay  close boy"))
		assertNull(c.get("mlkit", "Stay close, boy!"))
	}

	@Test
	fun evictsLeastRecentlyUsed()
	{
		val c = TranslationCache(maxEntries = 3)
		c.put("e", "one", "1")
		c.put("e", "two", "2")
		c.put("e", "three", "3")
		assertEquals("1", c.get("e", "one")) // "one" artık en yeni
		c.put("e", "four", "4")
		assertNull(c.get("e", "two"))
		assertEquals("1", c.get("e", "one"))
		assertEquals(3, c.size())
	}

	@Test
	fun jsonRoundTripWithEngineFilter()
	{
		val c = TranslationCache()
		c.put("gemini", "He said \"run\"\n", "\"Koş\" dedi")
		c.put("mlkit", "Hello", "Merhaba")
		assertTrue(c.dirty)
		val json = c.toJson(setOf("gemini"))
		assertFalse(c.dirty)
		val d = TranslationCache()
		assertEquals(1, d.loadJson(json))
		assertEquals("\"Koş\" dedi", d.get("gemini", "he said run"))
		assertNull(d.get("mlkit", "Hello"))
		assertEquals(0, d.loadJson("garbage"))
		assertEquals(0, d.loadJson("{\"v\":99,\"e\":[]}"))
	}

	@Test
	fun fileRoundTrip()
	{
		val dir = File(System.getProperty("java.io.tmpdir"), "tc-test-" + System.nanoTime()).also { it.mkdirs() }
		val f = File(dir, "cache.json")
		val c = TranslationCache()
		c.put("gemini", "Loss of awareness", "Bilinç kaybı")
		assertTrue(c.saveTo(f))
		val d = TranslationCache()
		assertEquals(1, d.loadFrom(f))
		assertEquals("Bilinç kaybı", d.get("gemini", "loss of awareness"))
		assertEquals(0, TranslationCache().loadFrom(File(dir, "missing.json")))
		dir.deleteRecursively()
	}

	@Test
	fun miniJsonParsesTypes()
	{
		val v = MiniJson.parse("{\"a\":[1,-2.5,true,false,null,\"x\\u00e7\\n\"],\"b\":{}}") as Map<*, *>
		assertEquals(listOf(1L, -2.5, true, false, null, "xç\n"), v["a"])
		assertEquals(emptyMap<String, Any?>(), v["b"])
		assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", MiniJson.quote("a\"b\\c\n\u0001"))
	}
}
