package app.bmsmonitor

import android.Manifest
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
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
    private val logs = ArrayDeque<String>()
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
    private lateinit var gauge: SocGauge
    private lateinit var heroVoltage: TextView
    private lateinit var heroCurrent: TextView
    private lateinit var heroState: TextView
    private lateinit var cellList: LinearLayout
    private lateinit var cellSummary: TextView
    private lateinit var temperatureRow: LinearLayout
    private lateinit var alarmList: LinearLayout
    private lateinit var alarmSummary: TextView
    private lateinit var logList: LinearLayout
    private lateinit var logSummary: TextView
    private lateinit var commandStatus: TextView
    private lateinit var page: LinearLayout

    private enum class Connection { IDLE, SCANNING, CONNECTING, DISCOVERING, CONNECTED }
    private enum class OpKind { READ, WRITE, DESCRIPTOR, LOCAL }
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
        super.onDestroy()
        stopScan()
        handler.removeCallbacksAndMessages(null)
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) { }
        gatt = null
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
        gauge = SocGauge(this, accent, mutedColor)
        heroRow.addView(gauge, LinearLayout.LayoutParams(dp(138), dp(138)))
        val heroStats = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        heroVoltage = label("0.00 V", 29f, textColor, true)
        heroCurrent = label("0.00 A", 18f, accent, true).apply { typeface = Typeface.MONOSPACE }
        heroState = label("—", 12f, mutedColor, false).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6)); background = rounded(cardSoft, borderColor, 40)
        }
        heroStats.addView(label("TEGANGAN PACK", 10f, mutedColor, true))
        heroStats.addView(heroVoltage, marginParams(-1, -2, top = 4))
        heroStats.addView(heroCurrent, marginParams(-1, -2, top = 7))
        heroStats.addView(heroState, marginParams(-2, -2, top = 12))
        heroRow.addView(heroStats, LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(heroRow)
        page.addView(hero, marginParams(-1, -2, top = 10))

        page.addView(sectionHeader("Ringkasan"), marginParams(-1, -2, top = 22))
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val statRows = listOf(
            listOf("soh" to "Kesehatan", "cells" to "Jumlah sel"),
            listOf("remaining" to "Kapasitas sisa", "full" to "Kapasitas penuh"),
            listOf("minTemp" to "Suhu sel min", "maxTemp" to "Suhu sel maks")
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

        page.addView(sectionHeader("Tegangan sel"), marginParams(-1, -2, top = 22))
        val cellsCard = card()
        cellSummary = label("▲ sel tertinggi · ▼ sel terendah · ! >100 mV dari rata-rata", 11f, mutedColor, false)
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
            setOnClickListener { confirmPowerAction("Matikan baterai?", "Perintah ini dapat memutus semua beban yang tersambung ke keluaran baterai. Pastikan beban aman untuk dimatikan.") { performPowerSequence(false) } }
        }
        val powerOn = actionButton("Nyalakan baterai", filled = false).apply {
            setOnClickListener { confirmPowerAction("Nyalakan baterai?", "Perintah akan dikirim ke BMS. Status aktual baterai tidak dapat dikonfirmasi lewat respons tulis BLE.") { performPowerSequence(true) } }
        }
        powerRow.addView(powerOff, LinearLayout.LayoutParams(0, dp(48), 1f))
        powerRow.addView(powerOn, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(9) })
        controls.addView(powerRow, marginParams(-1, -2, top = 14))
        val sleepButton = actionButton("Mode tidur", filled = false).apply {
            setOnClickListener { confirmPowerAction("Masuk mode tidur?", "Mode tidur dapat mengubah perilaku baterai dan memutus keluaran.") { sendDirectCommand("Mode tidur", BmsProtocol.sleep()) } }
        }
        controls.addView(sleepButton, marginParams(-1, dp(46), top = 9))
        val cutButton = actionButton("Putuskan semua keluaran", filled = false, destructive = true).apply {
            setOnClickListener { confirmPowerAction("Putuskan semua keluaran?", "MOS pengosongan (dan, bila perintah pertama gagal, pengisian) akan dimatikan. Motor, lampu, GPS, dan beban lain pada keluaran baterai dapat mati.") { cutAllOutputs() } }
        }
        controls.addView(cutButton, marginParams(-1, dp(46), top = 9))
        val safety = label("Perangkat yang disambung langsung ke sel baterai—misalnya pelacak GPS anti-maling—dapat tetap menyala. Status sakelar di aplikasi menunjukkan perintah terakhir, bukan keadaan MOS yang dibaca balik.", 11f, warn, false)
        safety.setPadding(dp(10), dp(10), dp(10), dp(10))
        safety.background = rounded(0x22271A0C, 0x664B3B24, 10)
        controls.addView(safety, marginParams(-1, -2, top = 12))
        commandStatus = label("Status sakelar belum dikonfirmasi oleh BMS.", 11f, mutedColor, false)
        controls.addView(commandStatus, marginParams(-1, -2, top = 8))
        page.addView(controls, marginParams(-1, -2, top = 10))

        page.addView(sectionHeader("Diagnostik GATT"), marginParams(-1, -2, top = 22))
        val diagnostic = card()
        diagnostic.addView(label("Daftar layanan dan karakteristik BLE yang ditemukan. Uji opcode otomatis dan pengiriman byte bebas tidak disertakan agar tidak mengirim perintah daya yang belum diverifikasi.", 12f, mutedColor, false))
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
            if (key == "charge") {
                confirmPowerAction(if (checked) "Izinkan pengisian?" else "Hentikan pengisian?", "Perintah akan mengubah MOS pengisian. Keadaan aktual tidak dibaca balik oleh BMS.") { sendMos(key, checked) }
            } else if (key == "discharge") {
                confirmPowerAction(if (checked) "Izinkan pengosongan?" else "Putuskan MOS pengosongan?", if (checked) "Baterai akan diizinkan memberi daya ke beban." else "Ini dapat memutus daya ke semua beban pada keluaran baterai, termasuk motor, lampu, dan GPS.") { sendMos(key, checked) }
            } else {
                sendMos(key, checked)
            }
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
        scanDevices.clear()
        scanNames.clear()
        setError(null)
        connection = Connection.SCANNING
        renderConnection()
        try {
            scanner = adapter.bluetoothLeScanner
            val bleScanner = scanner ?: throw IllegalStateException("Pemindai BLE tidak tersedia")
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) = collectScanResult(result)
                override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { collectScanResult(it) } }
                override fun onScanFailed(errorCode: Int) {
                    runOnUiThread { setError("Pemindaian BLE gagal (kode $errorCode).") }
                }
            }
            scanCallback = cb
            bleScanner.startScan(cb)
            addLog("Memindai perangkat BTD / layanan BMS selama 8 detik")
            val stop = Runnable { stopScan(); chooseDevice() }
            scanRunnable = stop
            handler.postDelayed(stop, 8_000)
        } catch (e: SecurityException) {
            connection = Connection.IDLE
            setError("Izin Bluetooth tidak tersedia: ${e.message}")
        } catch (e: Exception) {
            connection = Connection.IDLE
            setError("Tidak dapat memindai BLE: ${e.message}")
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
                scanNames[address] = "$name  ·  $address"
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

    private fun chooseDevice() {
        if (connection != Connection.SCANNING) return
        connection = Connection.IDLE
        renderConnection()
        val devices = scanDevices.values.toList()
        if (devices.isEmpty()) {
            setError("Tidak ditemukan baterai yang cocok. Pastikan BMS BLE menyala dan mengiklankan nama BTD atau layanan 0x8900.")
            return
        }
        val names = devices.map { scanNames[it.address] ?: it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pilih baterai")
            .setItems(names) { _, which -> devices.getOrNull(which)?.let { connectToDevice(it) } }
            .setNegativeButton("Batal", null)
            .show()
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
            if (success) addLog("BLE menerima $label (status fisik belum diverifikasi)")
            after?.invoke(success)
        })
    }

    private fun confirmPowerAction(title: String, message: String, action: () -> Unit) {
        if (connection != Connection.CONNECTED) { toast("Hubungkan baterai terlebih dahulu"); return }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Lanjutkan") { _, _ -> action() }
            .show()
    }

    private fun performPowerSequence(on: Boolean) {
        val label = if (on) "Nyalakan baterai" else "Matikan baterai"
        addLog("Handshake ulang 0x8901 sebelum $label")
        val authAvailable = gatt?.getService(UUID.fromString(BmsProtocol.SERVICE_UUID))
            ?.getCharacteristic(UUID.fromString(BmsProtocol.AUTH_UUID)) != null
        val startCommands = {
            tryPowerCommand(label, if (on) BmsProtocol.powerOnCommands() else BmsProtocol.powerOffCommands(), 0)
        }
        if (authAvailable) enqueueHandshake(startCommands) else {
            addLog("Auth 0x8901 tidak ditemukan; lanjut tanpa handshake")
            startCommands()
        }
    }

    private fun tryPowerCommand(label: String, commands: List<ByteArray>, index: Int) {
        if (index >= commands.size) {
            setError("Tidak ada opcode $label yang diterima oleh karakteristik 0x8910.")
            return
        }
        if (index > 0) addLog("Tulis gagal; mencoba opcode alternatif ${index + 1}/${commands.size}")
        val bytes = commands[index]
        addLog("Kirim $label: ${BmsProtocol.toHex(bytes)}")
        enqueue(GattOperation(OpKind.WRITE, BmsProtocol.COMMAND_UUID, bytes) { success, _ ->
            if (success) {
                addLog("Perintah $label diterima oleh BLE; keadaan fisik belum diverifikasi")
                commandStatus.text = "Perintah $label dikirim (${BmsProtocol.toHex(bytes)}); status baterai belum dibaca balik."
            } else {
                handler.postDelayed({ tryPowerCommand(label, commands, index + 1) }, 700)
            }
        })
    }

    private fun cutAllOutputs() {
        addLog("Handshake ulang 0x8901 sebelum memutus keluaran")
        val authAvailable = gatt?.getService(UUID.fromString(BmsProtocol.SERVICE_UUID))
            ?.getCharacteristic(UUID.fromString(BmsProtocol.AUTH_UUID)) != null
        val sendDischargeOff = {
            val bytes = BmsProtocol.dischargeMos(false)
            addLog("Kirim MOS pengosongan OFF: ${BmsProtocol.toHex(bytes)}")
            enqueue(GattOperation(OpKind.WRITE, BmsProtocol.COMMAND_UUID, bytes) { success, _ ->
                if (!success) {
                    val charge = BmsProtocol.chargeMos(false)
                    addLog("MOS pengosongan gagal; kirim MOS pengisian OFF: ${BmsProtocol.toHex(charge)}")
                    enqueue(GattOperation(OpKind.WRITE, BmsProtocol.COMMAND_UUID, charge) { chargeOk, _ ->
                        if (chargeOk) commandStatus.text = "MOS pengisian OFF dikirim; keadaan aktual belum diverifikasi."
                    })
                } else commandStatus.text = "MOS pengosongan OFF dikirim; keadaan aktual belum diverifikasi."
                if (success) commandStates["discharge"] = false
                updateSwitches()
            })
        }
        if (authAvailable) enqueueHandshake(sendDischargeOff) else sendDischargeOff()
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
        closeGattQueue()
        try { gatt?.disconnect() } catch (_: Exception) { }
        try { gatt?.close() } catch (_: Exception) { }
        gatt = null
        connection = Connection.IDLE
        deviceName = ""
        snapshot = null
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
            gauge.percent = 0
            heroVoltage.text = "0.00 V"
            heroCurrent.text = "0.00 A"
            heroState.text = "—"
            statValues["soh"]?.text = "—"
            statValues["cells"]?.text = "—"
            statValues["remaining"]?.text = "— Ah"
            statValues["full"]?.text = "— Ah"
            statValues["minTemp"]?.text = "— °C"
            statValues["maxTemp"]?.text = "— °C"
            cellSummary.text = "▲ sel tertinggi · ▼ sel terendah · ! >100 mV dari rata-rata"
            cellList.removeAllViews()
            cellList.addView(label("Belum ada data sel.", 13f, mutedColor, false))
            temperatureRow.removeAllViews()
            temperatureRow.addView(label("Belum ada data suhu.", 13f, mutedColor, false))
            alarmSummary.text = if (connection == Connection.CONNECTED) "Tidak ada alarm aktif." else "Hubungkan baterai untuk melihat alarm."
            alarmList.removeAllViews()
            return
        }
        val soc = status.soc.coerceIn(0, 100)
        gauge.percent = soc
        heroVoltage.text = String.format(Locale.US, "%.2f V", status.voltage)
        heroCurrent.text = String.format(Locale.US, "%.2f A", status.current)
        heroState.text = status.state
        statValues["soh"]?.text = "${status.soh}%"
        statValues["cells"]?.text = "${data.cellsVolts.size}"
        statValues["remaining"]?.text = status.remainingAh?.let { String.format(Locale.US, "%.2f Ah", it) } ?: "— Ah"
        statValues["full"]?.text = status.fullAh?.let { String.format(Locale.US, "%.2f Ah", it) } ?: "— Ah"
        statValues["minTemp"]?.text = "${status.minCellTemp} °C"
        statValues["maxTemp"]?.text = "${status.maxCellTemp} °C"
        updateCells(data.cellsVolts)
        updateTemperatures(status.temperature, status.minCellTemp, status.maxCellTemp)
        updateAlarms(data.alarms)
    }

    private fun updateCells(cells: List<Double>) {
        cellList.removeAllViews()
        if (cells.isEmpty()) {
            cellList.addView(label("Belum ada data sel.", 13f, mutedColor, false))
            return
        }
        val average = cells.average()
        val highest = cells.indices.maxByOrNull { cells[it] } ?: -1
        val lowest = cells.indices.minByOrNull { cells[it] } ?: -1
        cellSummary.text = "Rata-rata ${String.format(Locale.US, "%.3f V", average)} · ▲ tertinggi · ▼ terendah · ! deviasi >100 mV"
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

    private class SocGauge(context: android.content.Context, private val accent: Int, private val muted: Int) : View(context) {
        var percent: Int = 0
            set(value) { field = value.coerceIn(0, 100); invalidate() }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = context.resources.displayMetrics.density * 10f; color = 0xFF263548.toInt() }
        private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = context.resources.displayMetrics.density * 10f; color = accent }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEAF1F7.toInt(); textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
        private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textAlign = Paint.Align.CENTER; textSize = 11f * context.resources.displayMetrics.density; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            val stroke = trackPaint.strokeWidth
            val side = minOf(width, height).toFloat()
            val inset = stroke / 2 + 5 * density
            val bounds = RectF((width - side) / 2 + inset, (height - side) / 2 + inset, (width + side) / 2 - inset, (height + side) / 2 - inset)
            canvas.drawArc(bounds, 135f, 270f, false, trackPaint)
            canvas.drawArc(bounds, 135f, 270f * percent / 100f, false, valuePaint)
            textPaint.textSize = 29f * density
            val centerY = height / 2f - 1f * density
            canvas.drawText("$percent%", width / 2f, centerY, textPaint)
            canvas.drawText("SOC", width / 2f, centerY + 22f * density, captionPaint)
        }
    }

    companion object {
        private const val REQUEST_BLE_PERMISSIONS = 5101
        private const val REQUEST_ENABLE_BT = 5102
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    }
}
