package com.example.util

object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val CHAR_MAP = ALPHABET.withIndex().associate { it.value to it.index }

    fun decode(base32: String): ByteArray {
        val clean = base32.uppercase().replace("-", "").replace(" ", "")
        if (clean.isEmpty()) return ByteArray(0)
        
        // Remove padding characters
        val trimmed = clean.takeWhile { it != '=' }
        val outLength = (trimmed.length * 5) / 8
        val out = ByteArray(outLength)
        
        var buffer = 0
        var bitsLeft = 0
        var index = 0
        
        for (char in trimmed) {
            val value = CHAR_MAP[char] ?: continue
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out[index++] = (buffer shr (bitsLeft - 8)).toByte()
                bitsLeft -= 8
            }
        }
        return out
    }
}
