package com.rurkk.calorica.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.Transaction
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlin.math.roundToInt

@Entity(tableName = "foods")
data class FoodEntity(
    @PrimaryKey val id: String,
    val name: String,
    val caloriesPer100g: Int,
    val proteinPer100g: Double,
    val fatPer100g: Double,
    val carbsPer100g: Double,
    val isCustom: Boolean = false,
)

@Entity(tableName = "meal_entries")
data class MealEntryEntity(
    @PrimaryKey val id: String,
    val date: String,
    val meal: String,
    val foodName: String,
    val grams: Int,
    val calories: Int,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    val createdAtMillis: Long,
)

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey val id: Int = 0,
    val calories: Int,
    val protein: Int,
    val fat: Int,
    val carbs: Int,
)

@Entity(tableName = "weight_entries")
data class WeightEntryEntity(
    @PrimaryKey val id: String,
    val date: String,
    val kilograms: Double,
)

@Dao
interface CaloricaDao {
  @Query("SELECT * FROM foods ORDER BY isCustom ASC, name ASC") fun observeFoods(): Flow<List<FoodEntity>>

  @Query("SELECT * FROM meal_entries WHERE date = :date ORDER BY createdAtMillis ASC")
  fun observeEntries(date: String): Flow<List<MealEntryEntity>>

  @Query("SELECT * FROM goals WHERE id = 0") fun observeGoal(): Flow<GoalEntity?>

  @Query("SELECT * FROM weight_entries ORDER BY date DESC") fun observeWeights(): Flow<List<WeightEntryEntity>>

  @Query("SELECT COUNT(*) FROM foods") suspend fun foodCount(): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertFoods(foods: List<FoodEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertFood(food: FoodEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertEntry(entry: MealEntryEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun updateEntry(entry: MealEntryEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveGoal(goal: GoalEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertWeight(entry: WeightEntryEntity)

  @Query("DELETE FROM meal_entries WHERE id = :entryId") suspend fun deleteEntry(entryId: String)

  @Transaction
  suspend fun seedIfNeeded() {
    if (foodCount() > 0) return
    insertFoods(
        listOf(
            FoodEntity("oatmeal", "Овсяная каша", 68, 2.4, 1.4, 12.0),
            FoodEntity("egg", "Яйцо куриное", 143, 13.0, 10.0, 1.1),
            FoodEntity("chicken", "Куриное филе", 165, 31.0, 3.6, 0.0),
            FoodEntity("rice", "Рис варёный", 130, 2.7, 0.3, 28.0),
            FoodEntity("salmon", "Лосось", 208, 20.0, 13.0, 0.0),
            FoodEntity("apple", "Яблоко", 52, 0.3, 0.2, 14.0),
            FoodEntity("cottage_cheese", "Творог 5%", 121, 17.0, 5.0, 3.0),
            FoodEntity("buckwheat", "Гречка варёная", 110, 3.6, 1.1, 21.3),
        ),
    )
    saveGoal(GoalEntity(calories = 2000, protein = 120, fat = 65, carbs = 230))
  }
}

@Database(
    entities = [FoodEntity::class, MealEntryEntity::class, GoalEntity::class, WeightEntryEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class CaloricaDatabase : RoomDatabase() {
  abstract fun caloricaDao(): CaloricaDao
}

@Singleton
class CaloricaRepository @Inject constructor(private val dao: CaloricaDao) {
  fun foods() = dao.observeFoods()

  fun entries(date: LocalDate) = dao.observeEntries(date.toString())

  fun goal() = dao.observeGoal()

  fun weights() = dao.observeWeights()

  suspend fun seedIfNeeded() = dao.seedIfNeeded()

  suspend fun addEntry(food: FoodEntity, grams: Int, meal: String, date: LocalDate) {
    val factor = grams / 100.0
    dao.insertEntry(
        MealEntryEntity(
            id = UUID.randomUUID().toString(),
            date = date.toString(),
            meal = meal,
            foodName = food.name,
            grams = grams,
            calories = (food.caloriesPer100g * factor).toInt(),
            protein = food.proteinPer100g * factor,
            fat = food.fatPer100g * factor,
            carbs = food.carbsPer100g * factor,
            createdAtMillis = System.currentTimeMillis(),
        ),
    )
  }

  suspend fun deleteEntry(id: String) = dao.deleteEntry(id)

  suspend fun updateEntryAmount(entry: MealEntryEntity, grams: Int) {
    if (grams <= 0 || entry.grams <= 0) return
    val factor = grams.toDouble() / entry.grams
    dao.updateEntry(
        entry.copy(
            grams = grams,
            calories = (entry.calories * factor).roundToInt(),
            protein = entry.protein * factor,
            fat = entry.fat * factor,
            carbs = entry.carbs * factor,
        ),
    )
  }

  suspend fun addCustomFood(name: String, calories: Int, protein: Double, fat: Double, carbs: Double) {
    dao.insertFood(
        FoodEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            caloriesPer100g = calories,
            proteinPer100g = protein,
            fatPer100g = fat,
            carbsPer100g = carbs,
            isCustom = true,
        ),
    )
  }

  suspend fun updateGoal(calories: Int, protein: Int, fat: Int, carbs: Int) {
    dao.saveGoal(GoalEntity(calories = calories, protein = protein, fat = fat, carbs = carbs))
  }

  suspend fun addWeight(kilograms: Double, date: LocalDate) {
    dao.insertWeight(WeightEntryEntity(UUID.randomUUID().toString(), date.toString(), kilograms))
  }
}

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
  private val migration1To2 =
      object : Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE meal_entries ADD COLUMN createdAtMillis INTEGER NOT NULL DEFAULT 0")
        }
      }

  @Provides
  @Singleton
  fun provideDatabase(@ApplicationContext context: Context): CaloricaDatabase =
      Room.databaseBuilder(context, CaloricaDatabase::class.java, "calorica.db")
          .addMigrations(migration1To2)
          .build()

  @Provides fun provideRepository(database: CaloricaDatabase): CaloricaRepository = CaloricaRepository(database.caloricaDao())
}
