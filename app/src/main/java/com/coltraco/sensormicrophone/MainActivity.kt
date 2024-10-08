package com.coltraco.sensormicrophone

import AudioRecorder
import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.Manifest.permission.RECORD_AUDIO
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import com.coltraco.sensormicrophone.ui.theme.SensormicrophoneTheme
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    private val RECORD_AUDIO_REQUEST_CODE = 1

    private lateinit var audioRecorder: AudioRecorder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // handle microphone permission
        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                Toast.makeText(this, "Permission to record audio is granted", Toast.LENGTH_SHORT)
                    .show()
                proceedWithMainApp()
            } else {
                Toast.makeText(
                    this,
                    "Permission to record audio is not granted",
                    Toast.LENGTH_SHORT
                ).show()
                showFailedPermissionScreen()
            }
        }
        requestPermissionLauncher.launch(RECORD_AUDIO)

    }

    private fun showFailedPermissionScreen() {
        setContent {
            SensormicrophoneTheme {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Permission to record audio is not granted", fontSize = 20.sp)
                }
            }
        }
    }

    private fun proceedWithMainApp() {

        audioRecorder = AudioRecorder(this)

        setContent {

            LaunchedEffect(Unit) {
                audioRecorder.startRecording()
            }
            val nodeManager = NodeManager(audioRecorder.audioDataFlow, lifecycleScope)
            var cutoffFrequency by remember { mutableFloatStateOf(0f) }
            var amplitudeScaling by remember { mutableFloatStateOf(100f) }

            SensormicrophoneTheme {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Microphone Sensor Testing App",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(16.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    LineGraph(
                        nodeManager = nodeManager,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(400.dp),
                        rescaling = false
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Cutoff frequency for high-pass filter",
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                "${cutoffFrequency.roundToInt()}Hz",
                                fontSize = 25.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Slider(
                                value = cutoffFrequency, onValueChange = {
                                    val chosenValue = it
                                    cutoffFrequency = chosenValue
                                    audioRecorder.changeHighPassFilter(chosenValue)
                                }, valueRange = 0f..20000f,
                                modifier = Modifier.fillMaxWidth(0.9f)
                            )

                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                "Amplitude scaling factor",
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                "${amplitudeScaling.roundToInt()}%",
                                fontSize = 25.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Slider(
                                value = amplitudeScaling, onValueChange = {
                                    val chosenValue = it
                                    amplitudeScaling = chosenValue
                                    audioRecorder.changeAmplitudeScaling(chosenValue)
                                }, valueRange = 0f..200f,
                                modifier = Modifier.fillMaxWidth(0.9f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(50.dp))
                }
            }
        }
    }
}

const val MAX_READING = 1.0f

/**
 * Manager for nodes of a time series graph. Handles adding new nodes (raw measurements) and updating the nodes for animation.
 *
 * `NodeManager` assumes a square canvas with 100-100 coordinates. The x-axis is time, and the y-axis is the value of the measurement.
 *
 * It takes in raw measurements and maps them to these coordinates. It also handles the animation of the nodes.
 *
 * @param measurementFlow: a flow of measurements to add to the graph
 * @param coroutineScope: the coroutine scope to run the animation on
 *
 */
class NodeManager(
    measurementFlow: StateFlow<Float?>? = null,
    coroutineScope: CoroutineScope? = null
) {

    val MAX_VALUE: Float = MAX_READING
    val nodes: MutableStateFlow<List<Node>> = MutableStateFlow(listOf())

    init {
        if (measurementFlow != null && coroutineScope != null) {
            // handles posting new nodes
            coroutineScope.launch(Dispatchers.Default) {
                measurementFlow.collect {
                    if (it != null) {
                        addNode(it.toFloat())
                    }
                }
            }
            // handles animation of nodes
            // run on Dispatchers.Main to ensure snapshots complete
            coroutineScope.launch(Dispatchers.Main) {
                while (true) {
                    kotlinx.coroutines.delay(16)
                    updateNodes()
                }
            }
        }
    }

    val MAX_X = 100f
    val MAX_Y = 100f

    private val deltaX: Float = 7f

    var tSwap: Float = 2f

    private var timestamp: Long = System.currentTimeMillis()

    private val REMOVAL_CONSTANT = (tSwap + deltaX)

    var minY by mutableFloatStateOf(MAX_Y)
    var maxY by mutableFloatStateOf(0f)

    /**
     * Add a new node to the list of nodes. Does some work to ensure smooth animation.
     * @param measurement: the measurement associated to the node
     * @param maxValue: the maximum value of the graph
     */
    fun addNode(measurement: Float, maxValue: Float = MAX_VALUE) {
        val y = maxValue - measurement
        var nodesCopy = nodes.value
        nodesCopy = nodesCopy.map {
            if (it.x > MAX_X + tSwap) {
                // choose the max of MAX_X and the previous node (last node that is not infinity)
                // make sure that the list has enough nodes for this
                if (nodesCopy.size < 2) {
                    return@map Node(MAX_X, it.y)
                }
                val newX = maxOf(MAX_X, nodesCopy[nodesCopy.size - 2].x)
                Node(newX, it.y)
            }
            else it
        }
        // next, add the new node at tSwap
        nodesCopy += Node(MAX_X + tSwap, y / maxValue * MAX_Y)
        // finally, add a new node at infinity
        nodesCopy += Node(MAX_X + tSwap + 100, y / maxValue * MAX_Y)
        // done all the work? update the nodes
        nodes.value = nodesCopy
        Log.d("NodeManager", "Added node, size is now ${nodes.value.size}")
    }

    fun updateNodes() {
        // update time
        val currentTime: Long = System.currentTimeMillis()
        val deltaT = (currentTime - timestamp).toFloat() / 1000
        timestamp = currentTime

        // remove nodes that are far too left
        nodes.value = nodes.value.filter {
            it.x > -(REMOVAL_CONSTANT)
        }
        // do not update the node at infinity
        nodes.value = nodes.value.map {
            if (it.x <= MAX_X + tSwap) Node(it.x - deltaX*deltaT, it.y)
            else it
        }
        // update max & min value
        // smaller y = bigger value
        try {
            minY = min(minY, nodes.value.filter { it.x >= 90 }.minOf { it.y })
            maxY = max(maxY, nodes.value.filter { it.x >= 90 }.maxOf { it.y })
        } catch (e: Exception) {
            Log.e("NodeManager", "Error resetting max and min values: $e")
            // set to default values
            minY = MAX_Y
            maxY = 0f
        }
    }

}

// stores a measurement in canvas coordinates. Down is positive y, right is positive x
data class Node(var x: Float, var y: Float)

@Composable
fun LineGraph(modifier: Modifier = Modifier,
              nodeManager: NodeManager = NodeManager(),
              rescaling: Boolean = true
) {
    val nodes by nodeManager.nodes.collectAsState()

    // animate the max value with spring
    // I will add a small offset to these values to make sure the graph is not at the edge of the screen
    val minY: Float by animateFloatAsState(nodeManager.minY - 1f,
        animationSpec = spring(1f, 50f)
    )
    val maxY: Float by animateFloatAsState(nodeManager.maxY + 1f,
        animationSpec = spring(1f, 50f)
    )

    val scaleFactor = if (rescaling) {(nodeManager.MAX_Y / (25*max(0.5f, log10((maxY - minY)/2))))} else 2f
    val strokeWidth = 1f*scaleFactor

    val backgroundColor = MaterialTheme.colorScheme.background
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier
        .clip(MaterialTheme.shapes.medium)) {

        val maxYCanvas = size.height
        val maxXCanvas = size.width

        // draw a line representing the max value
        val yMax = (minY / nodeManager.MAX_Y) * maxYCanvas
        if (!rescaling) {
            drawLine(
                color = Color.Red.copy(alpha = 0.4f),
                start = Offset(0f, yMax),
                end = Offset(maxXCanvas, yMax),
                strokeWidth = 5f
            )
        }

        // draw a line representing the min value
        val yMin = (maxY / nodeManager.MAX_Y) * maxYCanvas
        if (!rescaling) {
            drawLine(
                color = Color.Green.copy(alpha = 0.4f),
                start = Offset(0f, yMin),
                end = Offset(maxXCanvas, yMin),
                strokeWidth = 5f
            )
        }

        for (node in nodes) {

            val x = (node.x / nodeManager.MAX_X) * maxXCanvas
            val y = if (rescaling) {
                (node.y - minY) / (maxY - minY) * maxYCanvas
            } else {
                (node.y / nodeManager.MAX_Y) * maxYCanvas
            }

            drawCircle(
                color = Color.Blue,
                radius = strokeWidth / 2,
                center = Offset(x, y)
            )
        }

        // draw lines between consecutive nodes

        for (i in 0 until nodes.size - 1) {
            val node1 = nodes[i]
            val node2 = nodes[i + 1]

            val x1 = (node1.x / nodeManager.MAX_X) * maxXCanvas
            val y1 = if (rescaling) {
                (node1.y - minY) / (maxY - minY) * maxYCanvas

            } else {
                (node1.y / nodeManager.MAX_Y) * maxYCanvas
            }

            val x2 = (node2.x / nodeManager.MAX_X) * maxXCanvas
            val y2 = if (rescaling) {
                (node2.y - minY) / (maxY - minY) * maxYCanvas

            } else {
                (node2.y / nodeManager.MAX_Y) * maxYCanvas
            }

            drawLine(
                color = Color.Blue,
                start = Offset(x1, y1),
                end = Offset(x2, y2),
                strokeWidth = strokeWidth
            )
        }

        val labelsX = maxXCanvas / 10
        // draw a vertical box on the left for axis labels
        drawRect(
            color = backgroundColor,
            topLeft = Offset(0f, 0f),
            size = Size(labelsX, maxYCanvas)
        )

        drawLine(
            color = Color.Black,
            start = Offset(labelsX, 0f),
            end = Offset(labelsX, maxYCanvas),
            strokeWidth = 1.5f
        )

        // draw ticks on y-axis
        val numTicks = 20
        for (i in 0 until numTicks) {
            val y = (i / numTicks.toFloat()) * maxYCanvas
            drawLine(
                color = Color.Black,
                start = Offset(labelsX - 8f, y),
                end = Offset(labelsX, y),
                strokeWidth = 1.2f
            )
        }

        // every 4 ticks, draw a label

        for (i in 0 until numTicks+1 step 4) {
            val y = maxYCanvas - (i / numTicks.toFloat()) * maxYCanvas
            val text = buildAnnotatedString {
                withStyle(
                    style = SpanStyle(
                        color = Color.Black,
                        fontSize = 8.sp
                    )
                ) {
                    if (rescaling) {
                        append("${(1 - (maxY / nodeManager.MAX_Y)) * nodeManager.MAX_VALUE + ((maxY - minY) / (nodeManager.MAX_Y)) * (nodeManager.MAX_VALUE / numTicks) * i}")
                    } else {
                        append("${(nodeManager.MAX_Y / numTicks) * i}")
                    }
                }
            }
            val textLayoutResult = textMeasurer.measure(text)
            drawText(
                textLayoutResult = textLayoutResult,
                topLeft = Offset(labelsX - textLayoutResult.size.width - 10f, y - textLayoutResult.size.height / 2)
            )
        }
    }
}
