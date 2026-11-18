package com.squaretoast.pos.payment

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.time.Clock
import java.time.Duration
import java.util.Base64

/** What the POS knows about an unpaid check while offline. Amounts are integer cents. */
data class ClaimDraft(
    val checkId: String,
    val locationId: String,
    val deviceId: String,
    val linesDigest: String,
    val subtotalCents: Long,
    val taxCents: Long,
    val serviceChargeCents: Long,
    val totalCents: Long,
    val currency: String,
)

@Serializable
data class CheckClaim(
    val checkId: String,
    val locationId: String,
    val deviceId: String,
    val linesDigest: String,
    val subtotalCents: Long,
    val taxCents: Long,
    val serviceChargeCents: Long,
    val totalCents: Long,
    val currency: String,
    val expiresAtEpochSecond: Long,
    val nonce: String,
) {
    internal fun isConsistent() = subtotalCents >= 0 && taxCents >= 0 && serviceChargeCents >= 0 && totalCents > 0 &&
        totalCents == subtotalCents + taxCents + serviceChargeCents && CURRENCY_CODE.matches(currency)

}

/** The device key. Production keeps the private key in Android Keystore; it never leaves the hardware. */
interface ClaimKey {
    val publicKey: PublicKey
    fun sign(data: ByteArray): ByteArray
}

class JcaClaimKey(private val privateKey: PrivateKey, override val publicKey: PublicKey) : ClaimKey {
    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance(ALGORITHM).run { initSign(privateKey); update(data); sign() }

    companion object { const val ALGORITHM = "SHA256withECDSA" }
}

private val CURRENCY_CODE = Regex("[A-Z]{3}")

private val b64 = Base64.getUrlEncoder().withoutPadding()

class SignedClaim(val claim: CheckClaim, private val payload: ByteArray, private val signature: ByteArray) {
    /** The claim rides in the fragment, which browsers never send to servers or in Referer headers. */
    fun qrUrl(base: String = "https://pay.squaretoast.app/c"): String =
        "$base#${b64.encodeToString(payload)}.${b64.encodeToString(signature)}"
}

/** Signs check claims for offline QR Pay-at-Table. Claims expire two hours after issue. */
class OfflineClaimSigner(private val key: ClaimKey, private val clock: Clock, private val nonce: () -> String) {

    fun issue(draft: ClaimDraft): SignedClaim {
        val claim = with(draft) {
            CheckClaim(checkId, locationId, deviceId, linesDigest, subtotalCents, taxCents, serviceChargeCents,
                totalCents, currency, clock.instant().plus(TTL).epochSecond, nonce())
        }
        require(claim.isConsistent()) { "claim amounts or currency are invalid" }
        val payload = Json.encodeToString(claim).toByteArray(Charsets.UTF_8)
        return SignedClaim(claim, payload, key.sign(payload))
    }

    companion object { val TTL: Duration = Duration.ofHours(2) }
}

/** The backend runs the same check; this copy lets the POS and tests prove the format round-trips. */
object ClaimVerifier {
    fun verify(token: String, publicKey: PublicKey, clock: Clock): CheckClaim? {
        val parts = token.split('.')
        if (parts.size != 2) return null
        return try {
            val payload = Base64.getUrlDecoder().decode(parts[0])
            val sig = Base64.getUrlDecoder().decode(parts[1])
            val ok = Signature.getInstance(JcaClaimKey.ALGORITHM)
                .run { initVerify(publicKey); update(payload); verify(sig) }
            if (!ok) return null
            val claim = Json.decodeFromString<CheckClaim>(String(payload, Charsets.UTF_8))
            claim.takeIf { it.isConsistent() && clock.instant().epochSecond < it.expiresAtEpochSecond }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: SerializationException) {
            null
        }
    }
}
