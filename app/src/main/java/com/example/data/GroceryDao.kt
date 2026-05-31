package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GroceryDao {
    @Query("SELECT * FROM day_records ORDER BY date DESC")
    fun getAllDayRecordsFlow(): Flow<List<DayRecord>>

    @Query("SELECT * FROM day_records WHERE date = :date LIMIT 1")
    fun getDayRecordFlow(date: String): Flow<DayRecord?>

    @Query("SELECT * FROM day_records WHERE date = :date LIMIT 1")
    suspend fun getDayRecord(date: String): DayRecord?

    @Query("SELECT * FROM day_records")
    suspend fun getAllDayRecords(): List<DayRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDayRecord(record: DayRecord)

    // Trips
    @Query("SELECT * FROM trips ORDER BY startTime DESC")
    fun getAllTripsFlow(): Flow<List<Trip>>

    @Query("SELECT * FROM trips WHERE date = :date ORDER BY startTime ASC")
    fun getTripsForDayFlow(date: String): Flow<List<Trip>>

    @Query("SELECT * FROM trips WHERE date = :date ORDER BY startTime ASC")
    suspend fun getTripsForDay(date: String): List<Trip>

    @Query("SELECT * FROM trips")
    suspend fun getAllTrips(): List<Trip>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: Trip): Long

    @Update
    suspend fun updateTrip(trip: Trip)

    @Delete
    suspend fun deleteTrip(trip: Trip)
    
    @Query("SELECT * FROM trips WHERE endTime IS NULL LIMIT 1")
    suspend fun getActiveTrip(): Trip?

    @Query("SELECT * FROM trips WHERE endTime IS NULL LIMIT 1")
    fun getActiveTripFlow(): Flow<Trip?>
}
