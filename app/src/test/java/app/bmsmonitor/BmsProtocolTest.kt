package app.bmsmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BmsProtocolTest {
    @Test fun decodesStatusScalingAndSignedCurrent() {
        val packet = ByteArray(20)
        packet[0] = 1
        packet[1] = 0x14
        packet[2] = 0x03 // 5123 / 100 = 51.23 V
        packet[3] = 0xFF.toByte()
        packet[4] = 0x6A // -150 / 100 = -1.50 A
        packet[5] = 73
        packet[6] = 95
        packet[7] = 42
        packet[9] = 25
        packet[10] = 20
        packet[11] = 30
        packet[13] = 12
        packet[14] = 0xA5.toByte()
        packet[15] = 0
        packet[16] = 0xD2.toByte() // Unmapped trailing bytes must not become capacity readings.
        packet[17] = 0x04
        packet[18] = 0xB8.toByte()
        packet[19] = 0x0B

        val status = BmsProtocol.parseStatus(packet)!!
        assertEquals("Diam", status.state)
        assertEquals(51.23, status.voltage, 0.0001)
        assertEquals(-1.5, status.current, 0.0001)
        assertEquals(73, status.soc)
        assertEquals(95, status.soh)
        assertEquals(42, status.speed)
        assertEquals(12, status.cellCount)
        assertEquals(0xA5, status.bmsStatus)
        assertEquals(BmsProtocol.parseStatus(packet.copyOf(16)), status)
    }

    @Test fun rejectsShortStatusPacket() {
        assertNull(BmsProtocol.parseStatus(ByteArray(15)))
    }

    @Test fun decodesCellsAndDropsEmptySentinelValues() {
        val block = ByteArray(20)
        block[0] = 0x0C
        block[1] = 0xE4.toByte()
        block[2] = 0x0D
        block[3] = 0x48
        block[6] = 0xFF.toByte()
        block[7] = 0xFF.toByte()
        assertEquals(listOf(3.3, 3.4), BmsProtocol.parseCells(block))
    }

    @Test fun ignoresCellBlocksShorterThanTwentyBytesAndCapsLongBlocks() {
        val partial = ByteArray(19).also { it[0] = 0x0C; it[1] = 0xE4.toByte() }
        assertTrue(BmsProtocol.parseCells(partial).isEmpty())
        val completeAndLong = ByteArray(22).also {
            it[0] = 0x0C; it[1] = 0xE4.toByte()
            it[20] = 0x0D; it[21] = 0x48
        }
        assertEquals(listOf(3.3), BmsProtocol.parseCells(completeAndLong))
    }

    @Test fun mapsFaultBitsAndReportsUnspecifiedBmsFault() {
        val faultA = ByteArray(3)
        faultA[1] = (1 shl 4).toByte()
        assertEquals("Hubung singkat", BmsProtocol.parseAlarms(faultA, null).single().label)

        val status = BmsProtocol.parseStatus(ByteArray(16).also { it[15] = 1 })!!
        val fallback = BmsProtocol.parseAlarms(null, null, status)
        assertTrue(fallback.single().label.contains("BMS"))
        assertFalse(BmsProtocol.parseAlarms(null, null).isNotEmpty())
    }

    @Test fun emitsOnlyListedOriginalAppCommandPayloads() {
        assertEquals("11 01 0B", BmsProtocol.toHex(BmsProtocol.chargeMos(true)))
        assertEquals("11 01 0A", BmsProtocol.toHex(BmsProtocol.chargeMos(false)))
        assertEquals("12 01 0B", BmsProtocol.toHex(BmsProtocol.dischargeMos(true)))
        assertEquals("12 01 0A", BmsProtocol.toHex(BmsProtocol.dischargeMos(false)))
        assertEquals("13 01 0B", BmsProtocol.toHex(BmsProtocol.buzzer(true)))
        assertEquals("13 01 0A", BmsProtocol.toHex(BmsProtocol.buzzer(false)))
        assertEquals("12 01 0B", BmsProtocol.toHex(BmsProtocol.setPowerSwitch(true)))
        assertEquals("12 01 0A", BmsProtocol.toHex(BmsProtocol.setPowerSwitch(false)))
        assertEquals("10 01 0A", BmsProtocol.toHex(BmsProtocol.sleep()))
        assertEquals("FF FF FF", BmsProtocol.toHex(BmsProtocol.handshake.take(3).toByteArray()))
    }
}
