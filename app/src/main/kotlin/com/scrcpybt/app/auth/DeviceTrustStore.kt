package com.scrcpybt.app.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.scrcpybt.common.util.Logger
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 设备信任列表管理器：基于加密 SharedPreferences 的持久化信任存储 | Trusted device list manager with encrypted SharedPreferences
 *
 * 核心功能 | Core Functions:
 * - 持久化记录已授权的设备和功能组合 | Persist authorized device-feature combinations
 * - 使用 EncryptedSharedPreferences 保护敏感数据 | Use EncryptedSharedPreferences to protect sensitive data
 * - 支持按设备指纹+功能 ID 的细粒度信任控制 | Support granular trust control by fingerprint + feature ID
 *
 * 数据格式 | Data Format:
 * - Key: "trust:{deviceFingerprint}:{featureId}" | Storage key format
 * - Value: JSON {"deviceName": "...", "firstSeen": 123, "lastSeen": 456, "trusted": true} | JSON value structure
 *
 * 安全特性 | Security Features:
 * - AES256-GCM 加密存储（符合 OWASP MASVS-STORAGE-1）| AES256-GCM encrypted storage (complies with OWASP MASVS-STORAGE-1)
 * - 每个功能独立授权（剪贴板授权不等于文件传输授权）| Independent authorization per feature
 * - 支持撤销单个设备的单个功能授权 | Support revoking individual feature authorization
 *
 * 使用场景 | Use Cases:
 * - 被控端在收到认证请求时查询 isDeviceTrusted() | Server queries isDeviceTrusted() on auth request
 * - 用户在设置界面手动管理信任列表 | User manages trust list in settings UI
 * - 首次授权时调用 trustDevice() 记住设备 | Call trustDevice() to remember device on first authorization
 *
 * @param context Android Context（用于访问 SharedPreferences）| Android Context for SharedPreferences access
 */
class DeviceTrustStore(context: Context) {
    // 加密的 SharedPreferences 实例 | Encrypted SharedPreferences instance
    private val prefs: SharedPreferences

    init {
        // 创建 AES256-GCM 主密钥 | Create AES256-GCM master key
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        // 初始化加密 SharedPreferences | Initialize encrypted SharedPreferences
        prefs = EncryptedSharedPreferences.create(
            context,
            "device_trust_store",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * 信任设备数据模型 | Trusted device data model
     *
     * @property fingerprint 设备指纹（SHA-256 哈希）| Device fingerprint (SHA-256 hash)
     * @property featureId 功能 ID | Feature ID
     * @property deviceName 设备名称（用户友好显示）| Device name (user-friendly display)
     * @property firstSeen 首次授权时间戳 | First authorization timestamp
     * @property lastSeen 最后授权时间戳 | Last authorization timestamp
     * @property trusted 是否信任 | Whether trusted
     */
    data class TrustedDevice(
        val fingerprint: String,
        val featureId: Byte,
        val deviceName: String,
        val firstSeen: Long,
        val lastSeen: Long,
        val trusted: Boolean
    )

    /**
     * 检查设备是否被信任用于指定功能 | Check if a device is trusted for a specific feature
     *
     * @param fingerprint 设备指纹 | Device fingerprint
     * @param featureId 功能 ID | Feature ID
     * @return true 已信任，false 未信任或不存在 | true if trusted, false if untrusted or not exists
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
     * 将设备标记为信任（用于指定功能）| Trust a device for a specific feature
     *
     * 保留 firstSeen 时间戳，更新 lastSeen 时间戳。| Preserve firstSeen timestamp, update lastSeen timestamp.
     *
     * @param fingerprint 设备指纹 | Device fingerprint
     * @param featureId 功能 ID | Feature ID
     * @param deviceName 设备名称 | Device name
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
     * 撤销设备对指定功能的信任 | Revoke trust for a device for a specific feature
     *
     * @param fingerprint 设备指纹 | Device fingerprint
     * @param featureId 功能 ID | Feature ID
     */
    fun revokeDevice(fingerprint: String, featureId: Byte) {
        val key = "trust:$fingerprint:$featureId"
        prefs.edit().remove(key).apply()
        Logger.i(TAG, "Device trust revoked: $fingerprint for feature $featureId")
    }

    /**
     * 获取所有信任的设备列表 | Get all trusted devices
     *
     * @return 信任设备列表（按 fingerprint + featureId 组合）| List of trusted devices (by fingerprint + featureId combination)
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
     * 从公钥计算设备指纹 | Compute device fingerprint from public key
     *
     * 使用 SHA-256 哈希，输出 64 字符十六进制字符串。| Use SHA-256 hash, output 64-char hex string.
     *
     * @param publicKey ECDH 公钥字节数组 | ECDH public key byte array
     * @return 设备指纹（十六进制）| Device fingerprint (hex)
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
