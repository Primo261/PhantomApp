package com.phantom.app.license

import android.util.Log

object CrockfordBase32 {

    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private val DECODE_TABLE: IntArray by lazy {
        IntArray(128) { -1 }.also { table ->
            for (i in ALPHABET.indices) {
                table[ALPHABET[i].code] = i
                table[ALPHABET[i].lowercaseChar().code] = i
            }
            // Crockford normalizations:
            table['I'.code] = ALPHABET.indexOf('1'); table['i'.code] = ALPHABET.indexOf('1')
            table['L'.code] = ALPHABET.indexOf('1'); table['l'.code] = ALPHABET.indexOf('1')
            table['O'.code] = ALPHABET.indexOf('0'); table['o'.code] = ALPHABET.indexOf('0')
            table['U'.code] = ALPHABET.indexOf('V'); table['u'.code] = ALPHABET.indexOf('V')
        }
    }

    /**
     * Decode a Crockford-base32 string into a byte array.
     *
     * 5 bits per char, packed MSB-first. The number of output bytes is
     * ceil(len*5/8). If the bit stream does not align on an 8-bit boundary
     * (e.g. 20 chars = 100 bits → 12 full bytes + 4 bits), the trailing bits
     * occupy the most-significant nibble of the final byte with the low 4
     * bits left as zero.
     *
     * Throws IllegalArgumentException on invalid characters.
     */
    fun decode(s: String): ByteArray {
        if (s.isEmpty()) return ByteArray(0)
        val outLen = (s.length * 5 + 7) / 8
        val out = ByteArray(outLen)
        var buffer = 0
        var bitsInBuffer = 0
        var outIdx = 0
        for (c in s) {
            val code = c.code
            val value = if (code < DECODE_TABLE.size) DECODE_TABLE[code] else -1
            if (value < 0) {
                throw IllegalArgumentException("Invalid Crockford char: '$c'")
            }
            buffer = (buffer shl 5) or value
            bitsInBuffer += 5
            if (bitsInBuffer >= 8) {
                bitsInBuffer -= 8
                out[outIdx++] = ((buffer shr bitsInBuffer) and 0xFF).toByte()
            }
        }
        if (bitsInBuffer > 0 && outIdx < outLen) {
            // Pad remaining bits into the high portion of the last byte.
            out[outIdx] = ((buffer shl (8 - bitsInBuffer)) and 0xFF).toByte()
        }
        return out
    }

    /**
     * Encode a byte array into a Crockford-base32 string (no dashes).
     * Pads with zero bits at the end if the bit count is not a multiple of 5.
     */
    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val totalBits = bytes.size * 8
        val outLen = (totalBits + 4) / 5
        val sb = StringBuilder(outLen)
        var buffer = 0
        var bitsInBuffer = 0
        var idx = 0
        var emitted = 0
        while (emitted < outLen) {
            if (bitsInBuffer < 5 && idx < bytes.size) {
                buffer = (buffer shl 8) or (bytes[idx].toInt() and 0xFF)
                bitsInBuffer += 8
                idx++
                continue
            }
            if (bitsInBuffer < 5) {
                // Pad with zero bits.
                buffer = buffer shl (5 - bitsInBuffer)
                bitsInBuffer = 5
            }
            val shift = bitsInBuffer - 5
            val symbol = (buffer shr shift) and 0x1F
            sb.append(ALPHABET[symbol])
            buffer = buffer and ((1 shl shift) - 1)
            bitsInBuffer -= 5
            emitted++
        }
        return sb.toString()
    }

    fun decodeSafe(s: String): ByteArray? = try {
        decode(s)
    } catch (e: Exception) {
        Log.w(LicenseConfig.LOG_TAG, "CrockfordBase32.decode failed: ${e.message}")
        null
    }
}
