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

        // Only evaluate past days/trips to avoid target leakage during active predictions
        val pastDays = allDays.filter { it.date < targetDate }
        val pastTrips = allTrips.filter { it.date < targetDate }

        // Step 2: For each time window, calculate personal statistical probability
        activeWindows.forEachIndexed { idx, win ->
            val startMin = timeToMinutes(win.startTime)
            val endMin = timeToMinutes(win.endTime)

            val startHour = (startMin / 60).coerceIn(0, 23)
            val endHour = ((endMin / 60).coerceIn(0, 23)).coerceAtLeast(startHour)

            // Current weather features for the target interval
            val forecastWmos = wmos.subList(startHour, (endHour + 1).coerceAtMost(24))
            val isRainyForecast = forecastWmos.any { WeatherHelper.isRainCode(it) }

            val hoursBefore = (startHour - 4).coerceAtLeast(0)
            val wasRainyEarlier = wmos.subList(hoursBefore, startHour).any { WeatherHelper.isRainCode(it) }
            val isRainClearingForecast = wasRainyEarlier && !isRainyForecast

            val avgTemp = temps.subList(startHour, (endHour + 1).coerceAtMost(24)).average()
            val tempCategory = when {
                avgTemp < 5 -> "cold"       // Cold (< 5°C)
                avgTemp > 24 -> "hot"       // Hot (> 24°C)
                else -> "pleasant"          // Comfort zone (5°C to 24°C)
            }

            var baseProb = win.defaultProb
            var explanation = ""

            // Case 1: Absolutely no user history — statistics are empty
            if (pastTrips.isEmpty() || pastDays.isEmpty()) {
                baseProb = win.defaultProb
                explanation = "Начальный базовый ориентир. Связь с погодой будет рассчитана при накоплении истории ваших выходов."
                predictions.add(
                    Prediction(
                        index = idx + 1,
                        probability = baseProb,
                        startTime = win.startTime,
                        endTime = win.endTime,
                        reason = explanation
                    )
                )
                return@forEachIndexed
            }

            // Case 2: History exists. Let's compute pure evidence-based Bayesian correlation.
            
            // A. Base Probability check in this window for this weekday
            val matchingDays = pastDays.filter { it.dayOfWeek == targetDayOfWeek }
            val totalMatchingDaysCount = matchingDays.size
            var tripsOnMatchingDaysCount = 0
            
            if (totalMatchingDaysCount > 0) {
                matchingDays.forEach { day ->
                    val dayTrips = pastTrips.filter { it.date == day.date }
                    val hasTripInWindow = dayTrips.any { trip ->
                        val tripStartMin = getMinutesOfDay(trip.startTime)
                        tripStartMin in startMin..endMin
                    }
                    if (hasTripInWindow) tripsOnMatchingDaysCount++
                }
                // Bayesian smoothed probability: (matching_trips + 1) / (matching_days + 2)
                val historicalProb = ((tripsOnMatchingDaysCount + 1).toFloat() / (totalMatchingDaysCount + 2).toFloat() * 100).toInt()
                baseProb = historicalProb.coerceIn(10, 90)
                explanation = "В этот день недели вы совершили $tripsOnMatchingDaysCount выходов за $totalMatchingDaysCount наблюдений. "
            } else {
                // Baseline across overall history if no matching day of week exists yet
                var overallTripsCount = 0
                pastDays.forEach { day ->
                    val dayTrips = pastTrips.filter { it.date == day.date }
                    val hasTripInWindow = dayTrips.any { trip ->
                        val tripStartMin = getMinutesOfDay(trip.startTime)
                        tripStartMin in startMin..endMin
                    }
                    if (hasTripInWindow) overallTripsCount++
                }
                val historicalProb = ((overallTripsCount + 1).toFloat() / (pastDays.size + 2).toFloat() * 100).toInt()
                baseProb = historicalProb.coerceIn(10, 90)
                explanation = "Базовый шанс по истории выходов ($overallTripsCount за ${pastDays.size} дн). "
            }

            // B. Evaluate dynamic weather multipliers based ONLY on past evidence/uniqueness of behavior
            val pWindowBase = baseProb.toDouble() / 100.0

            var rainMultiplier = 1.0
            var clearingMultiplier = 1.0
            var tempMultiplier = 1.0

            val weatherExplanations = mutableListOf<String>()

            // 1. Current rain feature
            if (isRainyForecast) {
                var rainDays = 0
                var rainTrips = 0
                pastDays.forEach { day ->
                    val dayWmos = parseCommaString(day.hourlyConditions)
                    if (dayWmos.size >= 24) {
                        val wasRainInHistWindow = dayWmos.subList(startHour, (endHour + 1).coerceAtMost(24)).any { WeatherHelper.isRainCode(it) }
                        if (wasRainInHistWindow) {
                            rainDays++
                            val dayTrips = pastTrips.filter { it.date == day.date }
                            val hasTripInWindow = dayTrips.any { trip ->
                                val tripStartMin = getMinutesOfDay(trip.startTime)
                                tripStartMin in startMin..endMin
                            }
                            if (hasTripInWindow) rainTrips++
                        }
                    }
                }

                if (rainDays > 0) {
                    val pRain = (rainTrips + 1).toDouble() / (rainDays + 2).toDouble()
                    rainMultiplier = pRain / pWindowBase
                    
                    if (rainMultiplier > 1.05) {
                        weatherExplanations.add("Вопреки дождю в эти часы вы часто выходили ранее ($rainTrips из $rainDays раз)")
                    } else if (rainMultiplier < 0.95) {
                        weatherExplanations.add("При дожде вы обычно предпочитаете пересидеть дома ($rainTrips выходов за $rainDays дождливых дней)")
                    } else {
                        weatherExplanations.add("Дождь не влияет на вашу частоту выходов по статистике")
                    }
                } else {
                    weatherExplanations.add("Влияние дождя не учтено: вы ещё ни разу не пользовались приложением в дождь в эти часы")
                }
            }

            // 2. Clear up transition feature
            if (isRainClearingForecast) {
                var clearDays = 0
                var clearTrips = 0
                pastDays.forEach { day ->
                    val dayWmos = parseCommaString(day.hourlyConditions)
                    if (dayWmos.size >= 24) {
                        val histWasRainyEarlier = dayWmos.subList(hoursBefore, startHour).any { WeatherHelper.isRainCode(it) }
                        val histIsDryNow = !dayWmos.subList(startHour, (endHour + 1).coerceAtMost(24)).any { WeatherHelper.isRainCode(it) }
                        if (histWasRainyEarlier && histIsDryNow) {
                            clearDays++
                            val dayTrips = pastTrips.filter { it.date == day.date }
                            val hasTripInWindow = dayTrips.any { trip ->
                                val tripStartMin = getMinutesOfDay(trip.startTime)
                                tripStartMin in startMin..endMin
                            }
                            if (hasTripInWindow) clearTrips++
                        }
                    }
                }

                if (clearDays > 0) {
                    val pClear = (clearTrips + 1).toDouble() / (clearDays + 2).toDouble()
                    clearingMultiplier = pClear / pWindowBase
                    
                    if (clearingMultiplier > 1.05) {
                        weatherExplanations.add("Вы склонны выходить сразу после окончания дождя ($clearTrips из $clearDays раз)")
                    } else if (clearingMultiplier < 0.95) {
                        weatherExplanations.add("После окончания дождя вы выходите реже обычного ($clearTrips из $clearDays раз)")
                    } else {
                        weatherExplanations.add("Прояснение после дождя не меняет вашу привычную активность")
                    }
                } else {
                    weatherExplanations.add("Эффект улучшения погоды не учтен: ранее таких ситуаций не происходило")
                }
            }

            // 3. Temperature category feature
            var tempDays = 0
            var tempTrips = 0
            pastDays.forEach { day ->
                val dayTemps = parseCommaString(day.hourlyTemperatures)
                if (dayTemps.size >= 24) {
                    val histAvgTemp = dayTemps.subList(startHour, (endHour + 1).coerceAtMost(24)).average()
                    val histTempCategory = when {
                        histAvgTemp < 5 -> "cold"
                        histAvgTemp > 24 -> "hot"
                        else -> "pleasant"
                    }
                    if (histTempCategory == tempCategory) {
                        tempDays++
                        val dayTrips = pastTrips.filter { it.date == day.date }
                        val hasTripInWindow = dayTrips.any { trip ->
                            val tripStartMin = getMinutesOfDay(trip.startTime)
                            tripStartMin in startMin..endMin
                        }
                        if (hasTripInWindow) tempTrips++
                    }
                }
            }

            if (tempDays > 0) {
                val pTemp = (tempTrips + 1).toDouble() / (tempDays + 2).toDouble()
                tempMultiplier = pTemp / pWindowBase
                
                if (tempMultiplier > 1.05) {
                    val label = when (tempCategory) {
                        "cold" -> "прохладную"
                        "hot" -> "жаркую"
                        else -> "комфортную"
                    }
                    weatherExplanations.add("Вы охотнее выходите в такую $label погоду ($tempTrips из $tempDays раз)")
                } else if (tempMultiplier < 0.95) {
                    val label = when (tempCategory) {
                        "cold" -> "холод"
                        "hot" -> "жару"
                        else -> "такую температуру"
                    }
                    weatherExplanations.add("Вы предпочитаете оставаться дома в $label ($tempTrips из $tempDays раз)")
                }
            } else {
                weatherExplanations.add("Статистика по температуре (${String.format("%.1f", avgTemp)}°C) за этот интервал отсутствует")
            }

            // Combine evidence multipliers under safety bounds (limit multipliers to prevent wild runaways)
            val totalMultiplier = (rainMultiplier * clearingMultiplier * tempMultiplier).coerceIn(0.2, 3.0)
            val finalProb = (baseProb * totalMultiplier).toInt().coerceIn(1, 99)

            if (weatherExplanations.isNotEmpty()) {
                explanation += weatherExplanations.joinToString(". ") + "."
            }

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
        val sorted = predictions.sortedByDescending { it.probability }
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
