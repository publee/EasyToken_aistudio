package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.TokenEntity
import com.example.data.TokenRepository
import com.example.util.TokenCalculator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class TokenViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: TokenRepository
    val allTokens: StateFlow<List<TokenEntity>>
    private val sharedPrefs = application.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)

    // ID of the actively selected token shown in the jumbo preview card
    private val _selectedTokenId = MutableStateFlow<Int?>(null)
    val selectedTokenId = _selectedTokenId.asStateFlow()

    // Ticker that emits current system timestamp in milliseconds every 100ms
    val systemTimeMillis = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(100)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), System.currentTimeMillis())

    init {
        val tokenDao = AppDatabase.getDatabase(application).tokenDao()
        repository = TokenRepository(tokenDao)
        allTokens = repository.allTokens
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

        // Retrieve selected token ID from SharedPreferences if available
        val savedId = sharedPrefs.getInt("selected_token_id", -1)
        if (savedId != -1) {
            _selectedTokenId.value = savedId
        }

        // Automatically select the first token if available
        viewModelScope.launch {
            allTokens.collect { list ->
                if (list.isNotEmpty()) {
                    val currentId = _selectedTokenId.value
                    if (currentId == null || !list.any { it.id == currentId }) {
                        val targetId = if (list.any { it.id == savedId }) savedId else list.first().id
                        _selectedTokenId.value = targetId
                        sharedPrefs.edit().putInt("selected_token_id", targetId).apply()
                        updateWidget()
                    }
                }
            }
        }
    }

    fun selectToken(id: Int) {
        _selectedTokenId.value = id
        sharedPrefs.edit().putInt("selected_token_id", id).apply()
        updateWidget()
    }

    private fun updateWidget() {
        val intent = android.content.Intent("com.example.UPDATE_WIDGET").apply {
            setPackage(getApplication<Application>().packageName)
        }
        getApplication<Application>().sendBroadcast(intent)
    }

    // Helper to insert a parsed or manually created token
    fun insertToken(token: TokenEntity) {
        viewModelScope.launch {
            repository.insert(token)
            updateWidget()
        }
    }

    // Deletes selected token and resets focus to remaining
    fun deleteToken(token: TokenEntity) {
        viewModelScope.launch {
            val targetId = if (_selectedTokenId.value == token.id) {
                allTokens.value.firstOrNull { it.id != token.id }?.id
            } else {
                _selectedTokenId.value
            }
            _selectedTokenId.value = targetId
            if (targetId != null) {
                sharedPrefs.edit().putInt("selected_token_id", targetId).apply()
            } else {
                sharedPrefs.edit().remove("selected_token_id").apply()
            }
            repository.delete(token)
            updateWidget()
        }
    }

    // Update token details (e.g. PIN or Name)
    fun updateToken(token: TokenEntity) {
        viewModelScope.launch {
            repository.update(token)
            updateWidget()
        }
    }

    // Increment counter for HOTP tokens
    fun incrementCounter(token: TokenEntity) {
        viewModelScope.launch {
            repository.update(token.copy(counter = token.counter + 1))
        }
    }

    // Direct helper to verify if a secret key is valid
    fun validateSecret(secret: String): Boolean {
        // Base32 or hex key is considered valid if trimmed is not blank
        return secret.trim().isNotEmpty()
    }

    // Import from standard otpauth:// strings
    fun importFromOtpauthUri(uriString: String): Boolean {
        var token = parseOtpauthUri(uriString)
        if (token == null) {
            token = parseCtfUri(uriString)
        }
        return if (token != null) {
            insertToken(token)
            true
        } else {
            false
        }
    }

    // Parse plain otpauth scheme or compressed token format
    fun parseCtfUri(uriString: String): TokenEntity? {
        try {
            val cleanUri = uriString.trim()
            val ctfDataDecoded = if (cleanUri.contains("ctfData=", ignoreCase = true)) {
                val ctfDataRegex = "[?&]ctfData=([^&]+)".toRegex(RegexOption.IGNORE_CASE)
                val match = ctfDataRegex.find(cleanUri) ?: return null
                try {
                    java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
                } catch (e: Exception) {
                    match.groupValues[1]
                }
            } else if ((cleanUri.startsWith("A") || cleanUri.startsWith("B") || cleanUri.startsWith("AwAA") || cleanUri.startsWith("AwAB")) && cleanUri.length >= 20 && !cleanUri.contains("://")) {
                cleanUri
            } else {
                return null
            }

            val cleanCtfDigits = ctfDataDecoded.replace("[^0-9]".toRegex(), "")
            val isV2Numeric = cleanCtfDigits.length >= 80 && (cleanCtfDigits.startsWith("1") || cleanCtfDigits.startsWith("2"))

            if (isV2Numeric) {
                val version = cleanCtfDigits[0] - '0'
                val serialRaw = cleanCtfDigits.substring(1, 13)
                val binencDigits = cleanCtfDigits.substring(13, 13 + 63)
                
                val d = TokenCalculator.numinputToBits(binencDigits, 189)
                val encSeed = d.copyOfRange(0, 16)
                val decSeed = TokenCalculator.decryptV2Seed(encSeed)
                
                var formattedSerial = serialRaw
                if (formattedSerial.length == 12) {
                    formattedSerial = "${formattedSerial.substring(0, 4)}-${formattedSerial.substring(4, 8)}-${formattedSerial.substring(8, 12)}"
                }
                
                val secretHex = TokenCalculator.byteArrayToHexString(decSeed)
                
                val nameRegex = "[?&]name=([^&]+)".toRegex(RegexOption.IGNORE_CASE)
                val nameMatch = nameRegex.find(cleanUri)
                val tokenName = if (nameMatch != null) {
                    try {
                        java.net.URLDecoder.decode(nameMatch.groupValues[1], "UTF-8")
                    } catch (e: Exception) {
                        nameMatch.groupValues[1]
                    }
                } else {
                    "SecurID ($formattedSerial)"
                }
                
                val digitsRegex = "[?&]digits=([^&]+)".toRegex(RegexOption.IGNORE_CASE)
                val digitsMatch = digitsRegex.find(cleanUri)
                val digits = digitsMatch?.groupValues?.get(1)?.toIntOrNull() ?: 8
                
                val intervalRegex = "[?&](interval|period)=([^&]+)".toRegex(RegexOption.IGNORE_CASE)
                val intervalMatch = intervalRegex.find(cleanUri)
                val interval = intervalMatch?.groupValues?.get(2)?.toIntOrNull() ?: 60
                
                val pinRegex = "[?&](pin|password|activation)=([^&]+)".toRegex(RegexOption.IGNORE_CASE)
                val pinMatch = pinRegex.find(cleanUri)
                val tokenPin = pinMatch?.groupValues?.get(2)?.let {
                    try {
                        java.net.URLDecoder.decode(it, "UTF-8")
                    } catch (e: Exception) {
                        it
                    }
                }
                
                val expDateRegex = "[?&](exp_date|expiration|exp)=([^&]+)".toRegex(RegexOption.IGNORE_CASE)
                val expDateMatch = expDateRegex.find(cleanUri)
                val expDate = expDateMatch?.groupValues?.get(2)?.let {
                    try { java.net.URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
                }

                val usesPinRegex = "[?&](uses_pin|pin_required|pin)=([^&]+)".toRegex(RegexOption.IGNORE_CASE)
                val usesPinMatch = usesPinRegex.find(cleanUri)
                val usesPin = usesPinMatch?.groupValues?.get(2)?.let {
                    val decoded = try { java.net.URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
                    decoded.toBoolean() || decoded == "1" || decoded.equals("yes", ignoreCase = true) || decoded.equals("true", ignoreCase = true)
                }

                return TokenEntity(
                    name = tokenName,
                    secret = secretHex,
                    type = "SECURID",
                    serial = formattedSerial,
                    digits = digits,
                    interval = interval,
                    pin = tokenPin,
                    expDate = expDate,
                    usesPin = usesPin,
                    version = "2"
                )
            }

            var b64 = ctfDataDecoded.trim()
            while (b64.endsWith("=") && b64.length % 4 != 0) {
                b64 = b64.substring(0, b64.length - 1)
            }

            val bytes = try {
                android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            } catch (e: Exception) {
                return null
            }

            if (bytes.isEmpty()) return null

            // Detect and handle stoken v3/v4 token (291 bytes)
            if (bytes.size == 291 && (bytes[0] == 3.toByte() || bytes[0] == 4.toByte())) {
                val version = bytes[0].toInt() and 0xFF
                val passwordLocked = bytes[1].toInt() != 0
                val devidLocked = bytes[2].toInt() != 0
                
                var serial = ""
                var secretHex = ""
                var digits = 8
                var interval = 60
                var expDate: String? = null
                var usesPin: Boolean? = null
                
                val nonceDevidHash = bytes.copyOfRange(3, 35)
                val nonceDevidPassHash = bytes.copyOfRange(35, 67)
                val nonce = bytes.copyOfRange(67, 83)
                val encPayload = bytes.copyOfRange(83, 259)

                // 1. Gather deviceId candidates
                val candidateDeviceIds = mutableListOf<String>()
                val uriDevidRegex = "[?&](deviceid|devid)=([^&]+)".toRegex(RegexOption.IGNORE_CASE)
                val uriDevidMatch = uriDevidRegex.find(cleanUri)
                if (uriDevidMatch != null) {
                    try {
                        candidateDeviceIds.add(java.net.URLDecoder.decode(uriDevidMatch.groupValues[2], "UTF-8"))
                    } catch (e: Exception) {
                        candidateDeviceIds.add(uriDevidMatch.groupValues[2])
                    }
                }
                // Add common rsa device IDs
                candidateDeviceIds.add("A01C4380FC014DF0B1137FB98EC74694") // Android standard Soft Token Device ID
                candidateDeviceIds.add("556F198533DD442C91553A0E994F21B1") // iOS/iPhone
                candidateDeviceIds.add("") // None / empty
                candidateDeviceIds.add("868C28F831BF49119876EBECE5C3F2AB") // BlackBerry
                candidateDeviceIds.add("B77A1D06D505420090D31BB397748704") // BlackBerry 10
                candidateDeviceIds.add("C483B59263F04F19B4CBA6BCE8E57159") // Windows Phone
                candidateDeviceIds.add("8F94B226D3624204AC523B21FA333B6F") // Windows
                candidateDeviceIds.add("D0955A53569B4ECC9CF76C2A59D4E775") // macOS

                // 2. Gather password candidates
                val candidatePasswords = mutableListOf<String>()
                val uriPassRegex = "[?&](password|pass|pin|activation)=([^&]+)".toRegex(RegexOption.IGNORE_CASE)
                val uriPassMatch = uriPassRegex.find(cleanUri)
                if (uriPassMatch != null) {
                    try {
                        candidatePasswords.add(java.net.URLDecoder.decode(uriPassMatch.groupValues[2], "UTF-8"))
                    } catch (e: Exception) {
                        candidatePasswords.add(uriPassMatch.groupValues[2])
                    }
                }
                candidatePasswords.add("") // Empty/none

                // 3. Find matching deviceId and password
                var matchingDeviceId: String? = null
                var matchingPassword = ""

                // Verify devid match via nonceDevidHash (which always uses empty password in computeHash)
                for (devId in candidateDeviceIds) {
                    val computed = computeV3Hash("", devId, nonce)
                    if (computed.contentEquals(nonceDevidHash)) {
                        matchingDeviceId = devId
                        break
                    }
                }

                // If no device ID matched the hash, but devid is NOT locked, we can use empty string
                if (matchingDeviceId == null && !devidLocked) {
                    matchingDeviceId = ""
                }

                if (matchingDeviceId != null) {
                    // Verify password match via nonceDevidPassHash
                    for (pass in candidatePasswords) {
                        val computed = computeV3Hash(pass, matchingDeviceId, nonce)
                        if (computed.contentEquals(nonceDevidPassHash)) {
                            matchingPassword = pass
                            break
                        }
                    }
                }

                if (matchingDeviceId != null) {
                    try {
                        val derivedKey = v3DeriveKey(
                            if (matchingPassword.isEmpty()) null else matchingPassword,
                            if (matchingDeviceId.isEmpty()) null else matchingDeviceId,
                            nonce,
                            1,
                            version
                        )
                        val decryptedPayload = aes256CbcDecrypt(derivedKey, nonce, encPayload)
                        
                        if (decryptedPayload.size == 176) {
                            val serialRaw = String(decryptedPayload, 0, 16, Charsets.UTF_8).trim { it <= ' ' || it.code == 0 }
                            serial = serialRaw
                            
                            val decSeed = decryptedPayload.copyOfRange(16, 32)
                            secretHex = decSeed.joinToString("") { String.format("%02X", it) }
                            
                            digits = decryptedPayload[35].toInt() and 0xFF
                            val addpin = decryptedPayload[36].toInt() and 0xFF
                            usesPin = (addpin != 0x1F)
                            
                            interval = decryptedPayload[37].toInt() and 0xFF
                            if (interval != 30 && interval != 60) {
                                interval = 60
                            }
                            
                            val expBytes = decryptedPayload.copyOfRange(48, 53)
                            val ticks = ((expBytes[0].toLong() and 0xFF) shl 32) or
                                           ((expBytes[1].toLong() and 0xFF) shl 24) or
                                           ((expBytes[2].toLong() and 0xFF) shl 16) or
                                           ((expBytes[3].toLong() and 0xFF) shl 8) or
                                           (expBytes[4].toLong() and 0xFF)
                            val expSeconds = Math.round(ticks * 0.256)
                            
                            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                            sdf.timeZone = java.util.TimeZone.getTimeZone("GMT")
                            expDate = sdf.format(java.util.Date(expSeconds * 1000L))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                if (serial.isEmpty()) {
                    val serialParamRegex = "[?&](serial|s|id|serial_number)=([^&]+)".toRegex(RegexOption.IGNORE_CASE)
                    val serialParamMatch = serialParamRegex.find(cleanUri)
                    if (serialParamMatch != null) {
                        serial = try {
                            java.net.URLDecoder.decode(serialParamMatch.groupValues[2], "UTF-8")
                        } catch (e: Exception) {
                            serialParamMatch.groupValues[2]
                        }
                    }
                }
                
                if (expDate == null) {
                    val expDateRegex = "[?&](exp_date|expiration|exp|expiration_date|expires)=([^&]+)".toRegex(RegexOption.IGNORE_CASE)
                    val expDateMatch = expDateRegex.find(cleanUri)
                    if (expDateMatch != null) {
                        expDate = try {
                            java.net.URLDecoder.decode(expDateMatch.groupValues[2], "UTF-8")
                        } catch (e: Exception) {
                            expDateMatch.groupValues[2]
                        }
                    }
                }
                
                if (usesPin == null) {
                    val usesPinRegex = "[?&](uses_pin|pin_required|pin)=([^&]+)".toRegex(RegexOption.IGNORE_CASE)
                    val usesPinMatch = usesPinRegex.find(cleanUri)
                    if (usesPinMatch != null) {
                        val decoded = try {
                            java.net.URLDecoder.decode(usesPinMatch.groupValues[2], "UTF-8")
                        } catch (e: Exception) {
                            usesPinMatch.groupValues[2]
                        }
                        usesPin = decoded.toBoolean() || decoded == "1" || decoded.equals("yes", ignoreCase = true) || decoded.equals("true", ignoreCase = true)
                    }
                }

                var tokenPin: String? = null
                val pinRegex = "[?&](pin|password|activation)=([^&]+)".toRegex(RegexOption.IGNORE_CASE)
                val pinMatch = pinRegex.find(cleanUri)
                if (pinMatch != null) {
                    tokenPin = try {
                        java.net.URLDecoder.decode(pinMatch.groupValues[2], "UTF-8")
                    } catch (e: Exception) {
                        pinMatch.groupValues[2]
                    }
                }

                val digitsRegex = "[?&]digits=([^&]+)".toRegex(RegexOption.IGNORE_CASE)
                val digitsMatch = digitsRegex.find(cleanUri)
                if (digitsMatch != null) {
                    digits = digitsMatch.groupValues[1].toIntOrNull() ?: digits
                }

                val intervalRegex = "[?&](interval|period)=([^&]+)".toRegex(RegexOption.IGNORE_CASE)
                val intervalMatch = intervalRegex.find(cleanUri)
                if (intervalMatch != null) {
                    interval = intervalMatch.groupValues[2].toIntOrNull() ?: interval
                }
                
                if (serial.isNotEmpty()) {
                    if (serial.length == 9) {
                        serial = "${serial.substring(0, 3)}-${serial.substring(3, 6)}-${serial.substring(6, 9)}"
                    } else if (serial.length == 12) {
                        serial = "${serial.substring(0, 4)}-${serial.substring(4, 8)}-${serial.substring(8, 12)}"
                    } else if (serial.length == 16) {
                        serial = "${serial.substring(0, 4)}-${serial.substring(4, 8)}-${serial.substring(8, 12)}-${serial.substring(12, 16)}"
                    }
                }
                
                val nameRegex = "[?&]name=([^&]+)".toRegex(RegexOption.IGNORE_CASE)
                val nameMatch = nameRegex.find(cleanUri)
                val tokenName = if (nameMatch != null) {
                    try {
                        java.net.URLDecoder.decode(nameMatch.groupValues[1], "UTF-8")
                    } catch (e: Exception) {
                        nameMatch.groupValues[1]
                    }
                } else {
                    "SecurID ($serial)"
                }
                
                return TokenEntity(
                    name = tokenName,
                    secret = secretHex,
                    type = "SECURID",
                    serial = serial,
                    digits = digits,
                    interval = interval,
                    pin = tokenPin,
                    expDate = expDate,
                    usesPin = usesPin,
                    version = version.toString()
                )
            }

            var serial = ""
            val serialParamRegex = "[?&](serial|s|id)=([^&]+)".toRegex(RegexOption.IGNORE_CASE)
            val serialParamMatch = serialParamRegex.find(cleanUri)
            if (serialParamMatch != null) {
                serial = try {
                    java.net.URLDecoder.decode(serialParamMatch.groupValues[2], "UTF-8")
                } catch (e: Exception) {
                    serialParamMatch.groupValues[2]
                }
            }

            if (serial.isEmpty()) {
                val currentDigits = java.lang.StringBuilder()
                for (b in bytes) {
                    if (b in '0'.toByte()..'9'.toByte()) {
                        currentDigits.append(b.toChar())
                    } else {
                        if (currentDigits.length in 9..16) {
                            serial = currentDigits.toString()
                            break
                        }
                        currentDigits.setLength(0)
                    }
                }
                if (serial.isEmpty() && currentDigits.length in 9..16) {
                    serial = currentDigits.toString()
                }
            }

            // Fallback: Swapped nibbles BCD
            if (serial.isEmpty()) {
                val currentDigits = java.lang.StringBuilder()
                for (b in bytes) {
                    val bInt = b.toInt() and 0xFF
                    val swapped = ((bInt and 0x0F) shl 4) or ((bInt and 0xF0) ushr 4)
                    if (swapped in '0'.code..'9'.code) {
                        currentDigits.append(swapped.toChar())
                    } else {
                        if (currentDigits.length in 9..16) {
                            serial = currentDigits.toString()
                            break
                        }
                        currentDigits.setLength(0)
                    }
                }
                if (serial.isEmpty() && currentDigits.length in 9..16) {
                    serial = currentDigits.toString()
                }
            }

            if (serial.isNotEmpty()) {
                if (serial.length == 9) {
                    serial = "${serial.substring(0, 3)}-${serial.substring(3, 6)}-${serial.substring(6, 9)}"
                } else if (serial.length == 12) {
                    serial = "${serial.substring(0, 4)}-${serial.substring(4, 8)}-${serial.substring(8, 12)}"
                } else if (serial.length == 16) {
                    serial = "${serial.substring(0, 4)}-${serial.substring(4, 8)}-${serial.substring(8, 12)}-${serial.substring(12, 16)}"
                }
            }

            val headerOffset = if (bytes.size >= 3 && bytes[0] == 0x03.toByte() && bytes[1] == 0x00.toByte() && bytes[2] == 0x01.toByte()) 3 else 0
            val seedBytes = if (bytes.size - headerOffset >= 16) {
                bytes.copyOfRange(headerOffset, headerOffset + 16)
            } else {
                val pad = ByteArray(16)
                System.arraycopy(bytes, headerOffset, pad, 0, bytes.size - headerOffset)
                pad
            }

            val decryptedSeed = if (headerOffset == 3) {
                try {
                    TokenCalculator.decryptV2Seed(seedBytes)
                } catch (e: Exception) {
                    seedBytes
                }
            } else {
                seedBytes
            }

            val secretHex = decryptedSeed.joinToString("") { String.format("%02X", it) }

            val nameRegex = "[?&]name=([^&]+)".toRegex(RegexOption.IGNORE_CASE)
            val nameMatch = nameRegex.find(cleanUri)
            val tokenName = if (nameMatch != null) {
                try {
                    java.net.URLDecoder.decode(nameMatch.groupValues[1], "UTF-8")
                } catch (e: Exception) {
                    nameMatch.groupValues[1]
                }
            } else {
                "SecurID ($serial)"
            }

            val digitsRegex = "[?&]digits=([^&]+)".toRegex(RegexOption.IGNORE_CASE)
            val digitsMatch = digitsRegex.find(cleanUri)
            val digits = digitsMatch?.groupValues?.get(1)?.toIntOrNull() ?: 8

            val intervalRegex = "[?&](interval|period)=([^&]+)".toRegex(RegexOption.IGNORE_CASE)
            val intervalMatch = intervalRegex.find(cleanUri)
            val interval = intervalMatch?.groupValues?.get(2)?.toIntOrNull() ?: 60

            val pinRegex = "[?&](pin|password|activation)=([^&]+)".toRegex(RegexOption.IGNORE_CASE)
            val pinMatch = pinRegex.find(cleanUri)
            val tokenPin = pinMatch?.groupValues?.get(2)?.let {
                try {
                    java.net.URLDecoder.decode(it, "UTF-8")
                } catch (e: Exception) {
                    it
                }
            }

            val expDateRegex = "[?&](exp_date|expiration|exp)=([^&]+)".toRegex(RegexOption.IGNORE_CASE)
            val expDateMatch = expDateRegex.find(cleanUri)
            val expDate = expDateMatch?.groupValues?.get(2)?.let {
                try { java.net.URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
            }

            val usesPinRegex = "[?&](uses_pin|pin_required|pin)=([^&]+)".toRegex(RegexOption.IGNORE_CASE)
            val usesPinMatch = usesPinRegex.find(cleanUri)
            val usesPin = usesPinMatch?.groupValues?.get(2)?.let {
                val decoded = try { java.net.URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
                decoded.toBoolean() || decoded == "1" || decoded.equals("yes", ignoreCase = true) || decoded.equals("true", ignoreCase = true)
            }

            val versionRegex = "[?&](version|v)=([^&]+)".toRegex(RegexOption.IGNORE_CASE)
            val versionMatch = versionRegex.find(cleanUri)
            val versionParsed = versionMatch?.groupValues?.get(2)?.let {
                try { java.net.URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
            } ?: if (headerOffset == 3) "3" else null

            return TokenEntity(
                name = tokenName,
                secret = secretHex,
                type = "SECURID",
                serial = serial,
                digits = digits,
                interval = interval,
                pin = tokenPin,
                expDate = expDate,
                usesPin = usesPin,
                version = versionParsed
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // Import from .sdtid XML contents
    fun importFromSdtidXml(xmlContent: String): Boolean {
        val token = parseSdtidXml(xmlContent)
        return if (token != null) {
            insertToken(token)
            true
        } else {
            false
        }
    }

    private fun formatDate(millis: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return sdf.format(java.util.Date(millis))
    }

    fun parseOtpauthUriPublic(uriString: String): TokenEntity? {
        var token = parseOtpauthUri(uriString)
        if (token == null) {
            token = parseCtfUri(uriString)
        }
        return token
    }

    fun parseSdtidXmlPublic(xmlContent: String): TokenEntity? {
        return parseSdtidXml(xmlContent)
    }

    // Parse plain otpauth scheme
    private fun parseOtpauthUri(uriString: String): TokenEntity? {
        if (!uriString.trim().startsWith("otpauth://")) return null
        try {
            val cleanUri = uriString.trim()
            val type = if (cleanUri.contains("otpauth://hotp")) "HOTP" else "TOTP"

            val secretRegex = "secret=([^&]+)".toRegex()
            val secret = secretRegex.find(cleanUri)?.groupValues?.get(1) ?: return null

            val labelStart = cleanUri.indexOf("://") + 10
            val labelEnd = cleanUri.indexOf("?")
            val labelRaw = if (labelEnd != -1) cleanUri.substring(labelStart, labelEnd) else cleanUri.substring(labelStart)
            var decodedLabel = java.net.URLDecoder.decode(labelRaw, "UTF-8")
            decodedLabel = decodedLabel.replace("totp/", "").replace("hotp/", "")

            val issuerRegex = "issuer=([^&]+)".toRegex()
            val issuer = issuerRegex.find(cleanUri)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }

            val digitsRegex = "digits=([^&]+)".toRegex()
            val digits = digitsRegex.find(cleanUri)?.groupValues?.get(1)?.toIntOrNull() ?: 6

            val periodRegex = "period=([^&]+)".toRegex()
            val interval = periodRegex.find(cleanUri)?.groupValues?.get(1)?.toIntOrNull() ?: 30

            val name = if (!issuer.isNullOrEmpty()) {
                val labelWithoutIssuer = decodedLabel.substringAfter("$issuer:")
                if (labelWithoutIssuer != decodedLabel) {
                    "$issuer ($labelWithoutIssuer)"
                } else {
                    "$issuer ($decodedLabel)"
                }
            } else {
                decodedLabel
            }

            return TokenEntity(
                name = name,
                secret = secret.trim(),
                type = type,
                digits = digits,
                interval = interval,
                serial = "",
                expDate = null,
                usesPin = null,
                version = null
            )
        } catch (e: Exception) {
            return null
        }
    }

    // Parse standard .sdtid formatted xml file
    private fun parseSdtidXml(xml: String): TokenEntity? {
        try {
            val serialRegex = "<SerialNumber>([^<]+)</SerialNumber>".toRegex(RegexOption.IGNORE_CASE)
            val seedRegex = "<Seed>([^<]+)</Seed>|<SeedValue>([^<]+)</SeedValue>|<Key>([^<]+)</Key>".toRegex(RegexOption.IGNORE_CASE)
            val digitsRegex = "<Digits>([^<]+)</Digits>|<DigitsValue>([^<]+)</DigitsValue>".toRegex(RegexOption.IGNORE_CASE)
            val intervalRegex = "<Interval>([^<]+)</Interval>|<TimeInterval>([^<]+)</TimeInterval>".toRegex(RegexOption.IGNORE_CASE)
            val nameRegex = "<TokenName>([^<]+)</TokenName>|<Title>([^<]+)</Title>".toRegex(RegexOption.IGNORE_CASE)

            val serial = serialRegex.find(xml)?.groupValues?.get(1)?.trim() ?: ""
            val seedMatch = seedRegex.find(xml)
            val seed = when {
                seedMatch == null -> ""
                seedMatch.groupValues[1].isNotEmpty() -> seedMatch.groupValues[1]
                seedMatch.groupValues[2].isNotEmpty() -> seedMatch.groupValues[2]
                seedMatch.groupValues[3].isNotEmpty() -> seedMatch.groupValues[3]
                else -> ""
            }.trim()

            if (seed.isEmpty()) return null

            val digitsStr = digitsRegex.find(xml)?.groupValues?.let { g ->
                g.getOrNull(1)?.ifEmpty { null } ?: g.getOrNull(2)
            }?.trim()
            val digits = digitsStr?.toIntOrNull() ?: 8

            val intervalStr = intervalRegex.find(xml)?.groupValues?.let { g ->
                g.getOrNull(1)?.ifEmpty { null } ?: g.getOrNull(2)
            }?.trim()
            val interval = intervalStr?.toIntOrNull() ?: 60

            val name = nameRegex.find(xml)?.groupValues?.let { g ->
                g.getOrNull(1)?.ifEmpty { null } ?: g.getOrNull(2)
            }?.trim() ?: "SecurID Key"

            val deathDateRegex = "<DeathDate>([^<]+)</DeathDate>|<ExpirationDate>([^<]+)</ExpirationDate>".toRegex(RegexOption.IGNORE_CASE)
            val expDateMatch = deathDateRegex.find(xml)
            val expDate = expDateMatch?.groupValues?.let { g ->
                g.getOrNull(1)?.ifEmpty { null } ?: g.getOrNull(2)
            }?.trim()

            val versionRegex = "<Version>([^<]+)</Version>".toRegex(RegexOption.IGNORE_CASE)
            val versionMatch = versionRegex.find(xml)
            val version = versionMatch?.groupValues?.get(1)?.trim()

            val usesPin = if (xml.contains("<PinRequired>", ignoreCase = true) || xml.contains("<PinType>", ignoreCase = true)) {
                !(xml.contains("<PinRequired>false</PinRequired>", ignoreCase = true) || xml.contains("<PinType>0</PinType>", ignoreCase = true))
            } else {
                null
            }

            return TokenEntity(
                name = name,
                secret = seed,
                type = "SECURID",
                serial = serial,
                digits = digits,
                interval = interval,
                expDate = expDate,
                usesPin = usesPin,
                version = version
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // Dynamic helper to compute token passcode depending on type and current system time
    fun getPasscodeForToken(token: TokenEntity, timestampMillis: Long): String {
        val timeSeconds = timestampMillis / 1000L
        return when (token.type) {
            "SECURID" -> TokenCalculator.calculateSecurID(
                secret = token.secret,
                timeSeconds = timeSeconds,
                digits = token.digits,
                interval = token.interval,
                pin = token.pin,
                serial = token.serial
            )
            "HOTP" -> TokenCalculator.calculateHOTP(
                secret = token.secret,
                counter = token.counter,
                digits = token.digits
            )
            else -> TokenCalculator.calculateTOTP( // "TOTP"
                secret = token.secret,
                timeSeconds = timeSeconds,
                digits = token.digits,
                interval = token.interval
            )
        }
    }

    private fun v3DeriveKey(pass: String?, devid: String?, salt: ByteArray, keyId: Int, version: Int): ByteArray {
        val passBytes = pass?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val passLen = passBytes.size
        val bufLen = 48 + 16 + 16 + passLen
        val buf0 = ByteArray(bufLen)
        
        if (passBytes.isNotEmpty()) {
            System.arraycopy(passBytes, 0, buf0, 0, passBytes.size)
        }
        
        val devidBytes = ByteArray(48)
        if (devid != null) {
            val scrubbed = devid.filter { it.isLetterOrDigit() }.uppercase()
            val scrubbedBytes = scrubbed.toByteArray(Charsets.UTF_8)
            System.arraycopy(scrubbedBytes, 0, devidBytes, 0, minOf(scrubbedBytes.size, 48))
        }
        System.arraycopy(devidBytes, 0, buf0, passLen, 48)
        
        val key0 = byteArrayOf(
            0xd0.toByte(), 0x14.toByte(), 0x43.toByte(), 0x3c.toByte(), 0x6d.toByte(), 0x17.toByte(), 0x9f.toByte(), 0xeb.toByte(),
            0xda.toByte(), 0x09.toByte(), 0xab.toByte(), 0xfc.toByte(), 0x32.toByte(), 0x49.toByte(), 0x63.toByte(), 0x4c.toByte()
        )
        val key1 = byteArrayOf(
            0x3b.toByte(), 0xaf.toByte(), 0xff.toByte(), 0x4d.toByte(), 0x91.toByte(), 0x8d.toByte(), 0x89.toByte(), 0xb6.toByte(),
            0x81.toByte(), 0x60.toByte(), 0xde.toByte(), 0x44.toByte(), 0x4e.toByte(), 0x05.toByte(), 0xc0.toByte(), 0xdd.toByte()
        )
        System.arraycopy(if (keyId != 0) key1 else key0, 0, buf0, passLen + 48, 16)
        System.arraycopy(salt, 0, buf0, passLen + 48 + 16, 16)
        
        var finalBuf = buf0
        var finalBufLen = bufLen
        if (version == 3) {
            val buf1 = ByteArray(bufLen shr 1)
            for (i in 1 until bufLen step 2) {
                buf1[i shr 1] = buf0[i]
            }
            finalBuf = buf1
            finalBufLen = bufLen shr 1
        }
        
        return pbkdf2Sha256(finalBuf, salt, 1000, 32)
    }

    private fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLengthBytes: Int): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val keySpec = javax.crypto.spec.SecretKeySpec(password, "HmacSHA256")
        mac.init(keySpec)
        
        val out = ByteArray(keyLengthBytes)
        val hLen = 32
        val l = (keyLengthBytes + hLen - 1) / hLen
        
        for (i in 1..l) {
            val sI = ByteArray(salt.size + 4)
            System.arraycopy(salt, 0, sI, 0, salt.size)
            sI[salt.size] = (i ushr 24).toByte()
            sI[salt.size + 1] = (i ushr 16).toByte()
            sI[salt.size + 2] = (i ushr 8).toByte()
            sI[salt.size + 3] = i.toByte()
            
            var u = mac.doFinal(sI)
            val t = u.clone()
            
            for (j in 2..iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) {
                    t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
                }
            }
            
            val offset = (i - 1) * hLen
            val len = minOf(hLen, keyLengthBytes - offset)
            System.arraycopy(t, 0, out, offset, len)
        }
        return out
    }

    private fun aes256CbcDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding")
        val keySpec = javax.crypto.spec.SecretKeySpec(key, "AES")
        val ivSpec = javax.crypto.spec.IvParameterSpec(iv)
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(ciphertext)
    }

    private fun computeV3Hash(password: String, deviceId: String, salt: ByteArray): ByteArray {
        val buf = ByteArray(salt.size + 48 + password.length)
        System.arraycopy(salt, 0, buf, 0, salt.size)
        if (deviceId.isNotEmpty()) {
            val scrubbed = deviceId.filter { it.isLetterOrDigit() }.uppercase()
            val devIdBytes = scrubbed.toByteArray(Charsets.UTF_8)
            System.arraycopy(devIdBytes, 0, buf, salt.size, minOf(devIdBytes.size, 48))
        }
        if (password.isNotEmpty()) {
            val passBytes = password.toByteArray(Charsets.UTF_8)
            System.arraycopy(passBytes, 0, buf, salt.size + 48, minOf(passBytes.size, password.length))
        }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(buf)
    }
}
