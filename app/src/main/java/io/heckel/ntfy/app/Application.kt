package io.heckel.ntfy.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import com.google.android.material.color.DynamicColors
import io.heckel.ntfy.crypto.AmberDecryptor
import io.heckel.ntfy.crypto.EventDecryptor
import io.heckel.ntfy.crypto.KeyManager
import io.heckel.ntfy.crypto.LocalKeyDecryptor
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class Application : Application() {
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val keyManager by lazy { KeyManager(applicationContext) }

    /** Pending encrypted events that couldn't be decrypted in the background (Amber not available) */
    val pendingEncryptedEvents = java.util.concurrent.ConcurrentLinkedQueue<com.vitorpamplona.quartz.nip01Core.core.Event>()

    /** LiveData to notify the Activity when new pending events arrive */
    val pendingEventSignal = androidx.lifecycle.MutableLiveData<Int>(0)

    fun createDecryptor(): EventDecryptor = when (keyManager.getLoginMode()) {
        KeyManager.LoginMode.INTERNAL -> LocalKeyDecryptor(keyManager)
        KeyManager.LoginMode.AMBER -> AmberDecryptor(
            pubKeyHex = keyManager.getAmberPubKeyHex(),
            packageName = keyManager.getAmberPackageName(),
            contentResolver = contentResolver
        )
        KeyManager.LoginMode.NONE -> LocalKeyDecryptor(keyManager) // isAvailable()=false
    }

    val repository by lazy {
        val repository = Repository.getInstance(applicationContext)
        if (repository.getRecordLogs()) {
            Log.setRecord(true)
        }
        repository
    }

    override fun onCreate() {
        super.onCreate()
        if (repository.getDynamicColorsEnabled()) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            // If there's already a default network at registration time, registerDefaultNetworkCallback
            // will deliver an initial onAvailable for it. That's not a real transition, so skip the
            // first onAvailable in that case to avoid a spurious reconnect on every cold start.
            private var skipInitialAvailable = connectivityManager.activeNetwork != null

            override fun onAvailable(network: Network) {
                if (skipInitialAvailable) {
                    skipInitialAvailable = false
                    Log.d(TAG, "Skipping initial onAvailable for pre-existing default network ($network)")
                    return
                }
                // Force reconnect of all WebSocket/JSON connections so they're rebound to the new
                // default network. This catches Wi-Fi <-> cellular handoffs and similar transitions
                // where the underlying socket is bound to a network that's no longer the default.
                // Without this, broken connections would only be detected via the (potentially
                // long) ping/pong timeout.
                Log.i(TAG, "Default network available ($network); forcing reconnect of all connections")
                ioScope.launch {
                    repository.getSubscriptions()
                        .map { it.baseUrl }
                        .distinct()
                        .forEach { repository.incrementConnectionForceReconnectVersion(it) }
                    SubscriberServiceManager.refresh(this@Application)
                }
            }
            override fun onLost(network: Network) {
                // Once we've observed a loss, any subsequent onAvailable is a real transition.
                skipInitialAvailable = false
                Log.i(TAG, "Default network lost ($network); refreshing subscriber service")
                SubscriberServiceManager.refresh(this@Application)
            }
        })
    }

    companion object {
        private const val TAG = "NtfyApplication"
    }
}
