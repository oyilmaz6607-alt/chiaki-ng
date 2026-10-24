// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.companion

import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * AltyazıTR telefon kumandası: bağımlılıksız, küçük HTTP/1.1 sunucusu. Android'e bağımlı değildir.
 *
 * - Kabul iş parçacığı + küçük, sınırlı iş parçacığı havuzu (bağlantı başına bir iş; havuz doluysa 503).
 * - Her bağlantıda tek istek (Connection: close), okuma zaman aşımı, en fazla 64 KB gövde.
 * - Yerel ağ dışındaki istemciler 403 ile reddedilir.
 * - Tüm iş parçacıkları daemon'dır: sunucu uygulamayı tek başına ayakta tutmaz.
 */
class CompanionHttpServer(
	private val handler: (HttpRequest) -> HttpResponse,
	private val bindAddress: InetAddress? = null,
	private val readTimeoutMs: Int = 10_000,
	private val maxWorkers: Int = 4,
	private val maxQueued: Int = 8,
	private val addressFilter: (InetAddress?) -> Boolean = LanAddressFilter::isAllowed,
	private val log: (String, Throwable?) -> Unit = { _, _ -> }
)
{
	companion object
	{
		const val DEFAULT_PORT = 8642
		const val LAST_PORT = 8650
	}

	@Volatile private var serverSocket: ServerSocket? = null
	@Volatile private var executor: ThreadPoolExecutor? = null
	private var acceptThread: Thread? = null

	val port: Int get() = serverSocket?.localPort ?: -1
	val isRunning: Boolean get() = serverSocket?.isClosed == false

	/**
	 * [firstPort]'tan [lastPort]'a kadar ilk boş bağlantı noktasında dinlemeye başlar.
	 * Hiçbiri boş değilse IOException.
	 */
	@Synchronized
	fun start(firstPort: Int = DEFAULT_PORT, lastPort: Int = LAST_PORT): Int
	{
		serverSocket?.let { if(!it.isClosed) return it.localPort }
		var lastError: IOException? = null
		var socket: ServerSocket? = null
		for(p in firstPort..lastPort)
		{
			val s = ServerSocket()
			try
			{
				s.reuseAddress = true
				s.bind(InetSocketAddress(bindAddress, p), 16)
				socket = s
				break
			}
			catch(e: IOException)
			{
				lastError = e
				try { s.close() } catch(_: IOException) {}
			}
		}
		if(socket == null)
			throw lastError ?: IOException("No free port")

		val counter = AtomicInteger()
		val factory = ThreadFactory { r ->
			Thread(r, "companion-http-${counter.incrementAndGet()}").also { it.isDaemon = true }
		}
		val pool = ThreadPoolExecutor(1, maxWorkers, 30, TimeUnit.SECONDS, ArrayBlockingQueue(maxQueued), factory)
		pool.allowCoreThreadTimeOut(true)
		executor = pool
		serverSocket = socket
		val thread = Thread({ acceptLoop(socket, pool) }, "companion-accept").also { it.isDaemon = true }
		acceptThread = thread
		thread.start()
		return socket.localPort
	}

	@Synchronized
	fun stop()
	{
		val socket = serverSocket
		serverSocket = null
		try { socket?.close() } catch(_: IOException) {}
		executor?.shutdownNow()
		executor = null
		acceptThread = null
	}

	private fun acceptLoop(socket: ServerSocket, pool: ThreadPoolExecutor)
	{
		while(!socket.isClosed)
		{
			val client = try
			{
				socket.accept()
			}
			catch(e: SocketException)
			{
				break // stop() ile kapatıldı
			}
			catch(e: IOException)
			{
				log("accept failed", e)
				continue
			}
			if(!addressFilter(client.inetAddress))
			{
				reject(client, HttpResponse.error(403, "Yalnızca aynı yerel ağdaki cihazlar bağlanabilir."))
				continue
			}
			try
			{
				pool.execute { handle(client) }
			}
			catch(e: RejectedExecutionException)
			{
				reject(client, HttpResponse.error(503, "Sunucu meşgul, tekrar deneyin."))
			}
		}
	}

	/** Kabul iş parçacığında, okumadan kısa bir yanıt gönderip kapatır. */
	private fun reject(client: Socket, response: HttpResponse)
	{
		try
		{
			client.soTimeout = 2000
			client.getOutputStream().let { response.writeTo(it) }
		}
		catch(_: IOException) {}
		finally
		{
			closeQuietly(client)
		}
	}

	private fun handle(client: Socket)
	{
		try
		{
			client.soTimeout = readTimeoutMs
			client.tcpNoDelay = true
			val input = BufferedInputStream(client.getInputStream(), 8192)
			val output = client.getOutputStream()
			val response = try
			{
				val request = HttpParser().parse(input, client.inetAddress?.hostAddress) ?: return
				val r = try
				{
					handler(request)
				}
				catch(e: HttpException)
				{
					HttpResponse.error(e.status, e.message ?: "Error")
				}
				catch(e: Exception)
				{
					log("handler failed", e)
					HttpResponse.error(500, "Beklenmeyen bir hata oluştu.")
				}
				if(request.method == "HEAD") HttpResponse(r.status, r.contentType, ByteArray(0), r.extraHeaders) else r
			}
			catch(e: HttpException)
			{
				HttpResponse.error(e.status, e.message ?: "Bad request")
			}
			catch(e: SocketTimeoutException)
			{
				HttpResponse.error(408, "Request timeout")
			}
			response.writeTo(output)
			try { client.shutdownOutput() } catch(_: IOException) {}
		}
		catch(e: IOException)
		{
			// İstemci bağlantıyı kopardı; önemli değil.
		}
		finally
		{
			closeQuietly(client)
		}
	}

	private fun closeQuietly(socket: Socket)
	{
		try { socket.close() } catch(_: IOException) {}
	}
}
