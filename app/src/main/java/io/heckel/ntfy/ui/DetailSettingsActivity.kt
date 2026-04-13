package io.heckel.ntfy.ui

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import androidx.preference.Preference.OnPreferenceClickListener
import com.google.android.material.appbar.AppBarLayout
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip19Bech32.bech32.Bech32
import com.vitorpamplona.quartz.utils.Hex
import io.heckel.ntfy.msg.DownloadAttachmentWorker
import io.heckel.ntfy.msg.NotificationService
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.*
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.util.*
import androidx.core.net.toUri

/**
 * Subscription settings
 */
class DetailSettingsActivity : AppCompatActivity() {
    private lateinit var repository: Repository
    private lateinit var serviceManager: SubscriberServiceManager
    private lateinit var settingsFragment: SettingsFragment
    private lateinit var notificationService: NotificationService
    private var subscriptionId: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        Log.d(TAG, "Create $this")

        repository = Repository.getInstance(this)
        serviceManager = SubscriberServiceManager(this)
        notificationService = NotificationService(this)
        subscriptionId = intent.getLongExtra(DetailActivity.EXTRA_SUBSCRIPTION_ID, 0)

        if (savedInstanceState == null) {
            settingsFragment = SettingsFragment() // Empty constructor!
            settingsFragment.arguments = Bundle().apply {
                this.putLong(DetailActivity.EXTRA_SUBSCRIPTION_ID, subscriptionId)
            }
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_layout, settingsFragment)
                .commit()
        }

        val toolbarLayout = findViewById<AppBarLayout>(R.id.app_bar_drawer)
        val dynamicColors = repository.getDynamicColorsEnabled()
        val darkMode = isDarkThemeOn(this)
        val statusBarColor = Colors.statusBarNormal(this, dynamicColors, darkMode)
        val toolbarTextColor = Colors.toolbarTextColor(this, dynamicColors, darkMode)
        toolbarLayout.setBackgroundColor(statusBarColor)
        
        val toolbar = toolbarLayout.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setTitleTextColor(toolbarTextColor)
        toolbar.setNavigationIconTint(toolbarTextColor)
        toolbar.overflowIcon?.setTint(toolbarTextColor)
        setSupportActionBar(toolbar)
        
        // Set system status bar appearance
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars =
            Colors.shouldUseLightStatusBar(dynamicColors, darkMode)
        
        // Title
        val displayName = intent.getStringExtra(DetailActivity.EXTRA_SUBSCRIPTION_DISPLAY_NAME) ?: return
        title = displayName

        // Show 'Back' button
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish() // Return to previous activity when nav "back" is pressed!
        return true
    }

    class SettingsFragment : BasePreferenceFragment() {
        private lateinit var resolver: ContentResolver
        private lateinit var repository: Repository
        private lateinit var serviceManager: SubscriberServiceManager
        private lateinit var notificationService: NotificationService
        private lateinit var subscription: Subscription

        private lateinit var iconSetPref: Preference
        private lateinit var openChannelsPref: Preference
        private lateinit var iconSetLauncher: ActivityResultLauncher<String>
        private lateinit var iconRemovePref: Preference
        private lateinit var appBaseUrl: String

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.detail_preferences, rootKey)

            // Dependencies (Fragments need a default constructor)
            repository = Repository.getInstance(requireActivity())
            serviceManager = SubscriberServiceManager(requireActivity())
            notificationService = NotificationService(requireActivity())
            resolver = requireContext().applicationContext.contentResolver
            appBaseUrl = requireContext().getString(R.string.app_base_url)

            // Create result launcher for custom icon (must be created in onCreatePreferences() directly)
            iconSetLauncher = createIconPickLauncher()

            // Load subscription and users
            val subscriptionId = arguments?.getLong(DetailActivity.EXTRA_SUBSCRIPTION_ID) ?: return
            runBlocking {
                withContext(Dispatchers.IO) {
                    subscription = repository.getSubscription(subscriptionId) ?: return@withContext
                    activity?.runOnUiThread {
                        loadView()
                    }
                }
            }
        }

        private fun loadView() {
            if (subscription.upAppId == null) {
                loadInstantPref()
                loadMutedUntilPref()
                loadMinPriorityPref()
                loadAutoDeletePref()
                loadInsistentMaxPriorityPref()
                loadIconSetPref()
                loadIconRemovePref()
                loadDedicatedChannelsPrefs()
                loadOpenChannelsPrefs()
            } else {
                val notificationsHeaderId = context?.getString(R.string.detail_settings_notifications_header_key) ?: return
                val notificationsHeader: PreferenceCategory? = findPreference(notificationsHeaderId)
                notificationsHeader?.isVisible = false
            }
            loadDisplayNamePref()
            loadTopicUrlPref()
            loadAllowedSendersPrefs()
        }

        private fun loadInstantPref() {
            val appBaseUrl = getString(R.string.app_base_url)
            val prefId = context?.getString(R.string.detail_settings_notifications_instant_key) ?: return
            val pref: SwitchPreferenceCompat? = findPreference(prefId)
            pref?.isVisible = BuildConfig.FIREBASE_AVAILABLE && subscription.baseUrl == appBaseUrl
            pref?.isChecked = subscription.instant
            pref?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putBoolean(key: String?, value: Boolean) {
                    save(subscription.copy(instant = value), refresh = true)
                }
                override fun getBoolean(key: String?, defValue: Boolean): Boolean {
                    return subscription.instant
                }
            }
            pref?.summaryProvider = Preference.SummaryProvider<SwitchPreferenceCompat> { preference ->
                if (preference.isChecked) {
                    getString(R.string.detail_settings_notifications_instant_summary_on)
                } else {
                    getString(R.string.detail_settings_notifications_instant_summary_off)
                }
            }
        }

        private fun loadDedicatedChannelsPrefs() {
            val prefId = context?.getString(R.string.detail_settings_notifications_dedicated_channels_key) ?: return
            val pref: SwitchPreferenceCompat? = findPreference(prefId)
            pref?.isVisible = true
            pref?.isChecked = subscription.dedicatedChannels
            pref?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putBoolean(key: String?, value: Boolean) {
                    save(subscription.copy(dedicatedChannels = value))
                    if (value) {
                        notificationService.createSubscriptionNotificationChannels(subscription)
                    } else {
                        notificationService.deleteSubscriptionNotificationChannels(subscription)
                    }
                    openChannelsPref.isVisible = value
                }
                override fun getBoolean(key: String?, defValue: Boolean): Boolean {
                    return subscription.dedicatedChannels
                }
            }
            pref?.summaryProvider = Preference.SummaryProvider<SwitchPreferenceCompat> { preference ->
                if (preference.isChecked) {
                    getString(R.string.detail_settings_notifications_dedicated_channels_summary_on)
                } else {
                    getString(R.string.detail_settings_notifications_dedicated_channels_summary_off)
                }
            }
        }

        private fun loadOpenChannelsPrefs() {
            val prefId = context?.getString(R.string.detail_settings_notifications_open_channels_key) ?: return
            openChannelsPref = findPreference(prefId) ?: return
            openChannelsPref.isVisible = subscription.dedicatedChannels
            openChannelsPref.preferenceDataStore = object : PreferenceDataStore() { } // Dummy store to protect from accidentally overwriting
            openChannelsPref.onPreferenceClickListener = Preference.OnPreferenceClickListener { _ ->
                val settingsIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().applicationContext.packageName)
                startActivity(settingsIntent);
                true
            }
        }

        private fun loadMutedUntilPref() {
            val prefId = context?.getString(R.string.detail_settings_notifications_muted_until_key) ?: return
            val pref: ListPreference? = findPreference(prefId)
            pref?.isVisible = true // Hack: Show all settings at once, because subscription is loaded asynchronously
            pref?.value = subscription.mutedUntil.toString()
            pref?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val mutedUntilValue = value?.toLongOrNull() ?:return
                    when (mutedUntilValue) {
                        Repository.MUTED_UNTIL_SHOW_ALL -> save(subscription.copy(mutedUntil = mutedUntilValue))
                        Repository.MUTED_UNTIL_FOREVER -> save(subscription.copy(mutedUntil = mutedUntilValue))
                        Repository.MUTED_UNTIL_TOMORROW -> {
                            val date = Calendar.getInstance()
                            date.add(Calendar.DAY_OF_MONTH, 1)
                            date.set(Calendar.HOUR_OF_DAY, 8)
                            date.set(Calendar.MINUTE, 30)
                            date.set(Calendar.SECOND, 0)
                            date.set(Calendar.MILLISECOND, 0)
                            save(subscription.copy(mutedUntil = date.timeInMillis/1000))
                        }
                        else -> {
                            val mutedUntilTimestamp = System.currentTimeMillis()/1000 + mutedUntilValue * 60
                            save(subscription.copy(mutedUntil = mutedUntilTimestamp))
                        }
                    }
                }
                override fun getString(key: String?, defValue: String?): String {
                    return subscription.mutedUntil.toString()
                }
            }
            pref?.summaryProvider = Preference.SummaryProvider<ListPreference> {
                when (val mutedUntilValue = subscription.mutedUntil) {
                    Repository.MUTED_UNTIL_SHOW_ALL -> getString(R.string.settings_notifications_muted_until_show_all)
                    Repository.MUTED_UNTIL_FOREVER -> getString(R.string.settings_notifications_muted_until_forever)
                    else -> {
                        val formattedDate = formatDateShort(mutedUntilValue)
                        getString(R.string.settings_notifications_muted_until_x, formattedDate)
                    }
                }
            }
        }

        private fun loadMinPriorityPref() {
            val prefId = context?.getString(R.string.detail_settings_notifications_min_priority_key) ?: return
            val pref: ListPreference? = findPreference(prefId)
            pref?.isVisible = true // Hack: Show all settings at once, because subscription is loaded asynchronously
            pref?.value = subscription.minPriority.toString()
            pref?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val minPriorityValue = value?.toIntOrNull() ?:return
                    save(subscription.copy(minPriority = minPriorityValue))
                }
                override fun getString(key: String?, defValue: String?): String {
                    return subscription.minPriority.toString()
                }
            }
            pref?.summaryProvider = Preference.SummaryProvider<ListPreference> { preference ->
                var value = preference.value.toIntOrNull() ?: Repository.MIN_PRIORITY_USE_GLOBAL
                val global = value == Repository.MIN_PRIORITY_USE_GLOBAL
                if (value == Repository.MIN_PRIORITY_USE_GLOBAL) {
                    value = repository.getMinPriority()
                }
                val summary = when (value) {
                    PRIORITY_MIN -> getString(R.string.settings_notifications_min_priority_summary_any)
                    PRIORITY_MAX -> getString(R.string.settings_notifications_min_priority_summary_max)
                    else -> {
                        val minPriorityString = toPriorityString(requireContext(), value)
                        getString(R.string.settings_notifications_min_priority_summary_x_or_higher, value, minPriorityString)
                    }
                }
                maybeAppendGlobal(summary, global)
            }
        }

        private fun loadAutoDeletePref() {
            val prefId = context?.getString(R.string.detail_settings_notifications_auto_delete_key) ?: return
            val pref: ListPreference? = findPreference(prefId)
            pref?.isVisible = true // Hack: Show all settings at once, because subscription is loaded asynchronously
            pref?.value = subscription.autoDelete.toString()
            pref?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val seconds = value?.toLongOrNull() ?:return
                    save(subscription.copy(autoDelete = seconds))
                }
                override fun getString(key: String?, defValue: String?): String {
                    return subscription.autoDelete.toString()
                }
            }
            pref?.summaryProvider = Preference.SummaryProvider<ListPreference> { preference ->
                var seconds = preference.value.toLongOrNull() ?: Repository.AUTO_DELETE_USE_GLOBAL
                val global = seconds == Repository.AUTO_DELETE_USE_GLOBAL
                if (global) {
                    seconds = repository.getAutoDeleteSeconds()
                }
                val summary = when (seconds) {
                    Repository.AUTO_DELETE_NEVER -> getString(R.string.settings_notifications_auto_delete_summary_never)
                    Repository.AUTO_DELETE_ONE_DAY_SECONDS -> getString(R.string.settings_notifications_auto_delete_summary_one_day)
                    Repository.AUTO_DELETE_THREE_DAYS_SECONDS -> getString(R.string.settings_notifications_auto_delete_summary_three_days)
                    Repository.AUTO_DELETE_ONE_WEEK_SECONDS -> getString(R.string.settings_notifications_auto_delete_summary_one_week)
                    Repository.AUTO_DELETE_ONE_MONTH_SECONDS -> getString(R.string.settings_notifications_auto_delete_summary_one_month)
                    Repository.AUTO_DELETE_THREE_MONTHS_SECONDS -> getString(R.string.settings_notifications_auto_delete_summary_three_months)
                    else -> getString(R.string.settings_notifications_auto_delete_summary_one_month) // Must match default const
                }
                maybeAppendGlobal(summary, global)
            }
        }

        private fun loadInsistentMaxPriorityPref() {
            val prefId = context?.getString(R.string.detail_settings_notifications_insistent_max_priority_key) ?: return
            val pref: ListPreference? = findPreference(prefId)
            pref?.isVisible = true
            pref?.value = subscription.insistent.toString()
            pref?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val intValue = value?.toIntOrNull() ?:return
                    save(subscription.copy(insistent = intValue))
                }
                override fun getString(key: String?, defValue: String?): String {
                    return subscription.insistent.toString()
                }
            }
            pref?.summaryProvider = Preference.SummaryProvider<ListPreference> { preference ->
                val value = preference.value.toIntOrNull() ?: Repository.INSISTENT_MAX_PRIORITY_USE_GLOBAL
                val global = value == Repository.INSISTENT_MAX_PRIORITY_USE_GLOBAL
                val enabled = if (global) repository.getInsistentMaxPriorityEnabled() else value == Repository.INSISTENT_MAX_PRIORITY_ENABLED
                val summary = if (enabled) {
                    getString(R.string.settings_notifications_insistent_max_priority_summary_enabled)
                } else {
                    getString(R.string.settings_notifications_insistent_max_priority_summary_disabled)
                }
                maybeAppendGlobal(summary, global)
            }
        }

        private fun loadIconSetPref() {
            val prefId = context?.getString(R.string.detail_settings_appearance_icon_set_key) ?: return
            iconSetPref = findPreference(prefId) ?: return
            iconSetPref.isVisible = subscription.icon == null
            iconSetPref.preferenceDataStore = object : PreferenceDataStore() { } // Dummy store to protect from accidentally overwriting
            iconSetPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                iconSetLauncher.launch("image/*")
                true
            }
        }

        private fun loadIconRemovePref() {
            val prefId = context?.getString(R.string.detail_settings_appearance_icon_remove_key) ?: return
            iconRemovePref = findPreference(prefId) ?: return
            iconRemovePref.isVisible = subscription.icon != null
            iconRemovePref.preferenceDataStore = object : PreferenceDataStore() { } // Dummy store to protect from accidentally overwriting
            iconRemovePref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                iconRemovePref.isVisible = false
                iconSetPref.isVisible = true
                deleteIcon(subscription.icon)
                save(subscription.copy(icon = null))
                true
            }

            // Set icon (if it exists)
            if (subscription.icon != null) {
                try {
                    val bitmap = subscription.icon!!.readBitmapFromUri(requireContext())
                    iconRemovePref.icon = bitmap.toDrawable(resources)
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to set icon ${subscription.icon}", e)
                }
            }
        }

        private fun loadDisplayNamePref() {
            val prefId = context?.getString(R.string.detail_settings_appearance_display_name_key) ?: return
            val pref: EditTextPreference? = findPreference(prefId)
            pref?.isVisible = true // Hack: Show all settings at once, because subscription is loaded asynchronously
            pref?.text = subscription.displayName
            pref?.dialogMessage = getString(R.string.detail_settings_appearance_display_name_message, topicShortUrl(subscription.baseUrl, subscription.topic))
            pref?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val displayName = if (value != "") value else null
                    val newSubscription = subscription.copy(displayName = displayName)
                    save(newSubscription)
                    // Update activity title
                    activity?.runOnUiThread {
                        activity?.title = displayName(appBaseUrl, newSubscription)
                    }
                    // Update dedicated notification channel
                    if (newSubscription.dedicatedChannels) {
                        notificationService.createSubscriptionNotificationChannels(newSubscription)
                    }
                }
                override fun getString(key: String?, defValue: String?): String {
                    return subscription.displayName ?: ""
                }
            }
            pref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { provider ->
                if (TextUtils.isEmpty(provider.text)) {
                    val appBaseUrl = context?.getString(R.string.app_base_url)
                    getString(
                        R.string.detail_settings_appearance_display_name_default_summary,
                        displayName(appBaseUrl, subscription)
                    )
                } else {
                    provider.text
                }
            }
        }

        private fun loadTopicUrlPref() {
            // Topic URL
            val topicUrlPrefId = context?.getString(R.string.detail_settings_about_topic_url_key) ?: return
            val topicUrlPref: Preference? = findPreference(topicUrlPrefId)
            val topicUrl = topicShortUrl(subscription.baseUrl, subscription.topic)
            topicUrlPref?.summary = topicUrl
            topicUrlPref?.onPreferenceClickListener = OnPreferenceClickListener {
                val context = context ?: return@OnPreferenceClickListener false
                copyToClipboard(context, "topic url", topicUrl)
                true
            }
        }

        private fun createIconPickLauncher(): ActivityResultLauncher<String> {
            return registerForActivityResult(ActivityResultContracts.GetContent()) { inputUri ->
                if (inputUri == null) {
                    return@registerForActivityResult
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val outputUri = createUri() ?: return@launch
                    try {
                        // Early size & mime type check
                        val mimeType = resolver.getType(inputUri)
                        if (!supportedImage(mimeType)) {
                            throw IOException("unknown image type or not supported")
                        }
                        val stat = fileStat(requireContext(), inputUri) // May throw
                        if (stat.size > SUBSCRIPTION_ICON_MAX_SIZE_BYTES) {
                            throw IOException("image too large, max supported is ${SUBSCRIPTION_ICON_MAX_SIZE_BYTES/1024/1024}MB")
                        }

                        // Write to cache storage
                        val inputStream = resolver.openInputStream(inputUri) ?: throw IOException("Couldn't open content URI for reading")
                        val outputStream = resolver.openOutputStream(outputUri) ?: throw IOException("Couldn't open content URI for writing")
                        inputStream.use {
                            it.copyTo(outputStream)
                        }

                        // Read image, check dimensions
                        val bitmap = outputUri.readBitmapFromUri(requireContext())
                        if (bitmap.width > SUBSCRIPTION_ICON_MAX_WIDTH || bitmap.height > SUBSCRIPTION_ICON_MAX_HEIGHT) {
                            throw IOException("image exceeds max dimensions of ${SUBSCRIPTION_ICON_MAX_WIDTH}x${SUBSCRIPTION_ICON_MAX_HEIGHT}")
                        }

                        // Display "remove" preference
                        iconRemovePref.icon = bitmap.toDrawable(resources)
                        iconRemovePref.isVisible = true
                        iconSetPref.isVisible = false

                        // Finally, save (this is last!)
                        save(subscription.copy(icon = outputUri.toString()))
                    } catch (e: Exception) {
                        Log.w(TAG, "Saving icon failed", e)
                        requireActivity().runOnUiThread {
                            Toast.makeText(context, getString(R.string.detail_settings_appearance_icon_error_saving, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        private fun createUri(): Uri? {
            val dir = File(requireContext().filesDir, SUBSCRIPTION_ICONS)
            if (!dir.exists() && !dir.mkdirs()) {
                return null
            }
            val file =  File(dir, subscription.id.toString())
            return FileProvider.getUriForFile(requireContext(), DownloadAttachmentWorker.FILE_PROVIDER_AUTHORITY, file)
        }

        private fun deleteIcon(uri: String?) {
            if (uri == null) {
                return
            }
            try {
                resolver.delete(uri.toUri(), null, null)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to delete $uri", e)
            }
        }

        private fun loadAllowedSendersPrefs() {
            val context = context ?: return

            // Whitelist enabled toggle — fully manual, no preference persistence framework
            val enabledPrefId = context.getString(R.string.detail_settings_allowed_senders_enabled_key)
            val enabledPref: SwitchPreferenceCompat? = findPreference(enabledPrefId)
            enabledPref?.isPersistent = false
            enabledPref?.isChecked = subscription.whitelistEnabled
            enabledPref?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                subscription = subscription.copy(whitelistEnabled = enabled)
                val subId = subscription.id
                lifecycleScope.launch(Dispatchers.IO) {
                    repository.updateWhitelistEnabled(subId, enabled)
                    serviceManager.refresh()
                }
                true
            }
            enabledPref?.summaryProvider = Preference.SummaryProvider<SwitchPreferenceCompat> { pref ->
                if (pref.isChecked) {
                    getString(R.string.detail_settings_allowed_senders_enabled_summary_on)
                } else {
                    getString(R.string.detail_settings_allowed_senders_enabled_summary_off)
                }
            }

            // Dynamically add existing senders to the preference category
            val headerPrefId = context.getString(R.string.detail_settings_allowed_senders_header_key)
            val headerCategory: PreferenceCategory? = findPreference(headerPrefId)

            fun refreshSenderList() {
                lifecycleScope.launch(Dispatchers.IO) {
                    val senders = repository.getAllowedSenders(subscription.id)
                    activity?.runOnUiThread {
                        // Remove dynamically-added sender prefs (keep the 3 static ones)
                        val toRemove = mutableListOf<Preference>()
                        for (i in 0 until (headerCategory?.preferenceCount ?: 0)) {
                            val pref = headerCategory?.getPreference(i)
                            if (pref?.key?.startsWith("sender_") == true) {
                                toRemove.add(pref)
                            }
                        }
                        toRemove.forEach { headerCategory?.removePreference(it) }

                        // Add sender prefs
                        senders.forEach { pubkeyHex ->
                            val npub = try {
                                Bech32.encodeBytes("npub", Hex.decode(pubkeyHex), Bech32.Encoding.Bech32)
                            } catch (e: Exception) { pubkeyHex }
                            val pref = Preference(context)
                            pref.key = "sender_$pubkeyHex"
                            pref.title = npub.take(20) + "…" + npub.takeLast(8)
                            pref.summary = "Tap to remove"
                            pref.onPreferenceClickListener = OnPreferenceClickListener {
                                MaterialAlertDialogBuilder(context)
                                    .setTitle(getString(R.string.detail_settings_allowed_senders_removed))
                                    .setMessage("Remove ${npub.take(20)}…?")
                                    .setPositiveButton(android.R.string.ok) { _, _ ->
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            repository.removeAllowedSender(subscription.id, pubkeyHex)
                                            activity?.runOnUiThread { refreshSenderList() }
                                        }
                                    }
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .show()
                                true
                            }
                            headerCategory?.addPreference(pref)
                        }
                    }
                }
            }
            refreshSenderList()

            // Add by npub
            val addNpubPrefId = context.getString(R.string.detail_settings_allowed_senders_add_npub_key)
            val addNpubPref: Preference? = findPreference(addNpubPrefId)
            addNpubPref?.onPreferenceClickListener = OnPreferenceClickListener {
                val input = android.widget.EditText(context)
                input.hint = "npub1… or hex"
                MaterialAlertDialogBuilder(context)
                    .setTitle(getString(R.string.detail_settings_allowed_senders_add_dialog_title))
                    .setMessage(getString(R.string.detail_settings_allowed_senders_add_dialog_message))
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val raw = input.text.toString().trim()
                        val hexKey = try {
                            if (raw.startsWith("npub1")) {
                                val decoded = Bech32.decodeBytes(raw)
                                require(decoded.first == "npub")
                                Hex.encode(decoded.second)
                            } else if (Hex.isHex64(raw)) {
                                raw.lowercase()
                            } else {
                                throw IllegalArgumentException("Invalid")
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, getString(R.string.detail_settings_allowed_senders_invalid), Toast.LENGTH_LONG).show()
                            return@setPositiveButton
                        }
                        lifecycleScope.launch(Dispatchers.IO) {
                            repository.addAllowedSender(subscription.id, hexKey)
                            activity?.runOnUiThread {
                                Toast.makeText(context, getString(R.string.detail_settings_allowed_senders_added), Toast.LENGTH_SHORT).show()
                                refreshSenderList()
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }

            // Search by name (WoT via brainstorm.world)
            val searchPrefId = context.getString(R.string.detail_settings_allowed_senders_search_key)
            val searchPref: Preference? = findPreference(searchPrefId)
            searchPref?.onPreferenceClickListener = OnPreferenceClickListener {
                showWotSearchDialog(refreshSenderList = { refreshSenderList() })
                true
            }
        }

        data class WotProfile(val pubkeyHex: String, val npub: String, val displayName: String, val avatarUrl: String?)

        private fun showWotSearchDialog(refreshSenderList: () -> Unit) {
            val context = context ?: return
            val dialogView = layoutInflater.inflate(R.layout.dialog_wot_search, null)
            val searchInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.wot_search_input)
            val progressBar = dialogView.findViewById<android.widget.ProgressBar>(R.id.wot_search_progress)
            val statusText = dialogView.findViewById<android.widget.TextView>(R.id.wot_search_status)
            val resultsList = dialogView.findViewById<android.widget.ListView>(R.id.wot_search_results)

            val profiles = mutableListOf<WotProfile>()
            val adapter = WotProfileAdapter(context, profiles)
            resultsList.adapter = adapter

            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(getString(R.string.detail_settings_allowed_senders_search_dialog_title))
                .setView(dialogView)
                .setNegativeButton(android.R.string.cancel, null)
                .create()

            var activeWebSocket: okhttp3.WebSocket? = null
            var searchJob: Job? = null

            // Tap a result to add
            resultsList.setOnItemClickListener { _, _, position, _ ->
                val selected = profiles[position]
                lifecycleScope.launch(Dispatchers.IO) {
                    repository.addAllowedSender(subscription.id, selected.pubkeyHex)
                    activity?.runOnUiThread {
                        Toast.makeText(context, getString(R.string.detail_settings_allowed_senders_added), Toast.LENGTH_SHORT).show()
                        refreshSenderList()
                        dialog.dismiss()
                    }
                }
            }

            val userPubkey = try {
                (requireActivity().application as io.heckel.ntfy.app.Application).keyManager.getPubKeyHex()
            } catch (e: Exception) { "" }

            val okHttpClient = okhttp3.OkHttpClient.Builder()
                .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            fun doSearch(query: String) {
                // Cancel previous search
                activeWebSocket?.close(1000, "new search")
                profiles.clear()
                adapter.notifyDataSetChanged()

                if (query.length < 2) {
                    progressBar.visibility = android.view.View.GONE
                    statusText.visibility = android.view.View.GONE
                    return
                }

                progressBar.visibility = android.view.View.VISIBLE
                statusText.visibility = android.view.View.VISIBLE
                statusText.text = getString(R.string.detail_settings_allowed_senders_searching)

                val searchStr = if (userPubkey.isNotEmpty()) {
                    "$query observer:$userPubkey sort:followers:desc filter:rank:gte:2"
                } else {
                    query
                }

                val request = okhttp3.Request.Builder()
                    .url("wss://brainstorm.world/relay")
                    .build()

                activeWebSocket = okHttpClient.newWebSocket(request, object : okhttp3.WebSocketListener() {
                    override fun onOpen(ws: okhttp3.WebSocket, response: okhttp3.Response) {
                        val escapedSearch = searchStr.replace("\"", "\\\"")
                        val req = """["REQ","wot-search",{"kinds":[0],"limit":15,"search":"$escapedSearch"}]"""
                        ws.send(req)
                    }
                    override fun onMessage(ws: okhttp3.WebSocket, text: String) {
                        try {
                            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                            val arr = json.parseToJsonElement(text)
                            if (arr is kotlinx.serialization.json.JsonArray && arr.size >= 3) {
                                val type = arr[0].toString().trim('"')
                                if (type == "EVENT") {
                                    val eventObj = arr[2] as? kotlinx.serialization.json.JsonObject ?: return
                                    val pubkey = eventObj["pubkey"]?.toString()?.trim('"') ?: return
                                    val contentRaw = eventObj["content"]?.toString() ?: "{}"
                                    // Content is a JSON string inside a JSON string — remove outer quotes and unescape
                                    val content = if (contentRaw.startsWith("\"") && contentRaw.endsWith("\"")) {
                                        contentRaw.substring(1, contentRaw.length - 1)
                                            .replace("\\\"", "\"")
                                            .replace("\\\\", "\\")
                                            .replace("\\/", "/")
                                            .replace("\\n", "\n")
                                    } else contentRaw
                                    val contentObj = try {
                                        json.parseToJsonElement(content) as? kotlinx.serialization.json.JsonObject
                                    } catch (e: Exception) { null }
                                    val name = contentObj?.get("display_name")?.toString()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
                                        ?: contentObj?.get("name")?.toString()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
                                        ?: pubkey.take(16)
                                    val avatarUrl = contentObj?.get("picture")?.toString()?.trim('"')?.takeIf { it.startsWith("http") }
                                    val npub = try {
                                        Bech32.encodeBytes("npub", Hex.decode(pubkey), Bech32.Encoding.Bech32)
                                    } catch (e: Exception) { pubkey }
                                    val profile = WotProfile(pubkey, npub, name, avatarUrl)
                                    activity?.runOnUiThread {
                                        profiles.add(profile)
                                        adapter.notifyDataSetChanged()
                                        statusText.text = "${profiles.size} results"
                                    }
                                } else if (type == "EOSE") {
                                    ws.close(1000, "done")
                                    activity?.runOnUiThread {
                                        progressBar.visibility = android.view.View.GONE
                                        if (profiles.isEmpty()) {
                                            statusText.text = getString(R.string.detail_settings_allowed_senders_no_results)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "WoT search parse error: ${e.message}")
                        }
                    }
                    override fun onFailure(ws: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                        Log.w(TAG, "WoT search failed: ${t.message}")
                        activity?.runOnUiThread {
                            progressBar.visibility = android.view.View.GONE
                            statusText.text = "Search failed: ${t.message}"
                        }
                    }
                })
            }

            // Debounced search on text change
            searchInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    searchJob?.cancel()
                    searchJob = lifecycleScope.launch {
                        delay(300)
                        doSearch(s?.toString()?.trim() ?: "")
                    }
                }
            })

            dialog.setOnDismissListener {
                activeWebSocket?.close(1000, "dialog closed")
                searchJob?.cancel()
            }

            dialog.show()

            // Auto-focus the search input
            searchInput.postDelayed({
                searchInput.requestFocus()
                val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }, 200)
        }

        private inner class WotProfileAdapter(
            context: android.content.Context,
            private val profiles: List<WotProfile>
        ) : android.widget.ArrayAdapter<WotProfile>(context, R.layout.item_wot_profile, profiles) {
            private val inflater = android.view.LayoutInflater.from(context)
            private val avatarCache = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap?>()
            private val okHttpClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = convertView ?: inflater.inflate(R.layout.item_wot_profile, parent, false)
                val profile = profiles[position]

                val nameView = view.findViewById<android.widget.TextView>(R.id.wot_profile_name)
                val npubView = view.findViewById<android.widget.TextView>(R.id.wot_profile_npub)
                val avatarView = view.findViewById<android.widget.ImageView>(R.id.wot_profile_avatar)

                nameView.text = profile.displayName
                npubView.text = profile.npub

                // Load avatar (tag the view to prevent recycling bugs)
                val avatarUrl = profile.avatarUrl
                avatarView.tag = avatarUrl
                if (avatarUrl != null) {
                    val cached = avatarCache[avatarUrl]
                    if (cached != null) {
                        avatarView.setImageBitmap(cached)
                    } else {
                        avatarView.setImageResource(R.drawable.ic_notification)
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val request = okhttp3.Request.Builder().url(avatarUrl).build()
                                val response = okHttpClient.newCall(request).execute()
                                val bytes = response.body?.bytes()
                                if (bytes != null) {
                                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    if (bitmap != null) {
                                        avatarCache[avatarUrl] = bitmap
                                        activity?.runOnUiThread {
                                            // Only set if this view still expects this URL
                                            if (avatarView.tag == avatarUrl) {
                                                avatarView.setImageBitmap(bitmap)
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Ignore avatar load failures
                            }
                        }
                    }
                } else {
                    avatarView.setImageResource(R.drawable.ic_notification)
                }

                return view
            }
        }

        private fun save(newSubscription: Subscription, refresh: Boolean = false) {
            subscription = newSubscription
            // Use application scope so the write isn't cancelled when the fragment is destroyed
            val appScope = (requireActivity().application as io.heckel.ntfy.app.Application).ioScope
            appScope.launch {
                repository.updateSubscription(newSubscription)
                if (refresh) {
                    SubscriberServiceManager.refresh(requireContext())
                }
            }
        }

        private fun maybeAppendGlobal(summary: String, global: Boolean): String {
            return if (global) {
                summary + " (" + getString(R.string.detail_settings_global_setting_suffix) + ")"
            } else {
                summary
            }
        }
    }

    companion object {
        private const val TAG = "NtfyDetailSettingsActiv"
        private const val SUBSCRIPTION_ICON_MAX_SIZE_BYTES = 4194304
        private const val SUBSCRIPTION_ICON_MAX_WIDTH = 2048
        private const val SUBSCRIPTION_ICON_MAX_HEIGHT = 2048
    }
}
