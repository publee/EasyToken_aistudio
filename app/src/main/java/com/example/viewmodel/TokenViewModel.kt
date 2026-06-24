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

        // Automatically select the first token if available
        viewModelScope.launch {
            allTokens.collect { list ->
                if (_selectedTokenId.value == null && list.isNotEmpty()) {
                    _selectedTokenId.value = list.first().id
                }
            }
        }
    }

    fun selectToken(id: Int) {
        _selectedTokenId.value = id
    }

    // Helper to insert a parsed or manually created token
    fun insertToken(token: TokenEntity) {
        viewModelScope.launch {
            repository.insert(token)
        }
    }

    // Deletes selected token and resets focus to remaining
    fun deleteToken(token: TokenEntity) {
        viewModelScope.launch {
            if (_selectedTokenId.value == token.id) {
                _selectedTokenId.value = allTokens.value.firstOrNull { it.id != token.id }?.id
            }
            repository.delete(token)
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
        val token = parseOtpauthUri(uriString)
        return if (token != null) {
            insertToken(token)
            true
        } else {
            false
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
                serial = "OTP-" + secret.take(6).uppercase()
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

            return TokenEntity(
                name = name,
                secret = seed,
                type = "SECURID",
                serial = if (serial.isNotEmpty()) serial else "987-112-901",
                digits = digits,
                interval = interval
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
                pin = token.pin
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
}
