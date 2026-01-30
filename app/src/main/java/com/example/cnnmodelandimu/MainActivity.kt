package com.example.cnnmodelandimu

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.cnnmodelandimu.viewmodel.ImuRecord
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.Executors

// --- Data classes for Local Parsing ---
data class ImuData(val accX: Float, val accY: Float, val accZ: Float,
                   val gyroX: Float, val gyroY: Float, val gyroZ: Float,
                   val magX: Float, val magY: Float, val magZ: Float)

data class RpyData(val roll: Float, val pitch: Float, val yaw: Float)

enum class ImuType { HF, YAHBOOM }

class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    // --- ViewModels ---
    private lateinit var respirationViewModel: RespirationViewModel

    // --- UI Elements ---
    private lateinit var rootLayout: LinearLayout
    private lateinit var statusTextView: TextView
    private lateinit var dataTextView: TextView
    private lateinit var imuTypeSpinner: Spinner

    // --- USB / Serial ---
    private var serialPort: UsbSerialPort? = null
    private var serialIoManager: SerialInputOutputManager? = null
    private lateinit var usbManager: UsbManager
    private val receiveBuffer = ByteArrayOutputStream()
    private var isPermissionRequestPending = false

    // --- State ---
    private var currentImuType = ImuType.HF
    // This buffer is for your CSV saving or logging (optional), NOT for the AI
    private val localLogBuffer = Collections.synchronizedList(ArrayList<ImuRecord>())

    // --- Temporary Data Storage ---
    private var lastImuData: ImuData? = null
    private var lastRpyData: RpyData? = null

    // Temporary storage for Yahboom parsing
    private var yhAcc = FloatArray(3)
    private var yhGyro = FloatArray(3)
    private var yhAngle = FloatArray(3)

    companion object {
        private const val VENDOR_ID = 4292
        private const val PRODUCT_ID = 60000
        private const val BAUD_RATE = 9600
        private const val TAG = "IMU_USB"
        private const val ACTION_USB_PERMISSION = "com.example.cnnmodelandimu.USB_PERMISSION"

        // --- Protocol Constants ---
        private const val HF_HEADER_1 = 0xAA.toByte()
        private const val HF_HEADER_2 = 0x55.toByte()
        private const val HF_MSG_ID_IMU = 0x2C.toByte()
        private const val HF_MSG_ID_RPY = 0x14.toByte()
        private const val HF_PACKET_LEN_IMU = 47
        private const val HF_PACKET_LEN_RPY = 23

        private const val YAHBOOM_HEADER = 0x55.toByte()
        private const val YAHBOOM_PACKET_LEN = 11
        private const val YAHBOOM_TYPE_ACC = 0x51.toByte()
        private const val YAHBOOM_TYPE_GYRO = 0x52.toByte()
        private const val YAHBOOM_TYPE_ANGLE = 0x53.toByte()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Setup UI
        rootLayout = findViewById(R.id.mainRootLayout)
        statusTextView = findViewById(R.id.statusTextView)
        dataTextView = findViewById(R.id.dataTextView)
        imuTypeSpinner = findViewById(R.id.imuTypeSpinner)
        dataTextView.movementMethod = ScrollingMovementMethod()

        // 2. Setup ViewModels
        respirationViewModel = ViewModelProvider(this)[RespirationViewModel::class.java]

        // 3. Observe Background Color & Status (UI Update)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // CORRECTION 1: Observe Background Color (Int)
                launch {
                    respirationViewModel.backgroundColor.collect { color ->
                        // Now 'color' is an Int, so this comparison works!
                        if (color == android.graphics.Color.GREEN) {
                            Log.d("UI_DEBUG", "Background set to GREEN")
                        } else {
                            Log.d("UI_DEBUG", "Background set to RED")
                        }

                        // Apply the color
                        rootLayout.setBackgroundColor(color)
                    }
                }

                // CORRECTION 2: Observe Status (Enum) separately if needed
                launch {
                    respirationViewModel.status.collect { status ->
                        // Here 'status' is RespirationStatus (NORMAL/ABNORMAL)
                        Log.d("UI_DEBUG", "Current Status: $status")
                    }
                }
            }
        }

        // 4. Setup Components
        setupSpinner()
        usbManager = getSystemService(USB_SERVICE) as UsbManager

        // 5. Register Receiver
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        findAndConnectToIMU()
    }

    // --- DATA HANDLING (The Bridge to AI) ---

    override fun onNewData(data: ByteArray) {
        receiveBuffer.write(data)
        if (currentImuType == ImuType.HF) {
            processHFBuffer()
        } else {
            processYahboomBuffer()
        }
    }

    private fun updateUI(imu: ImuData?, rpy: RpyData?) {
        // 1. Collect data for CSV and AI
        if (imu != null && rpy != null) {
            val record = ImuRecord(
                timestamp = System.currentTimeMillis(),
                accX = imu.accX, accY = imu.accY, accZ = imu.accZ,
                gyroX = imu.gyroX, gyroY = imu.gyroY, gyroZ = imu.gyroZ,
                magX = imu.magX, magY = imu.magY, magZ = imu.magZ,
                roll = rpy.roll, pitch = rpy.pitch, yaw = rpy.yaw
            )

            // [CRITICAL] Send to AI Model (This was missing in your snippet!)
            respirationViewModel.processImuData(record)
        }

        // 2. Format Strings (Exactly as you had them)
        val accStr = if (imu != null) "x=${"%.2f".format(imu.accX)}, y=${"%.2f".format(imu.accY)}, z=${"%.2f".format(imu.accZ)}" else "..."
        val gyroStr = if (imu != null) "x=${"%.2f".format(imu.gyroX)}, y=${"%.2f".format(imu.gyroY)}, z=${"%.2f".format(imu.gyroZ)}" else "..."
        val magStr = "N/A"
        val rpyStr = if (rpy != null) "R=${"%.2f".format(rpy.roll)}, P=${"%.2f".format(rpy.pitch)}, Y=${"%.2f".format(rpy.yaw)}" else "..."

        // 3. Get current AI Status to display text
        val aiStatus = respirationViewModel.status.value.name

        val outputText = """
        |Mode:         ${currentImuType.name}
        |AI Status:    $aiStatus
        |Accel (g):    $accStr
        |Gyro (°/s):   $gyroStr
        |Mag (uT):     $magStr
        |RPY (°):      $rpyStr
        |------------------------------------
        |
        """.trimMargin()

        runOnUiThread {
            dataTextView.text = outputText
        }
    }

    // --- PARSING LOGIC (Keep as is, just ensuring correct calls) ---

    private fun parseHFPacket(packet: ByteArray) {
        val msgId = packet[2]
        var isNewData = false
        try {
            val bb = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            if (msgId == HF_MSG_ID_IMU) {
                // Parse IMU (Acc/Gyro/Mag)
                val gx = bb.getFloat(3); val gy = bb.getFloat(7); val gz = bb.getFloat(11)
                val ax = bb.getFloat(15); val ay = bb.getFloat(19); val az = bb.getFloat(23)
                val mx = bb.getFloat(27); val my = bb.getFloat(31); val mz = bb.getFloat(35)
                lastImuData = ImuData(ax, ay, az, gx, gy, gz, mx, my, mz)
                isNewData = true
            } else if (msgId == HF_MSG_ID_RPY) {
                // Parse Angles
                val roll = bb.getFloat(11)
                val pitch = bb.getFloat(15)
                val yaw = bb.getFloat(19)
                lastRpyData = RpyData(roll, pitch, yaw)
                isNewData = true
            }
            if (isNewData) updateUI(lastImuData, lastRpyData)
        } catch (e: Exception) {
            Log.e(TAG, "HF Parse Error: ${e.message}")
        }
    }

    private fun processHFBuffer() {
        val buffer = receiveBuffer.toByteArray()
        var searchIndex = 0
        while (searchIndex < buffer.size - 1) {
            if (buffer[searchIndex] == HF_HEADER_1 && buffer[searchIndex + 1] == HF_HEADER_2) {
                if (searchIndex + 2 < buffer.size) {
                    val msgId = buffer[searchIndex + 2]
                    val packetLen = when (msgId) {
                        HF_MSG_ID_IMU -> HF_PACKET_LEN_IMU
                        HF_MSG_ID_RPY -> HF_PACKET_LEN_RPY
                        else -> 0
                    }
                    if (packetLen > 0) {
                        if (searchIndex + packetLen <= buffer.size) {
                            val packetBytes = buffer.copyOfRange(searchIndex, searchIndex + packetLen)
                            parseHFPacket(packetBytes)
                            searchIndex += packetLen
                            continue
                        } else {
                            break // Wait for more data
                        }
                    }
                }
            }
            searchIndex++
        }
        cleanBuffer(searchIndex, buffer)
    }

    private fun processYahboomBuffer() {
        val buffer = receiveBuffer.toByteArray()
        var searchIndex = 0
        while (searchIndex < buffer.size) {
            if (buffer[searchIndex] == YAHBOOM_HEADER) {
                if (searchIndex + YAHBOOM_PACKET_LEN <= buffer.size) {
                    val packetBytes = buffer.copyOfRange(searchIndex, searchIndex + YAHBOOM_PACKET_LEN)
                    if (verifyYahboomChecksum(packetBytes)) {
                        parseYahboomPacket(packetBytes)
                        searchIndex += YAHBOOM_PACKET_LEN
                        continue
                    }
                } else {
                    break
                }
            }
            searchIndex++
        }
        cleanBuffer(searchIndex, buffer)
    }

    private fun verifyYahboomChecksum(packet: ByteArray): Boolean {
        var sum = 0
        for (i in 0 until 10) sum += (packet[i].toInt() and 0xFF)
        return (sum.toByte() == packet[10])
    }

    private fun parseYahboomPacket(packet: ByteArray) {
        val type = packet[1]
        fun getShort(offset: Int): Short {
            val low = packet[offset].toInt() and 0xFF
            val high = packet[offset+1].toInt() and 0xFF
            return (high shl 8 or low).toShort()
        }
        try {
            var uiUpdateNeeded = false
            when (type) {
                YAHBOOM_TYPE_ACC -> {
                    yhAcc[0] = getShort(2) / 32768.0f * 16.0f
                    yhAcc[1] = getShort(4) / 32768.0f * 16.0f
                    yhAcc[2] = getShort(6) / 32768.0f * 16.0f
                }
                YAHBOOM_TYPE_GYRO -> {
                    yhGyro[0] = getShort(2) / 32768.0f * 2000.0f
                    yhGyro[1] = getShort(4) / 32768.0f * 2000.0f
                    yhGyro[2] = getShort(6) / 32768.0f * 2000.0f
                    lastImuData = ImuData(yhAcc[0], yhAcc[1], yhAcc[2], yhGyro[0], yhGyro[1], yhGyro[2], 0f, 0f, 0f)
                    uiUpdateNeeded = true
                }
                YAHBOOM_TYPE_ANGLE -> {
                    yhAngle[0] = getShort(2) / 32768.0f * 180.0f
                    yhAngle[1] = getShort(4) / 32768.0f * 180.0f
                    yhAngle[2] = getShort(6) / 32768.0f * 180.0f
                    lastRpyData = RpyData(yhAngle[0], yhAngle[1], yhAngle[2])
                    uiUpdateNeeded = true
                }
            }
            if (uiUpdateNeeded) updateUI(lastImuData, lastRpyData)
        } catch (e: Exception) {
            Log.e(TAG, "Yahboom Parse Error: ${e.message}")
        }
    }

    // --- USB BOILERPLATE ---
    private fun findAndConnectToIMU() {
        if (serialPort != null && serialPort!!.isOpen) return
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) return

        val imuDriver = availableDrivers.find { it.device.vendorId == VENDOR_ID && it.device.productId == PRODUCT_ID }
        if (imuDriver == null) return

        val device = imuDriver.device
        if (usbManager.hasPermission(device)) {
            connect(device)
        } else {
            if (!isPermissionRequestPending) {
                isPermissionRequestPending = true
                val flags = PendingIntent.FLAG_IMMUTABLE
                val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
                usbManager.requestPermission(device, permissionIntent)
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> findAndConnectToIMU()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> disconnect()
                ACTION_USB_PERMISSION -> {
                    isPermissionRequestPending = false
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        getDeviceFromIntent(intent)?.let { connect(it) }
                    }
                }
            }
        }
    }

    private fun getDeviceFromIntent(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private fun connect(device: UsbDevice) {
        try {
            val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            val conn = usbManager.openDevice(driver.device)
            serialPort = driver.ports[0]
            serialPort?.open(conn)
            serialPort?.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            serialIoManager = SerialInputOutputManager(serialPort, this)
            Executors.newSingleThreadExecutor().submit(serialIoManager)
            updateStatus("Connected to ${currentImuType.name}")
        } catch (e: Exception) {
            updateStatus("Connection failed: ${e.message}")
        }
    }

    private fun disconnect() {
        serialIoManager?.stop()
        serialPort?.close()
        serialIoManager = null
        serialPort = null
        updateStatus("Disconnected")
    }

    private fun setupSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ImuType.values())
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        imuTypeSpinner.adapter = adapter
        imuTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                currentImuType = ImuType.values()[pos]
                receiveBuffer.reset()
                updateStatus("Switched to ${currentImuType.name}")
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun updateStatus(msg: String) = runOnUiThread { statusTextView.text = msg }
    private fun cleanBuffer(idx: Int, b: ByteArray) { receiveBuffer.reset(); if(idx < b.size) receiveBuffer.write(b.copyOfRange(idx, b.size)) }
    override fun onRunError(e: Exception?) { updateStatus("Error: ${e?.message}") }
    override fun onDestroy() { super.onDestroy(); disconnect() }
}