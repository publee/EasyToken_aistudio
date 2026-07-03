package com.example

import com.example.util.TokenCalculator
import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testSecurIdPinCombination() {
    val secret = "0123456789ABCDEF0123456789ABCDEF" // 16-byte hex
    val timeSeconds = 1719811200L // arbitrary fixed timestamp
    
    // Calculate raw code (without PIN)
    val rawCode = TokenCalculator.calculateSecurID(secret, timeSeconds, digits = 8, interval = 60, pin = null)
    assertEquals(8, rawCode.length)
    
    // Calculate code with 4-digit PIN "4321"
    val pin = "4321"
    val passcodeWithPin = TokenCalculator.calculateSecurID(secret, timeSeconds, digits = 8, interval = 60, pin = pin)
    assertEquals(8, passcodeWithPin.length)
    
    // Verify each digit
    // Last digit (index 7): (rawCode[7] + '1') % 10
    val expectedLast = ((rawCode[7] - '0' + 1) % 10) + '0'.code
    assertEquals(expectedLast.toChar(), passcodeWithPin[7])
    
    // 2nd to last (index 6): (rawCode[6] + '2') % 10
    val expected6th = ((rawCode[6] - '0' + 2) % 10) + '0'.code
    assertEquals(expected6th.toChar(), passcodeWithPin[6])

    // 3rd to last (index 5): (rawCode[5] + '3') % 10
    val expected5th = ((rawCode[5] - '0' + 3) % 10) + '0'.code
    assertEquals(expected5th.toChar(), passcodeWithPin[5])

    // 4th to last (index 4): (rawCode[4] + '4') % 10
    val expected4th = ((rawCode[4] - '0' + 4) % 10) + '0'.code
    assertEquals(expected4th.toChar(), passcodeWithPin[4])

    // The first 4 digits must remain completely unchanged
    assertEquals(rawCode.substring(0, 4), passcodeWithPin.substring(0, 4))
  }

  @Test
  fun testSecurIdMacAndSeedDecryption() {
    // 1. Verify default V2 key hash calculation
    val defaultKeyHash = TokenCalculator.getV2DefaultKeyHash()
    assertNotNull(defaultKeyHash)
    assertEquals(16, defaultKeyHash.size)
    
    // 2. Test numinputToBits
    val inputDigits = "1234567890"
    val bits = TokenCalculator.numinputToBits(inputDigits, 15)
    assertNotNull(bits)
    assertTrue(bits.size >= 2)
    
    // 3. Test getBits
    val bitsVal = TokenCalculator.getBits(bits, 0, 15)
    assertTrue(bitsVal >= 0)
  }}
