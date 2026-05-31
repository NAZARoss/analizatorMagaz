package com.example.data

import android.content.Context
import android.util.Log
import com.example.network.WeatherApiClient
import com.example.network.WeatherHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class GroceryRepository(private val groceryDao: GroceryDao) {

    val allDayRecords: Flow<List<DayRecord>> = groceryDao.getAllDayRecordsFlow()
    val allTrips: Flow<List<Trip>> = groceryDao.getAllTripsFlow()
    val activeTrip: Flow<Trip?> = groceryDao.getActiveTripFlow()

    fun getTripsForDayFlow(date: String): Flow<List<Trip>> = groceryDao.getTripsForDayFlow(date)
    fun getDayRecordFlow(date: String): Flow<DayRecord?> = groceryDao.getDayRecordFlow(date)

    suspend fun getTripsForDay(date: String): List<Trip> = withContext(Dispatchers.IO) {
        groceryDao.getTripsForDay(date)
    }

    suspend fun insertDayRecord(record: DayRecord) = withContext(Dispatchers.IO) {
        groceryDao.insertDayRecord(record)
    }

    suspend fun insertTrip(trip: Trip): Long = withContext(Dispatchers.IO) {
        val id = groceryDao.insertTrip(trip)
        updateDayHasTrips(trip.date)
        id
    }

    suspend fun updateTrip(trip: Trip) = withContext(Dispatchers.IO) {
        groceryDao.updateTrip(trip)
        updateDayHasTrips(trip.date)
    }

    suspend fun deleteTrip(trip: Trip) = withContext(Dispatchers.IO) {
        groceryDao.deleteTrip(trip)
        updateDayHasTrips(trip.date)
    }

    suspend fun getActiveTrip(): Trip? = withContext(Dispatchers.IO) {
        groceryDao.getActiveTrip()
    }

    private suspend fun updateDayHasTrips(date: String) {
        val trips = groceryDao.getTripsForDay(date)
        val dayRecord = groceryDao.getDayRecord(date)
        if (dayRecord != null) {
            val updated = dayRecord.copy(hadTrips = trips.isNotEmpty())
            groceryDao.insertDayRecord(updated)
        } else {
            // Create default day record if not existing
            val parts = date.split("-")
            val dayOfWeek = if (parts.size == 3) {
                val cal = Calendar.getInstance()
                cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                var dow = cal.get(Calendar.DAY_OF_WEEK) - 1 // 1=Mon, ..., 7=Sun
                if (dow <= 0) dow = 7
                dow
            } else 1

            val dummyRecord = DayRecord(
                date = date,
                dayOfWeek = dayOfWeek,
                cityName = "Москва",
                highTemp = 18,
                lowTemp = 10,
                hourlyTemperatures = List(24) { 15 }.joinToString(","),
                hourlyConditions = List(24) { 0 }.joinToString(","),
                hadTrips = trips.isNotEmpty()
            )
            groceryDao.insertDayRecord(dummyRecord)
        }
    }

    /**
     * Prepopulates a specific day record if it does not exist, fetching weather if connected,
     * otherwise generating a highly realistic weather profile.
     */
    suspend fun ensureDayRecordExists(date: String, dayOfWeek: Int, cityName: String, latitude: Double, longitude: Double) = withContext(Dispatchers.IO) {
        val existing = groceryDao.getDayRecord(date)
        if (existing == null) {
            try {
                Log.d("GroceryRepository", "Fetching forecast from Open-Meteo for $cityName on $date")
                // Query general forecast
                val forecast = WeatherApiClient.forecastService.getForecast(latitude, longitude)
                val hourly = forecast.hourly
                if (hourly != null && hourly.temperatures.size >= 24) {
                    // Extract corresponding 24 hours of forecast
                    // Usually Open-Meteo returns hourly starting from today's midnight.
                    // Let's take the first 24 slots (today)
                    val tempsToday = hourly.temperatures.take(24).map { it.toInt() }
                    val wmoToday = hourly.weatherCodes.take(24)

                    val record = DayRecord(
                        date = date,
                        dayOfWeek = dayOfWeek,
                        cityName = cityName,
                        highTemp = tempsToday.maxOrNull() ?: 20,
                        lowTemp = tempsToday.minOrNull() ?: 10,
                        hourlyTemperatures = tempsToday.joinToString(","),
                        hourlyConditions = wmoToday.joinToString(","),
                        hadTrips = groceryDao.getTripsForDay(date).isNotEmpty()
                    )
                    groceryDao.insertDayRecord(record)
                    Log.d("GroceryRepository", "Saved real weather forecast for $date in database")
                    return@withContext
                }
            } catch (e: Exception) {
                Log.e("GroceryRepository", "Weather service failed, falling back to local simulation: ${e.message}")
            }

            // Fallback generation
            val generated = generateSimulatedWeather(date, dayOfWeek, cityName)
            groceryDao.insertDayRecord(generated)
        }
    }

    /**
     * Generates simulated weather profiles so prediction is fully offline-capable.
     */
    private suspend fun generateSimulatedWeather(date: String, dayOfWeek: Int, cityName: String): DayRecord {
        // Deterministic random based on date string so it remains stable for this date
        val seed = date.hashCode().toLong()
        val random = Random(seed)

        // General conditions: sunny, rainy, cloudy based on day of week or random
        val weatherType = random.nextInt(4) // 0=Sunny, 1=Cloudy, 2=Rainy, 3=Mixed
        val baseTemp = 12 + random.nextInt(15) // 12 .. 27

        val temps = mutableListOf<Int>()
        val wmoCodes = mutableListOf<Int>()

        for (h in 0..23) {
            // Temperature cycle: coldest at 4-5 AM, hottest at 3-4 PM (15:00)
            val rad = Math.PI * (h - 15) / 12.0
            val hourTemp = (baseTemp + 6 * Math.cos(rad) + random.nextInt(3) - 1).toInt()
            temps.add(hourTemp)

            // Weather code cycle: e.g. Rainy in the afternoon, dry in the evening!
            val code = when (weatherType) {
                0 -> 0 // always sunny
                1 -> if (random.nextInt(3) == 0) 2 else 3 // cloudy/overcast
                2 -> {
                    // Rain between 12:00 and 17:00, dry afterwards
                    if (h in 12..17) {
                        61 // slight rain
                    } else if (h > 17) {
                        1 // sunny clearance!
                    } else {
                        3 // overcast
                    }
                }
                else -> {
                    // Mixed
                    if (h in 8..12) 61 else 2 // Morning rain, afternoon cloud
                }
            }
            wmoCodes.add(code)
        }

        val trips = groceryDao.getTripsForDay(date)

        return DayRecord(
            date = date,
            dayOfWeek = dayOfWeek,
            cityName = cityName,
            highTemp = temps.maxOrNull() ?: 20,
            lowTemp = temps.minOrNull() ?: 10,
            hourlyTemperatures = temps.joinToString(","),
            hourlyConditions = wmoCodes.joinToString(","),
            hadTrips = trips.isNotEmpty()
        )
    }
}
