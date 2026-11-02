package app.bmsmonitor

import java.util.Locale

/** Protocol constants and packet decoding mirrored from the reference PWA. */
object BmsProtocol {
    const val SERVICE_UUID = "00008900-0000-1000-8000-00805f9b34fb"
    const val AUTH_UUID = "00008901-0000-1000-8000-00805f9b34fb"
    const val STATUS_UUID = "00008902-0000-1000-8000-00805f9b34fb"
    const val CELLS_A_UUID = "00008903-0000-1000-8000-00805f9b34fb"
    const val CELLS_B_UUID = "00008904-0000-1000-8000-00805f9b34fb"
    const val FAULT_A_UUID = "00008905-0000-1000-8000-00805f9b34fb"
    const val FAULT_B_UUID = "00008906-0000-1000-8000-00805f9b34fb"
    const val CELLS_C_UUID = "00008907-0000-1000-8000-00805f9b34fb"
    const val INFO_UUID = "00008908-0000-1000-8000-00805f9b34fb"
    const val COMMAND_UUID = "00008910-0000-1000-8000-00805f9b34fb"
    const val ADVERTISED_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"

    val handshake: ByteArray get() = ByteArray(17) { 0xFF.toByte() }

    data class Status(
        val state: String,
        val voltage: Double,
        val current: Double,
        val soc: Int,
        val soh: Int,
        val speed: Int,
        val temperature: Int,
        val minCellTemp: Int,
        val maxCellTemp: Int,
        val cellCount: Int,
        val bmsStatus: Int,
        val hasFault: Boolean
    )

    data class Alarm(val code: String, val label: String)

    data class Snapshot(
        val status: Status?,
        val cellsVolts: List<Double>,
        val alarms: List<Alarm>
    )

    private val states = listOf("Bergerak", "Diam", "Penyimpanan", "Tidur", "Tidur dalam")
    private data class BitAlarm(val byte: Int, val bit: Int, val label: String)
    private val alarmsA = listOf(
        BitAlarm(1, 2, "Arus pengisian berlebih"),
        BitAlarm(1, 3, "Arus pengosongan berlebih"),
        BitAlarm(1, 4, "Hubung singkat"),
        BitAlarm(1, 5, "Suhu pengisian tinggi"),
        BitAlarm(1, 6, "Suhu pengosongan tinggi"),
        BitAlarm(1, 7, "Suhu pengisian rendah"),
        BitAlarm(2, 0, "Suhu pengosongan rendah"),
        BitAlarm(2, 1, "MOS pengisian rusak"),
        BitAlarm(2, 2, "MOS pengosongan rusak"),
        BitAlarm(2, 3, "Kesalahan komunikasi internal")
    )
    private val alarmsB = listOf(
        BitAlarm(5, 0, "Selisih tegangan sel besar"),
        BitAlarm(5, 1, "Tegangan total berlebih"),
        BitAlarm(5, 2, "Tegangan total rendah"),
        BitAlarm(5, 3, "Suhu sangat tinggi"),
        BitAlarm(5, 4, "Suhu MOS tinggi"),
        BitAlarm(5, 5, "Suhu lingkungan rendah"),
        BitAlarm(5, 6, "Suhu lingkungan tinggi"),
        BitAlarm(5, 7, "Kegagalan sel"),
        BitAlarm(6, 3, "Baterai terlepas")
    )

    /** Only bytes 0–15 are mapped by the recovered status decoder; leave trailing bytes uninterpreted. */
    fun parseStatus(bytes: ByteArray?): Status? {
        if (bytes == null || bytes.size < 16) return null
        val stateIndex = u8(bytes[0])
        return Status(
            state = states.getOrNull(stateIndex) ?: "Tidak diketahui",
            voltage = u16be(bytes, 1) / 100.0,
            current = s16be(bytes, 3) / 100.0,
            soc = u8(bytes[5]),
            soh = u8(bytes[6]),
            speed = u8(bytes[7]),
            temperature = bytes[9].toInt(),
            minCellTemp = bytes[10].toInt(),
            maxCellTemp = bytes[11].toInt(),
            cellCount = u8(bytes[13]),
            bmsStatus = u8(bytes[14]),
            hasFault = u8(bytes[15]) == 1
        )
    }

    fun parseCells(vararg blocks: ByteArray?): List<Double> {
        val values = mutableListOf<Double>()
        blocks.forEach { block ->
            // Ignore incomplete characteristic payloads instead of treating them as cell lists.
            if (block == null || block.size < 20) return@forEach
            var offset = 0
            while (offset + 1 < block.size && offset < 20) {
                val raw = u16be(block, offset)
                if (raw != 0xFFFF && raw > 0) values += raw / 1000.0
                offset += 2
            }
        }
        return values
    }

    fun parseAlarms(faultA: ByteArray?, faultB: ByteArray?, status: Status? = null): List<Alarm> {
        val found = mutableListOf<Alarm>()
        addSetBits(found, faultA, alarmsA)
        addSetBits(found, faultB, alarmsB)
        if (status?.hasFault == true && found.isEmpty()) {
            found += Alarm("bms", "BMS melaporkan gangguan; detail tidak tersedia")
        }
        return found
    }

    fun snapshot(
        statusBytes: ByteArray?,
        cellsA: ByteArray?,
        cellsB: ByteArray?,
        cellsC: ByteArray?,
        faultA: ByteArray?,
        faultB: ByteArray?
    ): Snapshot {
        val status = parseStatus(statusBytes)
        val allCells = parseCells(cellsA, cellsB, cellsC)
        val cells = if (status != null && status.cellCount > 0) allCells.take(status.cellCount) else allCells
        return Snapshot(status, cells, parseAlarms(faultA, faultB, status))
    }

    /** Direct command payloads recovered from the original app; effects are not confirmed by BLE writes. */
    fun chargeMos(enabled: Boolean) = byteArrayOf(17, 1, if (enabled) 11 else 10)
    fun dischargeMos(enabled: Boolean) = byteArrayOf(18, 1, if (enabled) 11 else 10)
    fun setPowerSwitch(enabled: Boolean) = byteArrayOf(18, 1, if (enabled) 11 else 10)
    fun buzzer(enabled: Boolean) = byteArrayOf(19, 1, if (enabled) 11 else 10)
    fun sleep() = byteArrayOf(16, 1, 10)

    fun toHex(bytes: ByteArray): String = bytes.joinToString(" ") {
        String.format(Locale.US, "%02X", u8(it))
    }

    private fun addSetBits(into: MutableList<Alarm>, bytes: ByteArray?, definitions: List<BitAlarm>) {
        if (bytes == null) return
        definitions.forEach { item ->
            if (item.byte < bytes.size && (u8(bytes[item.byte]) and (1 shl item.bit)) != 0) {
                into += Alarm("${item.byte}.${item.bit}", item.label)
            }
        }
    }

    private fun u8(value: Byte) = value.toInt() and 0xFF
    private fun u16be(bytes: ByteArray, offset: Int) = (u8(bytes[offset]) shl 8) or u8(bytes[offset + 1])
    private fun s16be(bytes: ByteArray, offset: Int): Int = u16be(bytes, offset).let { if (it and 0x8000 != 0) it - 0x10000 else it }
}
