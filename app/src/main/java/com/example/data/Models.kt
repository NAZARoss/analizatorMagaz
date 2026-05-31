package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "day_records")
data class DayRecord(
    @PrimaryKey
    val date: String, // format "yyyy-MM-dd"
    val dayOfWeek: Int, // 1 = Monday, ..., 7 = Sunday
    val cityName: String,
    val highTemp: Int,
    val lowTemp: Int,
    // Comma-separated hourly values for 24 hours (0..23)
    val hourlyTemperatures: String, // e.g. "12,14,15,15,14..." (24 items)
    val hourlyConditions: String,   // WMO weather codes e.g. "0,1,2,51,3..." (24 items)
    val hadTrips: Boolean = false,
    val isManualEdit: Boolean = false
)

@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val date: String, // "yyyy-MM-dd"
    val startTime: Long, // Epoch timestamp in milliseconds
    val endTime: Long?, // Epoch timestamp in milliseconds. Null if still out.
    val isManual: Boolean = false,
    val startWeatherTemp: Int = 0,
    val startWeatherCondition: String = ""
) {
    val durationMinutes: Long
        get() {
            if (endTime == null) return 0L
            return (endTime - startTime) / (1000 * 60)
        }
}
