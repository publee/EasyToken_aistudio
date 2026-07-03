package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.viewmodel.TokenViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertNotNull(appName)
    org.junit.Assert.assertTrue(appName.isNotEmpty())
  }

  @Test
  fun testCtfUriParsing() {
    val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    val viewModel = TokenViewModel(app)
    val url = "http://127.0.0.1/securid/ctf?ctfData=AwAB9Rk%2B2lAJ4xNTY3ODkwMTIzNDU2Nzg5MA%3D%3D&name=Imported%20RSA%20VPN"
    val token = viewModel.parseCtfUri(url)
    assertNotNull("Token should not be null", token)
    assertEquals("Imported RSA VPN", token?.name)
    assertEquals("B6FBA939925784AA1D19D6F4AC67A3C9", token?.secret)
    assertEquals("1567-8901-2345-6789", token?.serial)
    assertEquals(8, token?.digits)
    assertEquals(60, token?.interval)
    org.junit.Assert.assertNull(token?.pin)

    // Verify URI with explicit &pin parameter
    val urlWithPin = "http://127.0.0.1/securid/ctf?ctfData=AwAB9Rk%2B2lAJ4xNTY3ODkwMTIzNDU2Nzg5MA%3D%3D&name=Imported%20RSA%20VPN&pin=4321"
    val tokenWithPin = viewModel.parseCtfUri(urlWithPin)
    assertNotNull(tokenWithPin)
    assertEquals("4321", tokenWithPin?.pin)
  }

  @Test
  fun testSecurIDTokenCalculation() {
    val secret = "B6FBA939925784AA1D19D6F4AC67A3C9"
    val timestampSeconds = 1719734400L // arbitrary timestamp
    
    // 1. Compute 8-digit token passcode
    val passcode8 = com.example.util.TokenCalculator.calculateSecurID(
        secret = secret,
        timeSeconds = timestampSeconds,
        digits = 8,
        interval = 60
    )
    assertNotNull(passcode8)
    assertEquals(8, passcode8.length)
    org.junit.Assert.assertTrue(passcode8.all { it.isDigit() })

    // 2. Compute 6-digit token passcode
    val passcode6 = com.example.util.TokenCalculator.calculateSecurID(
        secret = secret,
        timeSeconds = timestampSeconds,
        digits = 6,
        interval = 60
    )
    assertNotNull(passcode6)
    assertEquals(6, passcode6.length)
    org.junit.Assert.assertTrue(passcode6.all { it.isDigit() })

    // 3. Compute token with PIN combined via modular addition (stoken algorithm)
    val pin = "1234"
    val passcodeWithPin = com.example.util.TokenCalculator.calculateSecurID(
        secret = secret,
        timeSeconds = timestampSeconds,
        digits = 8,
        interval = 60,
        pin = pin
    )
    assertNotNull(passcodeWithPin)
    assertEquals(8, passcodeWithPin.length)
    
    // Verify last digit (index 7): (passcode8[7] + '4') % 10
    val expectedLast = ((passcode8[7] - '0' + 4) % 10) + '0'.code
    assertEquals(expectedLast.toChar(), passcodeWithPin[7])
    
    // The first 4 digits must remain completely unchanged
    assertEquals(passcode8.substring(0, 4), passcodeWithPin.substring(0, 4))
  }
}
