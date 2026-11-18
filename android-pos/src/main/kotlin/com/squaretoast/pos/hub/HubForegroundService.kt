package com.squaretoast.pos.hub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import javax.inject.Inject
import javax.inject.Singleton

/** Application-wide hub state: shared by the service (server) and the UI (pairing URL, rotate). */
@Singleton
class HubRuntime @Inject constructor() {
    val board = CourseBoard()
    val tokens = PairingTokens()
}

/**
 * The LAN sync hub. Runs as a connectedDevice foreground service (dataSync is capped at 6 h/day on Android 15)
 * holding Wi-Fi and wake locks, serves the KDS over plain LAN http, and advertises itself over mDNS.
 * No UPnP or router configuration is involved; the pairing URL/QR is the manual fallback.
 */
@AndroidEntryPoint
class HubForegroundService : Service() {

    @Inject lateinit var runtime: HubRuntime

    private lateinit var locks: LocksHolder
    private var server: EmbeddedServer<*, *>? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locks = LocksHolder(getSystemService(WifiManager::class.java), getSystemService(PowerManager::class.java))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        locks.acquire()
        if (server == null) {
            val bundle = KdsAssets { path ->
                try {
                    assets.open("kds/$path").use { it.readBytes() }
                } catch (_: java.io.IOException) {
                    null
                }
            }
            server = embeddedServer(CIO, port = PORT, host = "0.0.0.0") { hubModule(runtime.board, runtime.tokens, bundle) }
                .also { it.start(wait = false) }
            advertise()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        nsdListener?.let { runCatching { getSystemService(NsdManager::class.java).unregisterService(it) } }
        nsdListener = null
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        server = null
        locks.release()
        super.onDestroy()
    }

    private fun advertise() {
        val info = NsdServiceInfo().apply {
            serviceName = "SquareToast Hub"
            serviceType = SERVICE_TYPE
            port = PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        nsdListener = listener
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        run {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "LAN hub", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(this, 0, Intent(this, HubForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Kitchen hub running")
            .setContentText("KDS screens on this Wi-Fi stay in sync")
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val PORT = 8787
        const val SERVICE_TYPE = "_squaretoast._tcp"
        private const val CHANNEL = "hub"
        private const val NOTIFICATION_ID = 8787
        private const val ACTION_STOP = "com.squaretoast.pos.hub.STOP"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, HubForegroundService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, HubForegroundService::class.java))
    }
}
