package com.example.goprogps.model

data class GpsPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val speed2d: Double,
    val speed3d: Double,
    val timestampMs: Long
)
