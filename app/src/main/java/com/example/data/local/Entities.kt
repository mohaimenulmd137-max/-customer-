package com.example.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

object SaleType {
    const val CASH = "CASH"
    const val CREDIT = "CREDIT"
    const val PARTIAL = "PARTIAL"
}

object CreditSourceType {
    const val QUICK_CREDIT = "QUICK_CREDIT"
    const val MEMO = "MEMO"
    const val OPENING_CARRYOVER = "OPENING_CARRYOVER"
    const val REVERSAL = "REVERSAL"
}

object CashboxType {
    const val OPENING = "OPENING"
    const val CASH_SALE = "CASH_SALE"
    const val CUSTOMER_PAYMENT = "CUSTOMER_PAYMENT"
    const val OTHER_INCOME = "OTHER_INCOME"
    const val EXPENSE = "EXPENSE"
    const val SUPPLIER_PAYMENT = "SUPPLIER_PAYMENT"
    const val OTHER_WITHDRAWAL = "OTHER_WITHDRAWAL"
    const val CORRECTION = "CORRECTION"
}

object CashboxDirection {
    const val IN = "IN"
    const val OUT = "OUT"
}

object HalKhataStatus {
    const val ACTIVE = "ACTIVE"
    const val CLOSED = "CLOSED"
    const val ARCHIVED = "ARCHIVED"
}

@Entity(
    tableName = "customers",
    indices = [
        Index(value = ["customerCode"], unique = true),
        Index(value = ["name"]),
        Index(value = ["mobile"]),
        Index(value = ["currentBalancePaisa"])
    ]
)
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val customerCode: String,
    val name: String,
    val address: String,
    val mobile: String = "",
    val nickname: String = "",
    val notes: String = "",
    val openingBalancePaisa: Long = 0L,
    val currentBalancePaisa: Long = 0L,
    val totalPurchasesPaisa: Long = 0L,
    val totalCreditPaisa: Long = 0L,
    val totalPaymentsPaisa: Long = 0L,
    val halKhataPeriodId: Long = 1L,
    val oldestUnpaidTimestamp: Long? = null,
    val lastTransactionDate: Long? = null,
    val lastPaymentDate: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "memos",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["memoNumber"], unique = true),
        Index(value = ["requestToken"], unique = true),
        Index(value = ["customerId"]),
        Index(value = ["halKhataPeriodId"]),
        Index(value = ["createdAt"])
    ]
)
data class MemoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val memoNumber: String,
    val requestToken: String,
    val customerId: Long? = null,
    val customerNameSnapshot: String,
    val customerAddressSnapshot: String,
    val saleType: String,
    val subtotalPaisa: Long,
    val discountPaisa: Long = 0L,
    val grandTotalPaisa: Long,
    val paidPaisa: Long,
    val outstandingPaisa: Long,
    val notes: String = "",
    val halKhataPeriodId: Long = 1L,
    val isCancelled: Boolean = false,
    val needsReview: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "memo_items",
    foreignKeys = [
        ForeignKey(
            entity = MemoEntity::class,
            parentColumns = ["id"],
            childColumns = ["memoId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["memoId"])]
)
data class MemoItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val memoId: Long,
    val productName: String,
    val quantityMillis: Long,
    val unit: String,
    val unitPricePaisa: Long,
    val lineTotalPaisa: Long
)

data class MemoWithItems(
    @Embedded val memo: MemoEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "memoId"
    )
    val items: List<MemoItemEntity>
)

@Entity(
    tableName = "payments",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = MemoEntity::class,
            parentColumns = ["id"],
            childColumns = ["memoId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["receiptNumber"], unique = true),
        Index(value = ["requestToken"], unique = true),
        Index(value = ["customerId"]),
        Index(value = ["memoId"]),
        Index(value = ["halKhataPeriodId"]),
        Index(value = ["createdAt"])
    ]
)
data class PaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val receiptNumber: String,
    val requestToken: String,
    val customerId: Long,
    val amountPaisa: Long,
    val previousBalancePaisa: Long,
    val newBalancePaisa: Long,
    val memoId: Long? = null,
    val notes: String = "",
    val halKhataPeriodId: Long = 1L,
    val isReversed: Boolean = false,
    val reversedById: Long? = null,
    val needsReview: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "credit_transactions",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = MemoEntity::class,
            parentColumns = ["id"],
            childColumns = ["memoId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["transactionCode"], unique = true),
        Index(value = ["requestToken"], unique = true),
        Index(value = ["customerId"]),
        Index(value = ["memoId"]),
        Index(value = ["halKhataPeriodId"]),
        Index(value = ["createdAt"])
    ]
)
data class CreditTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val transactionCode: String,
    val requestToken: String,
    val customerId: Long,
    val amountPaisa: Long,
    val previousBalancePaisa: Long,
    val newBalancePaisa: Long,
    val description: String = "",
    val notes: String = "",
    val sourceType: String = CreditSourceType.QUICK_CREDIT,
    val memoId: Long? = null,
    val halKhataPeriodId: Long = 1L,
    val isReversed: Boolean = false,
    val needsReview: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "expenses",
    indices = [
        Index(value = ["expenseCode"], unique = true),
        Index(value = ["requestToken"], unique = true),
        Index(value = ["halKhataPeriodId"]),
        Index(value = ["createdAt"])
    ]
)
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val expenseCode: String,
    val requestToken: String,
    val category: String,
    val amountPaisa: Long,
    val description: String,
    val notes: String = "",
    val paidFromCashbox: Boolean = true,
    val halKhataPeriodId: Long = 1L,
    val isCancelled: Boolean = false,
    val needsReview: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "cashbox_transactions",
    indices = [
        Index(value = ["transactionCode"], unique = true),
        Index(value = ["requestToken"], unique = true),
        Index(value = ["halKhataPeriodId"]),
        Index(value = ["createdAt"])
    ]
)
data class CashboxTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val transactionCode: String,
    val requestToken: String,
    val type: String,
    val direction: String,
    val amountPaisa: Long,
    val sourceType: String,
    val sourceId: Long? = null,
    val description: String,
    val notes: String = "",
    val halKhataPeriodId: Long = 1L,
    val isReversed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "hal_khata_periods",
    indices = [Index(value = ["status"])]
)
data class HalKhataPeriodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val description: String = "",
    val startDate: Long,
    val endDate: Long? = null,
    val status: String = HalKhataStatus.ACTIVE,
    val totalSalesPaisa: Long = 0L,
    val totalCreditPaisa: Long = 0L,
    val totalPaymentsPaisa: Long = 0L,
    val totalClosingDebtPaisa: Long = 0L,
    val closedAt: Long? = null,
    val archivePdfPath: String = "",
    val archiveBackupPath: String = "",
    val backupVerified: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "hal_khata_customer_balances",
    foreignKeys = [
        ForeignKey(
            entity = HalKhataPeriodEntity::class,
            parentColumns = ["id"],
            childColumns = ["periodId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["periodId"]),
        Index(value = ["customerId"])
    ]
)
data class HalKhataCustomerBalanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val periodId: Long,
    val customerId: Long,
    val customerCode: String,
    val customerName: String,
    val openingBalancePaisa: Long,
    val totalPurchasesPaisa: Long,
    val totalPaymentsPaisa: Long,
    val closingBalancePaisa: Long,
    val carriedForward: Boolean = false,
    val recordedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "temporary_customers",
    indices = [
        Index(value = ["tempCode"], unique = true),
        Index(value = ["isConverted"])
    ]
)
data class TemporaryCustomerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val tempCode: String,
    val name: String,
    val address: String = "",
    val mobile: String = "",
    val notes: String = "",
    val totalCreditPaisa: Long = 0L,
    val totalPaidPaisa: Long = 0L,
    val outstandingPaisa: Long = 0L,
    val convertedCustomerId: Long? = null,
    val isConverted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "temporary_memos",
    foreignKeys = [
        ForeignKey(
            entity = TemporaryCustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["tempCustomerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["memoNumber"], unique = true),
        Index(value = ["tempCustomerId"]),
        Index(value = ["createdAt"])
    ]
)
data class TemporaryMemoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val tempCustomerId: Long,
    val memoNumber: String,
    val type: String, // CREDIT, PAYMENT, MEMO
    val description: String,
    val itemsJson: String = "",
    val totalAmountPaisa: Long,
    val paidPaisa: Long,
    val creditPaisa: Long,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "audit_logs",
    indices = [
        Index(value = ["entityType", "entityId"]),
        Index(value = ["relatedCustomerId"]),
        Index(value = ["createdAt"])
    ]
)
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val transactionRef: String,
    val entityType: String,
    val entityId: Long,
    val actionType: String,
    val previousValueJson: String = "",
    val newValueJson: String = "",
    val reason: String = "",
    val relatedCustomerId: Long? = null,
    val relatedMemoId: Long? = null,
    val relatedPaymentId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "backup_metadata",
    indices = [Index(value = ["createdAt"])]
)
data class BackupMetadataEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val backupType: String,
    val fileName: String,
    val filePath: String,
    val checksumSha256: String,
    val fileSizeBytes: Long,
    val status: String, // SUCCESS, FAILED
    val errorMessage: String = "",
    val verified: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val shopName: String = "আমার দোকান",
    val ownerName: String = "",
    val shopAddress: String = "",
    val shopPhone: String = "",
    val currencySymbol: String = "৳",
    val memoPrefix: String = "MEMO",
    val customUnitsJson: String = "[]",
    val customExpenseCategoriesJson: String = "[]",
    val backupFrequency: String = "DAILY", // OFF, DAILY, WEEKLY
    val backupFolderUri: String = "",
    val pinHash: String = "",
    val pinSalt: String = "",
    val biometricEnabled: Boolean = false,
    val autoLockMinutes: Int = 5,
    val themeMode: String = "SYSTEM", // SYSTEM, LIGHT, DARK
    val pdfFooterNote: String = "ধন্যবাদ, আবার আসবেন।",
    val highExpenseThresholdPaisa: Long = 500000L, // 5,000 BDT default alert
    val overdueDaysThreshold: Int = 30,
    val activeHalKhataPeriodId: Long = 1L,
    val lastActualCashCountPaisa: Long? = null,
    val lastActualCashCountAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
