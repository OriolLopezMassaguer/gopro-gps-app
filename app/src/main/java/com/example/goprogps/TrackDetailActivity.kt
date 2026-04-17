package com.example.goprogps

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.goprogps.databinding.ActivityTrackDetailBinding
import com.example.goprogps.export.GpxExporter
import com.example.goprogps.model.GpsPoint
import com.example.goprogps.model.GpsTrack
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration

import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.concurrent.TimeUnit

class TrackDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_NAME = "extra_name"
    }

    private lateinit var binding: ActivityTrackDetailBinding
    private val viewModel: TrackViewModel by viewModels()
    private var currentTrack: GpsTrack? = null
    private var videoName = "GoPro Track"

    private val createDocLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri: Uri? ->
        uri?.let { exportGpxTo(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        binding = ActivityTrackDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        videoName = intent.getStringExtra(EXTRA_NAME) ?: "GoPro Track"
        title = videoName

        setupMap()
        setupCharts()

        binding.btnExportGpx.setOnClickListener {
            val safeName = videoName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            createDocLauncher.launch("$safeName.gpx")
        }

        val uriString = intent.getStringExtra(EXTRA_URI) ?: run { finish(); return }
        viewModel.loadTrack(contentResolver, Uri.parse(uriString))

        lifecycleScope.launch {
            viewModel.state.collect { state ->
                when (state) {
                    is TrackViewModel.State.Loading -> showLoading(true)
                    is TrackViewModel.State.Success -> {
                        showLoading(false)
                        currentTrack = state.track
                        displayTrack(state.track)
                    }
                    is TrackViewModel.State.Error -> {
                        showLoading(false)
                        binding.tvError.text = state.message
                        binding.tvError.visibility = View.VISIBLE
                    }
                    else -> {}
                }
            }
        }
    }

    private fun setupMap() {
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(12.0)
    }

    private fun setupCharts() {
        listOf(binding.chartSpeed, binding.chartAltitude).forEach { chart ->
            chart.description.isEnabled = false
            chart.legend.isEnabled = false
            chart.setTouchEnabled(false)
            chart.xAxis.position = XAxis.XAxisPosition.BOTTOM
            chart.xAxis.setDrawGridLines(false)
            chart.axisRight.isEnabled = false
        }
        binding.chartSpeed.xAxis.valueFormatter = timeFormatter
        binding.chartAltitude.xAxis.valueFormatter = timeFormatter
    }

    private val timeFormatter = object : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            val totalSec = value.toLong()
            val m = totalSec / 60
            val s = totalSec % 60
            return "%d:%02d".format(m, s)
        }
    }

    private fun displayTrack(track: GpsTrack) {
        drawRoute(track.points)
        updateStats(track)
        updateSpeedChart(track.points)
        updateAltitudeChart(track.points)
        binding.btnExportGpx.isEnabled = true
    }

    private fun drawRoute(points: List<GpsPoint>) {
        val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
        if (geoPoints.isEmpty()) return

        val polyline = Polyline().apply {
            setPoints(geoPoints)
            outlinePaint.color = 0xFF2979FF.toInt()
            outlinePaint.strokeWidth = 8f
        }
        binding.mapView.overlays.clear()
        binding.mapView.overlays.add(polyline)

        addMarker(geoPoints.first(), "Start")
        addMarker(geoPoints.last(), "End")

        val box = BoundingBox.fromGeoPointsSafe(geoPoints)
        binding.mapView.post {
            binding.mapView.zoomToBoundingBox(box.increaseByScale(1.1f), false)
        }
        binding.mapView.invalidate()
    }

    private fun addMarker(point: GeoPoint, title: String) {
        Marker(binding.mapView).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            this.title = title
            binding.mapView.overlays.add(this)
        }
    }

    private fun updateStats(track: GpsTrack) {
        val distKm = track.totalDistanceMeters / 1000.0
        val maxKmh = track.maxSpeedMs * 3.6
        val avgKmh = track.avgSpeedMs * 3.6
        val durMin = TimeUnit.MILLISECONDS.toSeconds(track.durationMs)

        binding.tvDistance.text = "%.2f km".format(distKm)
        binding.tvMaxSpeed.text = "%.0f km/h".format(maxKmh)
        binding.tvAvgSpeed.text = "%.0f km/h".format(avgKmh)
        binding.tvMaxAlt.text = "%.0f m".format(track.maxAltitude)
        binding.tvAltGain.text = "+%.0f m".format(track.altitudeGain)
        binding.tvDuration.text = "%d:%02d".format(durMin / 60, durMin % 60)
    }

    private fun updateSpeedChart(points: List<GpsPoint>) {
        val entries = points.mapIndexed { _, p ->
            Entry((p.timestampMs / 1000f), (p.speed2d * 3.6).toFloat())
        }
        val ds = LineDataSet(entries, "Speed km/h").apply {
            color = 0xFF2979FF.toInt()
            setDrawCircles(false)
            lineWidth = 1.5f
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
        binding.chartSpeed.data = LineData(ds)
        binding.chartSpeed.invalidate()
    }

    private fun updateAltitudeChart(points: List<GpsPoint>) {
        val entries = points.map { p ->
            Entry((p.timestampMs / 1000f), p.altitude.toFloat())
        }
        val ds = LineDataSet(entries, "Altitude m").apply {
            color = 0xFF43A047.toInt()
            setDrawCircles(false)
            lineWidth = 1.5f
            setDrawValues(false)
            setDrawFilled(true)
            fillAlpha = 60
            fillColor = 0xFF43A047.toInt()
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
        binding.chartAltitude.data = LineData(ds)
        binding.chartAltitude.invalidate()
    }

    private fun exportGpxTo(uri: Uri) {
        val track = currentTrack ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    GpxExporter.export(track, out, videoName)
                }
            }
            Toast.makeText(this@TrackDetailActivity, "GPX exported", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.contentLayout.visibility = if (loading) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }
}
