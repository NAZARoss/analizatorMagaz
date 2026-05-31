package com.example.ui

import android.app.DatePickerDialog
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.DayRecord
import com.example.data.Prediction
import com.example.data.PredictionEngine
import com.example.data.Trip
import com.example.network.WeatherHelper
import com.example.network.City
import java.text.SimpleDateFormat
import java.util.*

fun formatDisplayDate(dateStr: String): String {
    return try {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale("ru"))
        val date = inputFormat.parse(dateStr)
        if (date != null) {
            val outputFormat = SimpleDateFormat("d MMMM", Locale("ru"))
            outputFormat.format(date)
        } else {
            dateStr
        }
    } catch (e: Exception) {
        dateStr
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroceryAppUI(viewModel: GroceryViewModel) {
    val currentTab by viewModel.currentTab.collectAsState()
    val selectedCity by viewModel.selectedCity.collectAsState()
    val isDark = isSystemInDarkTheme()

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = if (isDark) Color(0xFF161C26) else Color(0xFFF0F3F9),
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { viewModel.setTab(0) },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = "Главная") },
                    label = { Text("Главная", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("tab_main")
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { viewModel.setTab(1) },
                    icon = { Icon(Icons.Default.History, contentDescription = "История") },
                    label = { Text("История", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("tab_history")
                )
                NavigationBarItem(
                    selected = currentTab == 2,
                    onClick = { viewModel.setTab(2) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Настройки") },
                    label = { Text("Настройки", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("tab_settings")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
        ) {
            when (currentTab) {
                0 -> MainDashboardScreen(viewModel)
                1 -> HistoryScreen(viewModel)
                2 -> SettingsScreen(viewModel)
            }
        }
    }
}

@Composable
fun MainDashboardScreen(viewModel: GroceryViewModel) {
    val context = LocalContext.current
    val todayDate = remember { viewModel.getFormattedDate(0) }
    val todayDayOfWeek = remember { viewModel.getDayOfWeekLabel(0) }
    val selectedCity by viewModel.selectedCity.collectAsState()
    val activeTrip by viewModel.activeTrip.collectAsState()
    val durationText by viewModel.activeTripDurationStr.collectAsState()
    val allDayRecords by viewModel.allDayRecords.collectAsState()
    val allTrips by viewModel.allTrips.collectAsState()
    val predictions by viewModel.predictions.collectAsState()

    // Determine if it is past 21:00 (after 9 PM)
    val currentHour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val isPastDeadline = currentHour >= 21

    val targetDate = if (isPastDeadline) viewModel.getFormattedDate(1) else todayDate
    val targetDayOfWeekLabel = if (isPastDeadline) viewModel.getDayOfWeekLabel(1) else todayDayOfWeek
    val todayRecord = allDayRecords.find { it.date == todayDate }
    val tomorrowRecord = allDayRecords.find { it.date == viewModel.getFormattedDate(1) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = (if (isPastDeadline) "Прогноз на завтра" else "Анализ выходов").uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = formatDisplayDate(targetDate),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Light
                    )
                    Text(
                        text = targetDayOfWeekLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Medium
                    )
                }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(100.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = "Город",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = selectedCity.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Section A: Current weather display card
        item {
            val recordToUse = if (isPastDeadline) tomorrowRecord else todayRecord
            val displayDateName = if (isPastDeadline) "Завтра" else "Сегодня"

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ПОГОДА — $displayDateName".uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.1.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        if (recordToUse != null) {
                            val wmoList = PredictionEngine.parseCommaString(recordToUse.hourlyConditions)
                            // take current hour or middle of the day for tomorrow
                            val checkHour = if (isPastDeadline) 14 else currentHour
                            val currentWmo = wmoList.getOrNull(checkHour) ?: 0
                            val weatherText = WeatherHelper.getWeatherDescription(currentWmo)
                            val emoji = WeatherHelper.getWeatherEmoji(currentWmo)

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = emoji,
                                    fontSize = 36.sp,
                                    modifier = Modifier.padding(end = 12.dp)
                                )
                                Column {
                                    Text(
                                        text = "${recordToUse.lowTemp}°C ... ${recordToUse.highTemp}°C",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Text(
                                        text = weatherText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "Загрузка метеоданных...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    IconButton(
                        onClick = { viewModel.refreshCurrentDayForecast() },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Обновить погоду",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Section B: ACTIVE TRACKING OR PRIMARY ACTION (Блок 2)
        if (!isPastDeadline) {
            item {
                AnimatedContent(
                    targetState = activeTrip,
                    transitionSpec = {
                        slideInVertically() + fadeIn() togetherWith slideOutVertically() + fadeOut()
                    },
                    label = "ActiveTripState"
                ) { trip ->
                    if (trip != null) {
                        // Current Trip Active Display
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f)
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(MaterialTheme.colorScheme.error)
                                    )
                                    Text(
                                        text = "ПОХОД В МАГАЗИН АКТИВЕН",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.1.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = durationText,
                                    fontSize = 46.sp,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center,
                                    letterSpacing = (-1).sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Вибрация «тык-тык-тык» каждые 15 минут",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(18.dp))
                                Button(
                                    onClick = { viewModel.stopTrip() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    ),
                                    shape = RoundedCornerShape(100.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                        .testTag("btn_stop_trip")
                                ) {
                                    Icon(Icons.Default.Home, contentDescription = "Пришел домой")
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Пришёл домой",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }
                    } else {
                        // Trip Inactive - Show Out button
                        Button(
                            onClick = { viewModel.startTrip() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(100.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("btn_start_trip")
                        ) {
                            Icon(
                                Icons.Default.DirectionsRun,
                                contentDescription = "Выхожу",
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Выхожу",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Section C: Итоги дня (If after 21:00)
        if (isPastDeadline) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.tertiary),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "i",
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "Итоги сегодняшнего дня",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        val todayTrips = allTrips.filter { it.date == todayDate && it.endTime != null }
                        if (todayTrips.isNotEmpty()) {
                            val totalDuration = todayTrips.sumOf { it.durationMinutes }
                            val count = todayTrips.size
                            Text(
                                text = "Сегодня вы совершили $count выходов общим временем $totalDuration минут.",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            todayTrips.forEachIndexed { idx, trip ->
                                val sTime = formatTime(trip.startTime)
                                val eTime = trip.endTime?.let { formatTime(it) } ?: ""
                                Text(
                                    text = "Выход ${idx + 1} — с $sTime до $eTime (${trip.durationMinutes} мин)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        } else {
                            Text(
                                text = "Сегодня вы никуда не выходили. Это важная информация для алгоритма предсказаний!",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.9f)
                            )
                        }
                    }
                }
            }
        }

        // Section D: Predictions list (Блок 1 & Блок 4)
        item {
            Text(
                text = "Прогноз вероятности выходов:".uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                letterSpacing = 1.1.sp
            )
        }

        if (predictions.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Выполняется аналитический расчет вероятностей выходов...",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        } else {
            items(predictions) { pred ->
                var expandedReason by remember { mutableStateOf(false) }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expandedReason = !expandedReason },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    val isDark = isSystemInDarkTheme()
                    val activeColor = getProbabilityColor(pred.probability, isDark)

                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(activeColor.copy(alpha = 0.12f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "${pred.index} ВЫХОД".uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = activeColor,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "${pred.startTime} — ${pred.endTime}",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "${pred.probability}%",
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Black,
                                    color = activeColor,
                                    letterSpacing = (-0.5).sp
                                )
                                Text(
                                    text = "ВЕРОЯТНОСТЬ",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }

                        // Linear representation
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { pred.probability / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = activeColor,
                            trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        AnimatedVisibility(visible = expandedReason) {
                            Column(modifier = Modifier.padding(top = 12.dp)) {
                                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = "Аналитика",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = pred.reason,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        if (!expandedReason) {
                            Text(
                                text = "Нажмите для подробной аналитики...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(top = 8.dp),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun HistoryScreen(viewModel: GroceryViewModel) {
    val allDayRecords by viewModel.allDayRecords.collectAsState()
    val allTrips by viewModel.allTrips.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var selectedTripToEdit by remember { mutableStateOf<Trip?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (allDayRecords.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "История пока пуста. Попробуйте нажать кнопку «Выхожу» или добавьте поход вручную.",
                    modifier = Modifier.padding(32.dp),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Архив выходов",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black
                    )
                }

                items(allDayRecords) { record ->
                    val dayTrips = allTrips.filter { it.date == record.date }
                    var isExpandedDay by remember { mutableStateOf(false) }

                    // Weekday format
                    val dayOfWeekLabel = remember {
                        val cal = Calendar.getInstance()
                        val parts = record.date.split("-")
                        if (parts.size == 3) {
                            cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                            SimpleDateFormat("EEEE", Locale("ru")).format(cal.time).replaceFirstChar { it.uppercase() }
                        } else "Будний день"
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isExpandedDay = !isExpandedDay },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "$dayOfWeekLabel, ${formatDisplayDate(record.date)}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    // Weather condition representation
                                    val wmoList = PredictionEngine.parseCommaString(record.hourlyConditions)
                                    val weatherIcon = WeatherHelper.getWeatherEmoji(wmoList.firstOrNull() ?: 0)
                                    val weatherName = WeatherHelper.getWeatherDescription(wmoList.firstOrNull() ?: 0)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(weatherIcon)
                                        Text(
                                            text = "$weatherName, от ${record.lowTemp}°C до ${record.highTemp}°C",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (dayTrips.isNotEmpty()) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = if (dayTrips.isNotEmpty()) "${dayTrips.size} вых." else "0 вых.",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (dayTrips.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            if (dayTrips.isNotEmpty()) {
                                AnimatedVisibility(visible = isExpandedDay) {
                                    Column(modifier = Modifier.padding(top = 12.dp)) {
                                        Divider(color = MaterialTheme.colorScheme.surfaceVariant)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        dayTrips.forEachIndexed { index, trip ->
                                            val startFormatted = formatTime(trip.startTime)
                                            val endFormatted = trip.endTime?.let { formatTime(it) } ?: "активен"
                                            val durationStr = if (trip.endTime != null) "${trip.durationMinutes} мин" else "идет"

                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { selectedTripToEdit = trip }
                                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Icon(
                                                        if (trip.isManual) Icons.Default.EditCalendar else Icons.Default.DirectionsRun,
                                                        contentDescription = "Вид",
                                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Text(
                                                        text = "Выход — с $startFormatted до $endFormatted",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                }
                                                Text(
                                                    text = durationStr,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "✏️ Нажмите на конкретный поход, чтобы поправить время или удалить",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }

        // Add retroactive button
        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
                .testTag("btn_fab_add"),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = Color.White
        ) {
            Icon(Icons.Default.Add, contentDescription = "Добавить вручную")
        }
    }

    if (showAddDialog) {
        AddManualTripDialog(
            viewModel = viewModel,
            onDismiss = { showAddDialog = false }
        )
    }

    if (selectedTripToEdit != null) {
        EditTripDialog(
            viewModel = viewModel,
            trip = selectedTripToEdit!!,
            onDismiss = { selectedTripToEdit = null }
        )
    }
}

@Composable
fun SettingsScreen(viewModel: GroceryViewModel) {
    val selectedCity by viewModel.selectedCity.collectAsState()
    val citySearchResults by viewModel.citySearchResults.collectAsState()
    val isSearching by viewModel.isSearchingCities.collectAsState()
    val allDayRecords by viewModel.allDayRecords.collectAsState()
    val allTrips by viewModel.allTrips.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var showCityDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Настройки системы",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black
            )
        }

        // Current Location setup
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ВЫБОР ГОРОДА (РУЧНОЙ)".uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = selectedCity.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Координаты: Шир ${selectedCity.latitude}, Долг ${selectedCity.longitude}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { showCityDialog = true },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(100.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Сменить")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Выбрать город РФ / СНГ")
                    }
                }
            }
        }

        // Section: System diagnostics & testing triggers (Блок 2 & Блок 5)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ИНСТРУМЕНТЫ РАЗРАБОТКИ И ОТЛАДКИ".uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary,
                        letterSpacing = 1.1.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        "Здесь вы можете вручную протестировать виброотклик трекера и срабатывание пуш-уведомлений.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Button(
                        onClick = { viewModel.testVibration() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(100.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Icon(Icons.Default.Vibration, contentDescription = "Вибрация")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Проверить вибрацию (3 коротких)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { viewModel.triggerTestNotification() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(100.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = "Уведомление")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Проверить push (прогноз на завтра)")
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "СИСТЕМНЫЕ ДАННЫЕ СТАТИСТИКИ".uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Накоплено дней в базе: ${allDayRecords.size}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Всего зафиксировано выходов: ${allTrips.size}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    if (showCityDialog) {
        Dialog(onDismissRequest = { showCityDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(480.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Быстрый выбор города",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // Predefined favorites
                    Text(
                        "Популярные города РФ:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WeatherHelper.DEFAULT_CITIES.take(5).forEach { city ->
                            AssistChip(
                                onClick = {
                                    viewModel.changeCity(city)
                                    showCityDialog = false
                                },
                                label = { Text(city.name) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            viewModel.searchCities(it)
                        },
                        label = { Text("Поиск города (например: Сочи)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            if (isSearching) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Search, contentDescription = "Найти")
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (citySearchResults.isNotEmpty()) {
                            items(citySearchResults) { city ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.changeCity(city)
                                            showCityDialog = false
                                        }
                                        .padding(vertical = 8.dp, horizontal = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(city.name, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = city.region,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                }
                                Divider(color = MaterialTheme.colorScheme.surfaceVariant)
                            }
                        } else if (searchQuery.length >= 2) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Города не найдены...")
                                }
                            }
                        } else {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Введите название для поиска",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showCityDialog = false }) {
                            Text("Закрыть")
                        }
                    }
                }
            }
        }
    }
}

// Retroactive dialog setup
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddManualTripDialog(viewModel: GroceryViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val todayDate = remember { viewModel.getFormattedDate(0) }

    var selectedDate by remember { mutableStateOf(todayDate) }
    var startHour by remember { mutableIntStateOf(12) }
    var startMin by remember { mutableIntStateOf(0) }
    var endHour by remember { mutableIntStateOf(12) }
    var endMin by remember { mutableIntStateOf(30) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Добавить поход задним числом",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                // Date selector button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Дата похода:", style = MaterialTheme.typography.labelSmall)
                        Text(selectedDate, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            val cal = Calendar.getInstance()
                            val parts = selectedDate.split("-")
                            if (parts.size == 3) {
                                cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                            }
                            DatePickerDialog(
                                context,
                                { _, year, month, dayOfMonth ->
                                    selectedDate = String.format("%04d-%02d-%02d", year, month + 1, dayOfMonth)
                                },
                                cal.get(Calendar.YEAR),
                                cal.get(Calendar.MONTH),
                                cal.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        }
                    ) {
                        Text("Выбрать дату")
                    }
                }

                Divider(color = MaterialTheme.colorScheme.surfaceVariant)

                // Start time spinners
                Text("Время выхода из дома:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                TimeSpinner(
                    hour = startHour,
                    minute = startMin,
                    onHourChange = { startHour = it },
                    onMinChange = { startMin = it }
                )

                Divider(color = MaterialTheme.colorScheme.surfaceVariant)

                // End time spinners
                Text("Время возвращения домой:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                TimeSpinner(
                    hour = endHour,
                    minute = endMin,
                    onHourChange = { endHour = it },
                    onMinChange = { endMin = it }
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Отмена")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            viewModel.addManualTrip(selectedDate, startHour, startMin, endHour, endMin)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.testTag("btn_save_add_dialog")
                    ) {
                        Text("Сохранить")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTripDialog(viewModel: GroceryViewModel, trip: Trip, onDismiss: () -> Unit) {
    val context = LocalContext.current

    var selectedDate by remember { mutableStateOf(trip.date) }
    
    val initialStartCal = remember {
        Calendar.getInstance().apply { timeInMillis = trip.startTime }
    }
    val initialEndCal = remember {
        Calendar.getInstance().apply { timeInMillis = trip.endTime ?: trip.startTime }
    }

    var startHour by remember { mutableIntStateOf(initialStartCal.get(Calendar.HOUR_OF_DAY)) }
    var startMin by remember { mutableIntStateOf(initialStartCal.get(Calendar.MINUTE)) }
    var endHour by remember { mutableIntStateOf(initialEndCal.get(Calendar.HOUR_OF_DAY)) }
    var endMin by remember { mutableIntStateOf(initialEndCal.get(Calendar.MINUTE)) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Редактировать поход",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                // Date Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Дата:", style = MaterialTheme.typography.labelSmall)
                        Text(selectedDate, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            val cal = Calendar.getInstance()
                            val parts = selectedDate.split("-")
                            if (parts.size == 3) {
                                cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                            }
                            DatePickerDialog(
                                context,
                                { _, year, month, dayOfMonth ->
                                    selectedDate = String.format("%04d-%02d-%02d", year, month + 1, dayOfMonth)
                                },
                                cal.get(Calendar.YEAR),
                                cal.get(Calendar.MONTH),
                                cal.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        }
                    ) {
                        Text("Изменить дату")
                    }
                }

                Divider(color = MaterialTheme.colorScheme.surfaceVariant)

                // Start Time Spinner
                Text("Время выхода из дома:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                TimeSpinner(
                    hour = startHour,
                    minute = startMin,
                    onHourChange = { startHour = it },
                    onMinChange = { startMin = it }
                )

                Divider(color = MaterialTheme.colorScheme.surfaceVariant)

                // End Time Spinner
                Text("Время возвращения домой:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                TimeSpinner(
                    hour = endHour,
                    minute = endMin,
                    onHourChange = { endHour = it },
                    onMinChange = { endMin = it }
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            viewModel.deleteTrip(trip)
                            onDismiss()
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Удалить поход",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }

                    Row {
                        TextButton(onClick = onDismiss) {
                            Text("Отмена")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                viewModel.editTripTimes(trip, selectedDate, startHour, startMin, endHour, endMin)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.testTag("btn_save_edit_dialog")
                        ) {
                            Text("Сохранить")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TimeSpinner(
    hour: Int,
    minute: Int,
    onHourChange: (Int) -> Unit,
    onMinChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Hours Column
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = { onHourChange(if (hour == 0) 23 else hour - 1) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Меньше часов")
            }
            Text(
                text = String.format("%02d", hour),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(36.dp),
                textAlign = TextAlign.Center
            )
            IconButton(
                onClick = { onHourChange((hour + 1) % 24) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Больше часов")
            }
        }

        Text(":", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        // Minutes Column
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = { onMinChange(if (minute == 0) 59 else minute - 1) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Меньше минут")
            }
            Text(
                text = String.format("%02d", minute),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(36.dp),
                textAlign = TextAlign.Center
            )
            IconButton(
                onClick = { onMinChange((minute + 1) % 60) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Больше минут")
            }
        }
    }
}

// Utility formatting functions
fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale("ru"))
    return sdf.format(Date(timestamp))
}

fun getProbabilityColor(prob: Int, isDark: Boolean = false): Color {
    return if (isDark) {
        when {
            prob >= 70 -> Color(0xFF4FA8FF) // Dark Brand Primary (Electric Blue)
            prob >= 40 -> Color(0xFFD7C1FB) // Dark Lavender
            else -> Color(0xFF8A92A6) // Dark Muted Slate
        }
    } else {
        when {
            prob >= 70 -> Color(0xFF0061A4) // Brand Blue
            prob >= 40 -> Color(0xFF6750A4) // Elegant Lavender
            else -> Color(0xFF44474E) // Slate Gray
        }
    }
}
