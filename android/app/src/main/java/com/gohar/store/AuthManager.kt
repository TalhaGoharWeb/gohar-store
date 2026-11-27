package com.gohar.store

import android.content.Context
import android.util.Base64
import android.util.Patterns
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * Owner-only sign-in for the developer console.
 *
 * On-device only: there is no server and no Firebase project, so the
 * gate is enforced here, on the phone. The owner's email + a salted
 * SHA-256 of the password live in private SharedPreferences; the
 * password itself is never stored. An optional Google account can be
 * linked at setup — signing in with it only works when the chosen
 * account is the one that was linked.
 *
 * Be clear about the limit: this hides the console from every normal
 * user of the store. The GitHub token is what actually authorizes a
 * catalogue publish — the sign-in just decides who gets to see the
 * console that holds it.
 */
object AuthManager {

    private const val PREFS = "owner_auth"
    private const val KEY_EMAIL = "owner_email"
    private const val KEY_SALT = "salt_b64"
    private const val KEY_HASH = "hash_b64"
    private const val KEY_GOOGLE_EMAIL = "google_email"
    private const val KEY_SESSION = "session_active"

    const val MIN_PASSWORD_LENGTH = 8

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun cleanEmail(email: String): String =
        email.trim().lowercase(Locale.ROOT)

    fun isEmailValid(email: String): Boolean =
        Patterns.EMAIL_ADDRESS.matcher(cleanEmail(email)).matches()

    /** True once an owner email + password hash exist on this phone. */
    fun isSetup(context: Context): Boolean {
        val p = prefs(context)
        return !p.getString(KEY_EMAIL, null).isNullOrBlank() &&
            !p.getString(KEY_HASH, null).isNullOrBlank()
    }

    /**
     * First-time setup. Stores the owner email and a salted hash of
     * the password, optionally links a Google account, and signs the
     * owner in. False when the email is invalid or the password is
     * too short — nothing is stored in that case.
     */
    fun setupOwner(
        context: Context,
        email: String,
        password: String,
        googleEmail: String?,
    ): Boolean {
        val clean = cleanEmail(email)
        if (!isEmailValid(clean)) return false
        if (password.length < MIN_PASSWORD_LENGTH) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = sha256(salt, password)
        prefs(context).edit()
            .putString(KEY_EMAIL, clean)
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putString(KEY_GOOGLE_EMAIL, googleEmail?.let { cleanEmail(it) }?.takeIf { it.isNotBlank() })
            .putBoolean(KEY_SESSION, true)
            .apply()
        return true
    }

    /** True only when setup exists, the email matches, and the password hashes match. */
    fun signInWithPassword(context: Context, email: String, password: String): Boolean {
        val p = prefs(context)
        val storedEmail = p.getString(KEY_EMAIL, null) ?: return false
        if (cleanEmail(email) != storedEmail) return false
        val salt = runCatching {
            Base64.decode(p.getString(KEY_SALT, null) ?: return false, Base64.NO_WRAP)
        }.getOrNull() ?: return false
        val storedHash = runCatching {
            Base64.decode(p.getString(KEY_HASH, null) ?: return false, Base64.NO_WRAP)
        }.getOrNull() ?: return false
        val ok = MessageDigest.isEqual(sha256(salt, password), storedHash)
        if (ok) p.edit().putBoolean(KEY_SESSION, true).apply()
        return ok
    }

    /**
     * "Continue with Google": true only when a Google account was
     * linked at setup and the chosen account is that same one.
     */
    fun signInWithGoogle(context: Context, email: String): Boolean {
        val p = prefs(context)
        val linked = p.getString(KEY_GOOGLE_EMAIL, null)?.takeIf { it.isNotBlank() } ?: return false
        if (cleanEmail(email) != linked) return false
        p.edit().putBoolean(KEY_SESSION, true).apply()
        return true
    }

    /**
     * Forgot-password change: replaces the password hash with a
     * fresh 16-byte salt + new SHA-256 hash. The owner email, the
     * linked Google account and the session are all KEPT — nothing
     * is wiped, unlike [resetOwner]. Signs the owner in. False when
     * no setup exists yet or the new password is too short, in
     * which case nothing is stored.
     */
    fun changePassword(context: Context, newPassword: String): Boolean {
        if (!isSetup(context)) return false
        if (newPassword.length < MIN_PASSWORD_LENGTH) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = sha256(salt, newPassword)
        prefs(context).edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putBoolean(KEY_SESSION, true)
            .apply()
        return true
    }

    fun isOwnerSignedIn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SESSION, false)

    fun signOut(context: Context) {
        prefs(context).edit().putBoolean(KEY_SESSION, false).apply()
    }

    /** Erases the whole owner setup on this phone (email, hash, linked Google, session). */
    fun resetOwner(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun getOwnerEmail(context: Context): String? =
        prefs(context).getString(KEY_EMAIL, null)

    fun getLinkedGoogleEmail(context: Context): String? =
        prefs(context).getString(KEY_GOOGLE_EMAIL, null)?.takeIf { it.isNotBlank() }

    private fun sha256(salt: ByteArray, password: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        return md.digest(password.toByteArray(Charsets.UTF_8))
    }
}
