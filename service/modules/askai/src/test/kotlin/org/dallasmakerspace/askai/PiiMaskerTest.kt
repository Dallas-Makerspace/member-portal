package org.dallasmakerspace.askai

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.dallasmakerspace.core.LoggerFactory
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class PiiMaskerTest {

  private lateinit var mockLoggerFactory: LoggerFactory
  private lateinit var mockLogger: Logger
  private lateinit var piiMasker: PiiMasker

  @Before
  fun setUp() {
    mockLoggerFactory = mock()
    mockLogger = mock()
    whenever(mockLoggerFactory.create(any<Class<*>>())).thenReturn(mockLogger)
    piiMasker = PiiMasker(mockLoggerFactory)
  }

  // === EMAIL TESTS ===

  @Test
  fun `should mask email addresses`() {
    val input = "Contact me at john.doe@example.com for more info"
    val result = piiMasker.mask(input)
    assertEquals("Contact me at [EMAIL] for more info", result)
  }

  @Test
  fun `should mask multiple email addresses`() {
    val input = "Email john@test.com or jane@example.org"
    val result = piiMasker.mask(input)
    assertEquals("Email [EMAIL] or [EMAIL]", result)
  }

  @Test
  fun `should mask email with plus addressing`() {
    val input = "Send to user+tag@domain.com"
    val result = piiMasker.mask(input)
    assertEquals("Send to [EMAIL]", result)
  }

  // === PHONE NUMBER TESTS ===

  @Test
  fun `should mask phone number with dashes`() {
    val input = "Call me at 214-555-1234"
    val result = piiMasker.mask(input)
    assertEquals("Call me at [PHONE]", result)
  }

  @Test
  fun `should mask phone number with parentheses`() {
    val input = "Phone: (214) 555-1234"
    val result = piiMasker.mask(input)
    assertEquals("Phone: [PHONE]", result)
  }

  @Test
  fun `should mask phone number with dots`() {
    val input = "Phone: 214.555.1234"
    val result = piiMasker.mask(input)
    assertEquals("Phone: [PHONE]", result)
  }

  @Test
  fun `should mask phone number with country code`() {
    val input = "Call +1-214-555-1234"
    val result = piiMasker.mask(input)
    assertEquals("Call [PHONE]", result)
  }

  // === SSN TESTS ===

  @Test
  fun `should mask SSN with dashes`() {
    val input = "SSN: 123-45-6789"
    val result = piiMasker.mask(input)
    assertEquals("SSN: [SSN]", result)
  }

  @Test
  fun `should mask SSN without dashes`() {
    val input = "SSN: 123456789"
    val result = piiMasker.mask(input)
    assertEquals("SSN: [SSN]", result)
  }

  // === CREDIT CARD TESTS ===

  @Test
  fun `should mask credit card with dashes`() {
    val input = "Card: 4111-1111-1111-1111"
    val result = piiMasker.mask(input)
    assertEquals("Card: [CREDIT_CARD]", result)
  }

  @Test
  fun `should mask credit card with spaces`() {
    val input = "Card: 4111 1111 1111 1111"
    val result = piiMasker.mask(input)
    assertEquals("Card: [CREDIT_CARD]", result)
  }

  // === IP ADDRESS TESTS ===

  @Test
  fun `should mask IPv4 address`() {
    val input = "Server IP: 192.168.1.100"
    val result = piiMasker.mask(input)
    assertEquals("Server IP: [IP_ADDRESS]", result)
  }

  @Test
  fun `should mask multiple IP addresses`() {
    val input = "From 10.0.0.1 to 192.168.0.1"
    val result = piiMasker.mask(input)
    assertEquals("From [IP_ADDRESS] to [IP_ADDRESS]", result)
  }

  // === STREET ADDRESS TESTS ===

  @Test
  fun `should mask street address`() {
    val input = "Located at 123 Main Street"
    val result = piiMasker.mask(input)
    assertEquals("Located at [ADDRESS]", result)
  }

  @Test
  fun `should mask street address with abbreviated type`() {
    val input = "Located at 456 Oak Ave"
    val result = piiMasker.mask(input)
    assertEquals("Located at [ADDRESS]", result)
  }

  // === COMBINED PII TESTS ===

  @Test
  fun `should mask multiple types of PII`() {
    val input = "Contact john@test.com at 214-555-1234"
    val result = piiMasker.mask(input)
    assertEquals("Contact [EMAIL] at [PHONE]", result)
  }

  @Test
  fun `should not modify text without PII`() {
    val input = "How do I use the laser cutter at DMS?"
    val result = piiMasker.mask(input)
    assertEquals(input, result)
  }

  @Test
  fun `should handle empty string`() {
    val input = ""
    val result = piiMasker.mask(input)
    assertEquals("", result)
  }

  @Test
  fun `should handle blank string`() {
    val input = "   "
    val result = piiMasker.mask(input)
    assertEquals("   ", result)
  }

  // === CONTAINS PII TESTS ===

  @Test
  fun `containsPii should return true for email`() {
    assertTrue(piiMasker.containsPii("test@example.com"))
  }

  @Test
  fun `containsPii should return true for phone`() {
    assertTrue(piiMasker.containsPii("214-555-1234"))
  }

  @Test
  fun `containsPii should return false for clean text`() {
    assertFalse(piiMasker.containsPii("How do I book the laser cutter?"))
  }

  // === MASK ALL TESTS ===

  @Test
  fun `maskAll should mask all strings in list`() {
    val inputs = listOf("Email: test@example.com", "Phone: 214-555-1234", "No PII here")
    val results = piiMasker.maskAll(inputs)
    assertEquals(listOf("Email: [EMAIL]", "Phone: [PHONE]", "No PII here"), results)
  }
}
