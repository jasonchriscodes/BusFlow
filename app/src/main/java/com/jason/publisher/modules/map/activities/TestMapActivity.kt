package com.jason.publisher.modules.map.activities

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.jason.publisher.R
import com.jason.publisher.modules.map.utils.calculateBearing
import com.jason.publisher.modules.map.utils.calculateDistance
import java.util.Calendar

class TestMapActivity : MapActivity() {
    private val simulationHandler = Handler(Looper.getMainLooper())
    private var simulationRunnable: Runnable? = null
    private var currentRouteDistance = 0.0
    private var lastSimulationMillis = 0L
    private var simulatedElapsedMillis = 0L
    private var scheduleStartMillis = 0L
    private var simulationSpeedText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        findViewById<View>(R.id.hiddenUIContainer)?.alpha = 1f
        scheduleStartMillis = resolveScheduleStartMillis()
        viewModel.speed = 0f
        applySimulationSpeed()
        addSimulationControls()
    }

    override fun getScheduleStatusNowMillis(): Long {
        return if (scheduleStartMillis > 0L) {
            scheduleStartMillis + simulatedElapsedMillis
        } else {
            super.getScheduleStatusNowMillis()
        }
    }

    override fun getDisplayNowMillis(): Long = getScheduleStatusNowMillis()

    private fun addSimulationControls() {
        val container = findViewById<LinearLayout>(R.id.scheduleStatusContainer) ?: return
        if (container.findViewWithTag<View>("test_sim_controls") != null) return

        val controls = LinearLayout(this).apply {
            tag = "test_sim_controls"
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
            }
        }

        fun addButton(text: String, onClick: () -> Unit) {
            controls.addView(Button(this).apply {
                this.text = text
                isAllCaps = false
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 4
                }
            })
        }

        addButton("Slow") {
            viewModel.speed = (viewModel.speed - 5f).coerceAtLeast(20f)
            applySimulationSpeed()
        }
        addButton("Speed") {
            viewModel.speed = if (viewModel.speed < 20f) 20f else (viewModel.speed + 5f).coerceAtMost(50f)
            applySimulationSpeed()
        }

        container.addView(controls)
        simulationSpeedText = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            gravity = android.view.Gravity.CENTER
        }
        container.addView(simulationSpeedText)
        updateSimulationSpeedText()
    }

    private fun updateSimulationSpeedText() {
        simulationSpeedText?.text = "Current speed: ${viewModel.speed.toInt()} km/h"
    }

    private fun applySimulationSpeed() {
        viewModel.updateSpeed(viewModel.speed)
        viewModel.smoothedSpeed = viewModel.speed
        viewModel.updateSpeedText()
        updateSimulationSpeedText()
        try {
            scheduleStatusManager.checkScheduleStatus()
        } catch (e: UninitializedPropertyAccessException) {
            Log.d("TestMapActivity", "Schedule status manager not ready yet")
        }
    }

    override fun startLocationUpdate() {
        currentRouteDistance = 0.0
        simulatedElapsedMillis = 0L
        lastSimulationMillis = System.currentTimeMillis()

        if (viewModel.route.isNotEmpty()) {
            val first = viewModel.route.first()
            viewModel.latitude = first.latitude ?: 0.0
            viewModel.longitude = first.longitude ?: 0.0
            mapController.updateBusMarkerPosition(viewModel.latitude, viewModel.longitude, 0f)
        }

        viewModel.speed = 20f
        applySimulationSpeed()
        startSimulatedLocationUpdates()
        Toast.makeText(this, "Test route simulation started", Toast.LENGTH_SHORT).show()
    }

    private fun startSimulatedLocationUpdates() {
        simulationRunnable?.let { simulationHandler.removeCallbacks(it) }
        simulationRunnable = object : Runnable {
            override fun run() {
                try {
                    simulateMovementTick()
                    updateUIElementsThrottled()
                    viewModel.updateClientAttributes()
                } catch (e: Exception) {
                    Log.e("TestMapActivity", "Simulation update failed: ${e.message}", e)
                }
                simulationHandler.postDelayed(this, 100L)
            }
        }
        simulationHandler.post(simulationRunnable!!)
    }

    private fun simulateMovementTick() {
        val now = System.currentTimeMillis()
        val elapsedMillis = (now - lastSimulationMillis).coerceAtLeast(0L)
        val elapsedSeconds = elapsedMillis / 1000.0
        lastSimulationMillis = now
        simulatedElapsedMillis += elapsedMillis
        viewModel.updateSpeed(viewModel.speed)
        viewModel.smoothedSpeed = viewModel.speed
        if (viewModel.route.isEmpty() || viewModel.speed <= 0f) return

        val speedMps = (viewModel.speed * viewModel.simulationSpeedFactor) / 3.6
        currentRouteDistance += speedMps * elapsedSeconds

        var traveled = 0.0
        for (i in 0 until viewModel.route.size - 1) {
            val p1 = viewModel.route[i]
            val p2 = viewModel.route[i + 1]
            val p1Lat = p1.latitude ?: continue
            val p1Lon = p1.longitude ?: continue
            val p2Lat = p2.latitude ?: continue
            val p2Lon = p2.longitude ?: continue
            val segmentDist = calculateDistance(p1Lat, p1Lon, p2Lat, p2Lon)

            if (traveled + segmentDist >= currentRouteDistance) {
                val fraction = ((currentRouteDistance - traveled) / segmentDist).coerceIn(0.0, 1.0)
                val lat = p1Lat + (p2Lat - p1Lat) * fraction
                val lon = p1Lon + (p2Lon - p1Lon) * fraction

                viewModel.bearing = calculateBearing(viewModel.latitude, viewModel.longitude, lat, lon)
                viewModel.latitude = lat
                viewModel.longitude = lon
                mapController.updateBusMarkerPosition(lat, lon, viewModel.bearing)
                return
            }
            traveled += segmentDist
        }

        val last = viewModel.route.last()
        viewModel.latitude = last.latitude ?: viewModel.latitude
        viewModel.longitude = last.longitude ?: viewModel.longitude
        mapController.updateBusMarkerPosition(viewModel.latitude, viewModel.longitude, viewModel.bearing)
    }

    private fun resolveScheduleStartMillis(): Long {
        val first = viewModel.scheduleList.firstOrNull() ?: return 0L
        val parts = first.startTime.split(":")
        if (parts.size != 2) return 0L
        val hour = parts[0].toIntOrNull() ?: return 0L
        val minute = parts[1].toIntOrNull() ?: return 0L
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    override fun onDestroy() {
        simulationHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
