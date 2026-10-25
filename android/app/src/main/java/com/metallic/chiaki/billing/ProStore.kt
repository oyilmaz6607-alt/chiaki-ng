// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.billing

import android.content.Context
import android.content.SharedPreferences
import com.metallic.chiaki.subtitle.ProPlan
import com.metallic.chiaki.subtitle.ProQuota

/**
 * AltyazıTR aboneliğinin son bilinen durumu (ayrı SharedPreferences dosyası; ayar dışa aktarma ve telefon
 * kumandası bu dosyaya erişmez, uygulama yedeğe dahil edilmez — manifest'te allowBackup=false).
 * Dosya adı geçiş gerekmesin diye eski ("altyazitr_pro") kaldı.
 *
 * BillingRepository Google Play'den doğrulanmış durumu buraya yazar; altyazı servisi Play'e bağlanmayı
 * beklemeden buradan okur. Satın alma jetonu her oturumda sunucu tarafından Google'a doğrulatılır.
 * Paket (Standart/Pro/Premium/deneme) Play'in Purchase nesnesinde yoktur; sunucunun /v1/session yanıtından gelir.
 */
class ProStore(context: Context)
{
	companion object
	{
		private const val FILE = "altyazitr_pro"
		const val KEY_TOKEN = "purchase_token"
		const val KEY_AUTO_RENEWING = "auto_renewing"
		const val KEY_PLAN = "plan"
		private const val KEY_PLAN_NAME = "plan_name"
		private const val KEY_BASE_PLAN = "base_plan"
		private const val KEY_TRIAL = "trial"
		private const val KEY_QUOTA_USED = "quota_used"
		private const val KEY_QUOTA_LIMIT = "quota_limit"
		private const val KEY_QUOTA_RESET = "quota_reset_at"
		/** Kota doldu bildiriminin gösterildiği kota döneminin bitişi (dönem başına bir kez). */
		private const val KEY_NOTICE_FOR = "quota_notice_for"
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
			// Farklı bir abonelik (ör. paket değişikliği → yeni jeton): eski paket ve kotayı gösterme.
			if(tokenChanged)
			{
				clearPlan(this)
				clearQuota(this)
			}
		}.apply()
	}

	fun clearSubscription()
	{
		if(sharedPreferences.contains(KEY_TOKEN) || sharedPreferences.contains(KEY_QUOTA_LIMIT) || sharedPreferences.contains(KEY_PLAN))
			sharedPreferences.edit().apply {
				remove(KEY_TOKEN)
				remove(KEY_AUTO_RENEWING)
				clearPlan(this)
				clearQuota(this)
			}.apply()
	}

	/** Sunucunun son bildirdiği paket; henüz bilinmiyorsa null. */
	val plan: ProPlan?
		get()
		{
			val id = sharedPreferences.getString(KEY_PLAN, null) ?: return null
			return ProPlan(id, sharedPreferences.getString(KEY_PLAN_NAME, null), sharedPreferences.getString(KEY_BASE_PLAN, null),
				sharedPreferences.getBoolean(KEY_TRIAL, false))
		}

	fun savePlan(plan: ProPlan)
	{
		if(plan == this.plan)
			return
		sharedPreferences.edit()
			.putString(KEY_PLAN, plan.plan)
			.putString(KEY_PLAN_NAME, plan.planName)
			.putString(KEY_BASE_PLAN, plan.basePlan)
			.putBoolean(KEY_TRIAL, plan.trial)
			.apply()
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

	/** Bu kota dönemi için "hak doldu" bildirimi gösterilmeli mi? Gösterilecekse işaretler (true döner). */
	fun claimQuotaNotice(resetAt: Long): Boolean
	{
		if(sharedPreferences.getLong(KEY_NOTICE_FOR, Long.MIN_VALUE) == resetAt)
			return false
		sharedPreferences.edit().putLong(KEY_NOTICE_FOR, resetAt).apply()
		return true
	}

	private fun clearPlan(editor: SharedPreferences.Editor)
	{
		editor.remove(KEY_PLAN)
		editor.remove(KEY_PLAN_NAME)
		editor.remove(KEY_BASE_PLAN)
		editor.remove(KEY_TRIAL)
	}

	private fun clearQuota(editor: SharedPreferences.Editor)
	{
		editor.remove(KEY_QUOTA_USED)
		editor.remove(KEY_QUOTA_LIMIT)
		editor.remove(KEY_QUOTA_RESET)
		editor.remove(KEY_NOTICE_FOR)
	}
}
