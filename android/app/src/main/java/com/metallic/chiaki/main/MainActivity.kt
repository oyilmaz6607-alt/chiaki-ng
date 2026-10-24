// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.BuildConfig
import com.metallic.chiaki.R
import com.metallic.chiaki.common.*
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.companion.CompanionServer
import com.metallic.chiaki.companion.QrCode
import com.metallic.chiaki.databinding.ActivityMainBinding
import com.metallic.chiaki.databinding.DialogCompanionBinding
import com.metallic.chiaki.databinding.DialogHomeAboutBinding
import com.metallic.chiaki.databinding.DialogHomeHelpBinding
import com.metallic.chiaki.databinding.ItemHomeHelpStepBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.manualconsole.EditManualConsoleActivity
import com.metallic.chiaki.regist.RegistActivity
import com.metallic.chiaki.settings.SettingsActivity
import com.metallic.chiaki.stream.StreamActivity
import com.metallic.chiaki.subtitle.CaptionService

/**
 * AltyazıTR: Google TV ana ekranı.
 * Solda seçili konsolun durum kartı ve tek ana işlem (Altyazıyı Başlat/Durdur),
 * sağda ikincil işlemler, altta birden fazla konsol varsa seçim sırası.
 */
class MainActivity : AppCompatActivity()
{
	companion object
	{
		private const val STATE_REFRESH_MS = 1500L
		private const val WAKEUP_TIMEOUT_MS = 45_000L
		private const val STOP_REFRESH_DELAY_MS = 700L
	}

	private enum class LaunchMode
	{
		CAPTION,
		STREAM
	}

	private lateinit var viewModel: MainViewModel
	private lateinit var binding: ActivityMainBinding
	private lateinit var homePrefs: HomePrefs
	private lateinit var consoleAdapter: HomeConsoleAdapter

	private var consoles: List<HomeConsole> = listOf()
	private var selectedKey: String? = null
	/** Kullanıcı bu oturumda elle konsol seçtiyse, hatırlanan konsol gelince seçim değiştirilmez. */
	private var userSelected = false
	private val selectedConsole: HomeConsole? get() = consoles.firstOrNull { it.key == selectedKey }

	/** Overlay izni için Ayarlar'a gidildiyse, dönüşte altyazı otomatik başlatılır. */
	private var pendingCaptionKey: String? = null

	/** Uyandırma paketi gönderilen konsol; hazır olunca seçilen işlem sürdürülür. */
	private var pendingWakeKey: String? = null
	private var pendingWakeMode = LaunchMode.CAPTION

	private val handler = Handler(Looper.getMainLooper())
	private var lastCaptionRunning: Boolean? = null

	/** Telefondan kontrol penceresi (açıksa). */
	private var companionDialog: androidx.appcompat.app.AlertDialog? = null
	private var companionBinding: DialogCompanionBinding? = null
	private var companionQrUrl: String? = null
	private val companionListener: () -> Unit = {
		updateCompanionHint()
		updateCompanionDialog()
	}

	private val refreshRunnable = object: Runnable
	{
		override fun run()
		{
			if(lastCaptionRunning != CaptionService.isRunning)
				updateHero()
			updateCompanionHint()
			handler.postDelayed(this, STATE_REFRESH_MS)
		}
	}

	private val wakeTimeoutRunnable = Runnable {
		if(pendingWakeKey != null)
		{
			pendingWakeKey = null
			MaterialAlertDialogBuilder(this)
				.setTitle(R.string.home_standby_title)
				.setMessage(R.string.home_wakeup_timeout)
				.setPositiveButton(R.string.action_ok, null)
				.show()
		}
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivityMainBinding.inflate(layoutInflater)
		setContentView(binding.root)

		homePrefs = HomePrefs(this)
		selectedKey = homePrefs.lastConsoleKey

		viewModel = ViewModelProvider(this, viewModelFactory { MainViewModel(getDatabase(this), Preferences(this)) })
			.get(MainViewModel::class.java)
		// TV'de konsolu bulmanın tek yolu ağ taraması; her zaman açık tutulur.
		viewModel.discoveryManager.active = true

		consoleAdapter = HomeConsoleAdapter(this::consoleClicked, this::consoleLongClicked)
		binding.consolesRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
		binding.consolesRecyclerView.adapter = consoleAdapter
		binding.consolesRecyclerView.itemAnimator = null
		binding.consolesLabelTextView.text = getString(R.string.home_section_consoles) + "  ·  " + getString(R.string.home_console_long_press_hint)

		listOf(binding.tileRemotePlay, binding.tileSettings, binding.tilePair, binding.tileCompanion, binding.tileHelp).forEach {
			HomeFocus.applyScale(it, 1.03f)
		}
		HomeFocus.applyScale(binding.primaryButton, 1.05f)
		HomeFocus.applyScale(binding.aboutButton, 1.06f)

		binding.primaryButton.setOnClickListener { primaryAction() }
		binding.tileRemotePlay.setOnClickListener { remotePlayAction() }
		binding.tileSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
		binding.tilePair.setOnClickListener { showAddConsole() }
		binding.tileCompanion.setOnClickListener { showCompanion() }
		binding.tileHelp.setOnClickListener { showHelp() }
		binding.aboutButton.setOnClickListener { showAbout() }

		viewModel.displayHosts.observe(this) { hosts ->
			onHostsChanged(hosts)
		}
		viewModel.discoveryActive.observe(this) { updateHero() }

		updateHero()
		binding.primaryButton.requestFocus()
	}

	override fun onStart()
	{
		super.onStart()
		viewModel.discoveryManager.resume()
		CompanionServer.addListener(companionListener)
		CompanionServer.acquire(this, CompanionServer.HOLDER_HOME)
		updateCompanionHint()
	}

	override fun onResume()
	{
		super.onResume()
		updateHero()
		handler.postDelayed(refreshRunnable, STATE_REFRESH_MS)

		val pendingKey = pendingCaptionKey
		if(pendingKey != null && CaptionService.canDrawOverlays(this))
		{
			pendingCaptionKey = null
			consoles.firstOrNull { it.key == pendingKey }?.let { console ->
				connectInfoFor(console)?.let { startCaptionMode(console, it) }
			}
		}
	}

	override fun onPause()
	{
		super.onPause()
		handler.removeCallbacks(refreshRunnable)
	}

	override fun onStop()
	{
		super.onStop()
		viewModel.discoveryManager.pause()
		CompanionServer.removeListener(companionListener)
		CompanionServer.release(CompanionServer.HOLDER_HOME)
	}

	override fun onDestroy()
	{
		super.onDestroy()
		handler.removeCallbacksAndMessages(null)
		companionDialog?.dismiss()
		companionDialog = null
	}

	// region Konsol listesi

	private fun onHostsChanged(hosts: List<DisplayHost>)
	{
		consoles = HomeConsole.merge(hosts)

		hosts.firstOrNull { it is DiscoveredDisplayHost && it.host.isNotEmpty() }?.let {
			homePrefs.lastDiscoveredHost = it.host
		}

		val rememberedKey = homePrefs.lastConsoleKey
		val remembered = consoles.firstOrNull { it.key == rememberedKey }
			?: homePrefs.lastConsoleHost?.let { host -> consoles.firstOrNull { it.host == host && it.isRegistered } }
		if(!userSelected && remembered != null)
			selectedKey = remembered.key
		else if(selectedConsole == null)
			selectedKey = (consoles.firstOrNull { it.isRegistered } ?: consoles.firstOrNull())?.key

		consoleAdapter.submitList(consoles)
		consoleAdapter.selectedKey = selectedKey
		binding.consolesSection.isVisible = consoles.size > 1
		updateHero()
		checkPendingWake()
	}

	private fun consoleClicked(console: HomeConsole)
	{
		selectConsole(console)
		binding.primaryButton.requestFocus()
	}

	private fun selectConsole(console: HomeConsole)
	{
		userSelected = true
		selectedKey = console.key
		consoleAdapter.selectedKey = console.key
		homePrefs.rememberConsole(console)
		updateHero()
	}

	private fun consoleLongClicked(console: HomeConsole)
	{
		val actions = mutableListOf<Pair<String, () -> Unit>>()
		actions.add(getString(R.string.home_console_option_select) to { consoleClicked(console) })
		if(console.isRegistered)
			actions.add(getString(R.string.home_console_option_wakeup) to { wakeupConsole(console) })
		if(!console.isRegistered)
			actions.add(getString(R.string.home_action_pair_this) to { openRegist(console) })
		val manual = console.displayHost as? ManualDisplayHost
		if(manual != null)
		{
			actions.add(getString(R.string.home_console_option_edit) to { editManual(manual) })
			actions.add(getString(R.string.home_console_option_delete) to { confirmDeleteManual(manual) })
		}
		MaterialAlertDialogBuilder(this)
			.setTitle(getString(R.string.home_console_options_title, consoleName(console)))
			.setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
			.show()
	}

	private fun editManual(host: ManualDisplayHost)
	{
		val intent = Intent(this, EditManualConsoleActivity::class.java)
		intent.putExtra(EditManualConsoleActivity.EXTRA_MANUAL_HOST_ID, host.manualHost.id)
		startActivity(intent)
	}

	private fun confirmDeleteManual(host: ManualDisplayHost)
	{
		MaterialAlertDialogBuilder(this)
			.setMessage(getString(R.string.home_console_delete_message, host.manualHost.host))
			.setPositiveButton(R.string.home_action_delete) { _, _ -> viewModel.deleteManualHost(host.manualHost) }
			.setNegativeButton(R.string.home_action_keep, null)
			.show()
	}

	private fun consoleName(console: HomeConsole) = console.name ?: getString(R.string.home_console_unnamed)

	// endregion

	// region Durum kartı

	private fun updateHero()
	{
		val running = CaptionService.isRunning
		lastCaptionRunning = running
		val console = selectedConsole

		// Altyazı çipi
		binding.captionTextView.setText(if(running) R.string.home_caption_running else R.string.home_caption_off)
		setDotColor(binding.captionDotImageView, if(running) R.color.home_ready else R.color.home_offline)

		if(console == null)
		{
			binding.heroLabelTextView.isVisible = false
			binding.heroNameTextView.setText(R.string.home_empty_title)
			binding.stateChip.isVisible = false
			binding.ipChip.isVisible = false
			binding.captionChip.isVisible = running
			binding.heroHintTextView.text = if(viewModel.discoveryActive.value == true)
				getString(R.string.home_empty_searching) + "\n" + getString(R.string.home_empty_body)
			else
				getString(R.string.home_empty_body)
			binding.heroHintTextView.maxLines = 3
			if(running)
				setPrimary(R.string.home_action_stop, R.drawable.ic_home_stop, stop = true)
			else
				setPrimary(R.string.home_action_pair_first, R.drawable.ic_home_add, stop = false)
			return
		}

		binding.heroHintTextView.maxLines = 2
		binding.heroLabelTextView.isVisible = true
		binding.heroNameTextView.text = consoleName(console)
		binding.stateChip.isVisible = true
		binding.ipChip.isVisible = true
		binding.captionChip.isVisible = true
		binding.stateTextView.setText(HomeUi.stateLabel(console))
		setDotColor(binding.stateDotImageView, HomeUi.stateColor(console))
		binding.ipTextView.text = if(console.host.isNotEmpty())
			getString(R.string.home_console_ip, console.host)
		else
			getString(R.string.home_console_ip_unknown)

		when
		{
			running ->
			{
				setPrimary(R.string.home_action_stop, R.drawable.ic_home_stop, stop = true)
				binding.heroHintTextView.setText(R.string.home_hint_running)
			}
			!console.isRegistered ->
			{
				setPrimary(R.string.home_action_pair_this, R.drawable.ic_home_add, stop = false)
				binding.heroHintTextView.setText(R.string.home_hint_unregistered)
			}
			else ->
			{
				setPrimary(R.string.home_action_start, R.drawable.ic_home_play, stop = false)
				binding.heroHintTextView.setText(R.string.home_hint_start)
			}
		}
	}

	private fun setPrimary(textRes: Int, iconRes: Int, stop: Boolean)
	{
		binding.primaryButton.setText(textRes)
		binding.primaryButton.setIconResource(iconRes)
		binding.primaryButton.backgroundTintList =
			ContextCompat.getColorStateList(this, if(stop) R.color.home_stop_bg else R.color.home_primary_bg)
	}

	private fun setDotColor(view: android.widget.ImageView, colorRes: Int)
	{
		ImageViewCompat.setImageTintList(view, ColorStateList.valueOf(ContextCompat.getColor(this, colorRes)))
	}

	// endregion

	// region İşlemler

	private fun primaryAction()
	{
		if(CaptionService.isRunning)
		{
			CaptionService.stop(this)
			Toast.makeText(this, R.string.home_caption_stopping, Toast.LENGTH_SHORT).show()
			handler.postDelayed({ updateHero() }, STOP_REFRESH_DELAY_MS)
			return
		}
		val console = selectedConsole
		if(console == null)
		{
			openRegist(null)
			return
		}
		launch(console, LaunchMode.CAPTION)
	}

	private fun remotePlayAction()
	{
		val console = selectedConsole
		if(console == null)
		{
			openRegist(null)
			return
		}
		launch(console, LaunchMode.STREAM)
	}

	private fun launch(console: HomeConsole, mode: LaunchMode)
	{
		if(!console.isRegistered)
		{
			Toast.makeText(this, R.string.home_need_registered, Toast.LENGTH_SHORT).show()
			openRegist(console)
			return
		}
		homePrefs.rememberConsole(console)

		if(console.state == HomeConsole.State.STANDBY)
		{
			MaterialAlertDialogBuilder(this)
				.setTitle(R.string.home_standby_title)
				.setMessage(R.string.home_standby_message)
				.setPositiveButton(R.string.home_standby_wake) { _, _ ->
					pendingWakeKey = console.key
					pendingWakeMode = mode
					wakeupConsole(console)
					Toast.makeText(this, R.string.home_waking_up, Toast.LENGTH_LONG).show()
					handler.removeCallbacks(wakeTimeoutRunnable)
					handler.postDelayed(wakeTimeoutRunnable, WAKEUP_TIMEOUT_MS)
				}
				.setNeutralButton(R.string.home_standby_connect_anyway) { _, _ -> proceed(console, mode) }
				.setNegativeButton(R.string.home_standby_cancel, null)
				.show()
			return
		}
		proceed(console, mode)
	}

	private fun checkPendingWake()
	{
		val key = pendingWakeKey ?: return
		val console = consoles.firstOrNull { it.key == key } ?: return
		if(console.state != HomeConsole.State.READY)
			return
		pendingWakeKey = null
		handler.removeCallbacks(wakeTimeoutRunnable)
		proceed(console, pendingWakeMode)
	}

	private fun proceed(console: HomeConsole, mode: LaunchMode)
	{
		val connectInfo = connectInfoFor(console) ?: return
		when(mode)
		{
			LaunchMode.CAPTION -> startCaptionMode(console, connectInfo)
			LaunchMode.STREAM -> Intent(this, StreamActivity::class.java).let {
				it.putExtra(StreamActivity.EXTRA_CONNECT_INFO, connectInfo)
				startActivity(it)
			}
		}
	}

	private fun connectInfoFor(console: HomeConsole): ConnectInfo?
	{
		val registeredHost = console.displayHost.registeredHost ?: return null
		return ConnectInfo(console.isPS5, console.host, registeredHost.rpRegistKey, registeredHost.rpKey, Preferences(this).videoProfile)
	}

	private fun startCaptionMode(console: HomeConsole, connectInfo: ConnectInfo)
	{
		if(!CaptionService.canDrawOverlays(this))
		{
			MaterialAlertDialogBuilder(this)
				.setMessage(R.string.caption_overlay_permission)
				.setPositiveButton(R.string.action_ok) { _, _ ->
					try
					{
						pendingCaptionKey = console.key
						startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
					}
					catch(e: ActivityNotFoundException)
					{
						pendingCaptionKey = null
						MaterialAlertDialogBuilder(this)
							.setMessage(R.string.caption_overlay_permission_missing)
							.setPositiveButton(R.string.action_ok, null)
							.show()
					}
				}
				.show()
			return
		}
		if(CaptionService.isRunning)
			CaptionService.stop(this)
		CaptionService.start(this, connectInfo)
		Toast.makeText(this, R.string.caption_started, Toast.LENGTH_LONG).show()
		moveTaskToBack(true)
	}

	private fun wakeupConsole(console: HomeConsole)
	{
		val registeredHost = console.displayHost.registeredHost ?: return
		viewModel.discoveryManager.sendWakeup(console.host, registeredHost.rpRegistKey, registeredHost.target.isPS5)
	}

	private fun openRegist(console: HomeConsole?)
	{
		Intent(this, RegistActivity::class.java).let {
			if(console != null && console.host.isNotEmpty())
			{
				it.putExtra(RegistActivity.EXTRA_HOST, console.host)
				it.putExtra(RegistActivity.EXTRA_BROADCAST, false)
				val manual = console.displayHost as? ManualDisplayHost
				if(manual != null)
					it.putExtra(RegistActivity.EXTRA_ASSIGN_MANUAL_HOST_ID, manual.manualHost.id)
			}
			startActivity(it)
		}
	}

	private fun showAddConsole()
	{
		val selected = selectedConsole
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.home_add_title)
			.setItems(arrayOf(getString(R.string.home_add_pair), getString(R.string.home_add_manual))) { _, which ->
				when(which)
				{
					0 -> openRegist(selected?.takeIf { !it.isRegistered })
					1 -> startActivity(Intent(this, EditManualConsoleActivity::class.java))
				}
			}
			.show()
	}

	private fun showHelp()
	{
		val helpBinding = DialogHomeHelpBinding.inflate(layoutInflater)
		bindStep(helpBinding.step1, 1, R.string.home_help_step1_title, R.string.home_help_step1_body)
		bindStep(helpBinding.step2, 2, R.string.home_help_step2_title, R.string.home_help_step2_body)
		bindStep(helpBinding.step3, 3, R.string.home_help_step3_title, R.string.home_help_step3_body)
		bindStep(helpBinding.step4, 4, R.string.home_help_step4_title, R.string.home_help_step4_body)
		MaterialAlertDialogBuilder(this)
			.setView(helpBinding.root)
			.setPositiveButton(R.string.home_help_close, null)
			.show()
	}

	private fun bindStep(step: ItemHomeHelpStepBinding, number: Int, titleRes: Int, bodyRes: Int)
	{
		step.numberTextView.text = number.toString()
		step.titleTextView.setText(titleRes)
		step.bodyTextView.setText(bodyRes)
	}

	private fun showAbout()
	{
		val aboutBinding = DialogHomeAboutBinding.inflate(layoutInflater)
		aboutBinding.versionTextView.text = getString(R.string.home_about_version, BuildConfig.VERSION_NAME)
		val url = getString(R.string.home_about_source_url)
		MaterialAlertDialogBuilder(this)
			.setView(aboutBinding.root)
			.setPositiveButton(R.string.home_about_close, null)
			.setNeutralButton(R.string.home_about_open_link) { _, _ -> openUrl(url) }
			.show()
	}

	// region Telefondan kontrol

	private fun updateCompanionHint()
	{
		val url = CompanionServer.url(withToken = false)
		val text = when
		{
			CompanionServer.info == null -> null
			url == null -> getString(R.string.companion_hint_no_network)
			CompanionServer.isClientActive -> getString(R.string.companion_hint_connected, url)
			else -> getString(R.string.companion_hint_ready, url)
		}
		binding.companionHintLayout.isVisible = text != null
		if(text != null && binding.companionHintTextView.text?.toString() != text)
			binding.companionHintTextView.text = text
		// Pencere açıkken telefonun bağlanması da yoklanarak gösterilir.
		if(companionDialog?.isShowing == true)
			updateCompanionDialog()
	}

	private fun showCompanion()
	{
		if(companionDialog?.isShowing == true)
			return
		val dialogBinding = DialogCompanionBinding.inflate(layoutInflater)
		companionBinding = dialogBinding
		companionQrUrl = null
		companionDialog = MaterialAlertDialogBuilder(this)
			.setView(dialogBinding.root)
			.setPositiveButton(R.string.companion_dialog_close, null)
			.setOnDismissListener {
				companionDialog = null
				companionBinding = null
				companionQrUrl = null
			}
			.show()
			.also {
				// QR (360dp) ve metin yan yana sığsın: TV'de pencere ekranın büyük kısmını kaplar.
				val width = (resources.displayMetrics.widthPixels * 0.86f).toInt()
				it.window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
			}
		// Sunucu onStart'ta başlatıldı; durmuşsa (ör. başlatma hatası) yeniden dene.
		CompanionServer.acquire(this, CompanionServer.HOLDER_HOME)
		updateCompanionDialog()
	}

	private fun updateCompanionDialog()
	{
		val b = companionBinding ?: return
		val info = CompanionServer.info
		val url = CompanionServer.url(withToken = true)
		val error = CompanionServer.lastError
		val ready = info != null && url != null

		b.qrProgressBar.isVisible = info == null && error == null
		b.codeLayout.isVisible = ready
		b.manualLabelTextView.isVisible = ready
		b.wifiTextView.isVisible = true

		if(ready && url != companionQrUrl)
		{
			companionQrUrl = url
			val sizePx = (resources.displayMetrics.density * 344).toInt()
			val bitmap = QrCode.render(url!!, sizePx)
			b.qrImageView.setImageDrawable(bitmap?.let {
				// Filtresiz büyütme: QR modülleri keskin kalır.
				android.graphics.drawable.BitmapDrawable(resources, it).apply { setFilterBitmap(false) }
			})
		}
		else if(!ready)
		{
			companionQrUrl = null
			b.qrImageView.setImageDrawable(null)
		}

		b.urlTextView.text = when
		{
			ready -> CompanionServer.url(withToken = false)
			info != null -> getString(R.string.companion_dialog_no_network)
			error != null -> getString(R.string.companion_dialog_failed, error)
			else -> getString(R.string.companion_dialog_starting)
		}
		b.urlTextView.textSize = if(ready) 20f else 16f
		b.codeTextView.text = info?.shortCode ?: ""

		val connected = ready && CompanionServer.isClientActive
		b.statusChip.isVisible = ready
		b.statusTextView.setText(if(connected) R.string.companion_dialog_connected else R.string.companion_dialog_waiting)
		setDotColor(b.statusDotImageView, if(connected) R.color.home_ready else R.color.home_offline)
	}

	// endregion

	private fun openUrl(url: String)
	{
		try
		{
			startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
		}
		catch(e: ActivityNotFoundException)
		{
			Toast.makeText(this, getString(R.string.home_about_no_browser, url), Toast.LENGTH_LONG).show()
		}
	}

	// endregion
}
