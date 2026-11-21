package com.multiroute.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class ProxyUpstreamProfile(
    val port: Int,
    val group: String,
    val mappings: Map<String, String>
) {
    init {
        require(port in 1..65535 && group.isNotBlank())
        require(mappings.isNotEmpty() && mappings.all { (key, member) -> key.isNotBlank() && member.isNotBlank() })
        require(mappings.values.toSet().size == mappings.size)
    }
}

internal data class StoredProxyUpstream(
    val profile: ProxyUpstreamProfile,
    val savedSecret: String?
)

/** Persists this device's upstream mapping and, optionally, its encrypted controller secret. */
internal class ProxyUpstreamStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))
    private val gatewayFile = AtomicFile(File(context.noBackupFilesDir, "proxy-gateway-mappings.json"))
    private val gatewayAppTargetsFile = AtomicFile(File(context.noBackupFilesDir, "proxy-gateway-app-targets.json"))

    /** Per-app proxy intent is device-local and independent from kernel route assignments. */
    fun readGatewayAppTargets(): Map<String, String> = synchronized(diskLock) {
        val bytes = try {
            gatewayAppTargetsFile.readFully()
        } catch (_: FileNotFoundException) {
            return emptyMap()
        }
        runCatching {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            buildMap {
                json.keys().forEach { key ->
                    val channelId = json.getString(key)
                    if (key.isNotBlank() && channelId.isNotBlank()) put(key, channelId)
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** Replaces the complete app-target snapshot atomically. An empty map clears all entries. */
    fun saveGatewayAppTargets(targets: Map<String, String>) = synchronized(diskLock) {
        writeRecord(gatewayAppTargetsFile, JSONObject(targets).toString().toByteArray(Charsets.UTF_8))
    }

    /** Null means the user has yet to save a gateway mapping. An empty object clears it explicitly. */
    fun readGatewayMappings(): Map<String, String>? = synchronized(diskLock) {
        val bytes = try {
            gatewayFile.readFully()
        } catch (_: FileNotFoundException) {
            return null
        }
        runCatching {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            buildMap {
                json.keys().forEach { key ->
                    val tag = json.getString(key)
                    if (key.isNotBlank() && tag.isNotBlank()) put(key, tag)
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun saveGatewayMappings(mappings: Map<String, String>) = synchronized(diskLock) {
        writeRecord(gatewayFile, JSONObject(mappings).toString().toByteArray(Charsets.UTF_8))
    }

    /** A missing/corrupt file returns null. A secret decryption failure does not discard its profile. */
    fun read(): StoredProxyUpstream? = synchronized(diskLock) {
        val raw = runCatching { file.readFully().toString(Charsets.UTF_8) }.getOrNull() ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (json.optInt("version") != FORMAT_VERSION) return null
        val profile = runCatching {
            val mappingsJson = json.getJSONObject("mappings")
            val mappings = buildMap {
                val keys = mappingsJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, mappingsJson.getString(key))
                }
            }
            ProxyUpstreamProfile(
                port = json.getInt("port"),
                group = json.getString("group"),
                mappings = mappings
            )
        }.getOrNull() ?: return null

        val savedSecret = runCatching { decrypt(json) }.getOrNull()
        StoredProxyUpstream(profile, savedSecret)
    }

    /**
     * Saves the profile and optional secret as one atomic record. Passing null explicitly removes any
     * previously saved secret while retaining the profile. Any write or encryption failure is thrown.
     */
    fun save(profile: ProxyUpstreamProfile, secretToSave: String?) = synchronized(diskLock) {
        val json = JSONObject().apply {
            put("version", FORMAT_VERSION)
            put("port", profile.port)
            put("group", profile.group)
            put("mappings", JSONObject().apply {
                profile.mappings.forEach { (key, value) -> put(key, value) }
            })
            if (secretToSave != null) {
                val encrypted = encrypt(secretToSave)
                put(IV_FIELD, Base64.getEncoder().encodeToString(encrypted.first))
                put(CIPHERTEXT_FIELD, Base64.getEncoder().encodeToString(encrypted.second))
            }
        }.toString().toByteArray(Charsets.UTF_8)

        writeRecord(file, json)
    }

    private fun writeRecord(target: AtomicFile, data: ByteArray) {
        var stream: FileOutputStream? = null
        try {
            stream = target.startWrite()
            stream.write(data)
            target.finishWrite(stream)
        } catch (error: Exception) {
            stream?.let(target::failWrite)
            throw error
        }
    }

    /** Removes both the persisted record and its device encryption key. */
    fun clear() = synchronized(diskLock) {
        file.delete()
        check(!file.baseFile.exists())
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }
        Unit
    }

    private fun encrypt(secret: String): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return cipher.iv to cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
    }

    private fun decrypt(json: JSONObject): String? {
        if (!json.has(IV_FIELD) || !json.has(CIPHERTEXT_FIELD)) return null
        val iv = Base64.getDecoder().decode(json.getString(IV_FIELD))
        val ciphertext = Base64.getDecoder().decode(json.getString(CIPHERTEXT_FIELD))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun getExistingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("Proxy upstream encryption key is unavailable")
    }

    private fun getOrCreateKey(): SecretKey {
        getExistingKeyOrNull()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun getExistingKeyOrNull(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private companion object {
        const val FILE_NAME = "proxy-upstream-profile.json"
        const val KEY_ALIAS = "multiroute-proxy-upstream-secret-v1"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val FORMAT_VERSION = 1
        const val IV_FIELD = "secretIv"
        const val CIPHERTEXT_FIELD = "secretCiphertext"
        val diskLock = Any()
    }
}
