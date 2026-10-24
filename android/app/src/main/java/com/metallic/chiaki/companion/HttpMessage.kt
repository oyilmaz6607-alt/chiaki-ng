// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.companion

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * AltyazıTR telefon kumandası: küçük HTTP/1.1 sunucusunun istek/yanıt modelleri.
 * Android'e bağımlı değildir (birim testleriyle doğrulanır).
 */
class HttpException(val status: Int, message: String): Exception(message)

class HttpRequest(
	val method: String,
	/** Yüzde kodlaması çözülmüş yol, sorgu dizesi olmadan (ör. "/api/state"). */
	val path: String,
	val query: Map<String, String>,
	/** Başlık adları küçük harfle. */
	val headers: Map<String, String>,
	val body: ByteArray,
	val remoteAddress: String? = null
)
{
	fun header(name: String): String? = headers[name.lowercase()]
	val bodyText: String get() = String(body, Charsets.UTF_8)
}

class HttpResponse(
	val status: Int,
	val contentType: String,
	val body: ByteArray,
	val extraHeaders: Map<String, String> = emptyMap()
)
{
	companion object
	{
		const val JSON = "application/json; charset=utf-8"
		const val HTML = "text/html; charset=utf-8"
		const val TEXT = "text/plain; charset=utf-8"

		fun json(status: Int, json: String) = HttpResponse(status, JSON, json.toByteArray(Charsets.UTF_8))
		fun text(status: Int, text: String) = HttpResponse(status, TEXT, text.toByteArray(Charsets.UTF_8))
		fun html(status: Int, html: String, extraHeaders: Map<String, String> = emptyMap()) =
			HttpResponse(status, HTML, html.toByteArray(Charsets.UTF_8), extraHeaders)

		/** {"ok":false,"error":"..."} */
		fun error(status: Int, message: String) =
			json(status, "{\"ok\":false,\"error\":${Json.quote(message)}}")

		fun empty(status: Int) = HttpResponse(status, TEXT, ByteArray(0))

		/** Her yanıta eklenen güvenlik başlıkları. */
		val SECURITY_HEADERS = linkedMapOf(
			"Cache-Control" to "no-store",
			"X-Content-Type-Options" to "nosniff",
			"X-Frame-Options" to "DENY",
			// Sayfadaki dış bağlantılara (ör. psn.flipscreen.games) jetonlu adres sızmasın.
			"Referrer-Policy" to "no-referrer"
		)
	}

	fun serialize(): ByteArray
	{
		val head = StringBuilder()
		head.append("HTTP/1.1 ").append(status).append(' ').append(HttpStatus.reason(status)).append("\r\n")
		head.append("Content-Type: ").append(contentType).append("\r\n")
		head.append("Content-Length: ").append(body.size).append("\r\n")
		head.append("Connection: close\r\n")
		val headers = LinkedHashMap(SECURITY_HEADERS)
		headers.putAll(extraHeaders)
		for((name, value) in headers)
		{
			// Başlık enjeksiyonuna karşı: CR/LF içeren başlıklar yazılmaz.
			if(name.any { it == '\r' || it == '\n' || it == ':' } || value.any { it == '\r' || it == '\n' })
				continue
			head.append(name).append(": ").append(value).append("\r\n")
		}
		head.append("\r\n")
		val out = ByteArrayOutputStream(head.length + body.size)
		out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
		out.write(body)
		return out.toByteArray()
	}

	fun writeTo(out: OutputStream)
	{
		out.write(serialize())
		out.flush()
	}
}

object HttpStatus
{
	fun reason(status: Int): String = when(status)
	{
		200 -> "OK"
		202 -> "Accepted"
		204 -> "No Content"
		400 -> "Bad Request"
		401 -> "Unauthorized"
		403 -> "Forbidden"
		404 -> "Not Found"
		405 -> "Method Not Allowed"
		408 -> "Request Timeout"
		409 -> "Conflict"
		411 -> "Length Required"
		413 -> "Payload Too Large"
		414 -> "URI Too Long"
		415 -> "Unsupported Media Type"
		429 -> "Too Many Requests"
		431 -> "Request Header Fields Too Large"
		500 -> "Internal Server Error"
		501 -> "Not Implemented"
		503 -> "Service Unavailable"
		505 -> "HTTP Version Not Supported"
		else -> "Status"
	}
}

/** org.json'a bağımlı olmayan küçük JSON yardımcıları (saf kod ve testler için). */
object Json
{
	fun quote(s: String): String
	{
		val sb = StringBuilder(s.length + 2)
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
				// '<' kaçırılır: JSON hiçbir koşulda HTML olarak yorumlanamasın.
				'<' -> sb.append("\\u003c")
				' ' -> sb.append("\\u2028")
				' ' -> sb.append("\\u2029")
				else ->
					if(c < ' ')
						sb.append(String.format("\\u%04x", c.code))
					else
						sb.append(c)
			}
		}
		sb.append('"')
		return sb.toString()
	}
}
