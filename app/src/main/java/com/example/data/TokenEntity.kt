package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tokens")
data class TokenEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val secret: String, // Hex string or Base32 secret key
    val type: String, // "TOTP", "HOTP", "SECURID"
    val serial: String = "", // Monospace serial number display (e.g. "9312-4211-19")
    val digits: Int = 6, // 6, 8, or 10 digits
    val interval: Int = 30, // 30 or 60 seconds
    val pin: String? = null, // Optional PIN
    val counter: Long = 0L, // Used mainly for HOTP
    val addedAt: Long = System.currentTimeMillis()
)
