package space.pitchstone.tether.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Whether the device has usable connectivity, and a way to wait for it to come back.
 *
 * The reconnect loop uses this so a returning network wakes it at once instead of sitting out the
 * rest of a backoff it started while the radio was off — the common case after a tunnel, a flight,
 * or a Wi-Fi handover.
 */
internal class NetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /**
     * VALIDATED and not just CONNECTED: a captive portal or a Wi-Fi network with no route out
     * reports a live link that the handshake would then fail against.
     */
    fun isOnline(): Boolean {
        val cm = connectivity ?: return true // unknown; let the connection attempt be the judge
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Suspends until a network is available, returning immediately if one already is. */
    suspend fun awaitOnline() {
        if (isOnline()) return
        val cm = connectivity ?: return
        suspendCancellableCoroutine { continuation ->
            // onAvailable fires per network, so a device with Wi-Fi and cellular can deliver it
            // more than once; resuming a continuation twice is a crash.
            val done = AtomicBoolean(false)
            lateinit var callback: ConnectivityManager.NetworkCallback
            callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (done.compareAndSet(false, true)) {
                        runCatching { cm.unregisterNetworkCallback(callback) }
                        continuation.resume(Unit)
                    }
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            runCatching { cm.registerNetworkCallback(request, callback) }.onFailure {
                if (done.compareAndSet(false, true)) continuation.resume(Unit)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                if (done.compareAndSet(false, true)) runCatching { cm.unregisterNetworkCallback(callback) }
            }
        }
    }
}
