package org.dallasmakerspace.notifications.templates

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class PlainExcerptTest {

  @Test
  fun `collapses whitespace and keeps short text whole`() {
    assertEquals("Line one Line two", plainExcerpt("  Line one\r\n\r\n  Line two \n"))
    assertEquals("", plainExcerpt("  \n "))
  }

  @Test
  fun `cuts long text at a word boundary with an ellipsis`() {
    val text = "alpha beta gamma delta epsilon zeta eta theta iota kappa"

    assertEquals("alpha beta gamma…", plainExcerpt(text, maxLength = 20))
  }

  @Test
  fun `cuts mid-word when there is no usable word boundary`() {
    assertEquals("abcdefgh…", plainExcerpt("abcdefghijklmnopqrstuvwxyz", maxLength = 8))
    assertTrue(plainExcerpt("x ".repeat(500)).length <= 201)
  }

  @Test
  fun `leaves markup alone since the result is escaped later`() {
    assertEquals("a <b> & c", plainExcerpt("a <b> & c"))
  }

  @Test
  fun `a word ending exactly at the limit is kept`() {
    assertEquals("abcde fghij…", plainExcerpt("abcde fghij klmno", maxLength = 11))
  }

  @Test
  fun `never leaves half of an emoji`() {
    val result = plainExcerpt("a".repeat(9) + "😀 more text", maxLength = 10)

    assertTrue(result.none { it == '\uD83D' }, result)
  }
}
