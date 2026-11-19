package org.mudlet.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.Locale
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The profiles' passwords, the game's and the proxy's ([Kind]), which the app sends with each `openProfile` and the
 * engine never keeps. Each is encrypted with an AES-GCM key that never leaves the Android KeyStore, in a private file
 * that backups and device transfers leave out and a restore does not keep ([BackupScope]). Names match ignoring case,
 * as the engine's do.
 */
class PasswordStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, FILE))

    /** Which of a profile's passwords: the game's, or its proxy's (desktop Mudlet's "proxy" credential). */
    enum class Kind(internal val prefix: String) {
        GAME(""),

        // No name made in the app or Mudlet holds a colon (Profile::newNameProblem), so no game's key is a proxy's
        PROXY("proxy:"),
    }

    fun has(profile: String, kind: Kind = Kind.GAME): Boolean = read()?.has(key(profile, kind)) ?: false

    /**
     * The stored password, or null. One that can never be decrypted (its key is gone, or it was not sealed for this
     * profile) is forgotten; after any other failure it is kept for the next try.
     */
    fun get(profile: String, kind: Kind = Kind.GAME): String? {
        val all = read() ?: return null
        val name = key(profile, kind)
        val sealed = all.optString(name).ifEmpty { return null }
        return try {
            val bytes = Base64.decode(sealed, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            cipher.updateAAD(profile.lowercase(Locale.ROOT).toByteArray())
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            forgetUnreadable(all, name, e)
        } catch (e: KeyPermanentlyInvalidatedException) {
            forgetUnreadable(all, name, e)
        } catch (e: IllegalArgumentException) {
            forgetUnreadable(all, name, e)
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "a stored password could not be decrypted this time", e)
            null
        } catch (e: ProviderException) {
            Log.w(TAG, "the KeyStore could not decrypt a stored password this time", e)
            null
        }
    }

    /**
     * Every stored password, the proxies' too, for a problem report to take out of what it sends; null if any of them,
     * or the file, cannot be read now.
     */
    fun all(): List<String>? {
        val all = read() ?: return null
        return all.keys().asSequence().toList().map { get(it) ?: return null }
    }

    /** False if it could not be stored. */
    fun set(profile: String, password: String, kind: Kind = Kind.GAME): Boolean {
        val name = key(profile, kind)
        val sealed = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD(profile.lowercase(Locale.ROOT).toByteArray())
            cipher.iv + cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "a password could not be encrypted", e)
            return false
        } catch (e: ProviderException) {
            Log.w(TAG, "the KeyStore could not encrypt a password", e)
            return false
        }
        val all = read() ?: return false
        return write(all.put(name, Base64.encodeToString(sealed, Base64.NO_WRAP)))
    }

    /** False if it could not be removed. */
    fun forget(profile: String, kind: Kind = Kind.GAME): Boolean {
        val all = read() ?: return false
        if (all.remove(key(profile, kind)) == null) return true
        return write(all)
    }

    // The ciphertext is bound to the name, so one profile's cannot be moved to another, nor a game's to a proxy
    private fun key(profile: String, kind: Kind) = kind.prefix + profile.lowercase(Locale.ROOT)

    private fun forgetUnreadable(all: JSONObject, name: String, e: Exception): String? {
        Log.w(TAG, "a stored password can never be decrypted, so it is forgotten", e)
        all.remove(name)
        write(all)
        return null
    }

    // Null if the file could not be read, so that a write does not replace the passwords it holds
    private fun read(): JSONObject? = try {
        JSONObject(String(file.readFully(), Charsets.UTF_8))
    } catch (_: FileNotFoundException) {
        JSONObject()
    } catch (e: IOException) {
        Log.w(TAG, "the stored passwords could not be read", e)
        null
    } catch (e: JSONException) {
        Log.w(TAG, "the stored passwords' file is damaged, so it is ignored", e)
        JSONObject()
    }

    private fun write(all: JSONObject): Boolean {
        file.baseFile.parentFile?.mkdirs()
        val out = try {
            file.startWrite()
        } catch (e: IOException) {
            Log.w(TAG, "the stored passwords could not be written", e)
            return false
        }
        return try {
            out.write(all.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
            true
        } catch (e: IOException) {
            file.failWrite(out)
            Log.w(TAG, "the stored passwords could not be written", e)
            false
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as SecretKey?)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // The engine needs the password when a profile opens, with no prompt
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        /** Under the app's files; [BackupScope] and the backup rules leave its folder out. */
        const val FILE = "passwords/profiles.json"

        private const val TAG = "MudletPasswords"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "profile-passwords"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
