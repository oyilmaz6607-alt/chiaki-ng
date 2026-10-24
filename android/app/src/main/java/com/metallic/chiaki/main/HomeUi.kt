// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.view.View
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.metallic.chiaki.R

/** AltyazıTR: konsol durumunun metni ve rengi. */
object HomeUi
{
	@StringRes
	fun stateLabel(console: HomeConsole): Int = when
	{
		!console.isRegistered -> R.string.home_state_unregistered
		console.state == HomeConsole.State.READY -> R.string.home_state_ready
		console.state == HomeConsole.State.STANDBY -> R.string.home_state_standby
		else -> R.string.home_state_offline
	}

	@ColorRes
	fun stateColor(console: HomeConsole): Int = when
	{
		!console.isRegistered -> R.color.home_accent
		console.state == HomeConsole.State.READY -> R.color.home_ready
		console.state == HomeConsole.State.STANDBY -> R.color.home_standby
		else -> R.color.home_offline
	}
}

/** AltyazıTR: kumandayla odaklanan öğeyi hafifçe büyütür ve öne çıkarır. */
object HomeFocus
{
	private const val DURATION_MS = 150L

	fun applyScale(view: View, scale: Float = 1.04f)
	{
		val previous = view.onFocusChangeListener
		view.setOnFocusChangeListener { v, hasFocus ->
			previous?.onFocusChange(v, hasFocus)
			val target = if(hasFocus) scale else 1f
			v.animate().scaleX(target).scaleY(target).setDuration(DURATION_MS).start()
			v.translationZ = if(hasFocus) v.resources.displayMetrics.density * 8f else 0f
		}
	}
}
