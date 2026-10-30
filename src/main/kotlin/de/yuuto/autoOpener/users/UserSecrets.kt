package de.yuuto.autoOpener.users

import java.security.MessageDigest

/** User secrets are 32 random bytes, hex encoded (see docs/keyword-pinger-prompt.md). */
val userSecretPattern = Regex("^[a-f0-9]{64}$")

object UserSecrets {
    /** A plain digest is enough here because the secret is high-entropy random data, not a password. */
    fun hash(secret: String): String =
        MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()).joinToString("") { "%02x".format(it) }

    fun matches(secret: String, storedHash: String): Boolean =
        MessageDigest.isEqual(hash(secret).toByteArray(), storedHash.toByteArray())
}
