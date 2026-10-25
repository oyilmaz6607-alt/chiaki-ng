// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

/**
 * Küçük JSON okuyucu/yazıcı (Android bağımlılığı yok; org.json birim testlerinde kullanılamadığı için).
 * Nesne → LinkedHashMap<String, Any?>, dizi → List<Any?>, sayı → Long veya Double, true/false, null, String.
 * Yalnızca Gemini yanıtı ve çeviri önbelleği gibi küçük belgeler içindir.
 */
object MiniJson
{
	class ParseException(message: String): Exception(message)

	fun parse(text: String): Any?
	{
		val p = Parser(text)
		p.skipWs()
		val v = p.value(0)
		p.skipWs()
		if(p.i != text.length)
			throw ParseException("trailing data at ${p.i}")
		return v
	}

	/** JSON dizgesi olarak tırnaklı ve kaçışlı yazar. */
	fun quote(s: String): String
	{
		val sb = StringBuilder(s.length + 2)
		quoteTo(sb, s)
		return sb.toString()
	}

	fun quoteTo(sb: StringBuilder, s: String)
	{
		sb.append('"')
		for(c in s)
		{
			when(c)
			{
				'"' -> sb.append("\\\"")
				'\\' -> sb.append("\\\\")
				'\n' -> sb.append("\\n")
				'\r' -> sb.append("\\r")
				'\t' -> sb.append("\\t")
				'\b' -> sb.append("\\b")
				'\u000C' -> sb.append("\\f")
				else ->
				{
					if(c < ' ' || c == ' ' || c == ' ')
						sb.append("\\u").append(String.format("%04x", c.code))
					else
						sb.append(c)
				}
			}
		}
		sb.append('"')
	}

	private class Parser(val s: String)
	{
		var i = 0

		fun skipWs()
		{
			while(i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t'))
				i++
		}

		fun value(depth: Int): Any?
		{
			if(depth > 64)
				throw ParseException("too deep")
			if(i >= s.length)
				throw ParseException("unexpected end")
			return when(val c = s[i])
			{
				'{' -> obj(depth)
				'[' -> arr(depth)
				'"' -> str()
				't' -> literal("true", true)
				'f' -> literal("false", false)
				'n' -> literal("null", null)
				else -> if(c == '-' || c in '0'..'9') num() else throw ParseException("unexpected '$c' at $i")
			}
		}

		private fun literal(word: String, v: Any?): Any?
		{
			if(!s.startsWith(word, i))
				throw ParseException("bad literal at $i")
			i += word.length
			return v
		}

		private fun obj(depth: Int): Map<String, Any?>
		{
			val m = LinkedHashMap<String, Any?>()
			i++
			skipWs()
			if(i < s.length && s[i] == '}')
			{
				i++
				return m
			}
			while(true)
			{
				skipWs()
				if(i >= s.length || s[i] != '"')
					throw ParseException("expected key at $i")
				val k = str()
				skipWs()
				expect(':')
				skipWs()
				m[k] = value(depth + 1)
				skipWs()
				if(i < s.length && s[i] == ',')
				{
					i++
					continue
				}
				expect('}')
				return m
			}
		}

		private fun arr(depth: Int): List<Any?>
		{
			val l = ArrayList<Any?>()
			i++
			skipWs()
			if(i < s.length && s[i] == ']')
			{
				i++
				return l
			}
			while(true)
			{
				skipWs()
				l.add(value(depth + 1))
				skipWs()
				if(i < s.length && s[i] == ',')
				{
					i++
					continue
				}
				expect(']')
				return l
			}
		}

		private fun expect(c: Char)
		{
			if(i >= s.length || s[i] != c)
				throw ParseException("expected '$c' at $i")
			i++
		}

		private fun str(): String
		{
			i++
			val sb = StringBuilder()
			while(true)
			{
				if(i >= s.length)
					throw ParseException("unterminated string")
				val c = s[i++]
				when(c)
				{
					'"' -> return sb.toString()
					'\\' ->
					{
						if(i >= s.length)
							throw ParseException("bad escape")
						when(val e = s[i++])
						{
							'"' -> sb.append('"')
							'\\' -> sb.append('\\')
							'/' -> sb.append('/')
							'b' -> sb.append('\b')
							'f' -> sb.append('\u000C')
							'n' -> sb.append('\n')
							'r' -> sb.append('\r')
							't' -> sb.append('\t')
							'u' ->
							{
								if(i + 4 > s.length)
									throw ParseException("bad unicode escape")
								sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: throw ParseException("bad unicode escape"))
								i += 4
							}
							else -> throw ParseException("bad escape '$e'")
						}
					}
					else -> sb.append(c)
				}
			}
		}

		private fun num(): Any
		{
			val start = i
			if(s[i] == '-')
				i++
			while(i < s.length && (s[i] in '0'..'9' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-'))
				i++
			val t = s.substring(start, i)
			if(t.none { it == '.' || it == 'e' || it == 'E' })
				t.toLongOrNull()?.let { return it }
			return t.toDoubleOrNull() ?: throw ParseException("bad number '$t'")
		}
	}
}
