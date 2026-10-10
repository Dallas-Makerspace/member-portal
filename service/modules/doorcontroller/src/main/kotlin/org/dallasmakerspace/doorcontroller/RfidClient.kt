package org.dallasmakerspace.doorcontroller

import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dallasmakerspace.core.LoggerFactory

/**
 * Client for communicating with RFID door controllers via TCP.
 *
 * @param ip IP address of the controller
 * @param serial Serial number written on the controller (also "Device NO" on web interface)
 * @param timeoutSeconds Socket timeout in seconds
 * @param loggerFactory Logger factory for creating logger instance
 */
class RfidClient(
    private val ip: String,
    private val serial: Long,
    private val timeoutSeconds: Int = 5,
    loggerFactory: LoggerFactory,
) : Closeable {
  private val log = loggerFactory.create(RfidClient::class.java)
  private val port = 60000
  private var socket: Socket? = null
  private val controllerSerial: String
  private val sourcePort = "0000" // Part of byte string replaced by CRC, not required to be valid
  private val startTransaction = "0d0d0000000000000000000000000000000000000000000000000000"

  init {
    // Pack serial as little-endian 32-bit integer
    controllerSerial =
        ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(serial.toInt())
            .array()
            .joinToString("") { "%02x".format(it) }
  }

  /** Establishes TCP connection to the controller. */
  private suspend fun connect(): Socket =
      withContext(Dispatchers.IO) {
        socket
            ?.takeIf { it.isConnected && !it.isClosed }
            ?.let {
              log.debug("Reusing existing connection to $ip:$port")
              return@withContext it
            }

        log.info("Connecting to door controller at $ip:$port (serial: $serial)")

        try {
          // Resolve hostname to InetAddress
          val address = java.net.InetAddress.getByName(ip)
          log.debug("Resolved $ip to ${address.hostAddress} (${address.javaClass.simpleName})")

          // Create socket address
          val socketAddress = InetSocketAddress(address, port)
          log.debug("Target address: ${socketAddress.address.hostAddress}:${socketAddress.port}")

          // Create and connect socket
          val sock = Socket()
          log.debug("Created socket, setting timeout to ${timeoutSeconds * 1000}ms")
          sock.soTimeout = timeoutSeconds * 1000

          log.debug("Attempting connection...")
          sock.connect(socketAddress, timeoutSeconds * 1000)

          socket = sock
          log.info(
              "Successfully connected to $ip:$port " +
                  "(remote: ${sock.inetAddress.hostAddress}:${sock.port}, " +
                  "local: ${sock.localAddress.hostAddress}:${sock.localPort})")
          sock
        } catch (e: Exception) {
          log.error("Failed to connect to $ip:$port: ${e.javaClass.simpleName} - ${e.message}", e)
          log.error("Stack trace:", e)
          throw e
        }
      }

  /**
   * Computes CRC-16-IBM checksum and inserts it into positions 4-8 of the hex string.
   *
   * @param data Original hex string which needs CRC values added
   * @return ByteArray with CRC values inserted
   */
  private fun computeCrc16Ibm(data: String): ByteArray {
    val hexData = data.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    var num1 = 0

    for (i in hexData.indices) {
      var num2 = hexData[i].toInt() and 0xFF
      // Zero out bytes at positions 2 and 3 (where CRC will be placed)
      if (i == 2 || i == 3) {
        num2 = 0
      }
      num1 = num1 xor num2

      repeat(8) {
        num1 =
            if ((num1 and 1) > 0) {
              (num1 shr 1) xor 40961
            } else {
              num1 shr 1
            }
      }
    }

    val code = num1 and 65535

    // Convert data string to mutable list
    val listString = data.toMutableList()

    // Pack CRC code as little-endian unsigned short and insert into positions 4-8
    val crcBytes =
        ByteBuffer.allocate(2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(code.toShort())
            .array()
            .joinToString("") { "%02x".format(it) }

    // Replace characters at positions 4-7 with CRC
    for (i in 0 until 4) {
      listString[4 + i] = crcBytes[i]
    }

    // Convert back to ByteArray
    return listString.joinToString("").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
  }

  /** Sends data to the controller and receives response. */
  private suspend fun sendAndReceive(data: ByteArray): ByteArray =
      withContext(Dispatchers.IO) {
        val sock = connect()
        val outputStream = sock.getOutputStream()
        val inputStream = sock.getInputStream()

        // Send start transaction
        val startBytes = startTransaction.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val startHex = startBytes.joinToString("") { "%02x".format(it) }
        log.debug("Sending start transaction: $startHex")
        outputStream.write(startBytes)

        // Send actual data
        val dataHex = data.joinToString("") { "%02x".format(it) }
        log.debug("Sending data packet (${data.size} bytes): $dataHex")
        outputStream.write(data)
        outputStream.flush()

        // Receive response
        val buffer = ByteArray(1024)
        val bytesRead = inputStream.read(buffer)
        val response = buffer.copyOf(bytesRead)
        val responseHex = response.joinToString("") { "%02x".format(it) }
        log.debug("Received response (${response.size} bytes): $responseHex")
        response
      }

  /**
   * Adds a user with access to specified doors.
   *
   * @param badge RFID badge number (in comma format, without comma)
   * @param doors List of door numbers to grant access to (1-4)
   */
  @Suppress("TooGenericExceptionThrown")
  suspend fun addUser(badge: Int, doors: List<Int>) {
    log.info("Adding user $badge to doors $doors on controller $serial")

    // Validate doors
    require(doors.all { it in 1..4 }) { "Door numbers must be between 1 and 4" }

    // Create doors list: "01" for enabled, "00" for disabled
    val doorsList = buildString {
      append(if (1 in doors) "01" else "00")
      append(if (2 in doors) "01" else "00")
      append(if (3 in doors) "01" else "00")
      append(if (4 in doors) "01" else "00")
    }

    // Pack badge as little-endian integer
    val badgeHex =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(badge).array().joinToString(
            "") {
              "%02x".format(it)
            }

    // First packet
    val addPacket1 =
        computeCrc16Ibm("2010${sourcePort}2800000000000000${controllerSerial}00000200ffffffff")

    val response1 = sendAndReceive(addPacket1)
    val response1Hex = response1.joinToString("") { "%02x".format(it) }

    if (!response1Hex.startsWith("2011")) {
      throw Exception("Unexpected response from controller: $response1Hex (expected 2011)")
    }

    // Second packet
    val addPacket2 =
        computeCrc16Ibm(
            "2320${sourcePort}2900000000000000${controllerSerial}00000200${badgeHex}" +
                "00000000a04e4605871c9f3b${doorsList}00000000")

    val response2 = sendAndReceive(addPacket2)
    val response2Hex = response2.joinToString("") { "%02x".format(it) }

    if (!response2Hex.startsWith("2321")) {
      throw Exception("Unexpected response from controller: $response2Hex (expected 2321)")
    }

    log.info("Successfully added user $badge to controller $serial")
  }

  /**
   * Removes a user's access.
   *
   * @param badge RFID badge number (in comma format, without comma)
   */
  @Suppress("TooGenericExceptionThrown")
  suspend fun removeUser(badge: Int) {
    log.info("Removing user $badge from controller $serial")

    // Pack badge as little-endian integer
    val badgeHex =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(badge).array().joinToString(
            "") {
              "%02x".format(it)
            }

    val removePacket =
        computeCrc16Ibm(
            "2320${sourcePort}2200000000000000${controllerSerial}00000200${badgeHex}" +
                "00000000204e460521149f3b0000000000000000")

    val response = sendAndReceive(removePacket)
    val responseHex = response.joinToString("") { "%02x".format(it) }

    if (!responseHex.startsWith("2321")) {
      throw Exception("Unexpected response from controller: $responseHex (expected 2321)")
    }

    log.info("Successfully removed user $badge from controller $serial")
  }

  /**
   * Checks if a user has access and which doors they can access.
   *
   * Based on the add/remove packet patterns, this attempts to query user access. The read operation
   * likely uses a different command code than add (0x2320) or initial query (0x2010).
   *
   * @param badge RFID badge number (in comma format, without comma)
   * @return List of door numbers the user has access to, or empty list if no access
   * @throws Exception if unable to query user access from the controller
   */
  suspend fun checkUserAccess(badge: Int): List<Int> {
    log.info("Checking user access for badge $badge on controller $serial")

    try {
      val responseHex = queryUserAccess(badge)
      return parseDoorsFromResponse(badge, responseHex)
    } catch (e: Exception) {
      log.error(
          "Failed to check user access for badge $badge on controller $serial: ${e.message}", e)
      throw Exception(
          "Failed to check user access for badge $badge on controller $serial: ${e.message}", e)
    }
  }

  private suspend fun queryUserAccess(badge: Int): String {
    log.debug("Querying user access for badge $badge on controller $serial")

    // Pack badge as little-endian integer
    val badgeHex =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(badge).array().joinToString(
            "") {
              "%02x".format(it)
            }

    log.debug("Badge $badge in hex (little-endian): $badgeHex")

    // Query packet - using 0x2310 as read command (pattern: 0x2010 = init, 0x2320 = write, 0x2310 =
    // read?)
    // This is an educated guess based on the command structure
    val queryPacketStr =
        "2310${sourcePort}2100000000000000${controllerSerial}00000200${badgeHex}0000000000000000"
    log.debug("Query packet (before CRC): $queryPacketStr")

    val queryPacket = computeCrc16Ibm(queryPacketStr)

    val response = sendAndReceive(queryPacket)
    val responseHex = response.joinToString("") { "%02x".format(it) }
    log.debug("Query user access response: $responseHex")
    return responseHex
  }

  private fun parseDoorsFromResponse(badge: Int, responseHex: String): List<Int> {
    log.debug("Parsing doors from response (length: ${responseHex.length})")

    // Expected response might be 0x2311 (following the pattern: request+1 = response)
    if (!responseHex.startsWith("2311")) {
      log.warn(
          "Unexpected response from controller: $responseHex (expected 2311, got ${
            responseHex.take(4)
          })")
      return emptyList()
    }

    val doors = parseDoorDataFromHex(responseHex)
    log.info("User $badge has access to doors: $doors on controller $serial")
    return doors
  }

  private fun parseDoorDataFromHex(responseHex: String): List<Int> {
    val doors = mutableListOf<Int>()

    // This offset is a guess - may need adjustment based on actual controller behavior
    if (responseHex.length >= 60) {
      val doorData = responseHex.substring(50, 58)
      log.debug("Door data substring (chars 50-58): $doorData")
      if (doorData.substring(0, 2) == "01") doors.add(1)
      if (doorData.substring(2, 4) == "01") doors.add(2)
      if (doorData.substring(4, 6) == "01") doors.add(3)
      if (doorData.substring(6, 8) == "01") doors.add(4)
    } else {
      log.warn(
          "Response too short (${responseHex.length} chars) to parse door data, need at least 60")
    }

    return doors
  }

  /**
   * Reads recent badge swipe events from the controller.
   *
   * Note: This is an educated guess based on the protocol patterns observed. The actual command
   * code and response format may need adjustment when tested against real controllers.
   *
   * Expected command pattern: 0x2340 for reading access logs Expected response: 0x2341 with log
   * entries
   *
   * @param minutes Number of minutes to look back
   * @return List of badge swipe events
   * @throws Exception if unable to read swipe events from the controller
   */
  suspend fun readRecentSwipes(minutes: Int): List<BadgeSwipeEvent> {
    log.info("Reading badge swipes for past $minutes minutes from controller $serial")

    try {
      val responseHex = queryAccessLogs(minutes)
      return parseSwipeEvents(responseHex)
    } catch (e: Exception) {
      log.error("Failed to read swipe events from controller $serial: ${e.message}", e)
      throw Exception("Failed to read swipe events from controller $serial: ${e.message}", e)
    }
  }

  private suspend fun queryAccessLogs(minutes: Int): String {
    log.debug("Querying access logs for past $minutes minutes on controller $serial")

    // Calculate time range (this is a guess - may need to be sent differently)
    val currentTime = System.currentTimeMillis() / 1000
    val startTime = currentTime - (minutes * 60)

    log.debug("Time range: $startTime to $currentTime (Unix seconds)")

    // Pack times as little-endian integers
    val startTimeHex =
        ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(startTime.toInt())
            .array()
            .joinToString("") { "%02x".format(it) }

    val endTimeHex =
        ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(currentTime.toInt())
            .array()
            .joinToString("") { "%02x".format(it) }

    log.debug("Start time hex: $startTimeHex, End time hex: $endTimeHex")

    // Query packet - using 0x2340 as read access log command
    // This is an educated guess and may need adjustment
    val queryPacketStr =
        "2340${sourcePort}3000000000000000${controllerSerial}00000200" +
            "${startTimeHex}${endTimeHex}00000000"
    log.debug("Access logs query packet (before CRC): $queryPacketStr")

    val queryPacket = computeCrc16Ibm(queryPacketStr)

    val response = sendAndReceive(queryPacket)
    val responseHex = response.joinToString("") { "%02x".format(it) }
    log.debug("Access logs response (${response.size} bytes): $responseHex")
    return responseHex
  }

  private fun parseSwipeEvents(responseHex: String): List<BadgeSwipeEvent> {
    val events = mutableListOf<BadgeSwipeEvent>()

    log.debug("Parsing swipe events from response (length: ${responseHex.length} chars)")

    // Expected response might be 0x2341 (following the pattern: request+1 = response)
    if (!responseHex.startsWith("2341")) {
      log.warn(
          "Unexpected response from controller: $responseHex (expected 2341, got ${
            responseHex.take(4)
          })")
      return emptyList()
    }

    // Parse log entries from response
    // Each entry might be 16-20 bytes containing:
    // - Badge number (4 bytes)
    // - Door number (1 byte)
    // - Event type (1 byte) - 0x01 = granted, 0x02 = denied
    // - Timestamp (4 bytes)
    // - Padding/other data

    // This is a guess at the response format - skip header and parse entries
    val dataStart = 20
    val entrySize = 20

    if (responseHex.length < dataStart) {
      log.warn(
          "Response too short (${responseHex.length} chars) to parse events, need at least $dataStart")
      return emptyList()
    }

    log.debug("Parsing events starting at offset $dataStart with entry size $entrySize")

    var offset = dataStart
    var eventCount = 0
    while (offset + entrySize <= responseHex.length) {
      try {
        eventCount++
        log.debug("Parsing event #$eventCount at offset $offset")

        // Extract badge number (4 bytes, little-endian)
        val badgeHex = responseHex.substring(offset, offset + 8)
        val badgeBytes = badgeHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val badge = ByteBuffer.wrap(badgeBytes).order(ByteOrder.LITTLE_ENDIAN).int
        log.debug("  Badge hex: $badgeHex -> $badge")

        // Extract door number (1 byte)
        val doorNumber = responseHex.substring(offset + 8, offset + 10).toInt(16)
        log.debug("  Door number: $doorNumber")

        // Extract event type (1 byte)
        val eventTypeCode = responseHex.substring(offset + 10, offset + 12).toInt(16)
        val eventType =
            when (eventTypeCode) {
              1 -> SwipeEventType.ACCESS_GRANTED
              2 -> SwipeEventType.ACCESS_DENIED
              else -> SwipeEventType.UNKNOWN
            }
        log.debug("  Event type code: $eventTypeCode -> $eventType")

        // Extract timestamp (4 bytes, little-endian, seconds since epoch)
        val timestampHex = responseHex.substring(offset + 12, offset + 20)
        val timestampBytes = timestampHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val timestamp = ByteBuffer.wrap(timestampBytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
        log.debug("  Timestamp hex: $timestampHex -> $timestamp")

        // Convert badge from comma format to 10-digit if needed
        // (This depends on how the controller stores badge numbers)
        val badgeConverted =
            try {
              RfidUtils.commaFormatToTenDigit(badge)
            } catch (e: Exception) {
              log.debug("  Badge conversion failed, using as-is: $badge")
              badge // If conversion fails, use as-is
            }

        events.add(
            BadgeSwipeEvent(
                controllerName = ip,
                doorNumber = doorNumber,
                badge = badgeConverted,
                timestamp = timestamp * 1000,
                eventType = eventType,
                friendlyDoorName = "door_$doorNumber"))

        offset += entrySize
      } catch (e: Exception) {
        log.warn("Failed to parse swipe event at offset $offset", e)
        break
      }
    }

    log.info("Parsed ${events.size} swipe events from controller $serial")
    return events
  }

  override fun close() {
    socket?.close()
    socket = null
    log.debug("Closed connection to controller at $ip:$port")
  }
}
