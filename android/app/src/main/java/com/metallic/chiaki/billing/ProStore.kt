// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.billing

import android.content.Context
import android.content.SharedPreferences
import com.metallic.chiaki.subtitle.ProQuota

/**
 * AltyazıTR Pro'nun son bilinen durumu (ayrı SharedPreferences dosyası; ayar dışa aktarma ve telefon
 * kumandası bu dosyaya erişmez, uygulama yedeğe dahil edilmez — manifest'te allowBackup=false).
 *
 * BillingRepository Google Play'den doğrulanmış durumu buraya yazar; altyazı servisi Play'e bağlanmayı
 * beklemeden buradan okur. Satın alma jetonu her oturumda sunucu tarafından Google'a doğrulatılır.
 */
class ProStore(context: Context)
{
	companion object
	{
		private const val FILE = "altyazitr_pro"
		const val KEY_TOKEN = "purchase_token"
		const val KEY_AUTO_RENEWING = "auto_renewing"
		private const val KEY_QUOTA_USED = "quota_used"
		private const val KEY_QUOTA_LIMIT = "quota_limit"
		private const val KEY_QUOTA_RESET = "quota_reset_at"
	}

	val sharedPreferences: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

	val purchaseToken: String? get() = sharedPreferences.getString(KEY_TOKEN, null)?.takeIf { it.isNotEmpty() }

	val isSubscribed: Boolean get() = purchaseToken != null

	val autoRenewing: Boolean get() = sharedPreferences.getBoolean(KEY_AUTO_RENEWING, true)

	fun saveSubscription(token: String, autoRenewing: Boolean)
	{
		if(token == purchaseToken && autoRenewing == this.autoRenewing)
			return
		val tokenChanged = token != purchaseToken
		sharedPreferences.edit().apply {
			putString(KEY_TOKEN, token)
			putBoolean(KEY_AUTO_RENEWING, autoRenewing)
			// Farklı bir abonelik: eski kotayı gösterme.
			if(tokenChanged)
				clearQuota(this)
		}.apply()
	}

	fun clearSubscription()
	{
		if(sharedPreferences.contains(KEY_TOKEN) || sharedPreferences.contains(KEY_QUOTA_LIMIT))
			sharedPreferences.edit().apply {
				remove(KEY_TOKEN)
				remove(KEY_AUTO_RENEWING)
				clearQuota(this)
			}.apply()
	}

	val quota: ProQuota?
		get()
		{
			val limit = sharedPreferences.getLong(KEY_QUOTA_LIMIT, -1L)
			if(limit < 0)
				return null
			return ProQuota(sharedPreferences.getLong(KEY_QUOTA_USED, 0L), limit, sharedPreferences.getLong(KEY_QUOTA_RESET, 0L))
		}

	fun saveQuota(quota: ProQuota)
	{
		sharedPreferences.edit()
			.putLong(KEY_QUOTA_USED, quota.used)
			.putLong(KEY_QUOTA_LIMIT, quota.limit)
			.putLong(KEY_QUOTA_RESET, quota.resetAt)
			.apply()
	}

	private fun clearQuota(editor: SharedPreferences.Editor)
	{
		editor.remove(KEY_QUOTA_USED)
		editor.remove(KEY_QUOTA_LIMIT)
		editor.remove(KEY_QUOTA_RESET)
	}
}
