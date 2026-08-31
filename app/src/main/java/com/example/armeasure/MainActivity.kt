package com.example.armeasure

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.atan2
import kotlin.math.tan

/**
 * Camera + tilt-angle measuring tool. Works on any phone with a camera and
 * an accelerometer (i.e. every Android phone) - no ARCore, no magnetometer
 * even. Distance/height are computed with basic trigonometry from the tilt
 * angle of the phone.
 *
 * Guided flow (this is the "easier to use" part):
 *  1. Enter phone height off the ground (a couple of quick presets, or type
 *     your own).
 *  2. Hold the phone level, tap "This is level."
 *  3. Tilt the phone down toward the floor, tap "This is tilted down."
 *     -> this auto-detects which sensor direction means "down" on this
 *        specific device, instead of me guessing (this was the bug).
 *  4. Aim and measure.
 */
class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var angleText: TextView
    private lateinit var heightInput: EditText
    private lateinit var primaryButton: Button
    private lateinit var modeButton: Button
    private lateinit var resetButton: Button
    private lateinit var presetChestButton: Button
    private lateinit var presetEyeButton: Button

    private lateinit var sensorManager: SensorManager
    private var gravitySensor: Sensor? = null
    private var accelerometer: Sensor? = null

    // Low-pass-filtered gravity vector, in device coordinates
    private val gravity = floatArrayOf(0f, 0f, 9.8f)
    private var haveGravity = false

    private var rawPitchDegrees = 0f
    private var levelOffsetDegrees = 0f
    private var directionSign = 1f

    private var phoneHeightMeters = 1.4f
    private var horizontalDistance: Float? = null

    private enum class Stage { ENTER_HEIGHT, CALIBRATE_LEVEL, CALIBRATE_DOWN, READY }
    private var stage = Stage.ENTER_HEIGHT

    private enum class Mode { HEIGHT, DISTANCE }
    private var mode = Mode.HEIGHT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        angleText = findViewById(R.id.angleText)
        heightInput = findViewById(R.id.heightInput)
        primaryButton = findViewById(R.id.primaryButton)
        modeButton = findViewById(R.id.modeButton)
        resetButton = findViewById(R.id.resetButton)
        presetChestButton = findViewById(R.id.presetChestButton)
        presetEyeButton = findViewById(R.id.presetEyeButton)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        presetChestButton.setOnClickListener { heightInput.setText("1.2") }
        presetEyeButton.setOnClickListener { heightInput.setText("1.5") }
        primaryButton.setOnClickListener { onPrimaryTapped() }
        resetButton.setOnClickListener { fullReset() }
        modeButton.setOnClickListener { toggleMode() }

        if (hasCameraPermission()) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)

        updateUiForStage()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startCamera()
            else statusText.text = "Camera permission is required to measure"
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (e: Exception) {
                statusText.text = "Could not start camera: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResume() {
        super.onResume()
        val sensor = gravitySensor ?: accelerometer
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    // ---------------- Sensors ----------------

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GRAVITY) {
            gravity[0] = event.values[0]; gravity[1] = event.values[1]; gravity[2] = event.values[2]
            haveGravity = true
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER && gravitySensor == null) {
            // Fallback: low-pass filter raw accelerometer to approximate gravity
            val alpha = 0.85f
            gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
            gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
            gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]
            haveGravity = true
        } else {
            return
        }

        // Pitch of the phone's pointing direction, derived purely from the
        // gravity vector - no magnetometer needed. Sign/zero-point are fixed
        // up by the guided calibration below, so the exact convention here
        // doesn't need to be guessed correctly in advance.
        rawPitchDegrees = Math.toDegrees(atan2(gravity[2].toDouble(), gravity[1].toDouble())).toFloat()

        val signedDownAngle = directionSign * (rawPitchDegrees - levelOffsetDegrees)
        val arrow = if (signedDownAngle > 0.5f) "▼" else if (signedDownAngle < -0.5f) "▲" else "●"
        angleText.text = String.format("%s %.1f°", arrow, kotlin.math.abs(signedDownAngle))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------------- Guided flow ----------------

    private fun onPrimaryTapped() {
        when (stage) {
            Stage.ENTER_HEIGHT -> {
                val h = heightInput.text.toString().toFloatOrNull()
                if (h == null || h <= 0f) {
                    statusText.text = "Enter a valid height in meters (e.g. 1.4)"
                    return
                }
                phoneHeightMeters = h
                stage = Stage.CALIBRATE_LEVEL
            }
            Stage.CALIBRATE_LEVEL -> {
                if (!haveGravity) { statusText.text = "Waiting for sensor..."; return }
                levelOffsetDegrees = rawPitchDegrees
                stage = Stage.CALIBRATE_DOWN
            }
            Stage.CALIBRATE_DOWN -> {
                if (!haveGravity) { statusText.text = "Waiting for sensor..."; return }
                val delta = rawPitchDegrees - levelOffsetDegrees
                if (kotlin.math.abs(delta) < 3f) {
                    statusText.text = "Tilt further down toward the floor, then tap again"
                    return
                }
                directionSign = if (delta > 0) 1f else -1f
                stage = Stage.READY
                resetMeasurementOnly()
            }
            Stage.READY -> onMeasureAction()
        }
        updateUiForStage()
    }

    private fun onMeasureAction() {
        val signedDownAngle = directionSign * (rawPitchDegrees - levelOffsetDegrees)
        val downRadians = Math.toRadians(signedDownAngle.toDouble())

        when (mode) {
            Mode.DISTANCE -> {
                val tanAngle = tan(downRadians).toFloat()
                if (tanAngle <= 0.02f) {
                    statusText.text = "Tilt the phone down toward the point first"
                    return
                }
                val distance = phoneHeightMeters / tanAngle
                statusText.text = String.format("Distance: %.2f m", distance)
            }
            Mode.HEIGHT -> {
                if (horizontalDistance == null) {
                    val tanAngle = tan(downRadians).toFloat()
                    if (tanAngle <= 0.02f) {
                        statusText.text = "Tilt the phone down toward the base first"
                        return
                    }
                    horizontalDistance = phoneHeightMeters / tanAngle
                    statusText.text = "Base set. Now aim at the top and tap the button again"
                    primaryButton.text = "Set Top"
                } else {
                    val d = horizontalDistance!!
                    val upAngleRadians = -downRadians
                    val objectHeight = phoneHeightMeters + d * tan(upAngleRadians).toFloat()
                    statusText.text = String.format("Height: %.2f m  (distance %.2f m)", objectHeight, d)
                    primaryButton.text = "Set Base"
                    horizontalDistance = null
                }
            }
        }
    }

    private fun toggleMode() {
        mode = if (mode == Mode.HEIGHT) Mode.DISTANCE else Mode.HEIGHT
        modeButton.text = if (mode == Mode.HEIGHT) "Mode: Height" else "Mode: Distance"
        resetMeasurementOnly()
        updateUiForStage()
    }

    private fun resetMeasurementOnly() {
        horizontalDistance = null
        if (stage == Stage.READY) {
            primaryButton.text = if (mode == Mode.HEIGHT) "Set Base" else "Measure"
        }
    }

    private fun fullReset() {
        stage = Stage.ENTER_HEIGHT
        horizontalDistance = null
        updateUiForStage()
    }

    private fun updateUiForStage() {
        when (stage) {
            Stage.ENTER_HEIGHT -> {
                statusText.text = "How high off the ground is your phone? Pick a preset or type it in."
                primaryButton.text = "Next"
                heightInput.isEnabled = true
            }
            Stage.CALIBRATE_LEVEL -> {
                statusText.text = "Hold the phone level, pointing straight at the horizon, then tap the button"
                primaryButton.text = "This is level"
                heightInput.isEnabled = false
            }
            Stage.CALIBRATE_DOWN -> {
                statusText.text = "Now tilt the phone down toward the floor (about halfway), then tap the button"
                primaryButton.text = "This is tilted down"
            }
            Stage.READY -> {
                statusText.text = when (mode) {
                    Mode.HEIGHT -> if (horizontalDistance == null)
                        "Aim at the BASE of the object, then tap the button"
                    else
                        "Now aim at the TOP of the object, then tap the button"
                    Mode.DISTANCE -> "Aim at a point on the ground, then tap the button"
                }
                primaryButton.text = when (mode) {
                    Mode.HEIGHT -> if (horizontalDistance == null) "Set Base" else "Set Top"
                    Mode.DISTANCE -> "Measure"
                }
            }
        }
    }

    companion object {
        private const val REQUEST_CAMERA = 1001
    }
}
