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
    fun calculateSecurID(
        secret: String,
        timeSeconds: Long,
        digits: Int = 8,
        interval: Int = 60,
        pin: String? = null,
        serial: String = "000000000000"
    ): String {
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

            // Get GMT/UTC date-time components
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("GMT"))
            cal.timeInMillis = timeSeconds * 1000L
            val year = cal.get(java.util.Calendar.YEAR)
            val month = cal.get(java.util.Calendar.MONTH) + 1 // Calendar.MONTH is 0-indexed
            val mday = cal.get(java.util.Calendar.DAY_OF_MONTH)
            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val min = cal.get(java.util.Calendar.MINUTE)
            val sec = cal.get(java.util.Calendar.SECOND)

            val is30 = interval == 30
            val bcdTime = ByteArray(8)
            bcdWrite(bcdTime, 0, year, 2)
            bcdWrite(bcdTime, 2, month, 1)
            bcdWrite(bcdTime, 3, mday, 1)
            bcdWrite(bcdTime, 4, hour, 1)
            bcdWrite(bcdTime, 5, min and if (is30) 0xFE else 0xFC, 1)
            bcdTime[6] = 0
            bcdTime[7] = 0

            val key0 = ByteArray(16)
            val key1 = ByteArray(16)

            // 1. key0 from year (2 bytes), encrypted with dec_seed (keyBytes)
            keyFromTime(bcdTime, 2, serial, key0)
            val encKey0_1 = aesEncryptBlock(keyBytes, key0)

            // 2. key1 from month (3 bytes), encrypted with encKey0_1
            keyFromTime(bcdTime, 3, serial, key1)
            val encKey1_2 = aesEncryptBlock(encKey0_1, key1)

            // 3. key0 from mday (4 bytes), encrypted with encKey1_2
            keyFromTime(bcdTime, 4, serial, key0)
            val encKey0_3 = aesEncryptBlock(encKey1_2, key0)

            // 4. key1 from hour (5 bytes), encrypted with encKey0_3
            keyFromTime(bcdTime, 5, serial, key1)
            val encKey1_4 = aesEncryptBlock(encKey0_3, key1)

            // 5. key0 from min/sec (8 bytes), encrypted with encKey1_4
            keyFromTime(bcdTime, 8, serial, key0)
            val finalBlock = aesEncryptBlock(encKey1_4, key0)

            // Index into the final block of 4 consecutive codes
            val iIdx = if (is30) {
                ((min and 0x01) shl 3) or (if (sec >= 30) 4 else 0)
            } else {
                (min and 0x03) shl 2
            }

            val b0 = finalBlock[iIdx].toLong() and 0xFF
            val b1 = finalBlock[iIdx + 1].toLong() and 0xFF
            val b2 = finalBlock[iIdx + 2].toLong() and 0xFF
            val b3 = finalBlock[iIdx + 3].toLong() and 0xFF
            val tokencode = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3

            // Format passcode with backwards modular addition of PIN
            val codeChars = CharArray(digits)
            var tempTokencode = tokencode
            val pinStr = pin ?: ""
            val pinLen = pinStr.length

            for (i in 0 until digits) {
                val j = digits - 1 - i
                var c = (tempTokencode % 10).toInt()
                tempTokencode /= 10
                if (i < pinLen) {
                    val pinDigit = pinStr[pinLen - 1 - i] - '0'
                    c += pinDigit
                }
                codeChars[j] = ((c % 10) + '0'.code).toChar()
            }
            return String(codeChars)
        } catch (e: Exception) {
            e.printStackTrace()
            return "0".repeat(digits)
        }
    }

    private fun bcdWrite(out: ByteArray, outOffset: Int, value: Int, bytes: Int) {
        var tempVal = value
        var idx = outOffset + bytes - 1
        for (b in 0 until bytes) {
            var byteVal = tempVal % 10
            tempVal /= 10
            byteVal = byteVal or ((tempVal % 10) shl 4)
            tempVal /= 10
            out[idx] = byteVal.toByte()
            idx--
        }
    }

    private fun keyFromTime(bcdTime: ByteArray, bcdTimeBytes: Int, serial: String, key: ByteArray) {
        for (i in 0 until 8) {
            key[i] = 0xAA.toByte()
        }
        System.arraycopy(bcdTime, 0, key, 0, bcdTimeBytes)
        for (i in 12 until 16) {
            key[i] = 0xBB.toByte()
        }

        // write BCD-encoded partial serial number
        val cleanSerial = serial.replace("[^0-9]".toRegex(), "")
        val paddedSerial = cleanSerial.takeLast(12).padStart(12, '0')

        var keyIdx = 8
        for (i in 4 until 12 step 2) {
            val high = paddedSerial[i] - '0'
            val low = paddedSerial[i + 1] - '0'
            key[keyIdx++] = ((high shl 4) or low).toByte()
        }
    }

    private fun aesEncryptBlock(key: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        return cipher.doFinal(input)
    }

    fun securidMac(input: ByteArray, out: ByteArray) {
        val incr = 16
        val work = ByteArray(incr) { 0xFF.toByte() }
        val enc = ByteArray(incr)
        val pad = ByteArray(incr)
        val zero = ByteArray(incr)
        val lastblk = ByteArray(incr)
        
        // padding
        var p = incr - 1
        var i = input.size * 8
        while (i > 0) {
            pad[p] = (i and 0xFF).toByte()
            p--
            i = i ushr 8
        }
        
        var inLen = input.size
        var inIdx = 0
        var odd = false
        
        // handle the bulk of the input data here
        while (inLen > incr) {
            val chunk = input.copyOfRange(inIdx, inIdx + incr)
            encryptThenXor(chunk, work, enc)
            inLen -= incr
            inIdx += incr
            odd = !odd
        }
        
        // final 0-16 bytes of input data
        System.arraycopy(input, inIdx, lastblk, 0, inLen)
        encryptThenXor(lastblk, work, enc)
        
        // hash an extra block of zeroes, for certain input lengths
        if (odd) {
            encryptThenXor(zero, work, enc)
        }
        
        // always hash the padding
        encryptThenXor(pad, work, enc)
        
        // run hash over current hash value, then return
        System.arraycopy(work, 0, out, 0, incr)
        encryptThenXor(work, out, enc)
    }

    private fun encryptThenXor(key: ByteArray, work: ByteArray, enc: ByteArray) {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        val result = cipher.doFinal(work)
        System.arraycopy(result, 0, enc, 0, 16)
        for (i in 0 until 16) {
            work[i] = (work[i].toInt() xor enc[i].toInt()).toByte()
        }
    }

    fun securidShortmac(input: ByteArray): Int {
        val hash = ByteArray(16)
        securidMac(input, hash)
        return ((hash[0].toInt() and 0xFF) shl 7) or ((hash[1].toInt() and 0xFF) ushr 1)
    }

    fun getV2DefaultKeyHash(): ByteArray {
        val magic = byteArrayOf(
            0xd8.toByte(), 0xf5.toByte(), 0x32.toByte(),
            0x53.toByte(), 0x82.toByte(), 0x89.toByte()
        )
        val keyHash = ByteArray(16)
        securidMac(magic, keyHash)
        return keyHash
    }

    fun decryptV2Seed(encSeed: ByteArray): ByteArray {
        val keyHash = getV2DefaultKeyHash()
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        val keySpec = SecretKeySpec(keyHash, "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec)
        return cipher.doFinal(encSeed)
    }

    fun numinputToBits(input: String, nBits: Int): ByteArray {
        val outBytes = ByteArray((nBits + 7) / 8)
        var bitpos = 13
        var outIdx = 0
        var bitsLeft = nBits
        var inIdx = 0
        
        while (bitsLeft > 0 && inIdx < input.length) {
            val charVal = (input[inIdx] - '0') and 0x07
            val decoded = charVal shl bitpos
            
            outBytes[outIdx] = (outBytes[outIdx].toInt() or (decoded ushr 8)).toByte()
            if (outIdx + 1 < outBytes.size) {
                outBytes[outIdx + 1] = (outBytes[outIdx + 1].toInt() or (decoded and 0xFF)).toByte()
            }
            
            bitpos -= 3
            if (bitpos < 0) {
                bitpos += 8
                outIdx++
            }
            bitsLeft -= 3
            inIdx++
        }
        return outBytes
    }

    fun getBits(bytes: ByteArray, startBit: Int, nBits: Int): Int {
        var out = 0
        var byteIdx = startBit / 8
        var bitOffset = startBit % 8
        var bitsRemaining = nBits
        
        while (bitsRemaining > 0 && byteIdx < bytes.size) {
            out = out shl 1
            val currentByte = bytes[byteIdx].toInt() and 0xFF
            if (((currentByte shl bitOffset) and 0x80) != 0) {
                out = out or 0x01
            }
            bitOffset++
            if (bitOffset == 8) {
                bitOffset = 0
                byteIdx++
            }
            bitsRemaining--
        }
        return out
    }

    private fun isHex(s: String): Boolean {
        val clean = s.replace("[^A-Fa-f0-9]".toRegex(), "")
        return clean.isNotEmpty() && clean.length == s.length
    }
}
