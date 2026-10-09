package com.brewpos.cafe

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Offline-verifiable ECDSA licenses. The private signing key MUST stay on the owner's computer. */
object LicenseManager {
    private const val PUBLIC_KEY_DER_B64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEubpoEi/rbJAmA6XF4jgNecQqNq0AD3hfbKaEIpk2XuCW6l2UkboGuqUq25E470PETTrX/N8/5NyPKskSjmEgMw=="
    private const val GRACE_MILLIS = 72L * 60 * 60 * 1000
    private const val CLOCK_LEEWAY_MILLIS = 5L * 60 * 1000

    data class Status(val active: Boolean, val plan: String = "", val message: String = "", val grace: Boolean = false)

    private fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

    fun evaluate(token: String, deviceId: String, nowMillis: Long, lastSeenMillis: Long = 0): Status {
        if (token.isBlank()) return Status(false, message = "This BrewPOS device is not activated.")
        if (lastSeenMillis > 0 && nowMillis + CLOCK_LEEWAY_MILLIS < lastSeenMillis)
            return Status(false, message = "Device clock moved backward. Correct its date and time to continue.")
        return try {
            val parts = token.trim().split('.')
            require(parts.size == 3 && parts[0] == "BP1")
            val raw = decode(parts[1]); val signatureBytes = decode(parts[2])
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY_DER_B64)))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(key)
            verifier.update(raw)
            require(verifier.verify(signatureBytes))
            val fields = String(raw, StandardCharsets.UTF_8).split('|')
            require(fields.size == 6 && fields[0] == "1")
            val version = fields[0]; val device = fields[1]; val plan = fields[2]
            val expiry = fields[3]; val id = fields[4]; val customer = fields[5]
            require(device.isNotEmpty() && id.isNotEmpty() && customer.isNotEmpty() && version == "1")
            if (device != deviceId) return Status(false, message = "This license belongs to another Android device.")
            require(plan in listOf("LIFETIME", "MONTHLY", "TRIAL"))
            val expiresSeconds = expiry.toLong()
            if (plan == "LIFETIME") {
                require(expiresSeconds == 0L)
                Status(true, plan, "Lifetime license active")
            } else {
                require(expiresSeconds > 0 && expiresSeconds < Long.MAX_VALUE / 1000)
                val expires = expiresSeconds * 1000L
                if (nowMillis <= expires) Status(true, plan, "$plan license active until ${java.util.Date(expires)}")
                else if (nowMillis - expires <= GRACE_MILLIS)
                    Status(true, plan, "$plan renewal overdue: temporary 72-hour offline grace period", grace = true)
                else Status(false, plan, "License expired. Request a renewed activation code.")
            }
        } catch (_: Exception) {
            Status(false, message = "Invalid or damaged activation code.")
        }
    }
}
