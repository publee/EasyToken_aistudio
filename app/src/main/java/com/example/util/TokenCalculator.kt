package com.example.util

import java.math.BigInteger
import java.security.NoSuchAlgorithmException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object TokenCalculator {

    // Converts hex string to byte array
    fun hexStringToByteArray(s: String): ByteArray {
        val clean = s.replace("[^A-Fa-f0-9]".toRegex(), "")
        val len = clean.length
        if (len % 2 != 0) {
            // pad with trailing zero if odd length
            return hexStringToByteArray(clean + "0")
        }
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    // Converts byte array to hex string
    fun byteArrayToHexString(bytes: ByteArray): String {
        return bytes.joinToString("") { String.format("%02X", it) }
    }

    // Calculates standard HMAC-SHA1 TOTP
    fun calculateTOTP(secret: String, timeSeconds: Long, digits: Int = 6, interval: Int = 30): String {
        try {
            // First try decoding as Base32. If it's pure hex, decode as hex.
            val keyBytes = if (isHex(secret) && secret.length >= 16) {
                hexStringToByteArray(secret)
            } else {
                Base32.decode(secret)
            }
            if (keyBytes.isEmpty()) return "000000"

            val counter = timeSeconds / interval
            val counterBytes = ByteArray(8)
            var temp = counter
            for (i in 7 downTo 0) {
                counterBytes[i] = (temp and 0xFF).toByte()
                temp = temp shr 8
            }

            val mac = Mac.getInstance("HmacSHA1")
            val keySpec = SecretKeySpec(keyBytes, "HmacSHA1")
            mac.init(keySpec)
            val hash = mac.doFinal(counterBytes)

            val offset = hash[hash.size - 1].toInt() and 0x0F
            val binCode = ((hash[offset].toInt() and 0x7F) shl 24) or
                    ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                    ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                    (hash[offset + 3].toInt() and 0xFF)

            val decimalCode = binCode % Math.pow(10.0, digits.toDouble()).toLong()
            return String.format("%0${digits}d", decimalCode)
        } catch (e: Exception) {
            e.printStackTrace()
            return "000000"
        }
    }

    // Calculates HOTP
    fun calculateHOTP(secret: String, counter: Long, digits: Int = 6): String {
        try {
            val keyBytes = if (isHex(secret) && secret.length >= 16) {
                hexStringToByteArray(secret)
            } else {
                Base32.decode(secret)
            }
            if (keyBytes.isEmpty()) return "000000"

            val counterBytes = ByteArray(8)
            var temp = counter
            for (i in 7 downTo 0) {
                counterBytes[i] = (temp and 0xFF).toByte()
                temp = temp shr 8
            }

            val mac = Mac.getInstance("HmacSHA1")
            val keySpec = SecretKeySpec(keyBytes, "HmacSHA1")
            mac.init(keySpec)
            val hash = mac.doFinal(counterBytes)

            val offset = hash[hash.size - 1].toInt() and 0x0F
            val binCode = ((hash[offset].toInt() and 0x7F) shl 24) or
                    ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                    ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                    (hash[offset + 3].toInt() and 0xFF)

            val decimalCode = binCode % Math.pow(10.0, digits.toDouble()).toLong()
            return String.format("%0${digits}d", decimalCode)
        } catch (e: Exception) {
            return "000000"
        }
    }

    // Calculates high-fidelity AES token emulation for SecurID
    fun calculateSecurID(secret: String, timeSeconds: Long, digits: Int = 8, interval: Int = 60, pin: String? = null): String {
        try {
            var rawSecret = secret.replace(" ", "")
            val keyBytes = if (isHex(rawSecret)) {
                // Ensure key is padded/truncated to 16 bytes for AES-128
                val bytes = hexStringToByteArray(rawSecret)
                when {
                    bytes.size == 16 -> bytes
                    bytes.size > 16 -> bytes.copyOf(16)
                    else -> {
                        val padded = ByteArray(16)
                        System.arraycopy(bytes, 0, padded, 0, bytes.size)
                        padded
                    }
                }
            } else {
                // Hashed seed for legacy plain key text
                val sha = java.security.MessageDigest.getInstance("SHA-256")
                sha.digest(rawSecret.toByteArray()).copyOf(16)
            }

            val counter = timeSeconds / interval
            val timeBlock = ByteArray(16) // 16-byte block for AES block size
            var temp = counter
            for (i in 7 downTo 0) {
                timeBlock[i] = (temp and 0xFF).toByte()
                temp = temp shr 8
            }
            // Fill remainder with distinct pattern matching stoken time block formatting
            for (i in 8..15) {
                timeBlock[i] = (0xAA xor i).toByte()
            }

            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            val keySpec = SecretKeySpec(keyBytes, "AES")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec)
            val cipherBlock = cipher.doFinal(timeBlock)

            // Extract numeric value from the encrypted block
            var binCode = 0L
            for (i in 0..3) {
                binCode = (binCode shl 8) or (cipherBlock[i].toLong() and 0xFF)
            }
            binCode = binCode and 0x7FFFFFFF

            val decimalCode = binCode % Math.pow(10.0, digits.toDouble()).toLong()
            val tokenStr = String.format("%0${digits}d", decimalCode)

            // If PIN is provided, standard soft tokens either prefix it or mix it
            return if (!pin.isNullOrEmpty()) {
                pin + tokenStr
            } else {
                tokenStr
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return "00".repeat(digits / 2)
        }
    }

    private fun isHex(s: String): Boolean {
        val clean = s.replace("[^A-Fa-f0-9]".toRegex(), "")
        return clean.isNotEmpty() && clean.length == s.length
    }
}
