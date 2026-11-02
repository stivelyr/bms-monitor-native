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
import android.widget.FrameLayout
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
    private val bg = 0xFF0B1012.toInt()
    private val cardColor = 0xFF121A1D.toInt()
    private val cardSoft = 0xFF172124.toInt()
    private val borderColor = 0xFF263236.toInt()
    private val textColor = 0xFFF0F6F2.toInt()
    private val mutedColor = 0xFF8D9B98.toInt()
    private val accent = 0xFFA6F279.toInt()
    private val cyan = 0xFF68DFD1.toInt()
    private val warn = 0xFFFFC56A.toInt()
    private val danger = 0xFFFF727A.toInt()
    private val handler = Handler(Looper.getMainLooper())
    private val gattQueue = ArrayDeque<GattOperation>()
    private val scanDevices = LinkedHashMap<String, BluetoothDevice>()
    private val scanNames = LinkedHashMap<String, String>()
    private val scanRssi = LinkedHashMap<String, Int>()
    private val logs = ArrayDeque<String>()
    private val commandLogs = ArrayDeque<CommandLogEntry>()
    private val voltageHistory = ArrayDeque<ChartReading>()
    private val commandStates = mutableMapOf<String, Boolean?>(
        "charge" to null, "discharge" to null, "buzzer" to null
    )
    private val statValues = mutableMapOf<String, TextView>()
    private val switchViews = mutableMapOf<String, Switch>()
    private val switchStateLabels = mutableMapOf<String, TextView>()
    private val switchDescriptions = mutableMapOf<String, String>()
    private val tabPages = linkedMapOf<String, ScrollView>()
    private val navItems = linkedMapOf<String, NavItemViews>()
    private val chartRangeButtons = mutableMapOf<Int, Button>()

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
    private var pickerCount: TextView? = null
    private var pickerSelectionLabel: TextView? = null
    private var pickerProgress: ProgressBar? = null
    private var pickerConnect: Button? = null
    private var deviceName = ""
    private var connection = Connection.IDLE
    private var snapshot: BmsProtocol.Snapshot? = null
    private var reading = false
    private var uiSwitchGuard = false
    private var lastError: String? = null
    private var activeTab = "overview"
    private var chartRangeHours = 24

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
    private lateinit var heroModeValue: TextView
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
    private lateinit var commandLogList: LinearLayout
    private lateinit var commandLogSummary: TextView
    private lateinit var commandStatus: TextView
    private lateinit var tabHost: FrameLayout
    private lateinit var overviewCellSummary: TextView
    private lateinit var overviewBalanceBars: LinearLayout
    private lateinit var cellAverageSummary: TextView
    private lateinit var cellCountSummary: TextView

    private enum class Connection { IDLE, SCANNING, CONNECTING, DISCOVERING, CONNECTED }
    private enum class OpKind { READ, WRITE, DESCRIPTOR, LOCAL }
    private data class ChartReading(val atMillis: Long, val volts: Double)
    private data class CommandLogEntry(val atMillis: Long, val label: String, val payload: String, var result: String)
    private data class NavItemViews(val root: LinearLayout, val icon: TextView, val label: TextView, val indicator: View)
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
        refreshCommandLog()
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(7), 0, 0)
            setBackgroundColor(bg)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(9), dp(18), dp(10))
        }
        val brandMark = TextView(this).apply {
            text = "B"
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF142015.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFD0FF91.toInt(), 0xFF79D979.toInt())).apply {
                cornerRadius = dp(13).toFloat()
            }
        }
        header.addView(brandMark, LinearLayout.LayoutParams(dp(38), dp(38)))
        val brandCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandCopy.addView(label("BMS Monitor", 16f, textColor, true))
        brandCopy.addView(label("POWER SYSTEMS", 8f, mutedColor, true).apply { letterSpacing = 0.16f }, marginParams(-1, -2, top = 2))
        header.addView(brandCopy, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        connectButton = actionButton("Connect", filled = true).apply {
            setOnClickListener { onConnectPressed() }
            minWidth = dp(91)
        }
        header.addView(connectButton)
        root.addView(header)

        val connectionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = rounded(cardSoft, borderColor, 15)
        }
        val connectionTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusView = label("Disconnected", 11f, mutedColor, true)
        connectionTop.addView(statusView, LinearLayout.LayoutParams(0, -2, 1f))
        refreshButton = actionButton("Refresh", filled = false).apply { textSize = 10f; setOnClickListener { beginReadCycle() } }
        disconnectButton = actionButton("Disconnect", filled = false).apply { textSize = 10f; setOnClickListener { disconnectFromBms() } }
        connectionTop.addView(refreshButton, marginParams(-2, dp(36), start = 6))
        connectionTop.addView(disconnectButton, marginParams(-2, dp(36), start = 6))
        connectionCard.addView(connectionTop)
        connectionHint = label("Select a compatible BLE battery to start reading live data.", 10f, mutedColor, false)
        connectionCard.addView(connectionHint, marginParams(-1, -2, top = 5))
        root.addView(connectionCard, marginParams(-1, -2, start = 18, end = 18, bottom = 8))

        errorPanel = label("", 12f, danger, false).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0x33271A1D, 0x66573A3D, 12)
            visibility = View.GONE
        }
        root.addView(errorPanel, marginParams(-1, -2, start = 18, end = 18, bottom = 8))

        tabHost = FrameLayout(this)
        root.addView(tabHost, LinearLayout.LayoutParams(-1, 0, 1f))
        addTabScreen("overview", buildOverviewTab())
        addTabScreen("cells", buildCellsTab())
        addTabScreen("alarms", buildAlarmsTab())
        addTabScreen("controls", buildControlsTab())
        root.addView(buildBottomNavigation(), LinearLayout.LayoutParams(-1, dp(62)))
        setContentView(root)
        showTab("overview")
    }

    private fun newTabPage(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(5), dp(18), dp(24))
        setBackgroundColor(bg)
    }

    private fun addTabScreen(key: String, body: LinearLayout) {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(bg)
            addView(body, ViewGroup.LayoutParams(-1, -2))
        }
        tabPages[key] = scroll
        tabHost.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        scroll.visibility = if (key == activeTab) View.VISIBLE else View.GONE
    }

    private fun buildOverviewTab(): LinearLayout {
        val body = newTabPage()
        val hero = card().apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF18241F.toInt(), 0xFF121A1D.toInt(), 0xFF14201D.toInt())
            ).apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), 0xFF38483D.toInt())
            }
            elevation = dp(4).toFloat()
        }
        val heroTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heroTop.addView(label("ENERGY REMAINING", 9f, mutedColor, true), LinearLayout.LayoutParams(0, -2, 1f))
        heroState = label("NO LIVE DATA", 8f, mutedColor, true).apply {
            letterSpacing = 0.08f
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = rounded(0xFF172124.toInt(), 0xFF34413A.toInt(), 30)
        }
        heroTop.addView(heroState)
        hero.addView(heroTop)

        val chargeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        socValue = label("—%", 47f, textColor, true).apply { letterSpacing = -0.07f }
        chargeRow.addView(socValue, LinearLayout.LayoutParams(0, -2, 1f))
        val statusChip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(0xFF17221D.toInt(), 0xFF34433A.toInt(), 14)
        }
        statusChip.addView(label("BMS STATE", 8f, mutedColor, true))
        heroModeValue = label("Waiting for data", 10f, textColor, true)
        statusChip.addView(heroModeValue, marginParams(-1, -2, top = 4))
        chargeRow.addView(statusChip)
        hero.addView(chargeRow, marginParams(-1, -2, top = 7))
        hero.addView(label("STATE OF CHARGE", 8f, mutedColor, true), marginParams(-1, -2, top = 0))

        batteryBar = WaveBatteryBar(this, accent)
        hero.addView(batteryBar, marginParams(-1, dp(43), top = 12))
        val scale = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        scale.addView(label("0%", 8f, mutedColor, false), LinearLayout.LayoutParams(0, -2, 1f))
        scale.addView(label("50%", 8f, mutedColor, false).apply { gravity = Gravity.CENTER })
        scale.addView(label("100%", 8f, mutedColor, false).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(scale, marginParams(-1, -2, top = 5))

        val heroStats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val voltageBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val currentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        voltageBox.addView(label("PACK VOLTAGE", 8f, mutedColor, true))
        heroVoltage = label("— V", 25f, textColor, true)
        voltageBox.addView(heroVoltage, marginParams(-1, -2, top = 4))
        currentBox.addView(label("CURRENT", 8f, mutedColor, true))
        heroCurrent = label("— A", 15f, cyan, true).apply { typeface = Typeface.MONOSPACE }
        currentBox.addView(heroCurrent, marginParams(-1, -2, top = 6))
        heroStats.addView(voltageBox, LinearLayout.LayoutParams(0, -2, 1f))
        heroStats.addView(divider(), LinearLayout.LayoutParams(dp(1), dp(35)).apply { marginStart = dp(12); marginEnd = dp(12) })
        heroStats.addView(currentBox, LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(heroStats, marginParams(-1, -2, top = 14))
        body.addView(hero, marginParams(-1, -2, top = 8))

        val conditionHeading = sectionHeading("Battery condition", "View cells") { showTab("cells") }
        body.addView(conditionHeading, marginParams(-1, -2, top = 20))
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = listOf(
            listOf("soh" to "State of health", "cells" to "Cell count"),
            listOf("minTemp" to "Min cell temperature", "maxTemp" to "Max cell temperature"),
            listOf("speed" to "Speed · raw", "bmsStatus" to "BMS status · raw")
        )
        rows.forEachIndexed { rowIndex, pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { colIndex, item ->
                row.addView(statTile(item.first, item.second), LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (colIndex > 0) marginStart = dp(9)
                })
            }
            grid.addView(row, marginParams(-1, -2, top = if (rowIndex == 0) 0 else 9))
        }
        body.addView(grid)
        body.addView(label("Speed and BMS status are shown as raw values; their meanings or units have not been verified.", 9f, mutedColor, false), marginParams(-1, -2, top = 7))

        body.addView(sectionHeader("Voltage history"), marginParams(-1, -2, top = 20))
        val chartCard = card()
        val chartTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        chartTop.addView(label("Pack voltage", 12f, textColor, true), LinearLayout.LayoutParams(0, -2, 1f))
        chartReadout = label("— V", 16f, cyan, true).apply { typeface = Typeface.MONOSPACE; gravity = Gravity.END }
        chartTop.addView(chartReadout)
        chartCard.addView(chartTop)
        chartSummary = label("Connect to the BMS to start collecting readings for this session.", 9f, mutedColor, false)
        chartCard.addView(chartSummary, marginParams(-1, -2, top = 4))
        chartView = VoltageChartView(this, accent, mutedColor) { selected ->
            if (selected == null) {
                chartReadout.text = "— V"
                chartSummary.text = "Connect to the BMS to start collecting readings for this session."
            } else {
                chartReadout.text = String.format(Locale.US, "%.2f V", selected.volts)
                chartSummary.text = "${SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date(selected.atMillis))} · ${chartView.sampleCount()} session readings · drag to inspect"
            }
        }
        chartCard.addView(chartView, marginParams(-1, dp(144), top = 7))
        val rangeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        chartRangeButtons.clear()
        listOf("24 h" to 24, "7 d" to 168, "30 d" to 720).forEach { (caption, hours) ->
            val rangeButton = actionButton(caption, filled = hours == chartRangeHours).apply {
                setOnClickListener {
                    chartRangeHours = hours
                    chartView.setRangeHours(hours)
                    updateChartRangeButtons()
                }
            }
            chartRangeButtons[hours] = rangeButton
            rangeRow.addView(rangeButton, LinearLayout.LayoutParams(0, dp(35), 1f).apply { if (hours != 24) marginStart = dp(7) })
        }
        updateChartRangeButtons()
        chartCard.addView(rangeRow, marginParams(-1, -2, top = 8))
        chartCard.addView(label("This chart uses measured readings from this app session only. History stays in memory and is cleared when disconnected.", 9f, mutedColor, false), marginParams(-1, -2, top = 8))
        body.addView(chartCard, marginParams(-1, -2, top = 9))

        body.addView(sectionHeading("Cell balance", "Details") { showTab("cells") }, marginParams(-1, -2, top = 20))
        val balanceCard = card()
        overviewCellSummary = label("No cell readings yet.", 10f, mutedColor, false)
        balanceCard.addView(overviewCellSummary)
        overviewBalanceBars = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(0, dp(9), 0, dp(3)) }
        balanceCard.addView(overviewBalanceBars, marginParams(-1, dp(43), top = 3))
        body.addView(balanceCard, marginParams(-1, -2, top = 8))

        body.addView(sectionHeader("Battery temperature"), marginParams(-1, -2, top = 20))
        val tempCard = card()
        temperatureRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tempCard.addView(temperatureRow)
        body.addView(tempCard, marginParams(-1, -2, top = 8))
        body.addView(label("Capacity is not shown because the capacity field has not been verified in the protocol.", 9f, mutedColor, false), marginParams(-1, -2, top = 8))
        return body
    }

    private fun buildCellsTab(): LinearLayout {
        val body = newTabPage()
        addPageIntro(body, "LIVE TELEMETRY", "Cell voltages", "Review individual cell readings and pack balance.")
        val summaryCard = card()
        cellSummary = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        summaryCard.addView(cellSummary)
        val metaRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cellAverageSummary = label("Average  —", 9f, mutedColor, false)
        cellCountSummary = label("0 cells read", 9f, mutedColor, false).apply { gravity = Gravity.END }
        metaRow.addView(cellAverageSummary, LinearLayout.LayoutParams(0, -2, 1f))
        metaRow.addView(cellCountSummary, LinearLayout.LayoutParams(0, -2, 1f))
        summaryCard.addView(metaRow, marginParams(-1, -2, top = 9))
        body.addView(summaryCard, marginParams(-1, -2, top = 8))

        body.addView(sectionHeader("All cells · series order"), marginParams(-1, -2, top = 17))
        val cellsCard = card()
        val legend = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        legend.addView(label("● Highest", 9f, accent, true), LinearLayout.LayoutParams(0, -2, 1f))
        legend.addView(label("● Lowest", 9f, cyan, true).apply { gravity = Gravity.END })
        cellsCard.addView(legend, marginParams(-1, -2, bottom = 8))
        cellList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cellsCard.addView(cellList)
        body.addView(cellsCard, marginParams(-1, -2, top = 8))
        return body
    }

    private fun buildAlarmsTab(): LinearLayout {
        val body = newTabPage()
        addPageIntro(body, "SAFETY", "Protections & alarms", "BMS-reported protection conditions. The BMS remains responsible for battery protection.")
        val statusCard = card().apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF17251D.toInt(), cardColor)).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0xFF304637.toInt())
            }
        }
        statusCard.addView(label("BMS-REPORTED STATUS", 8f, mutedColor, true))
        alarmSummary = label("Connect to view BMS-reported alarms.", 13f, textColor, true)
        statusCard.addView(alarmSummary, marginParams(-1, -2, top = 7))
        statusCard.addView(label("This app reports decoded BMS alerts; it does not replace the battery’s protection system or manufacturer guidance.", 9f, mutedColor, false), marginParams(-1, -2, top = 7))
        body.addView(statusCard, marginParams(-1, -2, top = 8))
        body.addView(sectionHeader("Reported alarms"), marginParams(-1, -2, top = 18))
        val alarmsCard = card()
        alarmList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        alarmsCard.addView(alarmList)
        body.addView(alarmsCard, marginParams(-1, -2, top = 8))
        return body
    }

    private fun buildControlsTab(): LinearLayout {
        val body = newTabPage()
        addPageIntro(body, "BATTERY CONTROLS", "Power controls", "Each action sends one fixed BLE payload after confirmation.")
        val controls = card()
        controls.addView(label("BATTERY MOS", 8f, mutedColor, true))
        controls.addView(switchRow("Charge MOS", "Allow current to flow into the battery", "charge"), marginParams(-1, -2, top = 5))
        controls.addView(divider(), marginParams(-1, 1, top = 8, bottom = 8))
        controls.addView(switchRow("Discharge MOS", "Allow the battery to power connected loads", "discharge"))
        controls.addView(divider(), marginParams(-1, 1, top = 8, bottom = 8))
        controls.addView(switchRow("Buzzer", "Turn on the battery’s audible indicator", "buzzer"))

        val powerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val powerOff = actionButton("Turn battery off", filled = false, destructive = true).apply {
            setOnClickListener {
                val payload = BmsProtocol.setPowerSwitch(false)
                confirmPowerAction("Turn battery off?", "The SetPowerSwitch command may cut power to connected loads. Make sure it is safe to turn the loads off.", payload) {
                    sendDirectCommand("SetPowerSwitch OFF", payload)
                }
            }
        }
        val powerOn = actionButton("Turn battery on", filled = false).apply {
            setOnClickListener {
                val payload = BmsProtocol.setPowerSwitch(true)
                confirmPowerAction("Turn battery on?", "The SetPowerSwitch command will be sent. The BLE write result cannot confirm physical state.", payload) {
                    sendDirectCommand("SetPowerSwitch ON", payload)
                }
            }
        }
        powerRow.addView(powerOff, LinearLayout.LayoutParams(0, dp(44), 1f))
        powerRow.addView(powerOn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) })
        controls.addView(powerRow, marginParams(-1, -2, top = 13))
        val sleepButton = actionButton("Sleep mode", filled = false).apply {
            setOnClickListener {
                val payload = BmsProtocol.sleep()
                confirmPowerAction("Enter sleep mode?", "Sleep mode may change battery behavior and disconnect the output.", payload) {
                    sendDirectCommand("Sleep mode", payload)
                }
            }
        }
        controls.addView(sleepButton, marginParams(-1, dp(42), top = 8))
        body.addView(controls, marginParams(-1, -2, top = 8))

        val safety = label("Controls use the direct payloads mapped from the original app. Disabling discharge may stop motors, lights, GPS, and other loads; devices connected directly to cells may remain powered. A successful BLE write does not confirm that the battery’s physical state changed.", 9f, warn, false)
        safety.setPadding(dp(12), dp(11), dp(12), dp(11))
        safety.background = rounded(0xFF201D16.toInt(), 0xFF4A402C.toInt(), 13)
        body.addView(safety, marginParams(-1, -2, top = 10))

        body.addView(sectionHeader("Direct command mapping"), marginParams(-1, -2, top = 18))
        val mappingCard = card()
        mappingCard.addView(label("Command characteristic 0x8910 · one payload per action · no fallback sequence", 9f, mutedColor, false))
        mappingCard.addView(mappingRow("Charge MOS", "ON  11 01 0B     OFF  11 01 0A"), marginParams(-1, -2, top = 8))
        mappingCard.addView(mappingRow("Discharge MOS", "ON  12 01 0B     OFF  12 01 0A"))
        mappingCard.addView(mappingRow("Buzzer", "ON  13 01 0B     OFF  13 01 0A"))
        mappingCard.addView(mappingRow("SetPowerSwitch", "ON  12 01 0B     OFF  12 01 0A"))
        mappingCard.addView(mappingRow("Sleep mode", "10 01 0A"))
        body.addView(mappingCard, marginParams(-1, -2, top = 8))

        body.addView(sectionHeader("Command status"), marginParams(-1, -2, top = 18))
        commandStatus = label("No command has been sent in this session. A BLE write does not verify physical battery state.", 10f, mutedColor, false).apply {
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = rounded(cardColor, borderColor, 13)
        }
        body.addView(commandStatus, marginParams(-1, -2, top = 8))

        body.addView(sectionHeader("Local command log"), marginParams(-1, -2, top = 18))
        val commandCard = card()
        commandLogSummary = label("No commands sent yet.", 10f, mutedColor, false)
        commandCard.addView(commandLogSummary)
        commandLogList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        commandCard.addView(commandLogList, marginParams(-1, -2, top = 6))
        commandCard.addView(label("Stored in memory only; not uploaded and cleared when the app closes.", 8f, mutedColor, false), marginParams(-1, -2, top = 8))
        body.addView(commandCard, marginParams(-1, -2, top = 8))

        body.addView(sectionHeader("GATT diagnostics"), marginParams(-1, -2, top = 18))
        val diagnostic = card()
        diagnostic.addView(label("Inspect discovered BLE services and characteristics. No automatic opcode probing or fallback commands are used.", 9f, mutedColor, false))
        val scanGattButton = actionButton("Scan GATT services", filled = false).apply { setOnClickListener { scanGattServices() } }
        diagnostic.addView(scanGattButton, marginParams(-1, dp(42), top = 10))
        body.addView(diagnostic, marginParams(-1, -2, top = 8))

        body.addView(sectionHeader("Recent BLE activity"), marginParams(-1, -2, top = 18))
        val activityCard = card()
        logSummary = label("No activity yet.", 9f, mutedColor, false)
        activityCard.addView(logSummary)
        logList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        activityCard.addView(logList, marginParams(-1, -2, top = 6))
        body.addView(activityCard, marginParams(-1, -2, top = 8))
        return body
    }

    private fun buildBottomNavigation(): LinearLayout {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            setBackgroundColor(0xFF0D1416.toInt())
        }
        nav.addView(divider(), LinearLayout.LayoutParams(-1, dp(1)))
        val items = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        val entries = listOf(
            Triple("overview", "⌂", "Overview"),
            Triple("cells", "▤", "Cells"),
            Triple("alarms", "◇", "Alarms"),
            Triple("controls", "⚙", "Controls")
        )
        entries.forEach { (key, glyph, title) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = title
            }
            val indicator = View(this).apply { background = rounded(accent, accent, 3); alpha = 0f }
            item.addView(indicator, LinearLayout.LayoutParams(dp(26), dp(2)).apply { bottomMargin = dp(4) })
            val icon = label(glyph, 16f, mutedColor, true).apply { gravity = Gravity.CENTER }
            item.addView(icon)
            val text = label(title, 8f, mutedColor, true).apply { gravity = Gravity.CENTER }
            item.addView(text, marginParams(-1, -2, top = 2))
            item.setOnClickListener { showTab(key) }
            navItems[key] = NavItemViews(item, icon, text, indicator)
            items.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
        }
        nav.addView(items, LinearLayout.LayoutParams(-1, 0, 1f))
        return nav
    }

    private fun showTab(key: String) {
        if (!tabPages.containsKey(key)) return
        activeTab = key
        tabPages.forEach { (name, view) -> view.visibility = if (name == key) View.VISIBLE else View.GONE }
        navItems.forEach { (name, views) ->
            val selected = name == key
            views.icon.setTextColor(if (selected) accent else mutedColor)
            views.label.setTextColor(if (selected) accent else mutedColor)
            views.indicator.alpha = if (selected) 1f else 0f
        }
    }

    private fun addPageIntro(body: LinearLayout, kicker: String, title: String, description: String) {
        body.addView(label(kicker, 8f, accent, true).apply { letterSpacing = 0.14f }, marginParams(-1, -2, top = 8))
        body.addView(label(title, 24f, textColor, true).apply { letterSpacing = -0.05f }, marginParams(-1, -2, top = 4))
        body.addView(label(description, 10f, mutedColor, false), marginParams(-1, -2, top = 5))
    }

    private fun sectionHeading(title: String, action: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(sectionHeader(title), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = "$action  ↗"
            textSize = 9f
            setTextColor(accent)
            setTypeface(typeface, Typeface.BOLD)
            isClickable = true
            setPadding(dp(6), dp(7), 0, dp(7))
            setOnClickListener { onClick() }
        })
        return row
    }

    private fun mappingRow(title: String, bytes: String): LinearLayout {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        row.addView(label(title, 9f, textColor, false), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label(bytes, 8f, accent, true).apply { typeface = Typeface.MONOSPACE; gravity = Gravity.END })
        wrapper.addView(row)
        wrapper.addView(divider(), marginParams(-1, 1))
        return wrapper
    }

    private fun updateChartRangeButtons() {
        chartRangeButtons.forEach { (hours, button) ->
            val selected = hours == chartRangeHours
            button.setTextColor(if (selected) bg else textColor)
            button.background = rounded(if (selected) accent else cardSoft, if (selected) accent else borderColor, 10)
        }
    }

    private fun statTile(key: String, title: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = rounded(cardColor, borderColor, 16)
        }
        box.addView(label(title, 9f, mutedColor, false))
        val value = label("—", 18f, textColor, true).apply { typeface = Typeface.MONOSPACE }
        statValues[key] = value
        box.addView(value, marginParams(-1, -2, top = 7))
        return box
    }

    private fun switchRow(title: String, description: String, key: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, dp(3)) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(label(title, 14f, textColor, true))
        column.addView(label(description, 11f, mutedColor, false), marginParams(-1, -2, top = 3))
        val stateLabel = label("Not confirmed by the BMS", 10f, mutedColor, false).apply { tag = "switch-state-$key" }
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
                toast("Connect to a battery first.")
                return@setOnCheckedChangeListener
            }
            val bytes = when (key) {
                "charge" -> BmsProtocol.chargeMos(checked)
                "discharge" -> BmsProtocol.dischargeMos(checked)
                else -> BmsProtocol.buzzer(checked)
            }
            val dialogTitle = when (key) {
                "charge" -> if (checked) "Allow charging?" else "Stop charging?"
                "discharge" -> if (checked) "Allow discharge?" else "Disconnect discharge MOS?"
                else -> if (checked) "Turn the buzzer on?" else "Turn the buzzer off?"
            }
            val warning = when (key) {
                "charge" -> "The command will change the charge MOS. The actual state is not read back by the BMS."
                "discharge" -> if (checked) "The battery will be allowed to power connected loads." else "This may cut power to all loads on the battery output, including motors, lights, and GPS."
                else -> "The buzzer command will be sent; its physical effect is not confirmed by the BLE write result."
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
                true -> "Last command: ON · not read back"
                false -> "Last command: OFF · not read back"
                null -> "Switch state has not been confirmed by the BMS."
            }
        }
        uiSwitchGuard = false
    }



        @SuppressLint("MissingPermission")
    private fun onConnectPressed() {
        if (connection == Connection.CONNECTED) return
        val adapter = bluetoothAdapter
        if (adapter == null) {
            setError("This device does not have Bluetooth.")
            return
        }
        if (!hasBlePermissions()) {
            requestBlePermissions()
            return
        }
        if (!adapter.isEnabled) {
            try { startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT) }
            catch (e: Exception) { setError("Could not enable Bluetooth: ${e.message}") }
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
            else setError("Bluetooth permission is required to scan for and connect to a battery.")
        }
    }

    @Deprecated("Android Bluetooth enable prompt result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) beginScan() else setError("Bluetooth is not enabled.")
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
        pickerStatus?.text = "Scanning for nearby BMS devices…"
        pickerProgress?.visibility = View.VISIBLE
        renderPickerDevices()
        try {
            scanner = adapter.bluetoothLeScanner
            val bleScanner = scanner ?: throw IllegalStateException("BLE scanner is unavailable.")
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) = collectScanResult(result)
                override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { collectScanResult(it) } }
                override fun onScanFailed(errorCode: Int) {
                    runOnUiThread {
                        stopScan()
                        connection = Connection.IDLE
                        renderConnection()
                        pickerProgress?.visibility = View.GONE
                        pickerStatus?.text = "Scan failed (code $errorCode). Try again."
                        setError("BLE scan failed (code $errorCode).")
                    }
                }
            }
            scanCallback = cb
            bleScanner.startScan(cb)
            addLog("Scanning for BTD devices / BMS service for 8 seconds")
            val stop = Runnable {
                stopScan()
                if (connection == Connection.SCANNING) {
                    connection = Connection.IDLE
                    renderConnection()
                    pickerProgress?.visibility = View.GONE
                    pickerStatus?.text = if (scanDevices.isEmpty()) "No matching devices. Make sure the battery is on, then scan again." else "${scanDevices.size} matching devices found. Select a battery to continue."
                    renderPickerDevices()
                }
            }
            scanRunnable = stop
            handler.postDelayed(stop, 8_000)
        } catch (e: SecurityException) {
            stopScan()
            connection = Connection.IDLE
            setError("Bluetooth permission unavailable: ${e.message}")
            pickerProgress?.visibility = View.GONE
            pickerStatus?.text = "Bluetooth permission is required to scan."
        } catch (e: Exception) {
            stopScan()
            connection = Connection.IDLE
            setError("Could not scan for BLE devices: ${e.message}")
            pickerProgress?.visibility = View.GONE
            pickerStatus?.text = "Could not start scanning. Try again."
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
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF14201D.toInt(), 0xFF0C1214.toInt())).apply {
                cornerRadius = dp(25).toFloat()
                setStroke(dp(1), 0xFF354743.toInt())
            }
        }
        val topbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val mark = TextView(this).apply {
            text = "B"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF142015.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFD0FF91.toInt(), 0xFF79D979.toInt())).apply { cornerRadius = dp(12).toFloat() }
        }
        topbar.addView(mark, LinearLayout.LayoutParams(dp(38), dp(38)))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(label("BMS DEVICE SCAN", 8f, mutedColor, true).apply { letterSpacing = 0.12f })
        brand.addView(label("Bluetooth Low Energy", 10f, textColor, true), marginParams(-1, -2, top = 3))
        topbar.addView(brand, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        topbar.addView(actionButton("Close", filled = false).apply { setOnClickListener { pickerDialog?.dismiss() } })
        root.addView(topbar)

        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(label("Find a battery", 24f, textColor, true).apply { letterSpacing = -0.05f })
        titleBox.addView(label("Compatible BMS devices found nearby.", 9f, mutedColor, false), marginParams(-1, -2, top = 4))
        titleRow.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        pickerCount = label("WAITING", 8f, accent, true).apply {
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = rounded(0xFF17241D.toInt(), 0xFF354A39.toInt(), 9)
        }
        titleRow.addView(pickerCount)
        root.addView(titleRow, marginParams(-1, -2, top = 14))

        val scanCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(10), dp(11), dp(10))
            background = rounded(0xFF17231F.toInt(), 0xFF30483D.toInt(), 16)
        }
        val bleMark = TextView(this).apply {
            text = "BLE"
            textSize = 8f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFD0F4D7.toInt())
            gravity = Gravity.CENTER
            background = rounded(0xFF204438.toInt(), 0xFF476957.toInt(), 13)
        }
        scanCard.addView(bleMark, LinearLayout.LayoutParams(dp(44), dp(44)))
        val scanCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pickerStatus = label("Preparing scan…", 10f, textColor, true)
        scanCopy.addView(pickerStatus)
        scanCopy.addView(label("BTD name or advertised BMS service 0x8900", 8f, mutedColor, false), marginParams(-1, -2, top = 4))
        pickerProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(accent)
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            visibility = View.GONE
        }
        scanCopy.addView(pickerProgress, marginParams(-1, dp(3), top = 7))
        scanCard.addView(scanCopy, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(11) })
        root.addView(scanCard, marginParams(-1, -2, top = 12))

        val listHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val listTitle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listTitle.addView(label("Scan results", 11f, textColor, true))
        listTitle.addView(label("Select a device to preview its details", 8f, mutedColor, false), marginParams(-1, -2, top = 3))
        listHeader.addView(listTitle, LinearLayout.LayoutParams(0, -2, 1f))
        listHeader.addView(actionButton("Scan again", filled = false).apply { textSize = 9f; setOnClickListener { beginScan() } })
        root.addView(listHeader, marginParams(-1, -2, top = 13, bottom = 7))

        val listScroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        pickerResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listScroll.addView(pickerResults, ViewGroup.LayoutParams(-1, -2))
        root.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(2); bottomMargin = dp(9) })

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(9), 0, 0)
            background = GradientDrawable().apply { setColor(0x00000000); setStroke(dp(1), 0x223F4A43); cornerRadius = dp(14).toFloat() }
        }
        footer.addView(label("SELECTED DEVICE", 7f, mutedColor, true).apply { letterSpacing = 0.12f })
        pickerSelectionLabel = label("Choose a device from the scan results.", 9f, textColor, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        footer.addView(pickerSelectionLabel, marginParams(-1, -2, top = 4))
        pickerConnect = actionButton("Connect", filled = true).apply {
            isEnabled = false
            setOnClickListener {
                val device = selectedDeviceAddress?.let { scanDevices[it] }
                if (device == null) {
                    toast("Select a device from the scan results first.")
                } else {
                    pickerDialog?.dismiss()
                    connectToDevice(device)
                }
            }
        }
        footer.addView(pickerConnect, marginParams(-1, dp(44), top = 9))
        footer.addView(label("Only actual scan results are selectable. Matches a BTD name or advertised BMS service 0x8900.", 8f, mutedColor, false).apply { gravity = Gravity.CENTER }, marginParams(-1, -2, top = 7))
        root.addView(footer, marginParams(-1, -2, top = 5))

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
            pickerCount = null
            pickerSelectionLabel = null
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
        pickerCount?.text = if (ordered.isEmpty()) "0 FOUND" else "${ordered.size} FOUND"
        if (ordered.isEmpty()) {
            list.addView(label("No matching devices found yet. Make sure the battery is powered on, then scan again.", 10f, mutedColor, false).apply {
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(28), dp(18), dp(28))
                background = rounded(cardSoft, borderColor, 14)
            })
        }
        ordered.forEach { address ->
            val selected = selectedDeviceAddress == address
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = rounded(if (selected) 0xFF1C2D23.toInt() else cardSoft, if (selected) accent else borderColor, 15)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedDeviceAddress = address
                    renderPickerDevices()
                }
            }
            val icon = TextView(this).apply {
                text = "BLE"
                textSize = 8f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (selected) accent else cyan)
                gravity = Gravity.CENTER
                background = rounded(0xFF1B2925.toInt(), 0xFF374840.toInt(), 12)
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(label(scanNames[address] ?: "BMS BLE", 10f, textColor, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            info.addView(label(address, 8f, mutedColor, false).apply { typeface = Typeface.MONOSPACE }, marginParams(-1, -2, top = 4))
            info.addView(label("BMS-compatible BLE · RSSI ${scanRssi[address] ?: "—"} dBm", 8f, cyan, false), marginParams(-1, -2, top = 3))
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(9); marginEnd = dp(8) })
            row.addView(label(if (selected) "✓" else "○", 15f, if (selected) accent else mutedColor, true))
            list.addView(row, marginParams(-1, -2, top = 6))
        }
        val selectedAddress = selectedDeviceAddress
        val selectedName = selectedAddress?.let { scanNames[it] }
        pickerSelectionLabel?.text = if (selectedAddress != null && selectedName != null) "$selectedName · $selectedAddress" else "Choose a device from the scan results."
        pickerConnect?.isEnabled = selectedAddress != null && scanDevices.containsKey(selectedAddress)
        pickerStatus?.let { status ->
            if (connection == Connection.SCANNING) status.text = "Scanning nearby BMS devices… ${scanDevices.size} found"
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasBlePermissions()) { requestBlePermissions(); return }
        try {
            connection = Connection.CONNECTING
            deviceName = try { device.name ?: "Battery" } catch (_: SecurityException) { "Battery" }
            setError(null)
            renderConnection()
            val newGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (newGatt == null) {
                connection = Connection.IDLE
                setError("Could not start the BLE connection.")
            } else gatt = newGatt
        } catch (e: SecurityException) {
            connection = Connection.IDLE
            setError("Bluetooth connection permission unavailable: ${e.message}")
        } catch (e: Exception) {
            connection = Connection.IDLE
            setError("Connection failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(callbackGatt: BluetoothGatt, status: Int, newState: Int) {
            runOnUiThread {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    gatt = callbackGatt
                    connection = Connection.DISCOVERING
                    addLog("BLE connected; discovering services…")
                    renderConnection()
                    try { callbackGatt.discoverServices() } catch (e: Exception) { setError("Service discovery failed: ${e.message}") }
                } else {
                    if (connection != Connection.IDLE) addLog("BLE connection disconnected (status $status)")
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
                    if (status != BluetoothGatt.GATT_SUCCESS) setError("Connection to battery lost (status $status).")
                }
            }
        }

        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
            runOnUiThread {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    connection = Connection.IDLE
                    setError("Could not read GATT services (status $status).")
                    renderConnection()
                    return@runOnUiThread
                }
                val service = callbackGatt.getService(UUID.fromString(BmsProtocol.SERVICE_UUID))
                if (service == null) {
                    connection = Connection.IDLE
                    setError("BMS service 0x8900 was not found on this device.")
                    try { callbackGatt.disconnect() } catch (_: Exception) { }
                    renderConnection()
                    return@runOnUiThread
                }
                connection = Connection.CONNECTED
                addLog("Connected to service 0x8900")
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
            addLog("Auth 0x8901 unavailable; continuing without handshake")
            beginReadCycle()
            return
        }
        try { callbackGatt.setCharacteristicNotification(auth, true) } catch (_: Exception) { }
        val cccd = auth.getDescriptor(UUID.fromString(CCCD_UUID))
        if (cccd != null) {
            enqueue(GattOperation(OpKind.DESCRIPTOR, BmsProtocol.AUTH_UUID, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.clone()) {
                    success, _ ->
                if (!success) addLog("Auth notifications are inactive; continuing")
                enqueueHandshake { beginReadCycle() }
            })
        } else enqueueHandshake { beginReadCycle() }
    }

    private fun enqueueHandshake(after: () -> Unit) {
        enqueue(GattOperation(OpKind.WRITE, BmsProtocol.AUTH_UUID, BmsProtocol.handshake) { success, _ ->
            addLog(if (success) "Handshake sent to 0x8901" else "Handshake failed; continuing reads")
            after()
        })
    }

    private fun handleNotification(uuid: String, value: ByteArray?) {
        if (uuid.equals(BmsProtocol.AUTH_UUID, true)) {
            addLog("Handshake response: ${value?.firstOrNull()?.toInt()?.and(0xFF) ?: "—"}")
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
            if (next.status == null) addLog("Failed to read status (0x8902)")
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
            addLog("Characteristic ${next.uuid.takeLast(4)} not found")
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
                        addLog("BLE ${next.kind.name.lowercase(Locale.US)} timeout ${next.uuid.takeLast(4)}")
                        completeOperation(next.uuid, false, null)
                    }
                }
                operationTimeout = timeout
                handler.postDelayed(timeout, 5_000)
            }
        } catch (e: SecurityException) {
            addLog("BLE permission failed: ${e.message}")
            completeOperation(next.uuid, false, null)
        } catch (e: Exception) {
            addLog("BLE operation failed: ${e.message}")
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
        if (!success && operation.kind != OpKind.READ) addLog("BLE write failed: ${operation.uuid.takeLast(4)}")
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
        val commandLabel = when (key) { "charge" -> "Charge MOS"; "discharge" -> "Discharge MOS"; else -> "Buzzer" }
        val bytes = when (key) { "charge" -> BmsProtocol.chargeMos(enabled); "discharge" -> BmsProtocol.dischargeMos(enabled); else -> BmsProtocol.buzzer(enabled) }
        sendDirectCommand(commandLabel, bytes) { success ->
            if (success) {
                commandStates[key] = enabled
                updateSwitches()
                commandStatus.text = "BLE accepted the $commandLabel ${if (enabled) "ON" else "OFF"} command; the state has not been read back."
            }
        }
    }

    private fun sendDirectCommand(label: String, bytes: ByteArray, after: ((Boolean) -> Unit)? = null) {
        if (connection != Connection.CONNECTED) { toast("Connect to a battery first."); return }
        val entry = addCommandLog(label, bytes, "Waiting for BLE write result…")
        addLog("Send $label: ${BmsProtocol.toHex(bytes)}")
        commandStatus.text = "$label command sent to BLE; physical battery state is not verified."
        enqueue(GattOperation(OpKind.WRITE, BmsProtocol.COMMAND_UUID, bytes) { success, _ ->
            if (success) {
                entry.result = "BLE write accepted · physical state unverified"
                addLog("BLE accepted $label (physical state not verified)")
                commandStatus.text = "$label accepted by BLE; the battery’s physical state has not been read back."
            } else {
                entry.result = "BLE write failed · physical state unknown"
                commandStatus.text = "BLE write for $label failed; physical battery state is unknown."
            }
            refreshCommandLog()
            after?.invoke(success)
        })
    }

    private fun confirmPowerAction(title: String, message: String, bytes: ByteArray? = null, action: () -> Unit) {
        if (connection != Connection.CONNECTED) { toast("Connect to a battery first."); return }
        val exactMessage = if (bytes == null) message else "$message\n\nBLE payload: ${BmsProtocol.toHex(bytes)}\nThe BLE write result does not confirm the battery’s physical state."
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(exactMessage)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ -> action() }
            .show()
    }



    private fun scanGattServices() {
        if (connection != Connection.CONNECTED) { toast("Connect to a battery first."); return }
        val services = gatt?.services.orEmpty()
        if (services.isEmpty()) { setError("No GATT services are available to scan."); return }
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
        AlertDialog.Builder(this).setTitle("GATT services").setMessage(lines.joinToString("\n"))
            .setPositiveButton("Close", null).show()
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
        commandStatus.text = "Switch state has not been confirmed by the BMS."
        updateData(null)
        renderConnection()
        addLog("Disconnected")
    }



    private fun setError(message: String?) {
        lastError = message
        errorPanel.text = message.orEmpty()
        errorPanel.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun renderConnection() {
        when (connection) {
            Connection.CONNECTED -> {
                statusView.text = "Connected · $deviceName"
                statusView.setTextColor(accent)
                connectionHint.text = "BLE active · automatic reads every 1.5 seconds"
                connectButton.text = "Connected"
                connectButton.isEnabled = false
                refreshButton.isEnabled = true
                disconnectButton.isEnabled = true
            }
            Connection.SCANNING -> {
                statusView.text = "Searching for devices…"
                statusView.setTextColor(warn)
                connectionHint.text = "Searching for BTD batteries / service 0x8900…"
                connectButton.text = "Scanning…"
                connectButton.isEnabled = false
                refreshButton.isEnabled = false
                disconnectButton.isEnabled = true
            }
            Connection.CONNECTING, Connection.DISCOVERING -> {
                statusView.text = if (connection == Connection.CONNECTING) "Connecting…" else "Discovering services…"
                statusView.setTextColor(warn)
                connectionHint.text = "Establishing a BLE connection."
                connectButton.text = "Connecting…"
                connectButton.isEnabled = false
                refreshButton.isEnabled = false
                disconnectButton.isEnabled = true
            }
            Connection.IDLE -> {
                statusView.text = "Disconnected"
                statusView.setTextColor(mutedColor)
                connectionHint.text = "Select a compatible BLE battery to start reading data."
                connectButton.text = "Connect"
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
            heroVoltage.text = "— V"
            heroCurrent.text = "— A"
            heroState.text = "NO LIVE DATA"
            heroState.setTextColor(mutedColor)
            heroModeValue.text = if (connection == Connection.CONNECTED) "No status data" else "Waiting for BMS"
            statValues["soh"]?.text = "—"
            statValues["cells"]?.text = "—"
            statValues["minTemp"]?.text = "— °C"
            statValues["maxTemp"]?.text = "— °C"
            statValues["speed"]?.text = "—"
            statValues["bmsStatus"]?.text = "—"
            updateCellSummary(emptyList())
            cellList.removeAllViews()
            cellList.addView(label("No cell readings yet. Connect to a BMS to begin.", 12f, mutedColor, false))
            temperatureRow.removeAllViews()
            temperatureRow.addView(label("No temperature readings yet.", 10f, mutedColor, false))
            alarmSummary.text = if (connection == Connection.CONNECTED) "No alarm data has been received from the BMS yet." else "Connect to view BMS-reported alarms."
            alarmSummary.setTextColor(mutedColor)
            alarmList.removeAllViews()
            chartView.updateReadings(voltageHistory.toList())
            return
        }
        val soc = status.soc.coerceIn(0, 100)
        batteryBar.percent = soc
        socValue.text = "$soc%"
        heroVoltage.text = String.format(Locale.US, "%.2f V", status.voltage)
        heroCurrent.text = String.format(Locale.US, "%.2f A", status.current)
        heroState.text = "LIVE DATA"
        heroState.setTextColor(accent)
        heroModeValue.text = status.state
        statValues["soh"]?.text = "${status.soh}%"
        statValues["cells"]?.text = "${data.cellsVolts.size}"
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
        updateCellSummary(cells)
        if (cells.isEmpty()) {
            cellList.addView(label("No cell readings yet. Connect to a BMS to begin.", 12f, mutedColor, false))
            return
        }
        val highest = cells.indices.maxByOrNull { cells[it] } ?: -1
        val lowest = cells.indices.minByOrNull { cells[it] } ?: -1
        val minValue = cells.minOrNull() ?: 0.0
        val maxValue = cells.maxOrNull() ?: 0.0
        val span = maxValue - minValue
        cells.forEachIndexed { index, value ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val marker = when (index) { highest -> "↑ "; lowest -> "↓ "; else -> "" }
            val markerColor = when (index) { highest -> accent; lowest -> cyan; else -> mutedColor }
            val name = label("$marker Cell ${String.format(Locale.US, "%02d", index + 1)}", 10f, markerColor, false).apply { typeface = Typeface.MONOSPACE }
            row.addView(name, LinearLayout.LayoutParams(dp(78), -2))
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = if (span <= 0.000001) 650 else (150 + ((value - minValue) / span * 850)).toInt().coerceIn(150, 1000)
                progressTintList = android.content.res.ColorStateList.valueOf(markerColor)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(borderColor)
            }
            row.addView(bar, LinearLayout.LayoutParams(0, dp(6), 1f).apply { marginStart = dp(6); marginEnd = dp(7) })
            val volts = label(String.format(Locale.US, "%.3f V", value), 10f, textColor, true).apply { typeface = Typeface.MONOSPACE; gravity = Gravity.END }
            row.addView(volts, LinearLayout.LayoutParams(dp(73), -2))
            cellList.addView(row, marginParams(-1, dp(29), top = if (index == 0) 1 else 5))
            if (index < cells.lastIndex) cellList.addView(divider(), marginParams(-1, 1, top = 3, bottom = 2))
        }
    }

    private fun updateCellSummary(cells: List<Double>) {
        cellSummary.removeAllViews()
        overviewBalanceBars.removeAllViews()
        if (cells.isEmpty()) {
            overviewCellSummary.text = "No cell readings yet."
            cellAverageSummary.text = "Average  —"
            cellCountSummary.text = "0 cells read"
        } else {
            val highIndex = cells.indices.maxByOrNull { cells[it] } ?: return
            val lowIndex = cells.indices.minByOrNull { cells[it] } ?: return
            val delta = cells[highIndex] - cells[lowIndex]
            val average = cells.average()
            overviewCellSummary.text = "${cells.size} cells read · spread ${String.format(Locale.US, "%.3f V", delta)}"
            cellAverageSummary.text = "Average  ${String.format(Locale.US, "%.3f V", average)}"
            cellCountSummary.text = "${cells.size} cells read"
            val minValue = cells.minOrNull() ?: 0.0
            val maxValue = cells.maxOrNull() ?: 0.0
            val span = maxValue - minValue
            cells.forEachIndexed { index, value ->
                val height = dp(12 + if (span <= 0.000001) 10 else (value - minValue).div(span).times(19).toInt())
                val color = when (index) { highIndex -> accent; lowIndex -> cyan; else -> 0xFF52675A.toInt() }
                val bar = View(this).apply {
                    background = rounded(color, color, 4)
                    contentDescription = "Cell ${index + 1}: ${String.format(Locale.US, "%.3f V", value)}"
                }
                overviewBalanceBars.addView(bar, LinearLayout.LayoutParams(0, height, 1f).apply {
                    if (index > 0) marginStart = dp(4)
                })
            }
        }
        val summaries = if (cells.isEmpty()) {
            listOf(
                Triple("Lowest", "—", cyan),
                Triple("Highest", "—", accent),
                Triple("Voltage (V)", "—", textColor)
            )
        } else {
            val highIndex = cells.indices.maxByOrNull { cells[it] } ?: 0
            val lowIndex = cells.indices.minByOrNull { cells[it] } ?: 0
            val delta = cells[highIndex] - cells[lowIndex]
            listOf(
                Triple("Lowest", "Cell ${lowIndex + 1} · ${String.format(Locale.US, "%.3f V", cells[lowIndex])}", cyan),
                Triple("Highest", "Cell ${highIndex + 1} · ${String.format(Locale.US, "%.3f V", cells[highIndex])}", accent),
                Triple("Voltage (V)", String.format(Locale.US, "%.3f V", delta), textColor)
            )
        }
        summaries.forEachIndexed { index, item ->
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = rounded(cardSoft, borderColor, 12)
                addView(label(item.first, 8f, mutedColor, true))
                addView(label(item.second, 9f, item.third, true).apply { typeface = Typeface.MONOSPACE }, marginParams(-1, -2, top = 5))
            }
            cellSummary.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(6) })
        }
    }

    private fun updateTemperatures(pack: Int, min: Int, max: Int) {
        temperatureRow.removeAllViews()
        listOf("Pack" to pack, "Min" to min, "Max" to max).forEachIndexed { index, pair ->
            val chip = label("${pair.first}  ${pair.second}°C", 10f, textColor, true).apply {
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(9), dp(8), dp(9))
                background = rounded(cardSoft, borderColor, 11)
            }
            temperatureRow.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(7) })
        }
    }

    private fun updateAlarms(alarms: List<BmsProtocol.Alarm>) {
        alarmList.removeAllViews()
        if (alarms.isEmpty()) {
            alarmSummary.text = "No active alarms reported by the BMS."
            alarmSummary.setTextColor(accent)
            alarmList.addView(label("Protection remains managed by the battery’s BMS.", 10f, mutedColor, false))
            return
        }
        val countLabel = if (alarms.size == 1) "1 active alarm reported by the BMS" else "${alarms.size} active alarms reported by the BMS"
        alarmSummary.text = countLabel
        alarmSummary.setTextColor(danger)
        alarms.forEach { alarm ->
            val line = label("⚠  ${alarm.label}", 12f, textColor, false).apply {
                setPadding(dp(10), dp(9), dp(10), dp(9))
                background = rounded(0xFF271A1D.toInt(), 0xFF573A3D.toInt(), 12)
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

    private fun addCommandLog(label: String, bytes: ByteArray, result: String): CommandLogEntry {
        val entry = CommandLogEntry(System.currentTimeMillis(), label, BmsProtocol.toHex(bytes), result)
        commandLogs.addFirst(entry)
        while (commandLogs.size > 30) commandLogs.removeLast()
        refreshCommandLog()
        return entry
    }

    private fun refreshCommandLog() {
        if (!::commandLogList.isInitialized) return
        commandLogList.removeAllViews()
        if (commandLogs.isEmpty()) {
            commandLogSummary.text = "No commands sent yet."
            return
        }
        commandLogSummary.text = "${commandLogs.size} command${if (commandLogs.size == 1) "" else "s"} in this app session"
        commandLogs.forEach { entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(0, dp(8), 0, dp(8))
            }
            val mark = label("↗", 12f, accent, true).apply {
                gravity = Gravity.CENTER
                background = rounded(0xFF1B2920.toInt(), 0xFF354638.toInt(), 9)
            }
            row.addView(mark, LinearLayout.LayoutParams(dp(29), dp(29)))
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val title = label(entry.label, 10f, textColor, true)
            details.addView(title)
            details.addView(label(entry.payload, 9f, accent, true).apply { typeface = Typeface.MONOSPACE }, marginParams(-1, -2, top = 3))
            val resultColor = when {
                entry.result.startsWith("BLE write accepted") -> accent
                entry.result.startsWith("BLE write failed") -> danger
                else -> warn
            }
            details.addView(label(entry.result, 8f, resultColor, false), marginParams(-1, -2, top = 3))
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(9) })
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.atMillis))
            row.addView(label(time, 8f, mutedColor, false).apply { typeface = Typeface.MONOSPACE; gravity = Gravity.END })
            commandLogList.addView(row)
            if (entry !== commandLogs.lastOrNull()) commandLogList.addView(divider(), marginParams(-1, 1))
        }
    }

    private fun refreshLogs() {
        if (!::logList.isInitialized) return
        logList.removeAllViews()
        if (logs.isEmpty()) {
            logSummary.text = "No activity yet."
            return
        }
        logSummary.text = "${logs.size} recent activities"
        logs.forEach { line ->
            logList.addView(label(line, 9f, mutedColor, false).apply { typeface = Typeface.MONOSPACE }, marginParams(-1, -2, top = 4))
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
            fillAnimator?.cancel()
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
        private var selectedFraction = 1f
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
            selectedFraction = if (visible.size <= 1) 1f else selectedIndex.toFloat() / (visible.size - 1)
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
            selectedFraction = 1f
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
                canvas.drawText("No voltage readings yet.", left, (top + bottom) / 2f, textPaint)
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
                val position = selectedFraction.coerceIn(0f, 1f) * (visible.size - 1).coerceAtLeast(0)
                val leftIndex = position.toInt().coerceIn(0, visible.lastIndex)
                val rightIndex = (leftIndex + 1).coerceAtMost(visible.lastIndex)
                val fraction = position - leftIndex
                val markerVolts = visible[leftIndex].volts + (visible[rightIndex].volts - visible[leftIndex].volts) * fraction
                val x = if (visible.size <= 1) right else left + (right - left) * selectedFraction
                val minimum = visible.minOf { it.volts }
                val maximum = visible.maxOf { it.volts }
                val padding = maxOf((maximum - minimum) * 0.12, 0.02)
                val yNorm = ((markerVolts - (minimum - padding)) / ((maximum - minimum) + padding * 2.0)).toFloat().coerceIn(0f, 1f)
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

        override fun onDetachedFromWindow() {
            rangeAnimator?.cancel()
            super.onDetachedFromWindow()
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
            selectedFraction = fraction
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
