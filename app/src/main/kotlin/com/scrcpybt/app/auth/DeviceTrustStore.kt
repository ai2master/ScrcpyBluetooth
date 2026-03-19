package com.scrcpybt.app.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.scrcpybt.common.util.Logger
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Manages trusted device list using SharedPreferences (encrypted with EncryptedSharedPreferences).
 *
 * Key format: "trust:{deviceFingerprint}:{featureId}"
 * Value: JSON with { deviceName, firstSeen, lastSeen, trusted }
 */
class DeviceTrustStore(context: Context) {
    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            "device_trust_store",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    data class TrustedDevice(
        val fingerprint: String,
        val featureId: Byte,
        val deviceName: String,
        val firstSeen: Long,
        val lastSeen: Long,
        val trusted: Boolean
    )

    /**
     * Check if a device is trusted for a specific feature.
     */
    fun isDeviceTrusted(fingerprint: String, featureId: Byte): Boolean {
        val key = "trust:$fingerprint:$featureId"
        val json = prefs.getString(key, null) ?: return false

        return try {
            val obj = JSONObject(json)
            obj.getBoolean("trusted")
        } catch (e: Exception) {
            Logger.e(TAG, "Error reading trust entry", e)
            false
        }
    }

    /**
     * Trust a device for a specific feature.
     */
    fun trustDevice(fingerprint: String, featureId: Byte, deviceName: String) {
        val key = "trust:$fingerprint:$featureId"
        val now = System.currentTimeMillis()

        val existing = prefs.getString(key, null)
        val firstSeen = if (existing != null) {
            try {
                JSONObject(existing).getLong("firstSeen")
            } catch (e: Exception) {
                now
            }
        } else {
            now
        }

        val obj = JSONObject().apply {
            put("deviceName", deviceName)
            put("firstSeen", firstSeen)
            put("lastSeen", now)
            put("trusted", true)
        }

        prefs.edit().putString(key, obj.toString()).apply()
        Logger.i(TAG, "Device trusted: $deviceName for feature $featureId")
    }

    /**
     * Revoke trust for a device for a specific feature.
     */
    fun revokeDevice(fingerprint: String, featureId: Byte) {
        val key = "trust:$fingerprint:$featureId"
        prefs.edit().remove(key).apply()
        Logger.i(TAG, "Device trust revoked: $fingerprint for feature $featureId")
    }

    /**
     * Get all trusted devices.
     */
    fun getTrustedDevices(): List<TrustedDevice> {
        val devices = mutableListOf<TrustedDevice>()

        for ((key, value) in prefs.all) {
            if (key.startsWith("trust:") && value is String) {
                try {
                    val parts = key.split(":")
                    if (parts.size == 3) {
                        val fingerprint = parts[1]
                        val featureId = parts[2].toByte()

                        val obj = JSONObject(value)
                        devices.add(
                            TrustedDevice(
                                fingerprint = fingerprint,
                                featureId = featureId,
                                deviceName = obj.getString("deviceName"),
                                firstSeen = obj.getLong("firstSeen"),
                                lastSeen = obj.getLong("lastSeen"),
                                trusted = obj.getBoolean("trusted")
                            )
                        )
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "Error parsing trust entry: $key", e)
                }
            }
        }

        return devices
    }

    /**
     * Compute device fingerprint from public key (SHA-256 hex).
     */
    fun getDeviceFingerprint(publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey)
        return hash.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "DeviceTrustStore"
    }
}
