package com.scrcpybt.app.auth

import android.os.Build
import com.scrcpybt.common.protocol.message.AuthMessage
import com.scrcpybt.common.protocol.stream.MessageReader
import com.scrcpybt.common.protocol.stream.MessageWriter
import com.scrcpybt.common.util.Logger
import java.io.IOException

/**
 * Manages per-feature authentication from the controller side.
 *
 * Before using any feature, the controller must authenticate:
 *   fun authenticateFeature(featureId: Byte, writer: MessageWriter, reader: MessageReader): Boolean
 *
 * Flow:
 * 1. Compute device fingerprint from own ECDH public key
 * 2. Send AuthMessage(SUB_AUTH_REQUEST, featureId, fingerprint, deviceName)
 * 3. Wait for response
 * 4. If AUTH_GRANTED: cache locally, return true
 * 5. If AUTH_DENIED: show reason, return false
 *
 * Cached auth is per-session (cleared on disconnect).
 */
class FeatureAuthClient(private val publicKey: ByteArray) {
    private val deviceFingerprint: String
    private val deviceName: String = Build.MODEL
    private val authorizedFeatures = mutableSetOf<Byte>()

    init {
        deviceFingerprint = computeFingerprint(publicKey)
    }

    /**
     * Authenticate for a specific feature. Returns true if granted, false if denied.
     */
    fun authenticateFeature(
        featureId: Byte,
        writer: MessageWriter,
        reader: MessageReader
    ): Boolean {
        // Check if already authenticated in this session
        if (authorizedFeatures.contains(featureId)) {
            Logger.d(TAG, "Feature ${AuthMessage.getFeatureName(featureId)} already authorized")
            return true
        }

        try {
            // Send auth request
            val request = AuthMessage(
                subType = AuthMessage.SUB_AUTH_REQUEST,
                featureId = featureId,
                deviceFingerprint = deviceFingerprint,
                deviceName = deviceName,
                reason = ""
            )
            writer.writeMessage(request)
            Logger.i(TAG, "Auth request sent for feature: ${AuthMessage.getFeatureName(featureId)}")

            // Wait for response with timeout
            val response = reader.readMessage()

            if (response !is AuthMessage) {
                Logger.e(TAG, "Unexpected response type: ${response?.type}")
                return false
            }

            when (response.subType) {
                AuthMessage.SUB_AUTH_GRANTED -> {
                    authorizedFeatures.add(featureId)
                    Logger.i(TAG, "Auth granted for feature: ${AuthMessage.getFeatureName(featureId)}")
                    return true
                }
                AuthMessage.SUB_AUTH_DENIED -> {
                    Logger.w(TAG, "Auth denied for feature: ${AuthMessage.getFeatureName(featureId)}, reason: ${response.reason}")
                    return false
                }
                else -> {
                    Logger.e(TAG, "Unexpected auth response subtype: ${response.subType}")
                    return false
                }
            }
        } catch (e: IOException) {
            Logger.e(TAG, "Auth request failed", e)
            return false
        }
    }

    /**
     * Check if a feature is authorized in the current session.
     */
    fun isFeatureAuthorized(featureId: Byte): Boolean {
        return authorizedFeatures.contains(featureId)
    }

    /**
     * Clear all session authorizations (call on disconnect).
     */
    fun clearSession() {
        authorizedFeatures.clear()
        Logger.d(TAG, "Session cleared")
    }

    /**
     * Get the device fingerprint.
     */
    fun getFingerprint(): String = deviceFingerprint

    private fun computeFingerprint(publicKey: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey)
        return hash.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "FeatureAuthClient"
    }
}
