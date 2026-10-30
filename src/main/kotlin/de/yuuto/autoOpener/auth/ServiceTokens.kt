package de.yuuto.autoOpener.auth

import de.yuuto.autoOpener.users.UserSecrets

/**
 * The configured service tokens. Service JWTs carry the token's digest as subject, so a leaked JWT
 * does not reveal the long-lived token. Removing a token from the configuration revokes its JWTs.
 */
class ServiceTokens(tokens: Set<String>) {
    private val subjects = tokens.map(UserSecrets::hash).toSet()

    /** Returns the JWT subject for [token], or null if it is not a configured service token. */
    fun subjectFor(token: String): String? = UserSecrets.hash(token).takeIf { it in subjects }

    fun isValidSubject(subject: String): Boolean = subject in subjects
}
