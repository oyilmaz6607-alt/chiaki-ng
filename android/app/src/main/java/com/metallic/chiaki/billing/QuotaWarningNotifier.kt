// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.billing

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import com.metallic.chiaki.main.MainActivity
import com.metallic.chiaki.subtitle.CaptionService

/**
 * %80 uyarısı ([QuotaWarning]): ana ekran (MainActivity) öne gelince, dönem başına bir kez kısa bir bilgi.
 * Oyun sırasında (altyazı modu açıkken) hiç gösterilmez. Uygulama ömrü boyunca bir kez kurulur
 * (BillingRepository ilk oluşturulduğunda); ana ekran kodunda değişiklik gerektirmez.
 */
object QuotaWarningNotifier
{
	@Volatile private var installed = false

	fun install(app: Application)
	{
		if(installed)
			return
		synchronized(this) {
			if(installed)
				return
			installed = true
		}
		app.registerActivityLifecycleCallbacks(object: Application.ActivityLifecycleCallbacks
		{
			override fun onActivityResumed(activity: Activity)
			{
				if(activity is MainActivity)
					maybeShow(activity)
			}

			override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
			override fun onActivityStarted(activity: Activity) {}
			override fun onActivityPaused(activity: Activity) {}
			override fun onActivityStopped(activity: Activity) {}
			override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
			override fun onActivityDestroyed(activity: Activity) {}
		})
	}

	/** Uyarı gerekiyorsa (ve bu dönem henüz gösterilmediyse) gösterir; true = gösterildi. */
	fun maybeShow(context: Context): Boolean
	{
		if(CaptionService.isRunning)
			return false
		val store = ProStore(context)
		if(!store.isSubscribed)
			return false
		val q = store.quota ?: return false
		val now = System.currentTimeMillis()
		if(!QuotaWarning.shouldWarn(q.used, q.limit, q.resetAt, now) || !store.claimQuotaWarning(q.resetAt))
			return false
		Toast.makeText(context.applicationContext, QuotaWarning.message(q.used, q.limit, q.resetAt, now, trial = store.plan?.trial == true),
			Toast.LENGTH_LONG).show()
		return true
	}
}
