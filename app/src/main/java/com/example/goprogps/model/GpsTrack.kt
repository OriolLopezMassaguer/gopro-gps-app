package com.example.goprogps.model

import kotlin.math.*

data class GpsTrack(val points: List<GpsPoint>) {

    val totalDistanceMeters: Double get() {
        var dist = 0.0
        for (i in 1 until points.size) dist += haversine(points[i - 1], points[i])
        return dist
    }

    val maxSpeedMs: Double get() = points.maxOfOrNull { it.speed2d } ?: 0.0
    val avgSpeedMs: Double get() = if (points.isEmpty()) 0.0 else points.sumOf { it.speed2d } / points.size
    val maxAltitude: Double get() = points.maxOfOrNull { it.altitude } ?: 0.0
    val minAltitude: Double get() = points.minOfOrNull { it.altitude } ?: 0.0

    val altitudeGain: Double get() {
        var gain = 0.0
        for (i in 1 until points.size) {
            val diff = points[i].altitude - points[i - 1].altitude
            if (diff > 0) gain += diff
        }
        return gain
    }

    val durationMs: Long get() =
        if (points.size < 2) 0L else points.last().timestampMs - points.first().timestampMs

    private fun haversine(a: GpsPoint, b: GpsPoint): Double {
        val r = 6371000.0
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val x = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(x), sqrt(1 - x))
    }
}
