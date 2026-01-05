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
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.Executors
import java.io.IOException

// --- Data classes ---
data class ImuData(val accX: Float, val accY: Float, val accZ: Float,
                   val gyroX: Float, val gyroY: Float, val gyroZ: Float,
                   val magX: Float, val magY: Float, val magZ: Float)

data class RpyData(val roll: Float, val pitch: Float, val yaw: Float)

enum class ImuType {
    HF,
    YAHBOOM
}

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
    private val imuDataBuffer = Collections.synchronizedList(ArrayList<ImuRecord>())

    // --- Protocol Constants ---

    private var lastImuData: com.example.cnnmodelandimu.ImuData? = null
    private var lastRpyData: com.example.cnnmodelandimu.RpyData? = null

    // Temporary storage for Yahboom
    private var yhAcc = FloatArray(3)
    private var yhGyro = FloatArray(3)
    private var yhAngle = FloatArray(3)

    companion object {
        private const val VENDOR_ID = 4292
        private const val PRODUCT_ID = 60000
        private const val BAUD_RATE = 9600
        private const val TAG = "IMU_USB"
        private const val ACTION_USB_PERMISSION = "com.example.myfirstkotlinapp.USB_PERMISSION"
        private const val POLLING_INTERVAL_MS = 10000L

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

    private fun getDeviceFromIntent(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private fun disconnect() {
        try {
            serialIoManager?.stop()
            serialPort?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error opening port: ${e.message}")
        } finally {
            serialIoManager = null
            serialPort = null
            updateStatus("Disconnected")
        }
    }

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
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    updateStatus("USB device detected. Checking...")
                    findAndConnectToIMU()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = getDeviceFromIntent(intent)
                    if (device?.vendorId == VENDOR_ID && device.productId == PRODUCT_ID) {
                        disconnect()
                    }
                }
                ACTION_USB_PERMISSION -> {
                    isPermissionRequestPending = false
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        val device: UsbDevice? = getDeviceFromIntent(intent)
                        device?.let { connect(it) }
                    } else {
                        updateStatus("Error: Permission denied.")
                    }
                }
            }
        }
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

        // 3. Observe Background Color (AI Feedback)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                respirationViewModel.backgroundColor.collect { color ->
                    rootLayout.setBackgroundColor(color)
                }
            }
        }

        // 4. Setup IMU Brand Selector
        setupSpinner()

        // 5. Setup USB
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        findAndConnect()
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

    // --- DATA HANDLING (The Backend Logic) ---

    override fun onNewData(data: ByteArray) {
        receiveBuffer.write(data)
        if (currentImuType == ImuType.HF) {
            processHFBuffer()
        } else {
            processYahboomBuffer()
        }
    }

    private fun parseHFPacket(packet: ByteArray) {
        val msgId = packet[2]
        var isNewData = false
        try {
            val bb = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            if (msgId == com.example.cnnmodelandimu.MainActivity.Companion.HF_MSG_ID_IMU) {
                val gx = bb.getFloat(3); val gy = bb.getFloat(7); val gz = bb.getFloat(11)
                val ax = bb.getFloat(15); val ay = bb.getFloat(19); val az = bb.getFloat(23)
                val mx = bb.getFloat(27); val my = bb.getFloat(31); val mz = bb.getFloat(35)
                lastImuData = ImuData(ax, ay, az, gx, gy, gz, mx, my, mz)
                isNewData = true
            } else if (msgId == com.example.cnnmodelandimu.MainActivity.Companion.HF_MSG_ID_RPY) {
                val roll = bb.getFloat(11)
                val pitch = bb.getFloat(15)
                val yaw = bb.getFloat(19)
                lastRpyData = RpyData(roll, pitch, yaw)
                isNewData = true
            }
            if (isNewData) updateUI(lastImuData, lastRpyData)
        } catch (e: Exception) {
            Log.e(com.example.cnnmodelandimu.MainActivity.Companion.TAG, "HF Parse Error: ${e.message}")
        }
    }

    private fun processHFBuffer() {
        val buffer = receiveBuffer.toByteArray()
        var searchIndex = 0
        while (searchIndex < buffer.size - 1) {
            if (buffer[searchIndex] == com.example.cnnmodelandimu.MainActivity.Companion.HF_HEADER_1 && buffer[searchIndex + 1] == com.example.cnnmodelandimu.MainActivity.Companion.HF_HEADER_2) {
                if (searchIndex + 2 < buffer.size) {
                    val msgId = buffer[searchIndex + 2]
                    val packetLen = if (msgId == com.example.cnnmodelandimu.MainActivity.Companion.HF_MSG_ID_IMU) com.example.cnnmodelandimu.MainActivity.Companion.HF_PACKET_LEN_IMU else if (msgId == com.example.cnnmodelandimu.MainActivity.Companion.HF_MSG_ID_RPY) com.example.cnnmodelandimu.MainActivity.Companion.HF_PACKET_LEN_RPY else 0
                    if (packetLen > 0) {
                        if (searchIndex + packetLen <= buffer.size) {
                            val packetBytes = buffer.copyOfRange(searchIndex, searchIndex + packetLen)
                            parseHFPacket(packetBytes)
                            searchIndex += packetLen
                            continue
                        } else {
                            break
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
            if (buffer[searchIndex] == com.example.cnnmodelandimu.MainActivity.Companion.YAHBOOM_HEADER) {
                if (searchIndex + com.example.cnnmodelandimu.MainActivity.Companion.YAHBOOM_PACKET_LEN <= buffer.size) {
                    val packetBytes = buffer.copyOfRange(searchIndex, searchIndex + com.example.cnnmodelandimu.MainActivity.Companion.YAHBOOM_PACKET_LEN)
                    if (verifyYahboomChecksum(packetBytes)) {
                        parseYahboomPacket(packetBytes)
                        searchIndex += com.example.cnnmodelandimu.MainActivity.Companion.YAHBOOM_PACKET_LEN
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
        for (i in 0 until 10) {
            sum += (packet[i].toInt() and 0xFF)
        }
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
                com.example.cnnmodelandimu.MainActivity.Companion.YAHBOOM_TYPE_ACC -> {
                    yhAcc[0] = getShort(2) / 32768.0f * 16.0f
                    yhAcc[1] = getShort(4) / 32768.0f * 16.0f
                    yhAcc[2] = getShort(6) / 32768.0f * 16.0f
                }
                com.example.cnnmodelandimu.MainActivity.Companion.YAHBOOM_TYPE_GYRO -> {
                    yhGyro[0] = getShort(2) / 32768.0f * 2000.0f
                    yhGyro[1] = getShort(4) / 32768.0f * 2000.0f
                    yhGyro[2] = getShort(6) / 32768.0f * 2000.0f
                    lastImuData = ImuData(yhAcc[0], yhAcc[1], yhAcc[2],
                        yhGyro[0], yhGyro[1], yhGyro[2],
                        0f, 0f, 0f)
                    uiUpdateNeeded = true
                }
                com.example.cnnmodelandimu.MainActivity.Companion.YAHBOOM_TYPE_ANGLE -> {
                    yhAngle[0] = getShort(2) / 32768.0f * 180.0f
                    yhAngle[1] = getShort(4) / 32768.0f * 180.0f
                    yhAngle[2] = getShort(6) / 32768.0f * 180.0f
                    lastRpyData = RpyData(yhAngle[0], yhAngle[1], yhAngle[2])
                    uiUpdateNeeded = true
                }
            }
            if (uiUpdateNeeded) updateUI(lastImuData, lastRpyData)
        } catch (e: Exception) {
            Log.e(com.example.cnnmodelandimu.MainActivity.Companion.TAG, "Yahboom Parse Error: ${e.message}")
        }
    }

    private fun dispatchRecord(record: ImuRecord) {
        imuDataBuffer.add(record)
        respirationViewModel.processImuData(record) // REAL-TIME AI
        runOnUiThread {
            dataTextView.text = "AccX: ${"%.2f".format(record.accX)}\nStatus: ${respirationViewModel.status.value}"
        }
//        imuDataBuffer.add(record)
//        respirationViewModel.processImuData(record) // REAL-TIME AI
//        runOnUiThread {
//            dataTextView.text = "AccX: ${"%.2f".format(record.accX)}\nStatus: ${respirationViewModel.status.value}"
//        }
    }

    // --- USB BOILERPLATE ---
    private fun findAndConnect() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver = drivers.find { it.device.vendorId == VENDOR_ID } ?: return
        if (usbManager.hasPermission(driver.device)) connect(driver.device)
        else usbManager.requestPermission(driver.device, PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE))
    }

    private fun connect(device: UsbDevice) {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        val conn = usbManager.openDevice(driver.device)
        serialPort = driver.ports[0]
        serialPort?.open(conn)
        serialPort?.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        serialIoManager = SerialInputOutputManager(serialPort, this)
        Executors.newSingleThreadExecutor().submit(serialIoManager)
        updateStatus("Connected to ${currentImuType.name}")
    }

    private fun updateStatus(msg: String) = runOnUiThread { statusTextView.text = msg }
    private fun cleanBuffer(idx: Int, b: ByteArray) { receiveBuffer.reset(); if(idx < b.size) receiveBuffer.write(b.copyOfRange(idx, b.size)) }
    private fun updateUI(imu: com.example.cnnmodelandimu.ImuData?, rpy: com.example.cnnmodelandimu.RpyData?) {
        // Collect data for CSV
        if (imu != null && rpy != null) {
            val record = ImuRecord(
                timestamp = System.currentTimeMillis(),
                accX = imu.accX, accY = imu.accY, accZ = imu.accZ,
                gyroX = imu.gyroX, gyroY = imu.gyroY, gyroZ = imu.gyroZ,
                magX = imu.magX, magY = imu.magY, magZ = imu.magZ,
                roll = rpy.roll, pitch = rpy.pitch, yaw = rpy.yaw
            )
            imuDataBuffer.add(record)

            // 1. MUST ADD THIS LINE HERE:
            respirationViewModel.processImuData(record)

            // 2. Clear the imuDataBuffer occasionally so it doesn't crash your RAM
            if (imuDataBuffer.size > 1000) imuDataBuffer.clear()
            imuDataBuffer.add(record)
        }

        val accStr = if (imu != null) "x=${"%.2f".format(imu.accX)}, y=${"%.2f".format(imu.accY)}, z=${"%.2f".format(imu.accZ)}" else "..."
        val gyroStr = if (imu != null) "x=${"%.2f".format(imu.gyroX)}, y=${"%.2f".format(imu.gyroY)}, z=${"%.2f".format(imu.gyroZ)}" else "..."
        val magStr = "N/A"
        val rpyStr = if (rpy != null) "R=${"%.2f".format(rpy.roll)}, P=${"%.2f".format(rpy.pitch)}, Y=${"%.2f".format(rpy.yaw)}" else "..."

        val outputText = """
        |Mode:         ${currentImuType.name}
        |Buffered:     ${imuDataBuffer.size}
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
    override fun onRunError(e: Exception?) { updateStatus("Error: ${e?.message}") }
    override fun onDestroy() { super.onDestroy(); serialIoManager?.stop(); serialPort?.close() }
}