// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.companion

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors

/**
 * AltyazıTR telefon kumandası: yerel ağdaki telefona hizmet veren sunucunun yaşam döngüsü.
 *
 * Sunucu, ihtiyaç duyan en az bir "tutucu" olduğu sürece çalışır (referans sayımı):
 * - [HOLDER_HOME]: ana ekran görünürken (MainActivity onStart/onStop)
 * - [HOLDER_CAPTION]: altyazı servisi çalışırken (CaptionService)
 * Son tutucu bıraktığında kısa bir bekleme süresinden sonra durur (ekranlar arası geçişlerde
 * jeton değişip telefonun bağlantısı kopmasın diye). Sunucunun iş parçacıkları daemon'dır ve
 * hiçbir servis/bildirim başlatmaz: uygulamayı tek başına ayakta tutmaz.
 *
 * [acquire]/[release] yalnızca ana iş parçacığından çağrılmalıdır.
 */
object CompanionServer
{
	private const val TAG = "CompanionServer"

	const val HOLDER_HOME = "home"
	const val HOLDER_CAPTION = "caption"

	private const val STOP_GRACE_MS = 60_000L
	/** Bu süre içinde jetonlu istek geldiyse "telefon bağlı" sayılır (sayfa birkaç saniyede bir yoklar). */
	private const val CLIENT_ACTIVE_MS = 15_000L

	data class Info(val port: Int, val token: String, val shortCode: String)

	private val mainHandler = Handler(Looper.getMainLooper())
	private val worker = Executors.newSingleThreadExecutor { r ->
		Thread(r, "companion-lifecycle").also { it.isDaemon = true }
	}

	// ---- ana iş parçacığı
	private val holders = HashSet<String>()
	private val listeners = LinkedHashSet<() -> Unit>()

	// ---- worker iş parçacığı (okumalar her yerden)
	@Volatile private var wanted = false
	@Volatile private var server: CompanionHttpServer? = null
	@Volatile private var api: CompanionApi? = null
	@Volatile var info: Info? = null
		private set
	/** Son başlatma hatası (ör. tüm bağlantı noktaları dolu); başarılı başlangıçta temizlenir. */
	@Volatile var lastError: String? = null
		private set
	@Volatile private var lastClientAt = 0L

	private val stopRunnable = Runnable {
		if(holders.isEmpty())
		{
			wanted = false
			sync()
		}
	}

	fun acquire(context: Context, holder: String)
	{
		val app = context.applicationContext
		mainHandler.removeCallbacks(stopRunnable)
		holders.add(holder)
		if(!wanted || server == null)
		{
			wanted = true
			sync(app)
		}
	}

	fun release(holder: String)
	{
		holders.remove(holder)
		if(holders.isEmpty())
		{
			mainHandler.removeCallbacks(stopRunnable)
			mainHandler.postDelayed(stopRunnable, STOP_GRACE_MS)
		}
	}

	/** Bilgi değişince (başladı/durdu/telefon bağlandı) ana iş parçacığında çağrılır. */
	fun addListener(listener: () -> Unit)
	{
		listeners.add(listener)
	}

	fun removeListener(listener: () -> Unit)
	{
		listeners.remove(listener)
	}

	val isClientActive: Boolean
		get() = lastClientAt != 0L && SystemClock.elapsedRealtime() - lastClientAt < CLIENT_ACTIVE_MS

	internal fun onClientSeen()
	{
		val wasActive = isClientActive
		lastClientAt = SystemClock.elapsedRealtime()
		if(!wasActive)
			notifyListeners()
	}

	/** "http://192.168.1.20:8642/?t=XXXXXXXX" — TV ağa bağlı değilse null. */
	fun url(withToken: Boolean): String?
	{
		val i = info ?: return null
		val ip = lanAddress() ?: return null
		return if(withToken) "http://$ip:${i.port}/?t=${i.token}" else "http://$ip:${i.port}"
	}

	private fun sync(context: Context? = null)
	{
		worker.execute {
			try
			{
				if(wanted && server == null)
					startServer(context ?: return@execute)
				else if(!wanted && server != null)
					stopServer()
			}
			catch(e: Exception)
			{
				Log.e(TAG, "sync failed", e)
			}
			notifyListeners()
		}
	}

	private fun startServer(context: Context)
	{
		val token = CompanionToken.generate()
		val backend = CompanionApi(context, this::onClientSeen)
		val router = CompanionRouter(token, backend)
		val http = CompanionHttpServer(router::route, log = { msg, e -> Log.w(TAG, msg, e) })
		try
		{
			val port = http.start()
			server = http
			api = backend
			info = Info(port, token.token, token.shortCode)
			lastError = null
			lastClientAt = 0L
			Log.i(TAG, "Companion server listening on port $port")
		}
		catch(e: Exception)
		{
			Log.e(TAG, "Companion server could not start", e)
			lastError = e.message ?: e.javaClass.simpleName
			backend.dispose()
			// Tutucular hâlâ istiyorsa bir sonraki acquire yeniden dener.
			wanted = false
		}
	}

	private fun stopServer()
	{
		server?.stop()
		server = null
		api?.dispose()
		api = null
		info = null
		lastClientAt = 0L
		Log.i(TAG, "Companion server stopped")
	}

	private fun notifyListeners()
	{
		mainHandler.post { listeners.toList().forEach { it() } }
	}

	@Volatile private var cachedAddress: String? = null
	@Volatile private var cachedAddressAt = 0L
	private const val ADDRESS_CACHE_MS = 5_000L

	/** [findLanAddress] sonucunu birkaç saniye önbellekte tutar (ana ekran sık sık sorar). */
	fun lanAddress(): String?
	{
		val now = SystemClock.elapsedRealtime()
		if(cachedAddressAt != 0L && now - cachedAddressAt < ADDRESS_CACHE_MS)
			return cachedAddress
		val address = findLanAddress()
		cachedAddress = address
		cachedAddressAt = now
		return address
	}

	/**
	 * TV'nin yerel ağ IPv4 adresi. Önce Ethernet/Wi-Fi arayüzlerindeki özel (site-local) adres,
	 * yoksa herhangi bir özel adres seçilir. VPN/hücresel arayüzler atlanır.
	 */
	private fun findLanAddress(): String?
	{
		val candidates = mutableListOf<Pair<Int, String>>()
		try
		{
			val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
			for(ni in interfaces)
			{
				try
				{
					if(!ni.isUp || ni.isLoopback || ni.isVirtual)
						continue
				}
				catch(e: Exception)
				{
					continue
				}
				val name = ni.name?.lowercase() ?: ""
				if(name.startsWith("tun") || name.startsWith("rmnet") || name.startsWith("p2p") ||
					name.startsWith("dummy") || name.startsWith("ppp"))
					continue
				val rank = when
				{
					name.startsWith("eth") -> 0
					name.startsWith("wlan") -> 1
					else -> 2
				}
				for(addr in ni.inetAddresses)
				{
					if(addr is Inet4Address && addr.isSiteLocalAddress)
						addr.hostAddress?.let { candidates.add(rank to it) }
				}
			}
		}
		catch(e: Exception)
		{
			Log.w(TAG, "Could not enumerate network interfaces", e)
		}
		return candidates.minByOrNull { it.first }?.second
	}
}
