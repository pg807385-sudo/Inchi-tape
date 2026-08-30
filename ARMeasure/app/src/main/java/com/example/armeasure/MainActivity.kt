package com.example.armeasure

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import java.util.concurrent.ArrayBlockingQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

/**
 * AR point-to-point distance measuring, similar in spirit to iOS's Measure app:
 * tap a real-world surface to drop a point, tap again to drop a second point,
 * and the straight-line distance between them is shown. All tracking happens
 * on-device via ARCore -- nothing here calls the network.
 */
class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var distanceText: TextView
    private lateinit var resetButton: Button
    private lateinit var unitButton: Button

    private var session: Session? = null
    private var installRequested = false

    private val backgroundRenderer = BackgroundRenderer()
    private val lineRenderer = LineRenderer()

    private val tapQueue = ArrayBlockingQueue<MotionEvent>(16)
    private lateinit var gestureDetector: GestureDetector

    private data class Point3D(val x: Float, val y: Float, val z: Float)

    private val completedMeasurements = mutableListOf<Pair<Point3D, Point3D>>()
    private var pendingPoint: Point3D? = null
    private var lastDistanceMeters: Float? = null
    private var useFeet = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceView)
        distanceText = findViewById(R.id.distanceText)
        resetButton = findViewById(R.id.resetButton)
        unitButton = findViewById(R.id.unitButton)

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                tapQueue.offer(e)
                return true
            }
        })
        surfaceView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        resetButton.setOnClickListener {
            completedMeasurements.clear()
            pendingPoint = null
            lastDistanceMeters = null
            distanceText.text = getString(R.string.tap_to_start)
        }

        unitButton.setOnClickListener {
            useFeet = !useFeet
            unitButton.text = if (useFeet) getString(R.string.units_ft) else getString(R.string.units_m)
            updateDistanceLabel()
        }

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    override fun onResume() {
        super.onResume()

        if (session == null) {
            if (!hasCameraPermission()) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
                return
            }
            if (!tryCreateSession()) return
        }

        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
            session = null
            return
        }
        surfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        session?.let {
            surfaceView.onPause()
            it.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close()
        session = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                onResume()
            } else {
                finish()
            }
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun tryCreateSession(): Boolean {
        return try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    false
                }
                ArCoreApk.InstallStatus.INSTALLED -> {
                    val newSession = Session(this)
                    val config = Config(newSession).apply {
                        focusMode = Config.FocusMode.AUTO
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        lightEstimationMode = Config.LightEstimationMode.DISABLED
                        if (newSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                            depthMode = Config.DepthMode.AUTOMATIC
                        }
                    }
                    newSession.configure(config)
                    session = newSession
                    true
                }
                else -> false
            }
        } catch (e: UnavailableException) {
            Log.e(TAG, "ARCore unavailable", e)
            distanceText.text = getString(R.string.ar_unavailable)
            false
        }
    }

    // ---------------- GLSurfaceView.Renderer ----------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        android.opengl.GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer.createOnGlThread()
        lineRenderer.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        android.opengl.GLES20.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(display?.rotation ?: 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        android.opengl.GLES20.glClear(
            android.opengl.GLES20.GL_COLOR_BUFFER_BIT or android.opengl.GLES20.GL_DEPTH_BUFFER_BIT
        )
        val sess = session ?: return

        try {
            sess.setCameraTextureName(backgroundRenderer.textureId)
            val frame: Frame = sess.update()
            val camera = frame.camera

            backgroundRenderer.draw(frame)

            if (camera.trackingState != TrackingState.TRACKING) {
                return
            }

            handleTap(frame, camera)

            val viewMatrix = FloatArray(16)
            val projMatrix = FloatArray(16)
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projMatrix, 0, 0.01f, 100f)

            for ((a, b) in completedMeasurements) {
                lineRenderer.drawSegment(viewMatrix, projMatrix, a.x, a.y, a.z, b.x, b.y, b.z)
                lineRenderer.drawPoint(viewMatrix, projMatrix, a.x, a.y, a.z)
                lineRenderer.drawPoint(viewMatrix, projMatrix, b.x, b.y, b.z)
            }
            pendingPoint?.let { p ->
                lineRenderer.drawPoint(viewMatrix, projMatrix, p.x, p.y, p.z)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error on draw frame", t)
        }
    }

    private fun handleTap(frame: Frame, camera: com.google.ar.core.Camera) {
        val tap = tapQueue.poll() ?: return
        if (camera.trackingState != TrackingState.TRACKING) return

        val hits = frame.hitTest(tap)
        for (hit in hits) {
            val trackable = hit.trackable
            val valid = (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) ||
                (trackable is Point && trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) ||
                (trackable is DepthPoint)
            if (valid) {
                val pose = hit.hitPose
                registerPoint(Point3D(pose.tx(), pose.ty(), pose.tz()))
                break
            }
        }
    }

    private fun registerPoint(p: Point3D) {
        val first = pendingPoint
        if (first == null) {
            pendingPoint = p
            runOnUiThread { distanceText.text = getString(R.string.tap_second_point) }
        } else {
            completedMeasurements.add(first to p)
            lastDistanceMeters = distanceBetween(first, p)
            pendingPoint = null
            runOnUiThread { updateDistanceLabel() }
        }
    }

    private fun distanceBetween(a: Point3D, b: Point3D): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun updateDistanceLabel() {
        val meters = lastDistanceMeters
        if (meters == null) {
            distanceText.text = getString(R.string.tap_to_start)
            return
        }
        distanceText.text = if (useFeet) {
            val totalFeet = meters * 3.28084f
            val feet = totalFeet.toInt()
            val inches = (totalFeet - feet) * 12f
            String.format("%d ft %.1f in", feet, inches)
        } else {
            String.format("%.2f m", meters)
        }
    }

    companion object {
        private const val TAG = "ARMeasure"
        private const val REQUEST_CAMERA = 1001
    }
}
