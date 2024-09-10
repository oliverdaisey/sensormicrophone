import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.math.abs

class AudioRecorder(context: Context) {

    // Define StateFlow to hold the recorded audio data
    private val _audioDataFlow = MutableStateFlow<Float?>(null)
    val audioDataFlow: StateFlow<Float?> = _audioDataFlow

    // AudioRecord settings
    private val sampleRate = 44100
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

    @SuppressLint("MissingPermission")
    private val audioRecord = if (ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        // TODO: Consider calling
        //    ActivityCompat#requestPermissions
        // here to request the missing permissions, and then overriding
        //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
        //                                          int[] grantResults)
        // to handle the case where the user grants the permission. See the documentation
        // for ActivityCompat#requestPermissions for more details.
        throw SecurityException("Permission to record audio is not granted")
    } else {
        AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
    }

    // Function to start recording and push the audio data to StateFlow
    suspend fun startRecording() {
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(bufferSize)
            audioRecord.startRecording()

            while (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                // Read audio data into the buffer
                val read = audioRecord.read(buffer, 0, buffer.size)

                if (read > 0) {
                    // Push the recorded data into the StateFlow
                    val cutoffFrequency = 0f  // Cutoff frequency for the high-pass filter
                    val sampleRate = 44100  // Sample rate of the audio data
                    _audioDataFlow.value = calculateAmplitude(
                        shortArrayToFloatArray(
                            byteArrayToShortArray(
                                buffer.copyOfRange(0, read))
                        ))
                    Log.d("AudioRecorder", "Amplitude: ${_audioDataFlow.value}")
                }
            }
        }
    }

    // Stop the recording
    fun stopRecording() {
        audioRecord.stop()
    }

    fun byteArrayToShortArray(byteArray: ByteArray): ShortArray {
        val shortArray = ShortArray(byteArray.size / 2)
        for (i in shortArray.indices) {
            // Combine two bytes to create a short, little endian (standard for PCM audio)
            shortArray[i] = ((byteArray[i * 2].toInt() and 0xFF) or
                    (byteArray[i * 2 + 1].toInt() shl 8)).toShort()
        }
        return shortArray
    }

    fun shortArrayToFloatArray(shortArray: ShortArray): FloatArray {
        return FloatArray(shortArray.size) { i ->
            shortArray[i] / 32768.0f // Normalize short value to [-1.0, 1.0]
        }
    }

    fun applyHighPassFilter(input: FloatArray, cutoffFrequency: Float, sampleRate: Int): FloatArray {
        val output = FloatArray(input.size)
        val rc = 1.0f / (cutoffFrequency * 2 * Math.PI)
        val dt = 1.0f / sampleRate
        val alpha = rc / (rc + dt)

        output[0] = input[0] // Start with the first sample

        for (i in 1 until input.size) {
            output[i] = (alpha * (output[i - 1] + input[i] - input[i - 1])).toFloat()
        }

        return output
    }

    fun calculateAmplitude(floatArray: FloatArray): Float {
        return FloatArray(floatArray.size) { i ->
            abs(floatArray[i].toFloat()) / Short.MAX_VALUE
        }.maxOrNull() ?: 0.0f
    }



}
