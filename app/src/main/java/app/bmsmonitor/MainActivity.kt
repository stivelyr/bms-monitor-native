package app.bmsmonitor

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {
    private val bg = 0xFF080D15.toInt()
    private val cardColor = 0xFF111A27.toInt()
    private val cardSoft = 0xFF172231.toInt()
    private val borderColor = 0xFF263548.toInt()
    private val textColor = 0xFFEAF1F7.toInt()
    private val mutedColor = 0xFF92A2B4.toInt()
    private val accent = 0xFF51D6B2.toInt()
    private val warn = 0xFFFFC56A.toInt()
    private val danger = 0xFFFF727A.toInt()
    private val handler = Handler(Looper.getMainLooper())
    private val gattQueue = ArrayDeque<GattOperation>()
    private val scanDevices = LinkedHashMap<String, BluetoothDevice>()
    private val scanNames = LinkedHashMap<String, String>()
    private val scanRssi = LinkedHashMap<String, Int>()
    private val logs = ArrayDeque<String>()
    private val voltageHistory = ArrayDeque<ChartReading>()
    private val commandStates = mutableMapOf<String, Boolean?>(
        "charge" to null, "discharge" to null, "buzzer" to null
    )
    private val statValues = mutableMapOf<String, TextView>()
    private val switchViews = mutableMapOf<String, Switch>()
    private val switchStateLabels = mutableMapOf<String, TextView>()
    private val switchDescriptions = mutableMapOf<String, String>()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var activeOperation: GattOperation? = null
    private var operationTimeout: Runnable? = null
    private var scanCallback: ScanCallback? = null
    private var scanRunnable: Runnable? = null
    private var pickerDialog: AlertDialog? = null
    private var selectedDeviceAddress: String? = null
    private var pickerResults: LinearLayout? = null
    private var pickerStatus: TextView? = null
    private var pickerProgress: ProgressBar? = null
    private var pickerConnect: Button? = null
    private var deviceName = ""
    private var connection = Connection.IDLE
    private var snapshot: BmsProtocol.Snapshot? = null
    private var reading = false
    private var uiSwitchGuard = false
    private var lastError: String? = null

    private lateinit var statusView: TextView
    private lateinit var connectionHint: TextView
    private lateinit var connectButton: Button
    private lateinit var refreshButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var errorPanel: TextView
    private lateinit var batteryBar: WaveBatteryBar
    private lateinit var socValue: TextView
    private lateinit var heroVoltage: TextView
    private lateinit var heroCurrent: TextView
    private lateinit var heroState: TextView
    private lateinit var cellList: LinearLayout
    private lateinit var cellSummary: LinearLayout
    private lateinit var chartView: VoltageChartView
    private lateinit var chartReadout: TextView
    private lateinit var chartSummary: TextView
    private lateinit var temperatureRow: LinearLayout
    private lateinit var alarmList: LinearLayout
    private lateinit var alarmSummary: TextView
    private lateinit var logList: LinearLayout
    private lateinit var logSummary: TextView
    private lateinit var commandStatus: TextView
    private lateinit var page: LinearLayout

    private enum class Connection { IDLE, SCANNING, CONNECTING, DISCOVERING, CONNECTED }
    private enum class OpKind { READ, WRITE, DESCRIPTOR, LOCAL }
    private data class ChartReading(val atMillis: Long, val volts: Double)
    private data class GattOperation(
        val kind: OpKind,
        val uuid: String = "",
        val bytes: ByteArray? = null,
        val callback: (Boolean, ByteArray?) -> Unit = { _, _ -> }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.setBackgroundColor(bg)
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        buildUi()
        renderConnection()
        updateData(null)
        refreshLogs()
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        stopScan()
        pickerDialog?.dismiss()
        handler.removeCallbacksAndMessages(null)
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) { }
        gatt = null
        voltageHistory.clear()
        super.onDestroy()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setFillViewport(true)
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(bg)
        }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            setBackgroundColor(bg)
        }
        scroll.addView(page, ViewGroup.LayoutParams(-1, -2))
        setContentView(scroll)

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(label("BMS Monitor", 22f, textColor, true))
        statusView = label("tidak terhubung", 12f, mutedColor, false).apply { typeface = Typeface.MONOSPACE }
        titleBox.addView(statusView, marginParams(-1, -2, top = 3))
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        connectButton = actionButton("Hubungkan", filled = true).apply { setOnClickListener { onConnectPressed() } }
        header.addView(connectButton)
        page.addView(header)

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        connectionHint = label("Pilih baterai BLE yang kompatibel untuk mulai membaca data.", 13f, mutedColor, false)
        actionRow.addView(connectionHint, LinearLayout.LayoutParams(0, -2, 1f))
        refreshButton = actionButton("Segarkan", filled = false).apply { setOnClickListener { beginReadCycle() } }
        disconnectButton = actionButton("Putus", filled = false).apply { setOnClickListener { disconnectFromBms() } }
        actionRow.addView(refreshButton, marginParams(-2, -2, start = 8))
        actionRow.addView(disconnectButton, marginParams(-2, -2, start = 8))
        page.addView(actionRow, marginParams(-1, -2, top = 12))

        errorPanel = label("", 13f, danger, false).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0x332D414A, borderColor, 12)
            visibility = View.GONE
        }
        page.addView(errorPanel, marginParams(-1, -2, top = 12))

        page.addView(sectionHeader("Baterai"), marginParams(-1, -2, top = 22))
        val hero = card()
        val heroRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val socBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        socValue = label("—%", 42f, textColor, true).apply { letterSpacing = -0.06f }
        socBox.addView(socValue)
        socBox.addView(label("STATE OF CHARGE", 9f, mutedColor, true), marginParams(-1, -2, top = 1))
        heroRow.addView(socBox, LinearLayout.LayoutParams(0, -2, 1f))
        heroState = label("—", 12f, mutedColor, false).apply {
            setPadding(dp(10), dp(7), dp(10), dp(7)); background = rounded(cardSoft, borderColor, 40)
        }
        heroRow.addView(heroState)
        hero.addView(heroRow)
        batteryBar = WaveBatteryBar(this, accent)
        hero.addView(batteryBar, marginParams(-1, dp(49), top = 10))
        val heroStats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val voltageBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val currentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroVoltage = label("0.00 V", 29f, textColor, true)
        heroCurrent = label("0.00 A", 18f, accent, true).apply { typeface = Typeface.MONOSPACE }
        voltageBox.addView(label("TEGANGAN PACK", 9f, mutedColor, true))
        voltageBox.addView(heroVoltage, marginParams(-1, -2, top = 3))
        currentBox.addView(label("ARUS", 9f, mutedColor, true))
        currentBox.addView(heroCurrent, marginParams(-1, -2, top = 3))
        heroStats.addView(voltageBox, LinearLayout.LayoutParams(0, -2, 1f))
        heroStats.addView(divider(), LinearLayout.LayoutParams(dp(1), dp(34)).apply { marginStart = dp(12); marginEnd = dp(12) })
        heroStats.addView(currentBox, LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(heroStats, marginParams(-1, -2, top = 13))
        page.addView(hero, marginParams(-1, -2, top = 10))

        page.addView(sectionHeader("Riwayat tegangan"), marginParams(-1, -2, top = 22))
        val chartCard = card()
        val chartTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        chartTop.addView(label("Voltage pack", 13f, textColor, true), LinearLayout.LayoutParams(0, -2, 1f))
        chartReadout = label("— V", 16f, accent, true).apply { typeface = Typeface.MONOSPACE; gravity = Gravity.END }
        chartTop.addView(chartReadout)
        chartCard.addView(chartTop)
        chartSummary = label("Sambungkan BMS untuk mulai mengumpulkan bacaan sesi ini.", 10f, mutedColor, false)
        chartCard.addView(chartSummary, marginParams(-1, -2, top = 4))
        chartView = VoltageChartView(this, accent, mutedColor) { selected ->
            if (selected == null) {
                chartReadout.text = "— V"
                chartSummary.text = "Sambungkan BMS untuk mulai mengumpulkan bacaan sesi ini."
            } else {
                chartReadout.text = String.format(Locale.US, "%.2f V", selected.volts)
                chartSummary.text = "${SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date(selected.atMillis))} · ${chartView.sampleCount()} bacaan sesi · geser untuk memeriksa"
            }
        }
        chartCard.addView(chartView, marginParams(-1, dp(150), top = 7))
        val rangeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf("24 jam" to 24, "7 hari" to 168, "30 hari" to 720).forEach { (caption, hours) ->
            val rangeButton = actionButton(caption, filled = false).apply {
                setOnClickListener {
                    chartView.setRangeHours(hours)
                }
            }
            rangeRow.addView(rangeButton, LinearLayout.LayoutParams(0, dp(37), 1f).apply { if (hours != 24) marginStart = dp(7) })
        }
        chartCard.addView(rangeRow, marginParams(-1, -2, top = 8))
        chartCard.addView(label("Grafik memakai data nyata dari sesi ini saja; riwayat tidak disimpan ke perangkat atau server.", 10f, mutedColor, false), marginParams(-1, -2, top = 8))
        page.addView(chartCard, marginParams(-1, -2, top = 10))

        page.addView(sectionHeader("Ringkasan"), marginParams(-1, -2, top = 22))
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val statRows = listOf(
            listOf("soh" to "Kesehatan", "cells" to "Jumlah sel"),
            listOf("remaining" to "Kapasitas sisa", "full" to "Kapasitas penuh"),
            listOf("minTemp" to "Suhu sel min", "maxTemp" to "Suhu sel maks"),
            listOf("speed" to "Kecepatan · raw", "bmsStatus" to "Status BMS · raw")
        )
        statRows.forEachIndexed { index, pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { col, item ->
                val tile = statTile(item.first, item.second)
                row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { if (col == 1) marginStart = dp(9) })
            }
            grid.addView(row, marginParams(-1, -2, top = if (index == 0) 0 else 9))
        }
        page.addView(grid, marginParams(-1, -2, top = 10))
        page.addView(label("Kapasitas tidak ditampilkan karena field kapasitas belum terverifikasi dalam protokol.", 11f, mutedColor, false), marginParams(-1, -2, top = 6))

        page.addView(sectionHeader("Tegangan sel"), marginParams(-1, -2, top = 22))
        val cellsCard = card()
        cellSummary = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cellsCard.addView(cellSummary)
        cellList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cellsCard.addView(cellList, marginParams(-1, -2, top = 10))
        page.addView(cellsCard, marginParams(-1, -2, top = 10))

        page.addView(sectionHeader("Suhu"), marginParams(-1, -2, top = 22))
        val tempCard = card()
        temperatureRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tempCard.addView(temperatureRow)
        page.addView(tempCard, marginParams(-1, -2, top = 10))

        page.addView(sectionHeader("Kontrol daya"), marginParams(-1, -2, top = 22))
        val controls = card()
        controls.addView(switchRow("MOS Pengisian", "Izinkan arus masuk ke baterai", "charge"))
        controls.addView(divider(), marginParams(-1, 1, top = 8, bottom = 8))
        controls.addView(switchRow("MOS Pengosongan", "Izinkan baterai memberi daya ke beban", "discharge"))
        controls.addView(divider(), marginParams(-1, 1, top = 8, bottom = 8))
        controls.addView(switchRow("Buzzer", "Nyalakan penanda suara pada baterai", "buzzer"))
        val powerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val powerOff = actionButton("Matikan baterai", filled = false, destructive = true).apply {
            setOnClickListener {
                val payload = BmsProtocol.setPowerSwitch(false)
                confirmPowerAction("Matikan baterai?", "Perintah SetPowerSwitch dapat memutus daya ke beban yang tersambung. Pastikan beban aman untuk dimatikan.", payload) {
                    sendDirectCommand("SetPowerSwitch OFF", payload)
                }
            }
        }
        val powerOn = actionButton("Nyalakan baterai", filled = false).apply {
            setOnClickListener {
                val payload = BmsProtocol.setPowerSwitch(true)
                confirmPowerAction("Nyalakan baterai?", "Perintah SetPowerSwitch akan dikirim. Status fisik tidak dapat dikonfirmasi melalui hasil tulis BLE.", payload) {
                    sendDirectCommand("SetPowerSwitch ON", payload)
                }
            }
        }
        powerRow.addView(powerOff, LinearLayout.LayoutParams(0, dp(48), 1f))
        powerRow.addView(powerOn, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(9) })
        controls.addView(powerRow, marginParams(-1, -2, top = 14))
        val sleepButton = actionButton("Mode tidur", filled = false).apply {
            setOnClickListener {
                val payload = BmsProtocol.sleep()
                confirmPowerAction("Masuk mode tidur?", "Mode tidur dapat mengubah perilaku baterai dan memutus keluaran.", payload) {
                    sendDirectCommand("Mode tidur", payload)
                }
            }
        }
        controls.addView(sleepButton, marginParams(-1, dp(46), top = 9))
        val safety = label("Kontrol memakai payload tetap dari pemetaan original app. Mematikan pengosongan dapat menghentikan motor, lampu, GPS, dan beban lain; perangkat yang tersambung langsung ke sel mungkin tetap menyala. Status menunjukkan hasil tulis BLE, bukan keadaan baterai yang dibaca balik.", 11f, warn, false)
        safety.setPadding(dp(10), dp(10), dp(10), dp(10))
        safety.background = rounded(0x22271A0C, 0x664B3B24, 10)
        controls.addView(safety, marginParams(-1, -2, top = 12))
        commandStatus = label("Status sakelar belum dikonfirmasi oleh BMS.", 11f, mutedColor, false)
        controls.addView(commandStatus, marginParams(-1, -2, top = 8))
        page.addView(controls, marginParams(-1, -2, top = 10))

        page.addView(sectionHeader("Diagnostik GATT"), marginParams(-1, -2, top = 22))
        val diagnostic = card()
        diagnostic.addView(label("Daftar layanan dan karakteristik BLE yang ditemukan. Tidak ada uji opcode otomatis atau rangkaian fallback; kontrol hanya mengirim payload langsung yang ditampilkan pada konfirmasi.", 12f, mutedColor, false))
        val scanGattButton = actionButton("Pindai GATT", filled = false).apply { setOnClickListener { scanGattServices() } }
        diagnostic.addView(scanGattButton, marginParams(-1, dp(44), top = 12))
        page.addView(diagnostic, marginParams(-1, -2, top = 10))

        page.addView(sectionHeader("Proteksi & alarm"), marginParams(-1, -2, top = 22))
        val alarms = card()
        alarmSummary = label("Hubungkan baterai untuk melihat alarm.", 13f, mutedColor, false)
        alarms.addView(alarmSummary)
        alarmList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        alarms.addView(alarmList, marginParams(-1, -2, top = 8))
        page.addView(alarms, marginParams(-1, -2, top = 10))

        page.addView(sectionHeader("Log perintah"), marginParams(-1, -2, top = 22))
        val logsCard = card()
        logSummary = label("Belum ada aktivitas.", 12f, mutedColor, false)
        logsCard.addView(logSummary)
        logList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logsCard.addView(logList, marginParams(-1, -2, top = 8))
        page.addView(logsCard, marginParams(-1, -2, top = 10))

        page.addView(label("BLE • Protocol 0x8900 • Pembacaan berkala setiap 1,5 detik", 10f, mutedColor, false).apply { gravity = Gravity.CENTER }, marginParams(-1, -2, top = 20))
    }

    private fun statTile(key: String, title: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = rounded(cardColor, borderColor, 16)
        }
        box.addView(label(title, 11f, mutedColor, false))
        val value = label("—", 19f, textColor, true).apply { typeface = Typeface.MONOSPACE }
        statValues[key] = value
        box.addView(value, marginParams(-1, -2, top = 7))
        return box
    }

    private fun switchRow(title: String, description: String, key: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, dp(3)) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(label(title, 14f, textColor, true))
        column.addView(label(description, 11f, mutedColor, false), marginParams(-1, -2, top = 3))
        val stateLabel = label("Belum dikonfirmasi oleh BMS", 10f, mutedColor, false).apply { tag = "switch-state-$key" }
        switchStateLabels[key] = stateLabel
        column.addView(stateLabel, marginParams(-1, -2, top = 3))
        row.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = Switch(this).apply {
            isEnabled = false
            isChecked = false
            contentDescription = title
        }
        switchViews[key] = toggle
        row.addView(toggle, marginParams(-2, -2, start = 8))
        switchDescriptions[key] = description
        toggle.setOnCheckedChangeListener { button, checked ->
            if (uiSwitchGuard) return@setOnCheckedChangeListener
            uiSwitchGuard = true
            button.isChecked = commandStates[key] == true
            uiSwitchGuard = false
            if (connection != Connection.CONNECTED) {
                toast("Hubungkan baterai terlebih dahulu")
                return@setOnCheckedChangeListener
            }
            val bytes = when (key) {
                "charge" -> BmsProtocol.chargeMos(checked)
                "discharge" -> BmsProtocol.dischargeMos(checked)
                else -> BmsProtocol.buzzer(checked)
            }
            val dialogTitle = when (key) {
                "charge" -> if (checked) "Izinkan pengisian?" else "Hentikan pengisian?"
                "discharge" -> if (checked) "Izinkan pengosongan?" else "Putuskan MOS pengosongan?"
                else -> if (checked) "Nyalakan buzzer?" else "Matikan buzzer?"
            }
            val warning = when (key) {
                "charge" -> "Perintah akan mengubah MOS pengisian. Keadaan aktual tidak dibaca balik oleh BMS."
                "discharge" -> if (checked) "Baterai akan diizinkan memberi daya ke beban." else "Ini dapat memutus daya ke semua beban pada keluaran baterai, termasuk motor, lampu, dan GPS."
                else -> "Perintah buzzer akan dikirim; efek fisik tidak dikonfirmasi melalui hasil tulis BLE."
            }
            confirmPowerAction(dialogTitle, warning, bytes) { sendMos(key, checked) }
        }
        return row
    }

    private fun updateSwitches() {
        uiSwitchGuard = true
        switchViews.forEach { (key, view) ->
            view.isEnabled = connection == Connection.CONNECTED
            view.isChecked = commandStates[key] == true
            switchStateLabels[key]?.text = when (commandStates[key]) {
                true -> "Perintah ON terakhir · belum dibaca balik"
                false -> "Perintah OFF terakhir · belum dibaca balik"
                null -> "Belum dikonfirmasi oleh BMS"
            }
        }
        uiSwitchGuard = false
    }

    @SuppressLint("MissingPermission")
    private fun onConnectPressed() {
        if (connection == Connection.CONNECTED) return
        val adapter = bluetoothAdapter
        if (adapter == null) {
            setError("Perangkat ini tidak memiliki Bluetooth.")
            return
        }
        if (!hasBlePermissions()) {
            requestBlePermissions()
            return
        }
        if (!adapter.isEnabled) {
            try { startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT) }
            catch (e: Exception) { setError("Tidak dapat mengaktifkan Bluetooth: ${e.message}") }
            return
        }
        beginScan()
    }

    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= 23) {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun requestBlePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 23) requestPermissions(permissions, REQUEST_BLE_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLE_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) onConnectPressed()
            else setError("Izin Bluetooth diperlukan untuk mencari dan menghubungkan baterai.")
        }
    }

    @Deprecated("Android Bluetooth enable prompt result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) beginScan() else setError("Bluetooth belum diaktifkan.")
        }
    }

    private fun beginScan() {
        val adapter = bluetoothAdapter ?: return
        if (!hasBlePermissions()) { requestBlePermissions(); return }
        if (pickerDialog?.isShowing != true) showDevicePicker()
        stopScan()
        scanDevices.clear()
        scanNames.clear()
        scanRssi.clear()
        selectedDeviceAddress = null
        setError(null)
        connection = Connection.SCANNING
        renderConnection()
        pickerStatus?.text = "Memindai perangkat BMS di sekitar…"
        pickerProgress?.visibility = View.VISIBLE
        renderPickerDevices()
        try {
            scanner = adapter.bluetoothLeScanner
            val bleScanner = scanner ?: throw IllegalStateException("Pemindai BLE tidak tersedia")
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) = collectScanResult(result)
                override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { collectScanResult(it) } }
                override fun onScanFailed(errorCode: Int) {
                    runOnUiThread {
                        stopScan()
                        connection = Connection.IDLE
                        renderConnection()
                        pickerProgress?.visibility = View.GONE
                        pickerStatus?.text = "Pemindaian gagal (kode $errorCode). Coba lagi."
                        setError("Pemindaian BLE gagal (kode $errorCode).")
                    }
                }
            }
            scanCallback = cb
            bleScanner.startScan(cb)
            addLog("Memindai perangkat BTD / layanan BMS selama 8 detik")
            val stop = Runnable {
                stopScan()
                if (connection == Connection.SCANNING) {
                    connection = Connection.IDLE
                    renderConnection()
                    pickerProgress?.visibility = View.GONE
                    pickerStatus?.text = if (scanDevices.isEmpty()) "Tidak ada perangkat cocok. Pastikan baterai menyala, lalu pindai lagi." else "${scanDevices.size} perangkat cocok ditemukan. Pilih baterai untuk melanjutkan."
                    renderPickerDevices()
                }
            }
            scanRunnable = stop
            handler.postDelayed(stop, 8_000)
        } catch (e: SecurityException) {
            stopScan()
            connection = Connection.IDLE
            setError("Izin Bluetooth tidak tersedia: ${e.message}")
            pickerProgress?.visibility = View.GONE
            pickerStatus?.text = "Izin Bluetooth diperlukan untuk memindai."
        } catch (e: Exception) {
            stopScan()
            connection = Connection.IDLE
            setError("Tidak dapat memindai BLE: ${e.message}")
            pickerProgress?.visibility = View.GONE
            pickerStatus?.text = "Pemindaian tidak dapat dimulai. Coba lagi."
        }
    }

    private fun collectScanResult(result: ScanResult) {
        try {
            val recordName = result.scanRecord?.deviceName
            val name = recordName ?: result.device.name ?: "BMS BLE"
            val serviceAdvertised = result.scanRecord?.serviceUuids?.any {
                it.uuid.toString().equals(BmsProtocol.SERVICE_UUID, true)
            } == true
            if (!name.startsWith("BTD", true) && !serviceAdvertised) return
            val address = result.device.address
            runOnUiThread {
                scanDevices[address] = result.device
                scanNames[address] = name
                scanRssi[address] = result.rssi
                renderPickerDevices()
            }
        } catch (_: SecurityException) { }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scanRunnable?.let { handler.removeCallbacks(it) }
        scanRunnable = null
        val cb = scanCallback
        if (cb != null) {
            try { scanner?.stopScan(cb) } catch (_: Exception) { }
        }
        scanCallback = null
    }

    private fun showDevicePicker() {
        if (pickerDialog?.isShowing == true) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
            background = rounded(cardColor, borderColor, 22)
        }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(label("Pilih baterai", 22f, textColor, true))
        titleBox.addView(label("Perangkat ditampilkan dari hasil scan BLE aktual.", 11f, mutedColor, false), marginParams(-1, -2, top = 4))
        titleRow.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        titleRow.addView(actionButton("Tutup", filled = false).apply {
            setOnClickListener { pickerDialog?.dismiss() }
        })
        root.addView(titleRow)
        pickerStatus = label("Menyiapkan pemindaian…", 12f, mutedColor, false)
        root.addView(pickerStatus, marginParams(-1, -2, top = 12))
        pickerProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(pickerProgress, marginParams(-1, dp(4), top = 8))
        val listScroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        pickerResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listScroll.addView(pickerResults, ViewGroup.LayoutParams(-1, -2))
        root.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12); bottomMargin = dp(10) })
        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        footer.addView(actionButton("Pindai lagi", filled = false).apply { setOnClickListener { beginScan() } }, LinearLayout.LayoutParams(0, dp(46), 1f))
        pickerConnect = actionButton("Hubungkan", filled = true).apply {
            isEnabled = false
            setOnClickListener {
                val device = selectedDeviceAddress?.let { scanDevices[it] }
                if (device == null) {
                    toast("Pilih perangkat dari hasil scan terlebih dahulu")
                } else {
                    pickerDialog?.dismiss()
                    connectToDevice(device)
                }
            }
        }
        footer.addView(pickerConnect, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(8) })
        root.addView(footer)
        val dialog = AlertDialog.Builder(this).setView(root).setCancelable(true).create()
        pickerDialog = dialog
        dialog.setOnDismissListener {
            if (connection == Connection.SCANNING) {
                stopScan()
                connection = Connection.IDLE
                renderConnection()
            }
            pickerDialog = null
            pickerResults = null
            pickerStatus = null
            pickerProgress = null
            pickerConnect = null
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        renderPickerDevices()
    }

    private fun renderPickerDevices() {
        val list = pickerResults ?: return
        list.removeAllViews()
        val ordered = scanDevices.keys.sortedByDescending { scanRssi[it] ?: -127 }
        if (ordered.isEmpty()) {
            list.addView(label("Perangkat BTD atau layanan BMS 0x8900 yang cocok akan muncul di sini.", 12f, mutedColor, false).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(32), dp(12), dp(32))
            })
        }
        ordered.forEach { address ->
            val selected = selectedDeviceAddress == address
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                background = rounded(if (selected) 0xFF1D3026.toInt() else cardSoft, if (selected) accent else borderColor, 14)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedDeviceAddress = address
                    renderPickerDevices()
                }
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(label(scanNames[address] ?: "BMS BLE", 14f, textColor, true))
            info.addView(label(address, 10f, mutedColor, false).apply { typeface = Typeface.MONOSPACE }, marginParams(-1, -2, top = 4))
            info.addView(label("BLE · RSSI ${scanRssi[address] ?: "—"} dBm", 10f, accent, false), marginParams(-1, -2, top = 4))
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label(if (selected) "●" else "○", 19f, if (selected) accent else mutedColor, true))
            list.addView(row, marginParams(-1, -2, top = 6))
        }
        pickerConnect?.isEnabled = selectedDeviceAddress != null && scanDevices.containsKey(selectedDeviceAddress)
        pickerStatus?.let { status ->
            if (connection == Connection.SCANNING) status.text = "Memindai… ${scanDevices.size} perangkat cocok ditemukan"
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasBlePermissions()) { requestBlePermissions(); return }
        try {
            connection = Connection.CONNECTING
            deviceName = try { device.name ?: "Baterai" } catch (_: SecurityException) { "Baterai" }
            setError(null)
            renderConnection()
            val newGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (newGatt == null) {
                connection = Connection.IDLE
                setError("Tidak dapat memulai koneksi BLE.")
            } else gatt = newGatt
        } catch (e: SecurityException) {
            connection = Connection.IDLE
            setError("Izin koneksi Bluetooth tidak tersedia: ${e.message}")
        } catch (e: Exception) {
            connection = Connection.IDLE
            setError("Koneksi gagal: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(callbackGatt: BluetoothGatt, status: Int, newState: Int) {
            runOnUiThread {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    gatt = callbackGatt
                    connection = Connection.DISCOVERING
                    addLog("BLE tersambung; menemukan layanan…")
                    renderConnection()
                    try { callbackGatt.discoverServices() } catch (e: Exception) { setError("Pencarian layanan gagal: ${e.message}") }
                } else {
                    if (connection != Connection.IDLE) addLog("Koneksi BLE terputus (status $status)")
                    closeGattQueue()
                    try { callbackGatt.close() } catch (_: Exception) { }
                    if (gatt === callbackGatt) gatt = null
                    connection = Connection.IDLE
                    deviceName = ""
                    reading = false
                    snapshot = null
                    voltageHistory.clear()
                    commandStates.keys.forEach { commandStates[it] = null }
                    updateData(null)
                    renderConnection()
                    if (status != BluetoothGatt.GATT_SUCCESS) setError("Koneksi ke baterai terputus (status $status).")
                }
            }
        }

        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
            runOnUiThread {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    connection = Connection.IDLE
                    setError("Tidak dapat membaca layanan GATT (status $status).")
                    renderConnection()
                    return@runOnUiThread
                }
                val service = callbackGatt.getService(UUID.fromString(BmsProtocol.SERVICE_UUID))
                if (service == null) {
                    connection = Connection.IDLE
                    setError("Layanan BMS 0x8900 tidak ditemukan pada perangkat ini.")
                    try { callbackGatt.disconnect() } catch (_: Exception) { }
                    renderConnection()
                    return@runOnUiThread
                }
                connection = Connection.CONNECTED
                addLog("Terhubung ke layanan 0x8900")
                renderConnection()
                enableAuthAndStart(callbackGatt)
            }
        }

        @Deprecated("Legacy callback for Android versions before 13")
        override fun onCharacteristicRead(callbackGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val value = characteristic.value?.clone()
            runOnUiThread { handleCharacteristicRead(characteristic.uuid.toString(), status, value) }
        }

        override fun onCharacteristicRead(callbackGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            val copy = value.clone()
            runOnUiThread { handleCharacteristicRead(characteristic.uuid.toString(), status, copy) }
        }

        override fun onCharacteristicWrite(callbackGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            runOnUiThread { completeOperation(characteristic.uuid.toString(), status == BluetoothGatt.GATT_SUCCESS, null) }
        }

        override fun onDescriptorWrite(callbackGatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            runOnUiThread { completeOperation(descriptor.characteristic.uuid.toString(), status == BluetoothGatt.GATT_SUCCESS, null) }
        }

        override fun onCharacteristicChanged(callbackGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value?.clone()
            runOnUiThread { handleNotification(characteristic.uuid.toString(), value) }
        }

        override fun onCharacteristicChanged(callbackGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.clone()
            runOnUiThread { handleNotification(characteristic.uuid.toString(), copy) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableAuthAndStart(callbackGatt: BluetoothGatt) {
        val auth = callbackGatt.getService(UUID.fromString(BmsProtocol.SERVICE_UUID))
            ?.getCharacteristic(UUID.fromString(BmsProtocol.AUTH_UUID))
        if (auth == null) {
            addLog("Auth 0x8901 tidak tersedia; lanjut tanpa handshake")
            beginReadCycle()
            return
        }
        try { callbackGatt.setCharacteristicNotification(auth, true) } catch (_: Exception) { }
        val cccd = auth.getDescriptor(UUID.fromString(CCCD_UUID))
        if (cccd != null) {
            enqueue(GattOperation(OpKind.DESCRIPTOR, BmsProtocol.AUTH_UUID, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.clone()) {
                    success, _ ->
                if (!success) addLog("Notifikasi auth tidak aktif; lanjut")
                enqueueHandshake { beginReadCycle() }
            })
        } else enqueueHandshake { beginReadCycle() }
    }

    private fun enqueueHandshake(after: () -> Unit) {
        enqueue(GattOperation(OpKind.WRITE, BmsProtocol.AUTH_UUID, BmsProtocol.handshake) { success, _ ->
            addLog(if (success) "Handshake dikirim ke 0x8901" else "Handshake gagal; lanjutkan pembacaan")
            after()
        })
    }

    private fun handleNotification(uuid: String, value: ByteArray?) {
        if (uuid.equals(BmsProtocol.AUTH_UUID, true)) {
            addLog("Handshake balasan: ${value?.firstOrNull()?.toInt()?.and(0xFF) ?: "—"}")
        }
    }

    private fun beginReadCycle() {
        if (connection != Connection.CONNECTED || reading) return
        if (gatt?.getService(UUID.fromString(BmsProtocol.SERVICE_UUID)) == null) return
        reading = true
        val values = mutableMapOf<String, ByteArray?>()
        val items = listOf(
            BmsProtocol.STATUS_UUID, BmsProtocol.CELLS_A_UUID, BmsProtocol.CELLS_B_UUID,
            BmsProtocol.CELLS_C_UUID, BmsProtocol.FAULT_A_UUID, BmsProtocol.FAULT_B_UUID
        )
        items.forEach { uuid ->
            enqueue(GattOperation(OpKind.READ, uuid) { success, bytes ->
                values[uuid] = if (success) bytes else null
            })
        }
        enqueue(GattOperation(OpKind.LOCAL, callback = { _, _ ->
            val next = BmsProtocol.snapshot(
                values[BmsProtocol.STATUS_UUID], values[BmsProtocol.CELLS_A_UUID], values[BmsProtocol.CELLS_B_UUID],
                values[BmsProtocol.CELLS_C_UUID], values[BmsProtocol.FAULT_A_UUID], values[BmsProtocol.FAULT_B_UUID]
            )
            snapshot = next
            updateData(next)
            if (next.status == null) addLog("Gagal membaca status (0x8902)")
            reading = false
            handler.postDelayed({ beginReadCycle() }, 1_500)
        }))
        pumpQueue()
    }

    private fun enqueue(operation: GattOperation) {
        gattQueue.addLast(operation)
        pumpQueue()
    }

    @Suppress("DEPRECATION")
    private fun pumpQueue() {
        if (activeOperation != null) return
        val next = gattQueue.pollFirst() ?: return
        if (next.kind == OpKind.LOCAL) {
            activeOperation = next
            completeOperation("", true, null)
            return
        }
        val activeGatt = gatt
        val service = activeGatt?.getService(UUID.fromString(BmsProtocol.SERVICE_UUID))
        if (activeGatt == null || service == null || connection != Connection.CONNECTED) {
            next.callback(false, null)
            pumpQueue()
            return
        }
        val characteristic = try { service.getCharacteristic(UUID.fromString(next.uuid)) } catch (_: Exception) { null }
        if (characteristic == null) {
            addLog("Karakteristik ${next.uuid.takeLast(4)} tidak ditemukan")
            next.callback(false, null)
            pumpQueue()
            return
        }
        activeOperation = next
        try {
            val started = when (next.kind) {
                OpKind.READ -> activeGatt.readCharacteristic(characteristic)
                OpKind.WRITE -> writeCharacteristic(activeGatt, characteristic, next.bytes ?: byteArrayOf())
                OpKind.DESCRIPTOR -> {
                    val descriptor = characteristic.getDescriptor(UUID.fromString(CCCD_UUID))
                    if (descriptor == null) false else writeDescriptor(activeGatt, descriptor, next.bytes ?: byteArrayOf())
                }
                OpKind.LOCAL -> true
            }
            if (!started) {
                completeOperation(next.uuid, false, null)
            } else if (next.kind != OpKind.LOCAL) {
                val timeout = Runnable {
                    if (activeOperation === next) {
                        addLog("Timeout BLE ${next.kind.name.lowercase(Locale.US)} ${next.uuid.takeLast(4)}")
                        completeOperation(next.uuid, false, null)
                    }
                }
                operationTimeout = timeout
                handler.postDelayed(timeout, 5_000)
            }
        } catch (e: SecurityException) {
            addLog("Izin BLE gagal: ${e.message}")
            completeOperation(next.uuid, false, null)
        } catch (e: Exception) {
            addLog("Operasi BLE gagal: ${e.message}")
            completeOperation(next.uuid, false, null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(activeGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        val properties = characteristic.properties
        val supportsResponse = properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val supportsNoResponse = properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        if (!supportsResponse && !supportsNoResponse) return false
        val writeType = if (supportsResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return if (Build.VERSION.SDK_INT >= 33) {
            activeGatt.writeCharacteristic(characteristic, bytes, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = writeType
            characteristic.value = bytes
            activeGatt.writeCharacteristic(characteristic)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptor(activeGatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, bytes: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            activeGatt.writeDescriptor(descriptor, bytes) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = bytes
            activeGatt.writeDescriptor(descriptor)
        }
    }

    private fun handleCharacteristicRead(uuid: String, status: Int, bytes: ByteArray?) {
        completeOperation(uuid, status == BluetoothGatt.GATT_SUCCESS, bytes)
    }

    private fun completeOperation(uuid: String, success: Boolean, bytes: ByteArray?) {
        val operation = activeOperation ?: return
        if (operation.kind != OpKind.LOCAL && uuid.isNotEmpty() && !operation.uuid.equals(uuid, true)) return
        operationTimeout?.let { handler.removeCallbacks(it) }
        operationTimeout = null
        activeOperation = null
        if (!success && operation.kind != OpKind.READ) addLog("BLE write gagal: ${operation.uuid.takeLast(4)}")
        operation.callback(success, bytes)
        pumpQueue()
    }

    private fun closeGattQueue() {
        operationTimeout?.let { handler.removeCallbacks(it) }
        operationTimeout = null
        activeOperation = null
        gattQueue.clear()
        reading = false
    }

    private fun sendMos(key: String, enabled: Boolean) {
        val label = when (key) { "charge" -> "MOS pengisian"; "discharge" -> "MOS pengosongan"; else -> "buzzer" }
        val bytes = when (key) { "charge" -> BmsProtocol.chargeMos(enabled); "discharge" -> BmsProtocol.dischargeMos(enabled); else -> BmsProtocol.buzzer(enabled) }
        sendDirectCommand(label, bytes) { success ->
            if (success) {
                commandStates[key] = enabled
                updateSwitches()
                commandStatus.text = "Perintah $label ${if (enabled) "ON" else "OFF"} diterima oleh BLE; status MOS tidak dibaca balik."
            }
        }
    }

    private fun sendDirectCommand(label: String, bytes: ByteArray, after: ((Boolean) -> Unit)? = null) {
        if (connection != Connection.CONNECTED) { toast("Hubungkan baterai terlebih dahulu"); return }
        addLog("Kirim $label: ${BmsProtocol.toHex(bytes)}")
        enqueue(GattOperation(OpKind.WRITE, BmsProtocol.COMMAND_UUID, bytes) { success, _ ->
            if (success) {
                addLog("BLE menerima $label (status fisik belum diverifikasi)")
                commandStatus.text = "$label diterima BLE; keadaan fisik baterai belum dibaca balik."
            } else {
                commandStatus.text = "Penulisan BLE $label gagal; keadaan fisik baterai tidak diketahui."
            }
            after?.invoke(success)
        })
    }

    private fun confirmPowerAction(title: String, message: String, bytes: ByteArray? = null, action: () -> Unit) {
        if (connection != Connection.CONNECTED) { toast("Hubungkan baterai terlebih dahulu"); return }
        val exactMessage = if (bytes == null) message else "$message\n\nPayload BLE: ${BmsProtocol.toHex(bytes)}\nHasil tulis BLE tidak mengonfirmasi keadaan fisik baterai."
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(exactMessage)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Lanjutkan") { _, _ -> action() }
            .show()
    }

    private fun scanGattServices() {
        if (connection != Connection.CONNECTED) { toast("Hubungkan baterai terlebih dahulu"); return }
        val services = gatt?.services.orEmpty()
        if (services.isEmpty()) { setError("Belum ada layanan GATT yang dapat dipindai."); return }
        val lines = mutableListOf<String>()
        services.forEach { service ->
            lines += "Service ${service.uuid}"
            service.characteristics.forEach { characteristic ->
                val p = characteristic.properties
                val props = buildList {
                    if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
                    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
                    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("writeNR")
                    if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
                    if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
                }.joinToString(", ").ifEmpty { "?" }
                lines += "  ${characteristic.uuid} [$props]"
            }
        }
        lines.forEach { addLog(it) }
        AlertDialog.Builder(this).setTitle("Layanan GATT").setMessage(lines.joinToString("\n"))
            .setPositiveButton("Tutup", null).show()
    }

    @SuppressLint("MissingPermission")
    private fun disconnectFromBms() {
        stopScan()
        pickerDialog?.dismiss()
        closeGattQueue()
        try { gatt?.disconnect() } catch (_: Exception) { }
        try { gatt?.close() } catch (_: Exception) { }
        gatt = null
        connection = Connection.IDLE
        deviceName = ""
        snapshot = null
        voltageHistory.clear()
        commandStates.keys.forEach { commandStates[it] = null }
        commandStatus.text = "Status sakelar belum dikonfirmasi oleh BMS."
        updateData(null)
        renderConnection()
        addLog("Koneksi diputus")
    }

    private fun setError(message: String?) {
        lastError = message
        errorPanel.text = message.orEmpty()
        errorPanel.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun renderConnection() {
        when (connection) {
            Connection.CONNECTED -> {
                statusView.text = "terhubung · $deviceName"
                statusView.setTextColor(accent)
                connectionHint.text = "BLE aktif · pembacaan otomatis setiap 1,5 detik"
                connectButton.text = "Terhubung"
                connectButton.isEnabled = false
                refreshButton.isEnabled = true
                disconnectButton.isEnabled = true
            }
            Connection.SCANNING -> {
                statusView.text = "mencari perangkat…"
                statusView.setTextColor(warn)
                connectionHint.text = "Mencari baterai BTD / layanan 0x8900…"
                connectButton.text = "Mencari…"
                connectButton.isEnabled = false
                refreshButton.isEnabled = false
                disconnectButton.isEnabled = true
            }
            Connection.CONNECTING, Connection.DISCOVERING -> {
                statusView.text = if (connection == Connection.CONNECTING) "menyambung…" else "menemukan layanan…"
                statusView.setTextColor(warn)
                connectionHint.text = "Sedang membuat koneksi BLE."
                connectButton.text = "Menyambung…"
                connectButton.isEnabled = false
                refreshButton.isEnabled = false
                disconnectButton.isEnabled = true
            }
            Connection.IDLE -> {
                statusView.text = "tidak terhubung"
                statusView.setTextColor(mutedColor)
                connectionHint.text = "Pilih baterai BLE yang kompatibel untuk mulai membaca data."
                connectButton.text = "Hubungkan"
                connectButton.isEnabled = true
                refreshButton.isEnabled = false
                disconnectButton.isEnabled = false
            }
        }
        updateSwitches()
    }

    private fun updateData(data: BmsProtocol.Snapshot?) {
        val status = data?.status
        if (status == null) {
            batteryBar.percent = 0
            socValue.text = "—%"
            heroVoltage.text = "0.00 V"
            heroCurrent.text = "0.00 A"
            heroState.text = "—"
            statValues["soh"]?.text = "—"
            statValues["cells"]?.text = "—"
            statValues["remaining"]?.text = "— Ah"
            statValues["full"]?.text = "— Ah"
            statValues["minTemp"]?.text = "— °C"
            statValues["maxTemp"]?.text = "— °C"
            statValues["speed"]?.text = "—"
            statValues["bmsStatus"]?.text = "—"
            updateCellSummary(emptyList())
            cellList.removeAllViews()
            cellList.addView(label("Belum ada data sel.", 13f, mutedColor, false))
            temperatureRow.removeAllViews()
            temperatureRow.addView(label("Belum ada data suhu.", 13f, mutedColor, false))
            alarmSummary.text = if (connection == Connection.CONNECTED) "Tidak ada alarm aktif." else "Hubungkan baterai untuk melihat alarm."
            alarmList.removeAllViews()
            chartView.updateReadings(voltageHistory.toList())
            return
        }
        val soc = status.soc.coerceIn(0, 100)
        batteryBar.percent = soc
        socValue.text = "$soc%"
        heroVoltage.text = String.format(Locale.US, "%.2f V", status.voltage)
        heroCurrent.text = String.format(Locale.US, "%.2f A", status.current)
        heroState.text = status.state
        statValues["soh"]?.text = "${status.soh}%"
        statValues["cells"]?.text = "${data.cellsVolts.size}"
        statValues["remaining"]?.text = "— Ah"
        statValues["full"]?.text = "— Ah"
        statValues["minTemp"]?.text = "${status.minCellTemp} °C"
        statValues["maxTemp"]?.text = "${status.maxCellTemp} °C"
        statValues["speed"]?.text = "${status.speed}"
        statValues["bmsStatus"]?.text = String.format(Locale.US, "0x%02X", status.bmsStatus)
        voltageHistory.addLast(ChartReading(System.currentTimeMillis(), status.voltage))
        while (voltageHistory.size > 2_000) voltageHistory.removeFirst()
        chartView.updateReadings(voltageHistory.toList())
        updateCells(data.cellsVolts)
        updateTemperatures(status.temperature, status.minCellTemp, status.maxCellTemp)
        updateAlarms(data.alarms)
    }

    private fun updateCells(cells: List<Double>) {
        cellList.removeAllViews()
        if (cells.isEmpty()) {
            updateCellSummary(emptyList())
            cellList.addView(label("Belum ada data sel.", 13f, mutedColor, false))
            return
        }
        val average = cells.average()
        val highest = cells.indices.maxByOrNull { cells[it] } ?: -1
        val lowest = cells.indices.minByOrNull { cells[it] } ?: -1
        updateCellSummary(cells)
        cells.forEachIndexed { index, value ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val marker = when (index) { highest -> "▲"; lowest -> "▼"; else -> "" }
            val imbalance = kotlin.math.abs(value - average) > 0.100
            val name = label("${marker} Sel ${index + 1}${if (imbalance) " !" else ""}", 12f, if (imbalance) warn else mutedColor, false)
            name.typeface = Typeface.MONOSPACE
            row.addView(name, LinearLayout.LayoutParams(dp(82), -2))
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = (((value - 2.5) / 1.8) * max).toInt().coerceIn(0, max)
                progressTintList = android.content.res.ColorStateList.valueOf(if (imbalance) warn else accent)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(borderColor)
            }
            row.addView(bar, LinearLayout.LayoutParams(0, dp(6), 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
            val volts = label(String.format(Locale.US, "%.3f V", value), 12f, textColor, true).apply { typeface = Typeface.MONOSPACE; gravity = Gravity.END }
            row.addView(volts, LinearLayout.LayoutParams(dp(78), -2))
            cellList.addView(row, marginParams(-1, dp(28), top = if (index == 0) 1 else 5))
            if (index < cells.lastIndex) cellList.addView(divider(), marginParams(-1, 1, top = 3, bottom = 2))
        }
    }

    private fun updateCellSummary(cells: List<Double>) {
        cellSummary.removeAllViews()
        if (cells.isEmpty()) {
            cellSummary.addView(label("Tegangan sel tertinggi/terendah dan selisih akan tampil setelah pembacaan.", 10f, mutedColor, false))
            return
        }
        val highIndex = cells.indices.maxByOrNull { cells[it] } ?: return
        val lowIndex = cells.indices.minByOrNull { cells[it] } ?: return
        val delta = cells[highIndex] - cells[lowIndex]
        val summaries = listOf(
            Triple("Terendah", "Sel ${lowIndex + 1} · ${String.format(Locale.US, "%.3f V", cells[lowIndex])}", 0xFF71E4D2.toInt()),
            Triple("Tertinggi", "Sel ${highIndex + 1} · ${String.format(Locale.US, "%.3f V", cells[highIndex])}", 0xFFC6FF94.toInt()),
            Triple("Voltage (V)", String.format(Locale.US, "%.3f V", delta), textColor)
        )
        summaries.forEachIndexed { index, item ->
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = rounded(cardSoft, borderColor, 11)
                addView(label(item.first, 9f, mutedColor, true))
                addView(label(item.second, 9f, item.third, true).apply { typeface = Typeface.MONOSPACE }, marginParams(-1, -2, top = 5))
            }
            cellSummary.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(6) })
        }
    }

    private fun updateTemperatures(pack: Int, min: Int, max: Int) {
        temperatureRow.removeAllViews()
        listOf("Pack" to pack, "Min" to min, "Maks" to max).forEachIndexed { i, pair ->
            val chip = label("${pair.first}  ${pair.second}°C", 12f, textColor, true).apply {
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(9), dp(10), dp(9))
                background = rounded(cardSoft, borderColor, 10)
            }
            temperatureRow.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(7) })
        }
    }

    private fun updateAlarms(alarms: List<BmsProtocol.Alarm>) {
        alarmList.removeAllViews()
        if (alarms.isEmpty()) {
            alarmSummary.text = "Tidak ada alarm aktif."
            alarmSummary.setTextColor(mutedColor)
            return
        }
        alarmSummary.text = "${alarms.size} alarm aktif"
        alarmSummary.setTextColor(danger)
        alarms.forEach { alarm ->
            val line = label("⚠  ${alarm.label}", 13f, textColor, false).apply {
                setPadding(dp(10), dp(9), dp(10), dp(9))
                background = rounded(0x332D171B, 0x664B2830, 10)
            }
            alarmList.addView(line, marginParams(-1, -2, top = 6))
        }
    }

    private fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logs.addFirst("$time  $message")
        while (logs.size > 30) logs.removeLast()
        refreshLogs()
    }

    private fun refreshLogs() {
        if (!::logList.isInitialized) return
        logList.removeAllViews()
        if (logs.isEmpty()) {
            logSummary.text = "Belum ada aktivitas."
            return
        }
        logSummary.text = "${logs.size} aktivitas terbaru"
        logs.forEach { line ->
            logList.addView(label(line, 10f, mutedColor, false).apply { typeface = Typeface.MONOSPACE }, marginParams(-1, -2, top = 4))
        }
    }

    private fun sectionHeader(text: String) = label(text, 16f, textColor, true)

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(15), dp(15), dp(15))
        background = rounded(cardColor, borderColor, 18)
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = true
    }

    private fun actionButton(text: String, filled: Boolean, destructive: Boolean = false): Button = Button(this).apply {
        this.text = text
        textSize = 12f
        isAllCaps = false
        setTextColor(if (filled) bg else if (destructive) danger else textColor)
        background = rounded(if (filled) accent else cardSoft, if (destructive) 0xFF65353E.toInt() else borderColor, 12)
        minHeight = dp(42)
        minimumHeight = dp(42)
        setPadding(dp(13), 0, dp(13), 0)
        stateListAnimator = null
    }

    private fun divider(): View = View(this).apply { setBackgroundColor(borderColor) }

    private fun rounded(color: Int, stroke: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun marginParams(width: Int, height: Int, start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(width, height).apply {
            leftMargin = dp(start); topMargin = dp(top); rightMargin = dp(end); bottomMargin = dp(bottom)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private class WaveBatteryBar(context: android.content.Context, private val accent: Int) : View(context) {
        var percent: Int = 0
            set(value) {
                val target = value.coerceIn(0, 100)
                if (field == target) return
                field = target
                fillAnimator?.cancel()
                fillAnimator = ValueAnimator.ofInt(displayPercent, target).apply {
                    duration = 650
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { displayPercent = it.animatedValue as Int; invalidate() }
                    start()
                }
            }
        private var displayPercent = 0
        private var fillAnimator: ValueAnimator? = null
        private val density = context.resources.displayMetrics.density
        private var phase = 0f
        private val waveAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 2_400
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
        }
        private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density; color = 0xFF6D8270.toInt() }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF26312D.toInt() }
        private val liquidPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val waveLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f * density; color = 0xFFDFFFCB.toInt(); alpha = 190 }
        private val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55101613 }
        private val wavePath = Path()
        private val innerRect = RectF()
        private val innerPath = Path()

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (!waveAnimator.isStarted) waveAnimator.start()
        }

        override fun onDetachedFromWindow() {
            waveAnimator.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val shellLeft = 2f * density
            val shellTop = 5f * density
            val terminalWidth = 6f * density
            val shellRight = width - terminalWidth - 3f * density
            val shellBottom = height - 5f * density
            val shell = RectF(shellLeft, shellTop, shellRight, shellBottom)
            val inset = 5f * density
            innerRect.set(shell.left + inset, shell.top + inset, shell.right - inset, shell.bottom - inset)
            canvas.drawRoundRect(shell, 10f * density, 10f * density, shellPaint)
            innerPath.reset()
            innerPath.addRoundRect(innerRect, 5f * density, 5f * density, Path.Direction.CW)
            canvas.drawPath(innerPath, trackPaint)
            val fillRight = innerRect.left + innerRect.width() * displayPercent / 100f
            if (displayPercent > 0) {
                val save = canvas.save()
                canvas.clipPath(innerPath)
                canvas.clipRect(innerRect.left, innerRect.top, fillRight, innerRect.bottom)
                val gradient = LinearGradient(innerRect.left, 0f, innerRect.right, 0f, 0xFF55CDB3.toInt(), 0xFFA6F279.toInt(), Shader.TileMode.CLAMP)
                liquidPaint.shader = gradient
                val averageY = innerRect.bottom - innerRect.height() * 0.57f
                val amplitude = innerRect.height() * 0.13f
                wavePath.reset()
                wavePath.moveTo(innerRect.left, innerRect.bottom)
                wavePath.lineTo(innerRect.left, averageY)
                val steps = maxOf(18, (innerRect.width() / (3f * density)).toInt())
                for (step in 1..steps) {
                    val x = innerRect.left + innerRect.width() * step / steps
                    val y = averageY + kotlin.math.sin((x / (innerRect.width() * 0.34f)) - phase).toFloat() * amplitude
                    wavePath.lineTo(x, y)
                }
                wavePath.lineTo(innerRect.right, innerRect.bottom)
                wavePath.close()
                canvas.drawPath(wavePath, liquidPaint)
                wavePath.reset()
                for (step in 0..steps) {
                    val x = innerRect.left + innerRect.width() * step / steps
                    val y = averageY + kotlin.math.sin((x / (innerRect.width() * 0.34f)) - phase).toFloat() * amplitude
                    if (step == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
                }
                canvas.drawPath(wavePath, waveLinePaint)
                for (seam in 1..9) {
                    val x = innerRect.left + innerRect.width() * seam / 10f
                    canvas.drawLine(x, innerRect.top, x, innerRect.bottom, seamPaint)
                }
                canvas.restoreToCount(save)
            }
            val terminal = RectF(shell.right + 1f * density, height / 2f - 8f * density, shell.right + terminalWidth, height / 2f + 8f * density)
            liquidPaint.shader = null
            liquidPaint.color = accent
            canvas.drawRoundRect(terminal, 3f * density, 3f * density, liquidPaint)
        }
    }

    private class VoltageChartView(
        context: android.content.Context,
        private val accent: Int,
        private val muted: Int,
        private val onSelection: (ChartReading?) -> Unit
    ) : View(context) {
        private val density = context.resources.displayMetrics.density
        private var readings: List<ChartReading> = emptyList()
        private var visible: List<ChartReading> = emptyList()
        private var rangeHours = 24
        private var selectedIndex = -1
        private var followLatest = true
        private var dragging = false
        private var animationProgress = 1f
        private var shapeFrom = FloatArray(0)
        private var shapeTo = FloatArray(0)
        private var rangeAnimator: ValueAnimator? = null
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF293638.toInt(); strokeWidth = 1f * density }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; strokeWidth = 2.2f * density; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; alpha = 32 }
        private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD8FFB2.toInt(); style = Paint.Style.FILL }
        private val markerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.STROKE; strokeWidth = 2f * density }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 9f * density; typeface = Typeface.MONOSPACE }
        private val path = Path()
        private val area = Path()

        init {
            isClickable = true
            contentDescription = "Voltage history. Drag across the chart to inspect session readings."
        }

        fun sampleCount(): Int = visible.size

        fun updateReadings(values: List<ChartReading>) {
            val oldSelectedTime = visible.getOrNull(selectedIndex)?.atMillis
            readings = values
            visible = filteredReadings()
            selectedIndex = if (followLatest) visible.lastIndex else oldSelectedTime?.let { oldTime ->
                visible.indices.minByOrNull { kotlin.math.abs(visible[it].atMillis - oldTime) } ?: visible.lastIndex
            } ?: visible.lastIndex
            shapeTo = normalizedShape(visible)
            shapeFrom = shapeTo.copyOf()
            animationProgress = 1f
            notifySelection()
            invalidate()
        }

        fun setRangeHours(hours: Int) {
            if (rangeHours == hours) return
            val oldShape = if (shapeTo.isEmpty()) normalizedShape(visible) else currentShape()
            rangeHours = hours
            visible = filteredReadings()
            selectedIndex = visible.lastIndex
            followLatest = true
            shapeFrom = oldShape
            shapeTo = normalizedShape(visible)
            rangeAnimator?.cancel()
            if (shapeFrom.isEmpty() || shapeTo.isEmpty()) {
                shapeFrom = shapeTo.copyOf()
                animationProgress = 1f
            } else {
                animationProgress = 0f
                rangeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 480
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { animationProgress = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
            notifySelection()
            invalidate()
        }

        private fun filteredReadings(): List<ChartReading> {
            val cutoff = System.currentTimeMillis() - rangeHours * 60L * 60L * 1_000L
            return readings.filter { it.atMillis >= cutoff }
        }

        private fun currentShape(): FloatArray {
            if (shapeFrom.isEmpty() || shapeTo.isEmpty()) return shapeTo
            return FloatArray(shapeTo.size) { index ->
                val start = shapeFrom.getOrElse(index) { shapeTo[index] }
                start + (shapeTo[index] - start) * animationProgress
            }
        }

        private fun normalizedShape(points: List<ChartReading>): FloatArray {
            val count = 48
            if (points.isEmpty()) return FloatArray(0)
            val minimum = points.minOf { it.volts }
            val maximum = points.maxOf { it.volts }
            val padding = maxOf((maximum - minimum) * 0.12, 0.02)
            val low = minimum - padding
            val span = (maximum - minimum) + padding * 2.0
            return FloatArray(count) { index ->
                val position = if (count == 1) 0.0 else index.toDouble() / (count - 1) * (points.size - 1)
                val left = position.toInt().coerceIn(0, points.lastIndex)
                val right = (left + 1).coerceAtMost(points.lastIndex)
                val fraction = position - left
                val value = points[left].volts + (points[right].volts - points[left].volts) * fraction
                ((value - low) / span).toFloat().coerceIn(0f, 1f)
            }
        }

        private fun notifySelection() {
            val selected = visible.getOrNull(selectedIndex)
            onSelection(selected)
            selected?.let { contentDescription = "${String.format(Locale.US, "%.2f", it.volts)} volts at ${SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date(it.atMillis))}. Drag to inspect other readings." }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val left = 8f * density
            val right = width - 8f * density
            val top = 8f * density
            val bottom = height - 22f * density
            if (right <= left || bottom <= top) return
            for (row in 0..2) {
                val y = top + (bottom - top) * row / 2f
                canvas.drawLine(left, y, right, y, gridPaint)
            }
            if (visible.isEmpty()) {
                textPaint.textSize = 10f * density
                canvas.drawText("Belum ada bacaan tegangan", left, (top + bottom) / 2f, textPaint)
                return
            }
            val interpolatedShape = currentShape()
            val shape = if (interpolatedShape.isEmpty()) normalizedShape(visible) else interpolatedShape
            path.reset()
            for (index in shape.indices) {
                val x = left + (right - left) * index / (shape.size - 1).coerceAtLeast(1)
                val y = bottom - (bottom - top) * shape[index]
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            area.set(path)
            area.lineTo(right, bottom)
            area.lineTo(left, bottom)
            area.close()
            canvas.drawPath(area, areaPaint)
            canvas.drawPath(path, linePaint)
            val chosen = visible.getOrNull(selectedIndex)
            if (chosen != null) {
                val x = if (visible.size <= 1) right else left + (right - left) * selectedIndex / (visible.size - 1)
                val minimum = visible.minOf { it.volts }
                val maximum = visible.maxOf { it.volts }
                val padding = maxOf((maximum - minimum) * 0.12, 0.02)
                val yNorm = ((chosen.volts - (minimum - padding)) / ((maximum - minimum) + padding * 2.0)).toFloat().coerceIn(0f, 1f)
                val y = bottom - (bottom - top) * yNorm
                canvas.drawLine(x, top, x, bottom, markerRingPaint)
                canvas.drawCircle(x, y, 4f * density, markerPaint)
                canvas.drawCircle(x, y, 4f * density, markerRingPaint)
            }
            textPaint.textSize = 8f * density
            val start = SimpleDateFormat("HH:mm", Locale.US).format(Date(visible.first().atMillis))
            val end = SimpleDateFormat("HH:mm", Locale.US).format(Date(visible.last().atMillis))
            canvas.drawText(start, left, height - 4f * density, textPaint)
            val endWidth = textPaint.measureText(end)
            canvas.drawText(end, right - endWidth, height - 4f * density, textPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (visible.isEmpty()) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    selectAt(event.x)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) selectAt(event.x)
                    return dragging
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) selectAt(event.x)
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return true
        }

        private fun selectAt(x: Float) {
            val left = 8f * density
            val right = width - 8f * density
            val fraction = ((x - left) / (right - left).coerceAtLeast(1f)).coerceIn(0f, 1f)
            selectedIndex = (fraction * (visible.size - 1)).toInt().coerceIn(0, visible.lastIndex)
            followLatest = selectedIndex == visible.lastIndex
            notifySelection()
            invalidate()
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    companion object {
        private const val REQUEST_BLE_PERMISSIONS = 5101
        private const val REQUEST_ENABLE_BT = 5102
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    }
}
