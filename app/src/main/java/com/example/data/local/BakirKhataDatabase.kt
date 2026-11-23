package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CustomerEntity::class,
        MemoEntity::class,
        MemoItemEntity::class,
        PaymentEntity::class,
        CreditTransactionEntity::class,
        ExpenseEntity::class,
        CashboxTransactionEntity::class,
        HalKhataPeriodEntity::class,
        HalKhataCustomerBalanceEntity::class,
        TemporaryCustomerEntity::class,
        TemporaryMemoEntity::class,
        AuditLogEntity::class,
        BackupMetadataEntity::class,
        AppSettingsEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class BakirKhataDatabase : RoomDatabase() {
    abstract fun customerDao(): CustomerDao
    abstract fun memoDao(): MemoDao
    abstract fun paymentDao(): PaymentDao
    abstract fun creditTransactionDao(): CreditTransactionDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun cashboxDao(): CashboxDao
    abstract fun halKhataDao(): HalKhataDao
    abstract fun temporaryCustomerDao(): TemporaryCustomerDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun backupMetadataDao(): BackupMetadataDao
    abstract fun appSettingsDao(): AppSettingsDao

    fun verifyIntegrity(): IntegrityCheckResult {
        return try {
            val db = openHelper.readableDatabase
            val cursor = db.query("PRAGMA integrity_check")
            val messages = mutableListOf<String>()
            cursor.use {
                while (it.moveToNext()) {
                    messages.add(it.getString(0))
                }
            }
            val fkCursor = db.query("PRAGMA foreign_key_check")
            val fkErrors = mutableListOf<String>()
            fkCursor.use {
                while (it.moveToNext()) {
                    fkErrors.add("${it.getString(0)} row ${it.getLong(1)}")
                }
            }
            val isOk = messages.size == 1 && messages[0].equals("ok", ignoreCase = true) && fkErrors.isEmpty()
            IntegrityCheckResult(
                isValid = isOk,
                message = if (isOk) "ডাটাবেস সম্পূর্ণ সুস্থ ও অক্ষত আছে (OK)" else (messages + fkErrors).joinToString("; ")
            )
        } catch (e: Exception) {
            IntegrityCheckResult(
                isValid = false,
                message = "ডাটাবেস যাচাইয়ে ত্রুটি: ${e.message ?: "অজানা ত্রুটি"}"
            )
        }
    }

    companion object {
        const val DB_NAME = "bakir_khata.db"

        @Volatile
        private var INSTANCE: BakirKhataDatabase? = null

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Reserved schema migration hook preserving all historical financial records.
            }
        }

        fun getInstance(context: Context): BakirKhataDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun buildDatabase(context: Context): BakirKhataDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                BakirKhataDatabase::class.java,
                DB_NAME
            )
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.execSQL("PRAGMA foreign_keys = ON;")
                    }

                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        val now = System.currentTimeMillis()
                        db.execSQL(
                            """
                            INSERT OR IGNORE INTO hal_khata_periods
                            (id, name, description, startDate, endDate, status, totalSalesPaisa, totalCreditPaisa, totalPaymentsPaisa, totalClosingDebtPaisa, closedAt, archivePdfPath, archiveBackupPath, backupVerified, createdAt)
                            VALUES (1, 'চলতি হিসাব কাল', 'প্রাথমিক হালখাতা হিসাব কাল', $now, NULL, 'ACTIVE', 0, 0, 0, 0, NULL, '', '', 0, $now)
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            INSERT OR IGNORE INTO app_settings
                            (id, shopName, ownerName, shopAddress, shopPhone, currencySymbol, memoPrefix, customUnitsJson, customExpenseCategoriesJson, backupFrequency, backupFolderUri, pinHash, pinSalt, biometricEnabled, autoLockMinutes, themeMode, pdfFooterNote, highExpenseThresholdPaisa, overdueDaysThreshold, activeHalKhataPeriodId, lastActualCashCountPaisa, lastActualCashCountAt, updatedAt)
                            VALUES (1, 'আমার দোকান', '', '', '', '৳', 'MEMO', '[]', '[]', 'DAILY', '', '', '', 0, 5, 'SYSTEM', 'ধন্যবাদ, আবার আসবেন।', 500000, 30, 1, NULL, NULL, $now)
                            """.trimIndent()
                        )
                    }
                })
                .build()
        }

        fun closeAndResetInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}

data class IntegrityCheckResult(
    val isValid: Boolean,
    val message: String
)
