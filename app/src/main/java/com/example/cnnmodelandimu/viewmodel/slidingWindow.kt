package com.example.cnnmodelandimu

import android.app.Application
import android.graphics.Color // Use standard Android Color
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

enum class RespirationStatus { NORMAL, ABNORMAL }

class RespirationViewModel(application: Application) : AndroidViewModel(application) {

    private val _status = MutableStateFlow(RespirationStatus.NORMAL)
    val status = _status.asStateFlow()

    // Use Int for color (Standard Android way)
    // Default to RED. It will only turn Green if the model loads successfully.
    private val _backgroundColor = MutableStateFlow(android.graphics.Color.RED)
    val backgroundColor = _backgroundColor.asStateFlow()

    // --- YOUR SCALER VALUES ---
    private val meanValues = floatArrayOf(-0.838311425f, -0.233810877f, 0.0588936349f, 0.0351670824f, 0.0637771886f, -0.0250057863f, -25.3618473f, 59.0208218f, -44.3374704f)
    private val stdValues = floatArrayOf(0.14203697f, 0.27489033f, 0.38050906f, 1.29784475f, 1.18760858f, 1.08091165f, 93.24399457f, 13.20873849f, 83.17580668f)

    private val windowSize = 100
    private val dataBuffer = mutableListOf<ImuRecord>()
    private var tflite: Interpreter? = null

    init { loadModel() }

    private fun loadModel() {
        try {
            val assetFileDescriptor = getApplication<Application>().assets.openFd("respiration_model.tflite")
            val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, assetFileDescriptor.startOffset, assetFileDescriptor.length)

            tflite = Interpreter(modelBuffer)

            // IF WE REACH HERE, MODEL LOADED! Set background to GREEN
            _backgroundColor.value = android.graphics.Color.GREEN
            Log.d("ML", "Model loaded successfully. UI set to Green.")
        } catch (e: Exception) {
            // IF MODEL IS NOT DETECTED / FAILS
            _backgroundColor.value = android.graphics.Color.RED
            Log.e("ML", "MODEL NOT DETECTED: ${e.message}. UI set to Red.")
        }
    }

    fun processImuData(record: ImuRecord) {
        dataBuffer.add(record)
        if (dataBuffer.size >= windowSize) {
            if (dataBuffer.size > windowSize) dataBuffer.removeAt(0)
            runInference()
        }
    }

    private fun runInference() {
        val model = tflite ?: return
        val input = Array(1) { Array(windowSize) { FloatArray(9) } }

        for (i in 0 until windowSize) {
            val d = dataBuffer[i]
            input[0][i][0] = (d.accX.toFloat() - meanValues[0]) / stdValues[0]
            input[0][i][1] = (d.accY.toFloat() - meanValues[1]) / stdValues[1]
            input[0][i][2] = (d.accZ.toFloat() - meanValues[2]) / stdValues[2]
            input[0][i][3] = (d.gyroX.toFloat() - meanValues[3]) / stdValues[3]
            input[0][i][4] = (d.gyroY.toFloat() - meanValues[4]) / stdValues[4]
            input[0][i][5] = (d.gyroZ.toFloat() - meanValues[5]) / stdValues[5]
            input[0][i][6] = (d.magX.toFloat() - meanValues[6]) / stdValues[6]
            input[0][i][7] = (d.magY.toFloat() - meanValues[7]) / stdValues[7]
            input[0][i][8] = (d.magZ.toFloat() - meanValues[8]) / stdValues[8]
        }

        val output = Array(1) { FloatArray(2) }
        try {
            model.run(input, output)
            val isAbnormal = output[0][1] > 0.5f
            viewModelScope.launch {
                if (isAbnormal) {
                    _status.value = RespirationStatus.ABNORMAL
                    _backgroundColor.value = Color.RED
                } else {
                    _status.value = RespirationStatus.NORMAL
                    _backgroundColor.value = Color.GREEN
                }
            }
        } catch (e: Exception) { Log.e("ML", "Inference error: ${e.message}") }
    }
}