package com.squaretoast.pos.hub

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LocksHolderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    @Test
    fun acquireHoldsAWifiLockAndAPartialWakeLockAndReleaseDropsBoth() {
        val locks = LocksHolder(wifi, power)

        locks.acquire()
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertEquals(1, shadowOf(wifi).activeLockCount)
        assertEquals("squaretoast:hub", shadowOf(ShadowPowerManager.getLatestWakeLock()).tag)

        locks.release()
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertEquals(0, shadowOf(wifi).activeLockCount)
    }

    @Test
    fun acquireAndReleaseAreIdempotent() {
        val locks = LocksHolder(wifi, power)
        locks.acquire()
        locks.acquire()
        assertEquals(1, shadowOf(wifi).activeLockCount)
        locks.release()
        locks.release()
        assertEquals(0, shadowOf(wifi).activeLockCount)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test
    @Config(sdk = [29])
    fun usesLowLatencyWifiOnAndroid10AndUp() {
        assertEquals(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, LocksHolder(wifi, power).wifiMode)
    }

    @Test
    @Config(sdk = [28])
    @Suppress("DEPRECATION")
    fun usesHighPerfWifiBelowAndroid10() {
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF, LocksHolder(wifi, power).wifiMode)
    }
}
