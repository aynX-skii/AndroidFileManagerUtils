package com.aynux.afmu.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.aynux.afmu.MainActivity
import com.aynux.afmu.R
import com.aynux.afmu.core.AuthRequests
import com.aynux.afmu.core.Bridge
import com.aynux.afmu.core.LocaleHelper
import com.aynux.afmu.core.PairSas
import com.aynux.afmu.core.PeerStore
import com.aynux.afmu.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the server alive while the app is in the background, and holds the Wi-Fi locks
 * Android would otherwise drop during doze — without them transfers stall on a locked screen.
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notifier: Job? = null
    private var authWatcher: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /** The notification must follow the language the user picked, like the rest of the UI. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Stopping from the notification is just as deliberate as flipping the switch
            // in the UI, so it has to be remembered the same way.
            Prefs(this).serverEnabled = false
            stopEverything()
            return START_NOT_STICKY
        }

        // Answering from the notification shade is the whole point of the feature: the phone
        // is usually in a pocket, not on the app's home screen, when the PC asks.
        if (intent?.action == ACTION_ALLOW || intent?.action == ACTION_DENY) {
            intent.getStringExtra(EXTRA_REQUEST_ID)?.let {
                // Approving here has to mean exactly what it means in the dialog — for a v2
                // pairing that includes writing the record. Calling decide() alone left the
                // pairing half-done: the initiator thought it was paired, this device refused
                // it from then on.
                if (intent.action == ACTION_ALLOW) AuthRequests.approve(it, PeerStore(this))
                else AuthRequests.decide(it, granted = false)
            }
            notificationManager().cancel(AUTH_NOTIFICATION_ID)
            // Falls through: the request only exists while the server runs, so this is not a
            // cold start and re-running the setup below is a no-op.
        }

        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_starting)))
        acquireLocks()
        Bridge.start(this, Prefs(this))

        // onStartCommand runs again on every start intent (and on a sticky restart);
        // without this guard each one would leave another collector updating the same
        // notification forever.
        if (notifier != null) return START_STICKY

        authWatcher = scope.launch {
            AuthRequests.pending.collectLatest { request ->
                // A pairing that has not reached step 2 has no compare code yet, and a prompt
                // with nothing to compare is a prompt asking the user to guess. Same rule as
                // the in-app dialog; the peer is mid-handshake and will get there in
                // milliseconds or time out on its own.
                if (request == null || (request.isPairing && request.sas.isEmpty())) {
                    notificationManager().cancel(AUTH_NOTIFICATION_ID)
                } else {
                    notificationManager().notify(AUTH_NOTIFICATION_ID, buildAuthNotification(request))
                }
            }
        }

        notifier = scope.launch {
            Bridge.state.collectLatest { state ->
                val text = if (state.running) {
                    state.urls.firstOrNull()?.let { getString(R.string.notif_reachable_at, it) }
                        ?: getString(R.string.notif_waiting_network)
                } else {
                    getString(R.string.notif_stopped)
                }
                notificationManager().notify(NOTIFICATION_ID, buildNotification(text))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopEverything()
        scope.cancel()
        super.onDestroy()
    }

    private fun stopEverything() {
        Bridge.stop()
        releaseLocks()
        notificationManager().cancel(AUTH_NOTIFICATION_ID)
        authWatcher?.cancel()
        authWatcher = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF is still the only option below API 29
    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi != null && wifiLock == null) {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi.createWifiLock(mode, TAG).apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
            // Needed to keep receiving broadcast discovery probes while the screen is off.
            multicastLock = wifi.createMulticastLock(TAG).apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
        }
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power != null && wakeLock == null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:transfer").apply {
                setReferenceCounted(false)
                runCatching { acquire(WAKE_LOCK_TIMEOUT_MS) }
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        multicastLock = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun buildNotification(text: String): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_stop_action), stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * The approval prompt. Heads-up (not full-screen): a full-screen intent needs a special
     * grant on Android 14+ and would be a heavy hammer for "your PC would like to connect".
     */
    private fun buildAuthNotification(request: AuthRequests.Request): android.app.Notification {
        fun action(name: String, code: Int) = PendingIntent.getService(
            this, code,
            Intent(this, TransferService::class.java)
                .setAction(name)
                .putExtra(EXTRA_REQUEST_ID, request.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // v2 shows the compare code, v1 the 4-digit confirmation code. `request.code` is
        // always empty for a pairing, so printing it unconditionally asked the user to approve
        // a pairing with no code at all — exactly what the dialog was fixed not to do.
        val body = if (request.isPairing) {
            getString(R.string.pair_notif_body, request.host, PairSas.format(request.sas))
        } else {
            getString(R.string.auth_notif_body, request.host, request.code)
        }
        return NotificationCompat.Builder(this, AUTH_CHANNEL_ID)
            .setContentTitle(
                getString(
                    if (request.isPairing) R.string.pair_notif_title else R.string.auth_notif_title,
                    request.name,
                )
            )
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(AuthRequests.TIMEOUT_SEC * 1000L)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 2, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .addAction(0, getString(R.string.auth_deny), action(ACTION_DENY, 3))
            .addAction(0, getString(R.string.auth_allow), action(ACTION_ALLOW, 4))
            .build()
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        // Separate channel, high importance: the ongoing transfer notification is meant to be
        // silent, but an approval prompt the user never sees is a dead end.
        val auth = NotificationChannel(
            AUTH_CHANNEL_ID, getString(R.string.auth_channel_name), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.auth_channel_desc)
            setShowBadge(true)
        }
        notificationManager().createNotificationChannel(channel)
        notificationManager().createNotificationChannel(auth)
    }

    companion object {
        const val ACTION_STOP = "com.aynux.afmu.STOP"
        const val ACTION_ALLOW = "com.aynux.afmu.AUTH_ALLOW"
        const val ACTION_DENY = "com.aynux.afmu.AUTH_DENY"
        const val EXTRA_REQUEST_ID = "request_id"
        private const val CHANNEL_ID = "afmu-transfer"
        private const val AUTH_CHANNEL_ID = "afmu-auth"
        private const val NOTIFICATION_ID = 1001
        private const val AUTH_NOTIFICATION_ID = 1002
        private const val TAG = "afmu"
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, TransferService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TransferService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
