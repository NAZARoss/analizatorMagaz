package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.network.City
import com.example.network.WeatherApiClient
import com.example.network.WeatherHelper
import com.example.utils.ReminderReceiver
import com.example.utils.TrackerService
import com.example.utils.VibrationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class GroceryViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val db = AppDatabase.getDatabase(context)
    private val repository = GroceryRepository(db.groceryDao())

    // App states
    val allDayRecords = repository.allDayRecords.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )
    val allTrips = repository.allTrips.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )
    val activeTrip = repository.activeTrip.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null
    )

    private val sharedPrefs = context.getSharedPreferences("grocery_prefs", Context.MODE_PRIVATE)

    // Current city selection
    private val _selectedCity = MutableStateFlow(loadSavedCity())
    val selectedCity: StateFlow<City> = _selectedCity.asStateFlow()

    private fun loadSavedCity(): City {
        val name = sharedPrefs.getString("city_name", null)
        val lat = sharedPrefs.getFloat("city_lat", -999f)
        val lon = sharedPrefs.getFloat("city_lon", -999f)
        val region = sharedPrefs.getString("city_region", "") ?: ""
        
        return if (name != null && lat != -999f && lon != -999f) {
            City(name, lat.toDouble(), lon.toDouble(), region)
        } else {
            WeatherHelper.DEFAULT_CITIES[0]
        }
    }

    // City search results
    private val _citySearchResults = MutableStateFlow<List<City>>(emptyList())
    val citySearchResults: StateFlow<List<City>> = _citySearchResults.asStateFlow()

    private val _isSearchingCities = MutableStateFlow(false)
    val isSearchingCities: StateFlow<Boolean> = _isSearchingCities.asStateFlow()

    // Predictions list
    private val _predictions = MutableStateFlow<List<Prediction>>(emptyList())
    val predictions: StateFlow<List<Prediction>> = _predictions.asStateFlow()

    // Active trip duration calculation
    private val _activeTripDurationStr = MutableStateFlow("00:00")
    val activeTripDurationStr: StateFlow<String> = _activeTripDurationStr.asStateFlow()

    // Navigation and tabs helper
    private val _currentTab = MutableStateFlow(0) // 0 = Main, 1 = History, 2 = Settings
    val currentTab: StateFlow<Int> = _currentTab.asStateFlow()

    init {
        // Schedule dynamic reminder
        ReminderReceiver.scheduleDailyReminder(context)

        // Invalidate lists and refresh today's forecast
        viewModelScope.launch(Dispatchers.IO) {
            // Note: Pre-population of historic data is deleted to keep the database completely clean and empty (zeros) on first load
            refreshCurrentDayForecast(forceRefresh = false)
            startActiveTripTimer()
            startPeriodicWeatherUpdater()
        }
    }

    private fun startPeriodicWeatherUpdater() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(30 * 60 * 1000) // 30 minutes
                Log.d("GroceryViewModel", "Periodic 30-minute weather update triggered.")
                refreshCurrentDayForecast(forceRefresh = true)
            }
        }
    }

    fun setTab(tab: Int) {
        _currentTab.value = tab
    }

    private fun startActiveTripTimer() {
        viewModelScope.launch {
            while (true) {
                val trip = repository.getActiveTrip()
                if (trip != null) {
                    val durationMs = System.currentTimeMillis() - trip.startTime
                    val minutes = (durationMs / (1000 * 60)) % 60
                    val hours = (durationMs / (1000 * 60 * 60))
                    val seconds = (durationMs / 1000) % 60
                    _activeTripDurationStr.value = if (hours > 0) {
                        String.format("%02d:%02d:%02d", hours, minutes, seconds)
                    } else {
                        String.format("%02d:%02d", minutes, seconds)
                    }
                } else {
                    _activeTripDurationStr.value = "00:00"
                }
                delay(1000)
            }
        }
    }

    fun changeCity(city: City) {
        viewModelScope.launch {
            _selectedCity.value = city
            sharedPrefs.edit().apply {
                putString("city_name", city.name)
                putFloat("city_lat", city.latitude.toFloat())
                putFloat("city_lon", city.longitude.toFloat())
                putString("city_region", city.region)
                apply()
            }
            refreshCurrentDayForecast(forceRefresh = true)
        }
    }

    fun searchCities(query: String) {
        if (query.trim().length < 2) {
            _citySearchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _isSearchingCities.value = true
            try {
                val response = WeatherApiClient.geocodingService.searchCity(query)
                val results = response.results
                if (results != null) {
                    _citySearchResults.value = results.map {
                        City(
                            name = it.name,
                            latitude = it.latitude,
                            longitude = it.longitude,
                            region = it.admin1 ?: it.country ?: ""
                        )
                    }
                } else {
                    _citySearchResults.value = emptyList()
                }
            } catch (e: Exception) {
                Log.e("GroceryViewModel", "City search error: ${e.message}")
            } finally {
                _isSearchingCities.value = false
            }
        }
    }

    fun refreshCurrentDayForecast(forceRefresh: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val city = _selectedCity.value
            val todayDate = getFormattedDate(0)
            val tomorrowDate = getFormattedDate(1)

            // Ensure forecasts exist
            repository.ensureDayRecordExists(todayDate, getDayOfWeekInt(0), city.name, city.latitude, city.longitude, forceRefresh)
            repository.ensureDayRecordExists(tomorrowDate, getDayOfWeekInt(1), city.name, city.latitude, city.longitude, forceRefresh)

            updatePredictions()
        }
    }

    /**
     * Triggers predictions dynamically based on the current hour.
     * Before 21:00 -> predicts for today.
     * After 21:00 -> predicts for tomorrow.
     */
    fun updatePredictions() {
        viewModelScope.launch(Dispatchers.IO) {
            val city = _selectedCity.value
            val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            
            // Check if before or after 21:00
            val targetDate = if (currentHour < 21) getFormattedDate(0) else getFormattedDate(1)
            val targetDayOfWeek = if (currentHour < 21) getDayOfWeekInt(0) else getDayOfWeekInt(1)

            // Load forecasted day record
            val dayRecord = db.groceryDao().getDayRecord(targetDate)
            val allDays = db.groceryDao().getAllDayRecords()
            val allTrips = db.groceryDao().getAllTrips()

            if (dayRecord != null) {
                val temps = PredictionEngine.parseCommaString(dayRecord.hourlyTemperatures)
                val wmos = PredictionEngine.parseCommaString(dayRecord.hourlyConditions)

                val results = PredictionEngine.predict(
                    targetDate = targetDate,
                    targetDayOfWeek = targetDayOfWeek,
                    targetDayHourlyTemp = temps,
                    targetDayHourlyWmo = wmos,
                    allDays = allDays,
                    allTrips = allTrips
                )
                _predictions.value = results
            } else {
                Log.e("GroceryViewModel", "No record found for date $targetDate in updatePredictions")
            }
        }
    }

    // Tracking
    fun startTrip() {
        viewModelScope.launch(Dispatchers.IO) {
            val todayDate = getFormattedDate(0)
            val currentTemp = getCurrentTemperature()
            val currentCondition = getCurrentConditionDescription()

            val newTrip = Trip(
                date = todayDate,
                startTime = System.currentTimeMillis(),
                endTime = null,
                isManual = false,
                startWeatherTemp = currentTemp,
                startWeatherCondition = currentCondition
            )

            repository.insertTrip(newTrip)

            // Start tracker service for foreground execution
            val serviceIntent = Intent(context, TrackerService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            updatePredictions()
        }
    }

    fun stopTrip() {
        viewModelScope.launch(Dispatchers.IO) {
            val active = repository.getActiveTrip()
            if (active != null) {
                val stopTime = System.currentTimeMillis()
                val updated = active.copy(endTime = stopTime)
                repository.updateTrip(updated)

                // Stop tracker service
                val serviceIntent = Intent(context, TrackerService::class.java)
                context.stopService(serviceIntent)

                updatePredictions()
            }
        }
    }

    // Manual additions and edits (History section)
    fun addManualTrip(date: String, startHour: Int, startMin: Int, endHour: Int, endMin: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val calStart = Calendar.getInstance()
            val parts = date.split("-")
            if (parts.size == 3) {
                calStart.set(Calendar.YEAR, parts[0].toInt())
                calStart.set(Calendar.MONTH, parts[1].toInt() - 1)
                calStart.set(Calendar.DAY_OF_MONTH, parts[2].toInt())
            }
            calStart.set(Calendar.HOUR_OF_DAY, startHour)
            calStart.set(Calendar.MINUTE, startMin)
            calStart.set(Calendar.SECOND, 0)

            val calEnd = Calendar.getInstance()
            if (parts.size == 3) {
                calEnd.set(Calendar.YEAR, parts[0].toInt())
                calEnd.set(Calendar.MONTH, parts[1].toInt() - 1)
                calEnd.set(Calendar.DAY_OF_MONTH, parts[2].toInt())
            }
            calEnd.set(Calendar.HOUR_OF_DAY, endHour)
            calEnd.set(Calendar.MINUTE, endMin)
            calEnd.set(Calendar.SECOND, 0)

            // Ensure chronological order
            if (calEnd.timeInMillis < calStart.timeInMillis) {
                calEnd.add(Calendar.DAY_OF_MONTH, 1)
            }

            val dayRec = db.groceryDao().getDayRecord(date)
            val currentTemp = dayRec?.highTemp ?: 17
            val currentCondition = if (dayRec != null) {
                val conds = PredictionEngine.parseCommaString(dayRec.hourlyConditions)
                val cond = conds.getOrNull(startHour) ?: 0
                WeatherHelper.getWeatherDescription(cond)
            } else "Преимущественно ясно"

            val trip = Trip(
                date = date,
                startTime = calStart.timeInMillis,
                endTime = calEnd.timeInMillis,
                isManual = true,
                startWeatherTemp = currentTemp,
                startWeatherCondition = currentCondition
            )

            repository.insertTrip(trip)
            updatePredictions()
        }
    }

    fun editTripTimes(trip: Trip, date: String, startHour: Int, startMin: Int, endHour: Int, endMin: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val calStart = Calendar.getInstance()
            val parts = date.split("-")
            if (parts.size == 3) {
                calStart.set(Calendar.YEAR, parts[0].toInt())
                calStart.set(Calendar.MONTH, parts[1].toInt() - 1)
                calStart.set(Calendar.DAY_OF_MONTH, parts[2].toInt())
            }
            calStart.set(Calendar.HOUR_OF_DAY, startHour)
            calStart.set(Calendar.MINUTE, startMin)
            calStart.set(Calendar.SECOND, 0)

            val calEnd = Calendar.getInstance()
            if (parts.size == 3) {
                calEnd.set(Calendar.YEAR, parts[0].toInt())
                calEnd.set(Calendar.MONTH, parts[1].toInt() - 1)
                calEnd.set(Calendar.DAY_OF_MONTH, parts[2].toInt())
            }
            calEnd.set(Calendar.HOUR_OF_DAY, endHour)
            calEnd.set(Calendar.MINUTE, endMin)
            calEnd.set(Calendar.SECOND, 0)

            if (calEnd.timeInMillis < calStart.timeInMillis) {
                calEnd.add(Calendar.DAY_OF_MONTH, 1)
            }

            val updated = trip.copy(
                date = date,
                startTime = calStart.timeInMillis,
                endTime = calEnd.timeInMillis
            )

            repository.updateTrip(updated)
            updatePredictions()
        }
    }

    fun deleteTrip(trip: Trip) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteTrip(trip)
            updatePredictions()
        }
    }

    fun testVibration() {
        VibrationHelper.vibrateTripleShort(context)
    }

    fun triggerTestNotification() {
        ReminderReceiver.fireImmediateNotification(context)
    }

    // Helper functions
    private suspend fun getCurrentTemperature(): Int {
        val today = getFormattedDate(0)
        val rec = db.groceryDao().getDayRecord(today) ?: return 18
        val temps = PredictionEngine.parseCommaString(rec.hourlyTemperatures)
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return temps.getOrNull(h) ?: rec.highTemp
    }

    private suspend fun getCurrentConditionDescription(): String {
        val today = getFormattedDate(0)
        val rec = db.groceryDao().getDayRecord(today) ?: return "Ясно"
        val conds = PredictionEngine.parseCommaString(rec.hourlyConditions)
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val code = conds.getOrNull(h) ?: 0
        return WeatherHelper.getWeatherDescription(code)
    }

    fun getFormattedDate(offsetDays: Int, customLocale: Locale = Locale("ru")): String {
        val format = SimpleDateFormat("yyyy-MM-dd", customLocale)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, offsetDays)
        return format.format(cal.time)
    }

    fun getDayOfWeekLabel(offsetDays: Int, customLocale: Locale = Locale("ru")): String {
        val format = SimpleDateFormat("EEEE", customLocale)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, offsetDays)
        return format.format(cal.time).replaceFirstChar { it.uppercase() }
    }

    fun getDayOfWeekInt(offsetDays: Int): Int {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, offsetDays)
        var dow = cal.get(Calendar.DAY_OF_WEEK) - 1 // convert: 1=Mon, ..., 7=Sun
        if (dow <= 0) dow = 7
        return dow
    }

    /**
     * Prepopulates a robust 14-day history to demonstrate the predictive capabilities.
     * Contains the exact scenario where the user stayed indoors on rainy Monday afternoons,
     * but stepped out to shop on dry Monday evenings once rain cleared up!
     */
    private suspend fun prepopulateHistoryIfEmpty() {
        val existingDaysCount = db.groceryDao().getAllDayRecords().size
        if (existingDaysCount > 0) return

        Log.d("GroceryViewModel", "Prepopulating grocery tracker database with adaptive statistical scenario...")

        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todayCal = Calendar.getInstance()

        // Generate 14 previous days (from 14 days ago to yesterday)
        for (offset in -14..-1) {
            val cal = Calendar.getInstance().apply {
                timeInMillis = todayCal.timeInMillis
                add(Calendar.DAY_OF_YEAR, offset)
            }
            val dateStr = format.format(cal.time)
            var dow = cal.get(Calendar.DAY_OF_WEEK) - 1
            if (dow <= 0) dow = 7

            val temps = mutableListOf<Int>()
            val wmos = mutableListOf<Int>()

            // Monday transition scenario: "10 times in Monday rain they didn't go. But 5 times evening dry, they went."
            val isMonday = (dow == 1) // 1 = Monday

            for (h in 0..23) {
                val rad = Math.PI * (h - 15) / 12.0
                val hourTemp = (18 + 5 * Math.cos(rad) + (offset % 3)).toInt()
                temps.add(hourTemp)

                val code = if (isMonday) {
                    // Monday weather: Rain in afternoon (12..16), Dry evening (17..21)
                    if (h in 12..16) 61 else if (h in 17..22) 1 else 3
                } else {
                    // Regular random weather
                    if (h in 13..15 && offset % 4 == 0) 61 else if (h in 18..20 && offset % 3 == 0) 2 else 0
                }
                wmos.add(code)
            }

            val maxTemp = temps.maxOrNull() ?: 22
            val minTemp = temps.minOrNull() ?: 12

            val record = DayRecord(
                date = dateStr,
                dayOfWeek = dow,
                cityName = "Москва",
                highTemp = maxTemp,
                lowTemp = minTemp,
                hourlyTemperatures = temps.joinToString(","),
                hourlyConditions = wmos.joinToString(","),
                hadTrips = false // adjusted below
            )

            db.groceryDao().insertDayRecord(record)

            // Add trips based on the weather/weekday scenario
            if (isMonday) {
                // User went exactly in the evening (dry) around 18:30 - 19:15, and NEVER during afternoon rain (12..16)!
                if (offset % 2 == 0) { // exactly 5 times out of the last mondays
                    val tripStart = Calendar.getInstance().apply {
                        timeInMillis = cal.timeInMillis
                        set(Calendar.HOUR_OF_DAY, 18)
                        set(Calendar.MINUTE, 35)
                        set(Calendar.SECOND, 0)
                    }
                    val tripEnd = Calendar.getInstance().apply {
                        timeInMillis = cal.timeInMillis
                        set(Calendar.HOUR_OF_DAY, 19)
                        set(Calendar.MINUTE, 20)
                        set(Calendar.SECOND, 0)
                    }
                    db.groceryDao().insertTrip(
                        Trip(
                            date = dateStr,
                            startTime = tripStart.timeInMillis,
                            endTime = tripEnd.timeInMillis,
                            isManual = false,
                            startWeatherTemp = temps[18],
                            startWeatherCondition = WeatherHelper.getWeatherDescription(wmos[18])
                        )
                    )
                }
            } else {
                // General day trips: 70% chance of grocery run at lunchtime or evening
                if (offset % 3 != 0) {
                    val tripStart = Calendar.getInstance().apply {
                        timeInMillis = cal.timeInMillis
                        set(Calendar.HOUR_OF_DAY, if (offset % 2 == 0) 14 else 18)
                        set(Calendar.MINUTE, 10 + (offset % 5) * 8)
                        set(Calendar.SECOND, 0)
                    }
                    val tripEnd = Calendar.getInstance().apply {
                        timeInMillis = cal.timeInMillis
                        set(Calendar.HOUR_OF_DAY, if (offset % 2 == 0) 14 else 18)
                        set(Calendar.MINUTE, 45 + (offset % 5) * 8)
                        set(Calendar.SECOND, 0)
                    }
                    db.groceryDao().insertTrip(
                        Trip(
                            date = dateStr,
                            startTime = tripStart.timeInMillis,
                            endTime = tripEnd.timeInMillis,
                            isManual = false,
                            startWeatherTemp = temps[tripStart.get(Calendar.HOUR_OF_DAY)],
                            startWeatherCondition = WeatherHelper.getWeatherDescription(wmos[tripStart.get(Calendar.HOUR_OF_DAY)])
                        )
                    )
                }
            }
            
            // Sync hadTrips flag in the record
            val tripsCount = db.groceryDao().getTripsForDay(dateStr).size
            if (tripsCount > 0) {
                db.groceryDao().insertDayRecord(record.copy(hadTrips = true))
            }
        }
        
        Log.d("GroceryViewModel", "Prepopulation completed successfully!")
    }
}
