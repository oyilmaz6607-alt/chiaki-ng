// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.companion

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * AltyazıTR telefon kumandası: tek bir HTTP/1.1 isteğini akıştan okur.
 * Sınırlar: istek satırı + başlıklar en fazla [maxHeaderBytes], gövde en fazla [maxBodyBytes].
 * Chunked gövde desteklenmez (tarayıcılar fetch ile JSON gönderirken Content-Length kullanır).
 * Android'e bağımlı değildir.
 */
class HttpParser(
	private val maxHeaderBytes: Int = MAX_HEADER_BYTES,
	private val maxBodyBytes: Int = MAX_BODY_BYTES
)
{
	companion object
	{
		const val MAX_HEADER_BYTES = 16 * 1024
		const val MAX_BODY_BYTES = 64 * 1024
		const val MAX_URI_LENGTH = 2048
		const val MAX_HEADER_COUNT = 64
		private val METHODS = setOf("GET", "POST", "HEAD", "OPTIONS", "PUT", "DELETE", "PATCH")

		/** "%41b+c" → "Ab+c" ('+' yolda boşluk değildir). Geçersiz kodlama → HttpException(400). */
		fun percentDecode(s: String, plusAsSpace: Boolean): String
		{
			if(s.indexOf('%') < 0 && !(plusAsSpace && s.indexOf('+') >= 0))
				return s
			val out = ByteArrayOutputStream(s.length)
			var i = 0
			while(i < s.length)
			{
				val c = s[i]
				when
				{
					c == '%' ->
					{
						if(i + 2 >= s.length)
							throw HttpException(400, "Bad percent-encoding")
						val hi = Character.digit(s[i + 1], 16)
						val lo = Character.digit(s[i + 2], 16)
						if(hi < 0 || lo < 0)
							throw HttpException(400, "Bad percent-encoding")
						out.write(hi * 16 + lo)
						i += 3
						continue
					}
					c == '+' && plusAsSpace -> out.write(' '.code)
					else ->
					{
						val bytes = c.toString().toByteArray(Charsets.UTF_8)
						out.write(bytes, 0, bytes.size)
					}
				}
				i++
			}
			return String(out.toByteArray(), Charsets.UTF_8)
		}

		fun parseQuery(query: String?): Map<String, String>
		{
			if(query.isNullOrEmpty())
				return emptyMap()
			val result = LinkedHashMap<String, String>()
			for(part in query.split('&'))
			{
				if(part.isEmpty())
					continue
				val eq = part.indexOf('=')
				val key = percentDecode(if(eq >= 0) part.substring(0, eq) else part, true)
				val value = if(eq >= 0) percentDecode(part.substring(eq + 1), true) else ""
				// İlk değer geçerli; tekrar eden anahtarlar yok sayılır.
				result.putIfAbsent(key, value)
			}
			return result
		}
	}

	/**
	 * Bağlantı istek göndermeden kapandıysa null döner.
	 * Hatalı istekte uygun durum koduyla [HttpException] fırlatır.
	 */
	fun parse(input: InputStream, remoteAddress: String? = null): HttpRequest?
	{
		val head = readHead(input) ?: return null
		val lines = head.split("\r\n")
		val requestLine = lines.first()
		val parts = requestLine.split(' ')
		if(parts.size != 3)
			throw HttpException(400, "Bad request line")
		val (method, target, version) = parts
		if(method !in METHODS)
			throw HttpException(501, "Method not implemented")
		if(version != "HTTP/1.1" && version != "HTTP/1.0")
			throw HttpException(505, "HTTP version not supported")
		if(target.length > MAX_URI_LENGTH)
			throw HttpException(414, "URI too long")
		if(!target.startsWith("/"))
			throw HttpException(400, "Bad request target")

		val headers = LinkedHashMap<String, String>()
		for(line in lines.drop(1))
		{
			if(line.isEmpty())
				continue
			if(line[0] == ' ' || line[0] == '\t')
				throw HttpException(400, "Obsolete header folding")
			val colon = line.indexOf(':')
			if(colon <= 0)
				throw HttpException(400, "Bad header")
			val name = line.substring(0, colon)
			if(name.any { it == ' ' || it == '\t' })
				throw HttpException(400, "Bad header name")
			val key = name.lowercase()
			val value = line.substring(colon + 1).trim()
			if(key == "content-length" && headers.containsKey(key) && headers[key] != value)
				throw HttpException(400, "Conflicting Content-Length")
			headers[key] = value
			if(headers.size > MAX_HEADER_COUNT)
				throw HttpException(431, "Too many headers")
		}

		if(headers.containsKey("transfer-encoding"))
			throw HttpException(411, "Chunked bodies are not supported")

		val contentLength = headers["content-length"]?.let {
			if(it.isEmpty() || !it.all { c -> c in '0'..'9' } || it.length > 9)
				throw HttpException(400, "Bad Content-Length")
			it.toInt()
		} ?: 0
		if(contentLength > maxBodyBytes)
			throw HttpException(413, "Body too large")
		val body = readExactly(input, contentLength)

		val q = target.indexOf('?')
		val rawPath = if(q >= 0) target.substring(0, q) else target
		val rawQuery = if(q >= 0) target.substring(q + 1).substringBefore('#') else null
		val path = percentDecode(rawPath.substringBefore('#'), false)
		if(path.contains('\u0000'))
			throw HttpException(400, "Bad path")
		return HttpRequest(method, path, parseQuery(rawQuery), headers, body, remoteAddress)
	}

	/** "\r\n\r\n" görülene kadar okur (sonlandırıcı hariç ISO-8859-1 metin döner). Yalnızca "\n" ayırıcı da kabul edilir. */
	private fun readHead(input: InputStream): String?
	{
		val buf = ByteArrayOutputStream(1024)
		var state = 0
		var total = 0
		while(true)
		{
			val b = input.read()
			if(b < 0)
			{
				if(total == 0)
					return null
				throw HttpException(400, "Unexpected end of request")
			}
			total++
			if(total > maxHeaderBytes)
				throw HttpException(431, "Header too large")
			// Baştaki boş satırlar (RFC 7230 3.5) atlanır.
			if(buf.size() == 0 && (b == '\r'.code || b == '\n'.code))
				continue
			buf.write(b)
			state = when
			{
				b == '\n'.code && (state == 1 || state == 3) -> state + 1 // \r\n veya \r\n\r\n
				b == '\n'.code && state == 0 -> 2 // tek \n
				b == '\n'.code && state == 2 -> 4 // \n\n
				b == '\r'.code && (state == 0 || state == 2) -> state + 1
				else -> 0
			}
			if(state == 4)
				break
		}
		return String(buf.toByteArray(), Charsets.ISO_8859_1)
			.replace("\r\n", "\n")
			.trimEnd('\n')
			.split('\n')
			.joinToString("\r\n")
	}

	private fun readExactly(input: InputStream, length: Int): ByteArray
	{
		val body = ByteArray(length)
		var read = 0
		while(read < length)
		{
			val n = input.read(body, read, length - read)
			if(n < 0)
				throw HttpException(400, "Body shorter than Content-Length")
			read += n
		}
		return body
	}
}
