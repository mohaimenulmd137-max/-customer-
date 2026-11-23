package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers ORDER BY name ASC, id ASC")
    fun observeAll(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers ORDER BY id ASC")
    suspend fun getAllOnce(): List<CustomerEntity>

    @Query("SELECT * FROM customers WHERE id = :id")
    fun observeById(id: Long): Flow<CustomerEntity?>

    @Query("SELECT * FROM customers WHERE id = :id")
    suspend fun getById(id: Long): CustomerEntity?

    @Query("SELECT * FROM customers WHERE customerCode = :code LIMIT 1")
    suspend fun getByCode(code: String): CustomerEntity?

    @Query("SELECT COALESCE(MAX(id), 0) FROM customers")
    suspend fun getMaxId(): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(customer: CustomerEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(customers: List<CustomerEntity>)

    @Update
    suspend fun update(customer: CustomerEntity)

    @Query("DELETE FROM customers")
    suspend fun deleteAll()
}

@Dao
interface MemoDao {
    @Transaction
    @Query("SELECT * FROM memos ORDER BY createdAt DESC, id DESC")
    fun observeAllWithItems(): Flow<List<MemoWithItems>>

    @Transaction
    @Query("SELECT * FROM memos WHERE customerId = :customerId ORDER BY createdAt DESC, id DESC")
    fun observeByCustomerWithItems(customerId: Long): Flow<List<MemoWithItems>>

    @Transaction
    @Query("SELECT * FROM memos WHERE id = :id")
    suspend fun getWithItemsById(id: Long): MemoWithItems?

    @Query("SELECT * FROM memos ORDER BY id ASC")
    suspend fun getAllMemosOnce(): List<MemoEntity>

    @Query("SELECT * FROM memo_items ORDER BY id ASC")
    suspend fun getAllMemoItemsOnce(): List<MemoItemEntity>

    @Query("SELECT * FROM memos WHERE requestToken = :token LIMIT 1")
    suspend fun findByRequestToken(token: String): MemoEntity?

    @Query("SELECT COALESCE(MAX(id), 0) FROM memos")
    suspend fun getMaxId(): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMemo(memo: MemoEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItems(items: List<MemoItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllMemos(memos: List<MemoEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllItems(items: List<MemoItemEntity>)

    @Update
    suspend fun updateMemo(memo: MemoEntity)

    @Query("DELETE FROM memo_items WHERE memoId = :memoId")
    suspend fun deleteItemsByMemoId(memoId: Long)

    @Query("DELETE FROM memos WHERE halKhataPeriodId = :periodId")
    suspend fun deleteMemosByPeriod(periodId: Long)

    @Query("DELETE FROM memos")
    suspend fun deleteAllMemos()
}

@Dao
interface PaymentDao {
    @Query("SELECT * FROM payments ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<PaymentEntity>>

    @Query("SELECT * FROM payments WHERE customerId = :customerId ORDER BY createdAt DESC, id DESC")
    fun observeByCustomer(customerId: Long): Flow<List<PaymentEntity>>

    @Query("SELECT * FROM payments ORDER BY id ASC")
    suspend fun getAllOnce(): List<PaymentEntity>

    @Query("SELECT * FROM payments WHERE id = :id")
    suspend fun getById(id: Long): PaymentEntity?

    @Query("SELECT * FROM payments WHERE requestToken = :token LIMIT 1")
    suspend fun findByRequestToken(token: String): PaymentEntity?

    @Query("SELECT COALESCE(MAX(id), 0) FROM payments")
    suspend fun getMaxId(): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(payment: PaymentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(payments: List<PaymentEntity>)

    @Update
    suspend fun update(payment: PaymentEntity)

    @Query("DELETE FROM payments WHERE halKhataPeriodId = :periodId")
    suspend fun deleteByPeriod(periodId: Long)

    @Query("DELETE FROM payments")
    suspend fun deleteAll()
}

@Dao
interface CreditTransactionDao {
    @Query("SELECT * FROM credit_transactions ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<CreditTransactionEntity>>

    @Query("SELECT * FROM credit_transactions WHERE customerId = :customerId ORDER BY createdAt DESC, id DESC")
    fun observeByCustomer(customerId: Long): Flow<List<CreditTransactionEntity>>

    @Query("SELECT * FROM credit_transactions ORDER BY id ASC")
    suspend fun getAllOnce(): List<CreditTransactionEntity>

    @Query("SELECT * FROM credit_transactions WHERE id = :id")
    suspend fun getById(id: Long): CreditTransactionEntity?

    @Query("SELECT * FROM credit_transactions WHERE requestToken = :token LIMIT 1")
    suspend fun findByRequestToken(token: String): CreditTransactionEntity?

    @Query("SELECT COALESCE(MAX(id), 0) FROM credit_transactions")
    suspend fun getMaxId(): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(credit: CreditTransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(credits: List<CreditTransactionEntity>)

    @Update
    suspend fun update(credit: CreditTransactionEntity)

    @Query("DELETE FROM credit_transactions WHERE halKhataPeriodId = :periodId")
    suspend fun deleteByPeriod(periodId: Long)

    @Query("DELETE FROM credit_transactions")
    suspend fun deleteAll()
}

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses ORDER BY id ASC")
    suspend fun getAllOnce(): List<ExpenseEntity>

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun getById(id: Long): ExpenseEntity?

    @Query("SELECT * FROM expenses WHERE requestToken = :token LIMIT 1")
    suspend fun findByRequestToken(token: String): ExpenseEntity?

    @Query("SELECT COALESCE(MAX(id), 0) FROM expenses")
    suspend fun getMaxId(): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(expense: ExpenseEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(expenses: List<ExpenseEntity>)

    @Update
    suspend fun update(expense: ExpenseEntity)

    @Query("DELETE FROM expenses WHERE halKhataPeriodId = :periodId")
    suspend fun deleteByPeriod(periodId: Long)

    @Query("DELETE FROM expenses")
    suspend fun deleteAll()
}

@Dao
interface CashboxDao {
    @Query("SELECT * FROM cashbox_transactions ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<CashboxTransactionEntity>>

    @Query("SELECT * FROM cashbox_transactions ORDER BY id ASC")
    suspend fun getAllOnce(): List<CashboxTransactionEntity>

    @Query("SELECT * FROM cashbox_transactions WHERE requestToken = :token LIMIT 1")
    suspend fun findByRequestToken(token: String): CashboxTransactionEntity?

    @Query("SELECT * FROM cashbox_transactions WHERE sourceType = :sourceType AND sourceId = :sourceId AND isReversed = 0 LIMIT 1")
    suspend fun findActiveBySource(sourceType: String, sourceId: Long): CashboxTransactionEntity?

    @Query("SELECT COALESCE(MAX(id), 0) FROM cashbox_transactions")
    suspend fun getMaxId(): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tx: CashboxTransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(txs: List<CashboxTransactionEntity>)

    @Update
    suspend fun update(tx: CashboxTransactionEntity)

    @Query("DELETE FROM cashbox_transactions WHERE halKhataPeriodId = :periodId")
    suspend fun deleteByPeriod(periodId: Long)

    @Query("DELETE FROM cashbox_transactions")
    suspend fun deleteAll()
}

@Dao
interface HalKhataDao {
    @Query("SELECT * FROM hal_khata_periods ORDER BY id DESC")
    fun observeAllPeriods(): Flow<List<HalKhataPeriodEntity>>

    @Query("SELECT * FROM hal_khata_periods ORDER BY id ASC")
    suspend fun getAllPeriodsOnce(): List<HalKhataPeriodEntity>

    @Query("SELECT * FROM hal_khata_periods WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1")
    fun observeActivePeriod(): Flow<HalKhataPeriodEntity?>

    @Query("SELECT * FROM hal_khata_periods WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1")
    suspend fun getActivePeriod(): HalKhataPeriodEntity?

    @Query("SELECT * FROM hal_khata_periods WHERE id = :id")
    suspend fun getPeriodById(id: Long): HalKhataPeriodEntity?

    @Query("SELECT * FROM hal_khata_customer_balances WHERE periodId = :periodId ORDER BY closingBalancePaisa DESC")
    fun observeBalancesByPeriod(periodId: Long): Flow<List<HalKhataCustomerBalanceEntity>>

    @Query("SELECT * FROM hal_khata_customer_balances ORDER BY id ASC")
    suspend fun getAllBalancesOnce(): List<HalKhataCustomerBalanceEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPeriod(period: HalKhataPeriodEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllPeriods(periods: List<HalKhataPeriodEntity>)

    @Update
    suspend fun updatePeriod(period: HalKhataPeriodEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBalances(balances: List<HalKhataCustomerBalanceEntity>)

    @Query("DELETE FROM hal_khata_customer_balances")
    suspend fun deleteAllBalances()

    @Query("DELETE FROM hal_khata_periods")
    suspend fun deleteAllPeriods()
}

@Dao
interface TemporaryCustomerDao {
    @Query("SELECT * FROM temporary_customers ORDER BY updatedAt DESC, id DESC")
    fun observeAll(): Flow<List<TemporaryCustomerEntity>>

    @Query("SELECT * FROM temporary_customers ORDER BY id ASC")
    suspend fun getAllOnce(): List<TemporaryCustomerEntity>

    @Query("SELECT * FROM temporary_customers WHERE id = :id")
    suspend fun getById(id: Long): TemporaryCustomerEntity?

    @Query("SELECT COALESCE(MAX(id), 0) FROM temporary_customers")
    suspend fun getMaxId(): Long

    @Query("SELECT * FROM temporary_memos WHERE tempCustomerId = :tempCustomerId ORDER BY createdAt DESC, id DESC")
    fun observeMemosByTempCustomer(tempCustomerId: Long): Flow<List<TemporaryMemoEntity>>

    @Query("SELECT * FROM temporary_memos WHERE tempCustomerId = :tempCustomerId ORDER BY createdAt ASC, id ASC")
    suspend fun getMemosByTempCustomerOnce(tempCustomerId: Long): List<TemporaryMemoEntity>

    @Query("SELECT * FROM temporary_memos ORDER BY id ASC")
    suspend fun getAllMemosOnce(): List<TemporaryMemoEntity>

    @Query("SELECT COALESCE(MAX(id), 0) FROM temporary_memos")
    suspend fun getMaxMemoId(): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCustomer(customer: TemporaryCustomerEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllCustomers(customers: List<TemporaryCustomerEntity>)

    @Update
    suspend fun updateCustomer(customer: TemporaryCustomerEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMemo(memo: TemporaryMemoEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllMemos(memos: List<TemporaryMemoEntity>)

    @Query("DELETE FROM temporary_memos")
    suspend fun deleteAllMemos()

    @Query("DELETE FROM temporary_customers")
    suspend fun deleteAllCustomers()
}

@Dao
interface AuditLogDao {
    @Query("SELECT * FROM audit_logs ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_logs ORDER BY id ASC")
    suspend fun getAllOnce(): List<AuditLogEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(log: AuditLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<AuditLogEntity>)

    @Query("DELETE FROM audit_logs")
    suspend fun deleteAll()
}

@Dao
interface BackupMetadataDao {
    @Query("SELECT * FROM backup_metadata ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<BackupMetadataEntity>>

    @Query("SELECT * FROM backup_metadata WHERE status = 'SUCCESS' ORDER BY createdAt DESC LIMIT 1")
    fun observeLastSuccess(): Flow<BackupMetadataEntity?>

    @Query("SELECT * FROM backup_metadata ORDER BY createdAt DESC LIMIT 1")
    fun observeLatest(): Flow<BackupMetadataEntity?>

    @Query("SELECT * FROM backup_metadata ORDER BY id ASC")
    suspend fun getAllOnce(): List<BackupMetadataEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metadata: BackupMetadataEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<BackupMetadataEntity>)

    @Query("DELETE FROM backup_metadata")
    suspend fun deleteAll()
}

@Dao
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun observeSettings(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun getSettings(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: AppSettingsEntity)
}
