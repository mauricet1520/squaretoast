package com.squaretoast.pos

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.squaretoast.pos.hub.HubForegroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManifestTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pm = context.packageManager

    @Test
    fun declaresThePermissionsTapToPayAndTheHubNeed() {
        val requested = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions!!.toSet()
        for (p in listOf(
            "android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.NFC",
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_WIFI_STATE",
            "android.permission.CHANGE_WIFI_MULTICAST_STATE",
            "android.permission.WAKE_LOCK",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT",
        )) {
            assertTrue("missing $p", p in requested)
        }
    }

    @Test
    fun minSdkIs26() {
        assertEquals(26, context.applicationInfo.minSdkVersion)
    }

    @Test
    fun hubRunsAsANonExportedConnectedDeviceForegroundService() {
        val info = pm.getServiceInfo(ComponentName(context, HubForegroundService::class.java), 0)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, info.foregroundServiceType)
        assertFalse(info.exported)
    }

    @Test
    fun theApplicationClassIsThePosApplication() {
        assertEquals(PosApplication::class.java.name, context.applicationInfo.className)
    }

    @Test
    fun cleartextIsNotAllowedToTheCloud() {
        // The hub serves the KDS over LAN http; the app itself only talks https to the backend.
        assertFalse(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0)
    }
}
