package com.phantom.app.license

object LicenseConfig {
    const val PUBLIC_KEY_HEX = "7fed7bc7d0ffb798a5b04673272d929ab39b19c28d39f523d3afb8391bec3865"
    const val VERIFY_API_URL = "https://admin.phantomapp.fr/api/verify"
    const val OFFLINE_GRACE_PERIOD_MS = 60L * 60L * 1000L  // 1 hour
    const val ONLINE_VERIFY_TIMEOUT_MS = 5_000L
    const val WATCHDOG_INTERVAL_MS = 60_000L
    const val LICENSE_PREFIX = "PHANTOM-"

    const val LOG_TAG = "PhantomLicense"

    // WhatsApp contact URL — XOR-obfuscated to keep the raw phone number
    // out of `strings`/grep on the decompiled APK. Resolved at runtime via
    // [whatsappUrl()] only when the user taps the "Contact" link.
    // Encoded form: each byte of "https://wa.me/33759700413" XORed with 0x5A.
    private val WHATSAPP_XOR_BYTES = byteArrayOf(
        0x32, 0x2E, 0x2E, 0x2A, 0x29, 0x60, 0x75, 0x75,
        0x2D, 0x3B, 0x74, 0x37, 0x3F, 0x75, 0x69, 0x69,
        0x6D, 0x6F, 0x63, 0x6D, 0x6A, 0x6A, 0x6E, 0x6B, 0x69
    )
    private const val WHATSAPP_XOR_KEY: Byte = 0x5A

    fun whatsappUrl(): String {
        val out = ByteArray(WHATSAPP_XOR_BYTES.size)
        for (i in WHATSAPP_XOR_BYTES.indices) {
            out[i] = (WHATSAPP_XOR_BYTES[i].toInt() xor WHATSAPP_XOR_KEY.toInt()).toByte()
        }
        return String(out, Charsets.US_ASCII)
    }
}
