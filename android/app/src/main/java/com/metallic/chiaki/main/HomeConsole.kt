// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import com.metallic.chiaki.common.DiscoveredDisplayHost
import com.metallic.chiaki.common.DisplayHost
import com.metallic.chiaki.common.ManualDisplayHost
import com.metallic.chiaki.lib.DiscoveryHost

/** AltyazıTR: ana ekranda gösterilen tek bir konsol. */
data class HomeConsole(
	val key: String,
	val displayHost: DisplayHost,
	val name: String?,
	val host: String,
	val state: State,
	val isPS5: Boolean,
	val isRegistered: Boolean
)
{
	enum class State
	{
		READY,
		STANDBY,
		UNKNOWN
	}

	val isManual get() = displayHost is ManualDisplayHost

	companion object
	{
		fun from(host: DisplayHost): HomeConsole
		{
			val state = if(host is DiscoveredDisplayHost)
				when(host.discoveredHost.state)
				{
					DiscoveryHost.State.READY -> State.READY
					DiscoveryHost.State.STANDBY -> State.STANDBY
					else -> State.UNKNOWN
				}
			else
				State.UNKNOWN
			return HomeConsole(
				key = keyFor(host),
				displayHost = host,
				name = host.name,
				host = host.host,
				state = state,
				isPS5 = host.isPS5,
				isRegistered = host.isRegistered)
		}

		fun keyFor(host: DisplayHost): String
		{
			val registered = host.registeredHost
			if(registered != null)
				return "mac:${registered.serverMac}"
			if(host is DiscoveredDisplayHost)
				host.discoveredHost.hostId?.let { return "id:$it" }
			return "ip:${host.host}"
		}

		/**
		 * Keşfedilen ve elle eklenen kayıtları tek listede birleştirir: aynı konsol iki kez görünmez
		 * (ağda bulunan kayıt, durum bilgisi taşıdığı için tercih edilir). Eşleştirilmiş ve hazır
		 * konsollar öne alınır.
		 */
		fun merge(hosts: List<DisplayHost>): List<HomeConsole>
		{
			val result = LinkedHashMap<String, HomeConsole>()
			for(host in hosts.filterIsInstance<DiscoveredDisplayHost>())
			{
				val console = from(host)
				result.putIfAbsent(console.key, console)
			}
			for(host in hosts.filterIsInstance<ManualDisplayHost>())
			{
				val console = from(host)
				if(result.containsKey(console.key))
					continue
				if(!console.isRegistered && result.values.any { it.host == console.host })
					continue
				result[console.key] = console
			}
			return result.values.sortedWith(compareByDescending<HomeConsole> { it.isRegistered }
				.thenBy { it.state.ordinal })
		}
	}
}
