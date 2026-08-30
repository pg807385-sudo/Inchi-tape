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
import kotlin.math.tan

/**
 * Camera-and-tilt-angle measuring tool - the classic "clinometer" technique
 * surveyors have used for centuries, no depth sensor or ARCore required.
 *
 * How it works:
 *  - You tell the app how high off the ground your phone is (measure once
 *    with a tape measure, or estimate).
 *  - You calibrate "level" by holding the phone pointing exactly horizontal
 *    and tapping Calibrate - this cancels out any sensor offset/drift.
 *  - Distance-to-point mode: aim the crosshair at a point on the ground and
 *    tap Measure. horizontalDistance = phoneHeight / tan(angleBelowLevel)
 *  - Object-height mode: aim at the base of an object (tap Set Base) to get
 *    the horizontal distance, then aim at the top (tap Set Top).
 *    objectHeight = phoneHeight + horizontalDistance * tan(angleAboveLevel)
 *
 * This only needs a normal camera and the accelerometer, both present on
 * essentially every Android phone -- it runs fine on Android 9 and on
 * devices that Google Play Services for AR refuses to support.
 */
class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var angleText: TextView
    private lateinit var heightInput: EditText
    private lateinit var calibrateButton: Button
    private lateinit var actionButton: Button
    private lateinit var modeButton: Button
    private lateinit var resetButton: Button

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var haveGravity = false
    private var haveGeomagnetic = false

    private var currentPitchDegrees = 0f      // raw sensor pitch
    private var calibrationOffsetDegrees = 0f // subtracted from raw pitch once calibrated
    private var isCalibrated = false

    private var phoneHeightMeters = 1.4f

    private enum class Mode { HEIGHT, DISTANCE }
    private var mode = Mode.HEIGHT

    private var horizontalDistance: Float? = null // captured at "Set Base" / "Measure"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        angleText = findViewById(R.id.angleText)
        heightInput = findViewById(R.id.heightInput)
        calibrateButton = findViewById(R.id.calibrateButton)
        actionButton = findViewById(R.id.actionButton)
        modeButton = findViewById(R.id.modeButton)
        resetButton = findViewById(R.id.resetButton)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        calibrateButton.setOnClickListener { calibrate() }
        actionButton.setOnClickListener { onActionTapped() }
        resetButton.setOnClickListener { resetMeasurement() }
        modeButton.setOnClickListener { toggleMode() }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                statusText.text = "Camera permission is required to measure"
            }
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview)
            } catch (e: Exception) {
                statusText.text = "Could not start camera: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    // ---------------- Sensors ----------------

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, gravity, 0, 3)
                haveGravity = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                haveGeomagnetic = true
            }
        }
        if (haveGravity && haveGeomagnetic) {
            val rotationMatrix = FloatArray(9)
            val remapped = FloatArray(9)
            val orientation = FloatArray(3)
            if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
                // Remap so "pitch" reflects tilt of the rear camera's pointing
                // direction relative to the horizon, for a phone held upright
                // in portrait with the rear camera facing away from the user.
                SensorManager.remapCoordinateSystem(
                    rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped
                )
                SensorManager.getOrientation(remapped, orientation)
                val rawPitchDegrees = Math.toDegrees(orientation[1].toDouble()).toFloat()
                currentPitchDegrees = rawPitchDegrees
                val displayAngle = currentPitchDegrees - calibrationOffsetDegrees
                angleText.text = String.format("%.1f°", displayAngle)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------------- Measurement flow ----------------

    private fun calibrate() {
        val heightStr = heightInput.text.toString()
        val h = heightStr.toFloatOrNull()
        if (h == null || h <= 0f) {
            statusText.text = "Enter a valid height in meters first"
            return
        }
        phoneHeightMeters = h
        calibrationOffsetDegrees = currentPitchDegrees
        isCalibrated = true
        actionButton.isEnabled = true
        horizontalDistance = null
        updateHintForMode()
    }

    private fun toggleMode() {
        mode = if (mode == Mode.HEIGHT) Mode.DISTANCE else Mode.HEIGHT
        modeButton.text = if (mode == Mode.HEIGHT) getString(R.string.height_mode) else getString(R.string.distance_mode)
        resetMeasurement()
    }

    private fun resetMeasurement() {
        horizontalDistance = null
        actionButton.text = if (mode == Mode.HEIGHT) getString(R.string.set_base) else getString(R.string.measure_button)
        updateHintForMode()
    }

    private fun updateHintForMode() {
        if (!isCalibrated) {
            statusText.text = getString(R.string.calibrate_prompt)
            return
        }
        statusText.text = when (mode) {
            Mode.HEIGHT -> if (horizontalDistance == null) getString(R.string.aim_base_hint) else getString(R.string.aim_top_hint)
            Mode.DISTANCE -> getString(R.string.aim_point_hint)
        }
        actionButton.text = when (mode) {
            Mode.HEIGHT -> if (horizontalDistance == null) getString(R.string.set_base) else getString(R.string.set_top)
            Mode.DISTANCE -> getString(R.string.measure_button)
        }
    }

    private fun onActionTapped() {
        if (!isCalibrated) return
        val angleDegrees = currentPitchDegrees - calibrationOffsetDegrees
        val angleRadians = Math.toRadians(angleDegrees.toDouble())

        when (mode) {
            Mode.DISTANCE -> {
                // Point is below the horizon -> angle should be negative (looking down)
                val tanAngle = tan(-angleRadians).toFloat()
                if (tanAngle <= 0f) {
                    statusText.text = "Tilt the phone downward toward the point first"
                    return
                }
                val distance = phoneHeightMeters / tanAngle
                statusText.text = String.format("Distance: %.2f m", distance)
            }
            Mode.HEIGHT -> {
                if (horizontalDistance == null) {
                    val tanAngle = tan(-angleRadians).toFloat()
                    if (tanAngle <= 0f) {
                        statusText.text = "Tilt the phone downward toward the base first"
                        return
                    }
                    horizontalDistance = phoneHeightMeters / tanAngle
                    updateHintForMode()
                } else {
                    val d = horizontalDistance!!
                    val tanAngle = tan(angleRadians).toFloat()
                    val objectHeight = phoneHeightMeters + d * tanAngle
                    statusText.text = String.format("Height: %.2f m (distance %.2f m)", objectHeight, d)
                    actionButton.text = getString(R.string.set_base)
                    horizontalDistance = null
                }
            }
        }
    }

    companion object {
        private const val REQUEST_CAMERA = 1001
    }
}
