package com.squaretoast.pos.payment

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.nfc.NfcAdapter
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

/** Real device answers for [TapToPayGate]; the backend and SDK calls are passed in. */
class AndroidDeviceSignals(
    private val context: Context,
    private val tokenRoundTrip: suspend () -> Boolean,
    private val supported: () -> Boolean,
    private val attestation: suspend () -> Boolean,
) : DeviceSignals {
    override val sdkInt: Int get() = Build.VERSION.SDK_INT
    override fun hasNfc() = NfcAdapter.getDefaultAdapter(context) != null
    override fun nfcEnabled() = NfcAdapter.getDefaultAdapter(context)?.isEnabled == true
    override fun networkValidated(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    override suspend fun connectionTokenRoundTrip() = tokenRoundTrip()
    override fun tapToPaySupported() = supported()
    override suspend fun attest() = attestation()
}

/**
 * The device's claim-signing key: ECDSA P-256 in Android Keystore (hardware-backed where available, every API
 * level from 23). The private key cannot be exported.
 */
object KeystoreClaimKey {
    private const val ALIAS = "squaretoast.claim.v1"

    fun loadOrCreate(): JcaClaimKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build())
            }.generateKeyPair()
        }
        val key = ks.getKey(ALIAS, null) as PrivateKey
        return JcaClaimKey(key, ks.getCertificate(ALIAS).publicKey)
    }
}
