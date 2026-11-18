package com.squaretoast.pos.hub

import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.annotation.SuppressLint

/** Keeps Wi-Fi and the CPU awake for the hub's whole shift. Both locks are released together. */
@SuppressLint("InlinedApi") // the API 29 constant is only chosen when sdkInt >= 29
class LocksHolder(wifi: WifiManager, power: PowerManager, sdkInt: Int = Build.VERSION.SDK_INT) {

    @Suppress("DEPRECATION") // FULL_HIGH_PERF is the only low-latency mode before API 29
    val wifiMode: Int = if (sdkInt >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF

    private val wifiLock = wifi.createWifiLock(wifiMode, TAG).apply { setReferenceCounted(false) }
    private val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).apply { setReferenceCounted(false) }

    @Synchronized
    fun acquire() {
        if (!wifiLock.isHeld) wifiLock.acquire()
        if (!wakeLock.isHeld) wakeLock.acquire(MAX_SHIFT_MS) // released in release(); capped so a leak cannot drain the battery
    }

    @Synchronized
    fun release() {
        if (wifiLock.isHeld) wifiLock.release()
        if (wakeLock.isHeld) wakeLock.release()
    }

    private companion object {
        const val TAG = "squaretoast:hub"
        const val MAX_SHIFT_MS = 16L * 60 * 60 * 1000
    }
}
