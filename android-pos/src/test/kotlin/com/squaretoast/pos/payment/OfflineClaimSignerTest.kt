package com.squaretoast.pos.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class OfflineClaimSignerTest {

    private val now = Instant.parse("2026-11-18T20:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun p256(): KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private val pair = p256()
    private val signer = OfflineClaimSigner(JcaClaimKey(pair.private, pair.public), clock) { "nonce-1" }

    private val draft = ClaimDraft(
        checkId = "0193a8f0-0000-7000-8000-000000000001",
        locationId = "0193a8f0-0000-7000-8000-00000000000a",
        deviceId = "0193a8f0-0000-7000-8000-0000000000d1",
        linesDigest = "sha256:9f2c",
        subtotalCents = 4600,
        taxCents = 409,
        serviceChargeCents = 0,
        totalCents = 5009,
        currency = "USD",
    )

    private fun fragment(url: String) = url.substringAfter('#')

    @Test
    fun issuedClaimVerifiesWithTheDevicePublicKey() {
        val signed = signer.issue(draft)

        val claim = ClaimVerifier.verify(fragment(signed.qrUrl()), pair.public, clock)!!
        assertEquals(draft.checkId, claim.checkId)
        assertEquals(5009L, claim.totalCents)
        assertEquals("USD", claim.currency)
        assertEquals("nonce-1", claim.nonce)
        assertEquals(now.plus(Duration.ofHours(2)).epochSecond, claim.expiresAtEpochSecond)
    }

    @Test
    fun theClaimTravelsInTheUrlFragmentSoItNeverReachesServerLogs() {
        val url = signer.issue(draft).qrUrl()
        assertTrue(url.startsWith("https://pay.squaretoast.app/c#"))
        assertFalse(url.contains('?'))
        val token = fragment(url)
        assertEquals(2, token.split('.').size)
        assertTrue(token.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' })
    }

    @Test
    fun aTamperedTotalFailsVerification() {
        val (payload, sig) = fragment(signer.issue(draft).qrUrl()).split('.')
        val json = String(Base64.getUrlDecoder().decode(payload))
        assertTrue(json.contains("\"totalCents\":5009"))
        val forged = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.replace("\"totalCents\":5009", "\"totalCents\":509").toByteArray())

        assertNull(ClaimVerifier.verify("$forged.$sig", pair.public, clock))
    }

    @Test
    fun anotherDevicesKeyDoesNotVerify() {
        assertNull(ClaimVerifier.verify(fragment(signer.issue(draft).qrUrl()), p256().public, clock))
    }

    @Test
    fun expiredClaimsAreRejected() {
        val token = fragment(signer.issue(draft).qrUrl())
        val later = Clock.fixed(now.plus(Duration.ofHours(2)).plusSeconds(1), ZoneOffset.UTC)
        assertNull(ClaimVerifier.verify(token, pair.public, later))
    }

    @Test
    fun malformedTokensAreRejectedWithoutThrowing() {
        for (bad in listOf("", ".", "abc", "abc.def", "!!!.???", "a.b.c")) {
            assertNull(bad, ClaimVerifier.verify(bad, pair.public, clock))
        }
    }

    @Test
    fun totalsThatDoNotAddUpAreNeverSigned() {
        assertThrows(IllegalArgumentException::class.java) { signer.issue(draft.copy(totalCents = 5000)) }
        assertThrows(IllegalArgumentException::class.java) { signer.issue(draft.copy(taxCents = -1, totalCents = 4599)) }
        assertThrows(IllegalArgumentException::class.java) { signer.issue(draft.copy(currency = "usd")) }
    }
}
