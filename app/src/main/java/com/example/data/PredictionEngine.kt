package com.example.data

import com.example.network.WeatherHelper
import java.util.Calendar

data class Prediction(
    val index: Int,
    val probability: Int, // 0 - 100
    val startTime: String, // HH:MM
    val endTime: String,   // HH:MM
    val reason: String     // Detailed text explanation
)

object PredictionEngine {

    // Defines initial default time windows to predict when there is no/little history
    private val DEFAULT_WINDOWS = listOf(
        DefaultWindow("10:15", "11:45", 35, 45), // Morning
        DefaultWindow("14:30", "16:20", 55, 75), // Afternoon
        DefaultWindow("18:15", "20:10", 70, 50), // Evening
        DefaultWindow("20:15", "20:55", 25, 20)  // Late run before 21:00
    )

    private data class DefaultWindow(
        val start: String,
        val end: String,
        val weekdayProb: Int,
        val weekendProb: Int
    )

    /**
     * Executes the predicting algorithm.
     * @param targetDate The date we are predicting for ("yyyy-MM-dd")
     * @param targetDayOfWeek (Calendar.MONDAY..Calendar.SUNDAY, where Monday=2, etc. convert to 1=Mon..7=Sun)
     * @param targetDayHourlyTemp List of 24 integers representing hourly temperature forecasts
     * @param targetDayHourlyWmo List of 24 WMO weather codes
     * @param allDays List of all historically recorded days
     * @param allTrips List of all historical trips
     */
    fun predict(
        targetDate: String,
        targetDayOfWeek: Int, // 1 = Monday, ..., 7 = Sunday
        targetDayHourlyTemp: List<Int>,
        targetDayHourlyWmo: List<Int>,
        allDays: List<DayRecord>,
        allTrips: List<Trip>
    ): List<Prediction> {
        val isWeekend = targetDayOfWeek == 6 || targetDayOfWeek == 7
        val predictions = mutableListOf<Prediction>()

        // Ensure we have correct size of weather arrays
        val temps = if (targetDayHourlyTemp.size >= 24) targetDayHourlyTemp else List(24) { 15 }
        val wmos = if (targetDayHourlyWmo.size >= 24) targetDayHourlyWmo else List(24) { 0 }

        // Step 1: Detect actual trip clusters from history to find personalized time windows
        val activeWindows = getAdaptiveTimeWindows(allTrips, isWeekend)

        // Step 2: For each time window, calculate personal statistical probability
        activeWindows.forEachIndexed { idx, win ->
            val startMin = timeToMinutes(win.startTime)
            val endMin = timeToMinutes(win.endTime)

            // A. Base Probability check
            var baseProb = win.defaultProb
            var explanation = "Базовый прогноз для ${if (isWeekend) "выходного" else "буднего"} дня."

            if (allDays.isNotEmpty()) {
                val matchingDays = allDays.filter { it.dayOfWeek == targetDayOfWeek }
                if (matchingDays.isNotEmpty()) {
                    var tripsInWindowCount = 0
                    matchingDays.forEach { day ->
                        val dayTrips = allTrips.filter { it.date == day.date }
                        val hasTripInWindow = dayTrips.any { trip ->
                            val tripStartMin = getMinutesOfDay(trip.startTime)
                            tripStartMin in startMin..endMin
                        }
                        if (hasTripInWindow) tripsInWindowCount++
                    }
                    // Bayesian smoothed frequency: (matching_trips + 1) / (matching_days + 2)
                    val historicalProb = ((tripsInWindowCount + 1).toFloat() / (matchingDays.size + 2).toFloat() * 100).toInt()
                    baseProb = historicalProb.coerceIn(5, 95)
                    explanation = "На основе ${matchingDays.size} похожих дней недели (${tripsInWindowCount} выходов)."
                } else {
                    // Overall history
                    var tripsInWindowCount = 0
                    allDays.forEach { day ->
                        val dayTrips = allTrips.filter { it.date == day.date }
                        val hasTripInWindow = dayTrips.any { trip ->
                            val tripStartMin = getMinutesOfDay(trip.startTime)
                            tripStartMin in startMin..endMin
                        }
                        if (hasTripInWindow) tripsInWindowCount++
                    }
                    val historicalProb = ((tripsInWindowCount + 1).toFloat() / (allDays.size + 2).toFloat() * 100).toInt()
                    baseProb = historicalProb.coerceIn(5, 95)
                    explanation = "На основе всей истории статистики (${tripsInWindowCount} выходов)."
                }
            }

            // B. Apply Weather Forecast Factor for the target interval
            val startHour = (startMin / 60).coerceIn(0, 23)
            val endHour = ((endMin / 60).coerceIn(0, 23)).coerceAtLeast(startHour)
            
            // Collect weather codes during these forecast hours
            val forecastWmos = wmos.subList(startHour, (endHour + 1).coerceAtMost(24))
            val isRainyForecast = forecastWmos.any { WeatherHelper.isRainCode(it) }

            var weatherMultiplier = 1.0
            
            if (allDays.isNotEmpty()) {
                // Let's analyze historical rain impact
                // How many days did it rain in this interval? And did the user go?
                var rainDaysInWindow = 0
                var rainTripsInWindow = 0
                
                var dryDaysInWindow = 0
                var dryTripsInWindow = 0

                allDays.forEach { day ->
                    val dayWmos = parseCommaString(day.hourlyConditions)
                    val dayTrips = allTrips.filter { it.date == day.date }
                    val hasTripInWindow = dayTrips.any { trip ->
                        val tripStartMin = getMinutesOfDay(trip.startTime)
                        tripStartMin in startMin..endMin
                    }

                    // Check if it was raining during this window on that historical day
                    val hadRainInHistWindow = if (dayWmos.size >= 24) {
                        dayWmos.subList(startHour, (endHour + 1).coerceAtMost(24)).any { WeatherHelper.isRainCode(it) }
                    } else false

                    if (hadRainInHistWindow) {
                        rainDaysInWindow++
                        if (hasTripInWindow) rainTripsInWindow++
                    } else {
                        dryDaysInWindow++
                        if (hasTripInWindow) dryTripsInWindow++
                    }
                }

                if (isRainyForecast) {
                    if (rainDaysInWindow > 0) {
                        // user's historical probability under rain
                        val rainRatio = rainTripsInWindow.toDouble() / rainDaysInWindow.toDouble()
                        val normalRatio = if (dryDaysInWindow > 0) dryTripsInWindow.toDouble() / dryDaysInWindow.toDouble() else 0.5
                        
                        if (rainRatio < normalRatio) {
                            weatherMultiplier = (0.3 + 0.7 * (rainRatio / (normalRatio.coerceAtLeast(0.01)))).coerceIn(0.1, 1.0)
                            explanation += " Вероятность снижена: во время дождя вы обычно остаетесь дома."
                        } else {
                            weatherMultiplier = 1.1 // User doesn't mind rain!
                            explanation += " Дождь не мешает вашим планам."
                        }
                    } else {
                        // Default severe drop for rain
                        weatherMultiplier = 0.4
                        explanation += " Ожидается дождь: вероятность похода обычно значительно ниже."
                    }
                } else {
                    // Forecast is DRY during window, but let's check transition! "сейчас дождь, но к вечеру сухо — вероятность вечернего выхода выше"
                    // If weather is wet leading up to this window (last 4 hours prior are rainy)
                    val hoursBefore = (startHour - 4).coerceAtLeast(0)
                    val wasRainyEarlier = wmos.subList(hoursBefore, startHour).any { WeatherHelper.isRainCode(it) }

                    if (wasRainyEarlier) {
                        // Rain clears up! Look at historical occurrences of rain clearing up
                        var clearUpDays = 0
                        var clearUpTrips = 0

                        allDays.forEach { day ->
                            val dayWmos = parseCommaString(day.hourlyConditions)
                            if (dayWmos.size >= 24) {
                                val histWasRainyEarlier = dayWmos.subList(hoursBefore, startHour).any { WeatherHelper.isRainCode(it) }
                                val histIsDryNow = !dayWmos.subList(startHour, (endHour + 1).coerceAtMost(24)).any { WeatherHelper.isRainCode(it) }
                                if (histWasRainyEarlier && histIsDryNow) {
                                    clearUpDays++
                                    val dayTrips = allTrips.filter { it.date == day.date }
                                    val hasTripInWindow = dayTrips.any { trip ->
                                        val tripStartMin = getMinutesOfDay(trip.startTime)
                                        tripStartMin in startMin..endMin
                                    }
                                    if (hasTripInWindow) clearUpTrips++
                                }
                            }
                        }

                        if (clearUpDays > 0 && clearUpTrips > 0) {
                            weatherMultiplier = 1.4 // strong increase based on history!
                            explanation += " Рост вероятности: дождь прекратится, повышенное желание выйти."
                        } else {
                            // Default positive transition boost
                            weatherMultiplier = 1.25
                            explanation += " Дождь прекратится к началу интервала (эффект накопленного спроса)."
                        }
                    }
                }
            } else {
                // Default fallback rules without daily records
                if (isRainyForecast) {
                    weatherMultiplier = 0.5
                    explanation += " В прогнозе дождь (коррекция вероятности)."
                }
            }

            // Apply temperature coefficient (extreme cold / extreme heat reduces grocery runs)
            val avgTemp = temps.subList(startHour, (endHour + 1).coerceAtMost(24)).average()
            if (avgTemp < -15) {
                weatherMultiplier *= 0.7
                explanation += " Очень холодно ($avgTemp°C), поход маловероятен."
            } else if (avgTemp > 33) {
                weatherMultiplier *= 0.8
                explanation += " Сильная жара ($avgTemp°C), выход переносится."
            }

            val finalProb = (baseProb * weatherMultiplier).toInt().coerceIn(1, 99)
            predictions.add(
                Prediction(
                    index = idx + 1,
                    probability = finalProb,
                    startTime = win.startTime,
                    endTime = win.endTime,
                    reason = explanation
                )
            )
        }

        // Return statistical selection (1 to 4 predictions based on probability threshold)
        // Sort: highest probability first
        val sorted = predictions.sortedByDescending { it.probability }
        
        // Return between 1 and 4 depending on how many are statistically viable (e.g. at least >15% probability)
        val filtered = sorted.filterIndexed { index, pred ->
            index == 0 || pred.probability >= 20
        }
        
        return filtered.take(4)
    }

    // Helper: extract adaptive intervals based on historical trip locations
    private fun getAdaptiveTimeWindows(allTrips: List<Trip>, isWeekend: Boolean): List<AdaptiveWindow> {
        if (allTrips.size < 3) {
            // No history or too little history: return defaults depending on weekday/weekend
            return DEFAULT_WINDOWS.map { gw ->
                AdaptiveWindow(
                    startTime = gw.start,
                    endTime = gw.end,
                    defaultProb = if (isWeekend) gw.weekendProb else gw.weekdayProb
                )
            }
        }

        // If we have history, let's look at cluster centers!
        // We will define 4 typical baskets and see where the user actually steps out:
        // Basket 1: 08:00 - 12:00 (Morning)
        // Basket 2: 12:00 - 15:30 (Midday)
        // Basket 3: 15:30 - 19:30 (Evening)
        // Basket 4: 19:30 - 22:00 (Night run)

        val basket1 = mutableListOf<Trip>()
        val basket2 = mutableListOf<Trip>()
        val basket3 = mutableListOf<Trip>()
        val basket4 = mutableListOf<Trip>()

        allTrips.forEach { trip ->
            val startMin = getMinutesOfDay(trip.startTime)
            when (startMin) {
                in 480 until 720 -> basket1.add(trip)   // 8:00 to 12:00
                in 720 until 930 -> basket2.add(trip)   // 12:00 to 15:30
                in 930 until 1170 -> basket3.add(trip)  // 15:30 to 19:30
                in 1170 until 1320 -> basket4.add(trip) // 19:30 to 22:00
            }
        }

        val result = mutableListOf<AdaptiveWindow>()

        // Analyze Basket 1
        if (basket1.isNotEmpty()) {
            val times = calculateAverages(basket1)
            result.add(AdaptiveWindow(times.first, times.second, 45))
        } else {
            result.add(AdaptiveWindow("10:00", "11:30", 30))
        }

        // Analyze Basket 2
        if (basket2.isNotEmpty()) {
            val times = calculateAverages(basket2)
            result.add(AdaptiveWindow(times.first, times.second, 55))
        } else {
            result.add(AdaptiveWindow("14:30", "16:00", 40))
        }

        // Analyze Basket 3
        if (basket3.isNotEmpty()) {
            val times = calculateAverages(basket3)
            result.add(AdaptiveWindow(times.first, times.second, 75))
        } else {
            result.add(AdaptiveWindow("18:00", "19:45", 65))
        }

        // Analyze Basket 4
        if (basket4.isNotEmpty()) {
            val times = calculateAverages(basket4)
            result.add(AdaptiveWindow(times.first, times.second, 30))
        } else {
            result.add(AdaptiveWindow("20:00", "20:55", 20))
        }

        return result
    }

    private fun calculateAverages(trips: List<Trip>): Pair<String, String> {
        var startSum = 0
        var endSum = 0
        trips.forEach {
            val startMin = getMinutesOfDay(it.startTime)
            startSum += startMin
            
            val endMin = if (it.endTime != null) {
                getMinutesOfDay(it.endTime)
            } else {
                startMin + 45 // assume 45 minutes duration if currently open
            }
            endSum += endMin
        }
        val avgStart = startSum / trips.size
        val avgEnd = (endSum / trips.size).coerceAtLeast(avgStart + 15) // at least 15 min trip
        
        return Pair(minutesToTime(avgStart), minutesToTime(avgEnd))
    }

    private fun minutesToTime(minutes: Int): String {
        val h = (minutes / 60) % 24
        val m = minutes % 60
        return String.format("%02d:%02d", h, m)
    }

    private fun timeToMinutes(timeStr: String): Int {
        val parts = timeStr.split(":")
        if (parts.size >= 2) {
            val h = parts[0].toIntOrNull() ?: 0
            val m = parts[1].toIntOrNull() ?: 0
            return h * 60 + m
        }
        return 0
    }

    private fun getMinutesOfDay(timestamp: Long): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        return h * 60 + m
    }

    fun parseCommaString(str: String): List<Int> {
        if (str.isEmpty()) return emptyList()
        return str.split(",").mapNotNull { it.trim().toIntOrNull() }
    }
}

data class AdaptiveWindow(
    val startTime: String,
    val endTime: String,
    val defaultProb: Int
)
