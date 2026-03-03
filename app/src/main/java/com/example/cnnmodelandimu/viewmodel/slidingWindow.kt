package com.example.cnnmodelandimu

import android.app.Application
import android.graphics.Color
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cnnmodelandimu.viewmodel.ImuRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel
import kotlin.math.sqrt
import java.util.*

enum class RespirationStatus { NORMAL, ABNORMAL }

class RespirationViewModel(application: Application) : AndroidViewModel(application) {
    // =========================================================================
    // 1. GLOBAL SCALER PARAMETERS (Exported from Python's StandardScaler\scaler.pkl)
    // =========================================================================
    private val GLOBAL_MEAN = floatArrayOf(
        -0.001320f, -0.001733f, 0.001547f, -0.002712f,
        0.002629f, -0.000248f, 0.003750f, 0.002443f,
        0.000820f, 0.000814f, -0.003193f)

    private val GLOBAL_STD = floatArrayOf(
        0.910048f, 0.910395f, 0.906762f, 0.907733f,
        0.908316f, 0.907176f, 0.907641f, 0.908266f,
        0.906763f, 0.911867f, 0.910370f)

    private val _status = MutableStateFlow(RespirationStatus.NORMAL)
    val status = _status.asStateFlow()

    private val _backgroundColor = MutableStateFlow(Color.RED)
    val backgroundColor = _backgroundColor.asStateFlow()

    private val windowSize = 150 // 6 seconds @ 25Hz
    private val featureCount = 11
    private val dataBuffer = mutableListOf<ImuRecord>()
    private var tflite: Interpreter? = null

    // Butterworth Filter States (one per feature)
    private val filterStates = Array(featureCount) { FloatArray(4) }

    init { loadModel() }

    // =========================================================================
    // 1. FILTER COEFFICIENTS (Order 4, 0.05-0.8Hz @ 25Hz FS)
    // Pre-calculated using scipy.signal.butter(4, [0.05, 0.8], btype='band', fs=25)
    // =========================================================================
    private val B = floatArrayOf(0.000782f, 0.0f, -0.001564f, 0.0f, 0.000782f)
    private val A = floatArrayOf(1.0f, -3.7852f, 5.3853f, -3.4116f, 0.8115f)

    private fun loadModel() {
        try {
            val assetFileDescriptor = getApplication<Application>().assets.openFd("respiration_model.tflite")
            val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, assetFileDescriptor.startOffset, assetFileDescriptor.length)
            tflite = Interpreter(modelBuffer)
            _backgroundColor.value = Color.GREEN
        } catch (e: Exception) {
            Log.e("ML", "MODEL LOAD ERROR: ${e.message}")
        }
    }

    private var sampleCounter = 0 // Add this property

    fun processImuData(record: ImuRecord) {
        synchronized(dataBuffer) {
            dataBuffer.add(record)
            if (dataBuffer.size > windowSize) dataBuffer.removeAt(0)

            // Only run inference when the buffer is full AND we've received
            // a "stride" of new samples (e.g., 25 samples = ~1 second @ 25Hz)
            sampleCounter++
            if (dataBuffer.size == windowSize && sampleCounter >= 25) {
                sampleCounter = 0
                Log.d("AI_DEBUG", "Buffer full, running inference...")
                runInference()
            }
        }
    }

    // 1. Move filter states OUTSIDE the function so they persist
    private val persistentFilterStates = Array(featureCount) { FloatArray(5) }

    private fun runInference() {
        val model = tflite ?: return
        val snapshot = synchronized(dataBuffer) { dataBuffer.toList() }
        if (snapshot.size != windowSize) return

        val processedData = Array(windowSize) { FloatArray(featureCount) }

        // --- STEP A: FEATURE EXTRACTION ---
        for (i in 0 until windowSize) {
            val d = snapshot[i]
            processedData[i][0] = d.accX; processedData[i][1] = d.accY; processedData[i][2] = d.accZ
            processedData[i][3] = d.gyroX; processedData[i][4] = d.gyroY; processedData[i][5] = d.gyroZ
            processedData[i][6] = d.roll; processedData[i][7] = d.pitch; processedData[i][8] = d.yaw
            processedData[i][9] = sqrt(d.accX * d.accX + d.accY * d.accY + d.accZ * d.accZ)
            processedData[i][10] = sqrt(d.gyroX * d.gyroX + d.gyroY * d.gyroY + d.gyroZ * d.gyroZ)
        }

        // --- STEP B: MEDIAN FILTER (Keep as is) ---
        // ... (Your existing median filter code) ...

        // --- STEP C: BUTTERWORTH (Using persistent state to prevent spikes) ---
        for (j in 0 until featureCount) {
            val w = persistentFilterStates[j]
            for (i in 0 until windowSize) {
                val x = processedData[i][j]
                val y = B[0] * x + w[1]
                w[1] = B[1] * x - A[1] * y + w[2]
                w[2] = B[2] * x - A[2] * y + w[3]
                w[3] = B[3] * x - A[3] * y + w[4]
                w[4] = B[4] * x - A[4] * y
                processedData[i][j] = y
            }
        }

        // --- STEP D: LOCAL NORMALIZATION (The "Stuck in Red" Fix) ---
        val input = Array(1) { Array(windowSize) { FloatArray(featureCount) } }
        for (j in 0 until featureCount) {
            // Calculate Mean and Std for THIS window specifically
            var sum = 0f
            for (i in 0 until windowSize) sum += processedData[i][j]
            val mean = sum / windowSize

            var variance = 0f
            for (i in 0 until windowSize) {
                val diff = processedData[i][j] - mean
                variance += diff * diff
            }
            val std = sqrt(variance / windowSize).coerceAtLeast(1e-6f)

            for (i in 0 until windowSize) {
                input[0][i][j] = (processedData[i][j] - mean) / std
            }
        }

        // --- STEP E: TFLITE INFERENCE ---
        val output = Array(1) { FloatArray(1) }
        try {
            model.run(input, output)
            val prob = output[0][0]

            // Log the probability to see what the AI is thinking!
            Log.d("AI_RESULT", "Probability of Abnormal: $prob")

            viewModelScope.launch {
                if (prob > 0.5f) {
                    _status.value = RespirationStatus.ABNORMAL
                    _backgroundColor.value = Color.RED
                } else {
                    _status.value = RespirationStatus.NORMAL
                    _backgroundColor.value = Color.GREEN
                }
            }
        } catch (e: Exception) {
            Log.e("ML", "Inference error: ${e.message}")
        }
    }
}