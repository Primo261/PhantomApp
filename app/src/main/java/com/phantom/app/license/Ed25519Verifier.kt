package com.phantom.app.license

import android.util.Log
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

object Ed25519Verifier {

    private const val TAG = LicenseConfig.LOG_TAG

    fun verify(publicKeyHex: String, message: ByteArray, signature: ByteArray): Boolean {
        return try {
            val pubBytes = hexDecode(publicKeyHex)
            if (pubBytes.size != 32) {
                Log.e(TAG, "Ed25519: invalid pubkey length=${pubBytes.size}")
                return false
            }
            if (signature.size != 64) {
                Log.e(TAG, "Ed25519: invalid signature length=${signature.size}")
                return false
            }
            val pubKey = Ed25519PublicKeyParameters(pubBytes, 0)
            val signer = Ed25519Signer()
            signer.init(false, pubKey)
            signer.update(message, 0, message.size)
            val ok = signer.verifySignature(signature)
            Log.d(TAG, "Ed25519.verify(msg=${message.size}B, sig=64B) -> $ok")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Ed25519.verify failed: ${e.message}")
            false
        }
    }

    fun hexDecode(hex: String): ByteArray {
        val clean = hex.trim().removePrefix("0x")
        require(clean.length % 2 == 0) { "Hex string must have even length" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex char at index ${i * 2}" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun hexEncode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(Character.forDigit((b.toInt() shr 4) and 0xF, 16))
            sb.append(Character.forDigit(b.toInt() and 0xF, 16))
        }
        return sb.toString()
    }
}
