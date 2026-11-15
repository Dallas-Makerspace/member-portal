package org.dallasmakerspace.dashboards

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DashboardImageServiceTest {

  private fun image(
      width: Int,
      height: Int,
      type: Int = BufferedImage.TYPE_INT_ARGB,
      format: String = "png",
  ): ByteArray {
    val picture = BufferedImage(width, height, type)
    val out = ByteArrayOutputStream()
    ImageIO.write(picture, format, out)
    return out.toByteArray()
  }

  private fun decoded(bytes: ByteArray): BufferedImage = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)))

  @Test
  fun `a png becomes a jpeg`() {
    val result = DashboardImageService.process(image(200, 100))

    assertEquals(0xFF.toByte(), result[0])
    assertEquals(0xD8.toByte(), result[1])
    assertEquals(200, decoded(result).width)
  }

  @Test
  fun `a jpeg is accepted`() {
    val result = DashboardImageService.process(image(64, 64, BufferedImage.TYPE_INT_RGB, "jpg"))

    assertEquals(64, decoded(result).height)
  }

  @Test
  fun `big pictures are shrunk to 1600 on the long side`() {
    val result = decoded(DashboardImageService.process(image(3200, 1600)))

    assertEquals(1600, result.width)
    assertEquals(800, result.height)
  }

  @Test
  fun `transparency is flattened onto white`() {
    val result = decoded(DashboardImageService.process(image(8, 8)))

    assertTrue((result.getRGB(0, 0) and 0xFFFFFF) > 0xF0F0F0)
  }

  @Test
  fun `other file types are rejected`() {
    assertFailsWith<DashboardException> { DashboardImageService.process("<svg></svg>".toByteArray()) }
    assertFailsWith<DashboardException> { DashboardImageService.process(ByteArray(0)) }
  }

  @Test
  fun `a truncated picture is rejected as unreadable`() {
    val png = image(50, 50)

    assertFailsWith<DashboardException> { DashboardImageService.process(png.copyOf(png.size / 3)) }
  }

  @Test
  fun `uploads over 5 MB are rejected`() {
    val big = ByteArray(DashboardImageService.MAX_UPLOAD_BYTES + 1) { 0x42 }

    assertFailsWith<DashboardException> { DashboardImageService.process(big) }
  }

  @Test
  fun `only generated file names are valid`() {
    assertTrue(DashboardImageService.isValidFile("3f2a6c1e-0b7d-4a55-9c1e-2d4b6a8c0e11.jpg"))
    assertFalse(DashboardImageService.isValidFile("../3f2a6c1e-0b7d-4a55-9c1e-2d4b6a8c0e11.jpg"))
    assertFalse(DashboardImageService.isValidFile("9/3f2a6c1e-0b7d-4a55-9c1e-2d4b6a8c0e11.jpg"))
    assertFalse(DashboardImageService.isValidFile("photo.jpg"))
  }
}
