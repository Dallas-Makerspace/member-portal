package org.dallasmakerspace.askai

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.core.LoggerFactory

/**
 * Utility for masking Personally Identifiable Information (PII) before sending data to external
 * LLMs. This helps protect user privacy by replacing sensitive patterns with placeholder tokens.
 *
 * Supported PII types:
 * - Email addresses
 * - Phone numbers (US formats)
 * - Social Security Numbers
 * - Credit card numbers
 * - IP addresses (v4)
 */
@Singleton
class PiiMasker @Inject constructor(loggerFactory: LoggerFactory) {
  private val log = loggerFactory.create(javaClass)

  /**
   * Mask all detected PII in the given text.
   *
   * @param text The text to scan and mask
   * @return The text with PII replaced by placeholder tokens
   */
  fun mask(text: String): String {
    if (text.isBlank()) return text

    var masked = text

    // Apply each masking pattern in order
    PII_PATTERNS.forEach { (name, pattern, replacement) ->
      val matches = pattern.findAll(masked).count()
      if (matches > 0) {
        log.debug("Masking $matches $name pattern(s)")
        masked = pattern.replace(masked, replacement)
      }
    }

    return masked
  }

  /**
   * Mask PII in a list of strings.
   *
   * @param texts List of texts to mask
   * @return List of masked texts
   */
  fun maskAll(texts: List<String>): List<String> = texts.map { mask(it) }

  /**
   * Check if text contains any detectable PII.
   *
   * @param text The text to check
   * @return true if PII is detected
   */
  fun containsPii(text: String): Boolean {
    return PII_PATTERNS.any { (_, pattern, _) -> pattern.containsMatchIn(text) }
  }

  companion object {
    // Pattern definitions: (name, regex, replacement)
    private val PII_PATTERNS =
        listOf(
            // Email addresses
            PiiPattern(
                "email",
                Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}", RegexOption.IGNORE_CASE),
                "[EMAIL]",
            ),

            // Social Security Numbers (XXX-XX-XXXX or XXXXXXXXX)
            PiiPattern("ssn", Regex("\\b\\d{3}[- ]?\\d{2}[- ]?\\d{4}\\b"), "[SSN]"),

            // Credit card numbers (various formats with 13-19 digits)
            // Matches common card formats: XXXX-XXXX-XXXX-XXXX, XXXX XXXX XXXX XXXX, etc.
            PiiPattern("credit_card", Regex("\\b(?:\\d{4}[- ]?){3,4}\\d{1,4}\\b"), "[CREDIT_CARD]"),

            // US Phone numbers (various formats)
            // Matches: (123) 456-7890, 123-456-7890, 123.456.7890, 1234567890, +1-123-456-7890
            PiiPattern(
                "phone",
                Regex("(?:\\+?1[-.\\s]?)?(?:\\(?\\d{3}\\)?[-.\\s]?)?\\d{3}[-.\\s]?\\d{4}\\b"),
                "[PHONE]",
            ),

            // IPv4 addresses
            PiiPattern(
                "ip_address",
                Regex(
                    "\\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b"),
                "[IP_ADDRESS]",
            ),

            // Street addresses (basic pattern - number + street name + type)
            // This is a simplified pattern that catches common formats
            PiiPattern(
                "street_address",
                Regex(
                    "\\b\\d{1,5}\\s+(?:[A-Za-z]+\\s+){1,3}(?:Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Drive|Dr|Lane|Ln|Court|Ct|Way|Circle|Cir|Place|Pl)\\.?\\b",
                    RegexOption.IGNORE_CASE,
                ),
                "[ADDRESS]",
            ),

            // ZIP codes (US format: 12345 or 12345-6789)
            // Only match when preceded by state abbreviation or comma to avoid false positives
            PiiPattern("zip_code", Regex("(?<=[A-Z]{2}[,\\s]+)\\d{5}(?:-\\d{4})?\\b"), "[ZIP]"),
        )
  }

  private data class PiiPattern(
      val name: String,
      val pattern: Regex,
      val replacement: String,
  )
}
