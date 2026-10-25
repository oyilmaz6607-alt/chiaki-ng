// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

import java.io.File

/**
 * Çeviri önbelleği (Android bağımlılığı yok): motor başına, normalize edilmiş İngilizce metinle anahtarlanır.
 * Bellekte LRU; [toJson]/[loadJson] ile diske yazılır (en fazla [maxEntries] kayıt, en yeniler korunur).
 * Gemini sonuçları diske kaydedilir: aynı cümle (menü, tekrar eden replik, yeniden oynanan sahne) bir daha
 * ağa gitmeden anında gelir. Tüm metotlar senkronize: ana ve IO iş parçacıklarından güvenle çağrılabilir.
 */
class TranslationCache(private val maxEntries: Int = 2000)
{
	private val map = object: LinkedHashMap<String, String>(256, 0.75f, true)
	{
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > maxEntries
	}
	@Volatile var dirty = false
		private set

	companion object
	{
		private const val VERSION = 1
		private const val MAX_TEXT = 500

		fun key(engine: String, english: String) = engine + "|" + SubtitleFilter.normalize(english)
	}

	@Synchronized fun get(engine: String, english: String): String? = map[key(engine, english)]

	@Synchronized fun put(engine: String, english: String, turkish: String)
	{
		val k = key(engine, english)
		if(k.length > MAX_TEXT || turkish.isBlank() || turkish.length > MAX_TEXT)
			return
		if(map.put(k, turkish) != turkish)
			dirty = true
	}

	@Synchronized fun size() = map.size

	@Synchronized fun clear()
	{
		map.clear()
		dirty = true
	}

	/** Yalnızca [engines] motorlarının kayıtları (eskiden yeniye). */
	@Synchronized fun toJson(engines: Set<String>? = null): String
	{
		val sb = StringBuilder(map.size * 80 + 32)
		sb.append("{\"v\":").append(VERSION).append(",\"e\":[")
		var first = true
		for((k, v) in map)
		{
			if(engines != null && k.substringBefore('|') !in engines)
				continue
			if(!first) sb.append(',')
			first = false
			sb.append('[')
			MiniJson.quoteTo(sb, k)
			sb.append(',')
			MiniJson.quoteTo(sb, v)
			sb.append(']')
		}
		sb.append("]}")
		dirty = false
		return sb.toString()
	}

	/** Bozuk/eski dosya sessizce yok sayılır. Yüklenen kayıt sayısı. */
	@Synchronized fun loadJson(json: String): Int
	{
		val root = try { MiniJson.parse(json) as? Map<*, *> } catch(e: MiniJson.ParseException) { null } ?: return 0
		if((root["v"] as? Long)?.toInt() != VERSION)
			return 0
		val entries = root["e"] as? List<*> ?: return 0
		var n = 0
		for(e in entries)
		{
			val pair = e as? List<*> ?: continue
			val k = pair.getOrNull(0) as? String ?: continue
			val v = pair.getOrNull(1) as? String ?: continue
			if(k.length > MAX_TEXT || v.length > MAX_TEXT || !k.contains('|'))
				continue
			map[k] = v
			n++
		}
		return n
	}

	fun loadFrom(file: File): Int =
		try
		{
			if(file.isFile && file.length() < 4L * 1024 * 1024) loadJson(file.readText(Charsets.UTF_8)) else 0
		}
		catch(e: Exception)
		{
			0
		}

	/** Geçici dosyaya yazıp yeniden adlandırır (yarım dosya kalmaz). Engelleyici: IO iş parçacığında çağırın. */
	fun saveTo(file: File, engines: Set<String>? = null): Boolean =
		try
		{
			val tmp = File(file.parentFile, file.name + ".tmp")
			tmp.writeText(toJson(engines), Charsets.UTF_8)
			tmp.renameTo(file) || run { file.delete(); tmp.renameTo(file) }
		}
		catch(e: Exception)
		{
			false
		}
}
