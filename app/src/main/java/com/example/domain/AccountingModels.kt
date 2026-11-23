package com.example.domain

import com.example.data.local.CashboxTransactionEntity
import com.example.data.local.CreditTransactionEntity
import com.example.data.local.CustomerEntity
import com.example.data.local.ExpenseEntity
import com.example.data.local.HalKhataPeriodEntity
import com.example.data.local.MemoWithItems
import com.example.data.local.PaymentEntity

enum class CustomerFilter(val labelBn: String) {
    ALL("সব কাস্টমার"),
    WITH_DEBT("বাকি আছে"),
    ZERO_BALANCE("শূন্য বাকি"),
    RECENTLY_ADDED("নতুন যোগ"),
    OLDEST_DEBT("পুরোনো বাকি"),
    HIGHEST_DEBT("সর্বোচ্চ বাকি")
}

enum class DebtAgeBucket(val labelBn: String, val minDays: Int, val maxDays: Int) {
    DAYS_1_7("১–৭ দিন", 0, 7),
    DAYS_8_15("৮–১৫ দিন", 8, 15),
    DAYS_16_30("১৬–৩০ দিন", 16, 30),
    DAYS_31_60("৩১–৬০ দিন", 31, 60),
    DAYS_61_90("৬১–৯০ দিন", 61, 90),
    DAYS_OVER_90("৯০+ দিন", 91, Int.MAX_VALUE);

    companion object {
        fun fromDays(days: Int): DebtAgeBucket = entries.first { days in it.minDays..it.maxDays }
    }
}

enum class DebtSortOption(val labelBn: String) {
    HIGHEST_AMOUNT("বেশি বাকি আগে"),
    OLDEST_AGE("বেশি দিনের বাকি"),
    CUSTOMER_NAME("নাম অনুযায়ী")
}

enum class AlertSeverity {
    INFO, WARNING, CRITICAL
}

data class ShopAlert(
    val id: String,
    val title: String,
    val message: String,
    val severity: AlertSeverity,
    val actionRoute: String? = null
)

data class LastTransactionInfo(
    val title: String,
    val subtitle: String,
    val amountPaisa: Long,
    val timestamp: Long
)

data class DashboardSummary(
    val todayTotalSalesPaisa: Long = 0L,
    val todayCashSalesPaisa: Long = 0L,
    val todayCreditSalesPaisa: Long = 0L,
    val todayCustomerPaymentsPaisa: Long = 0L,
    val todayNewlyAddedDebtPaisa: Long = 0L,
    val todayExpensesPaisa: Long = 0L,
    val totalOutstandingDebtPaisa: Long = 0L,
    val totalCustomers: Int = 0,
    val customersWithDebt: Int = 0,
    val customersWithZeroBalance: Int = 0,
    val todayTransactionCount: Int = 0,
    val currentCashboxBalancePaisa: Long = 0L,
    val todayCashInflowPaisa: Long = 0L,
    val todayCashOutflowPaisa: Long = 0L,
    val lastTransaction: LastTransactionInfo? = null,
    val lastBackupAt: Long? = null,
    val lastBackupStatus: String? = null,
    val activeHalKhata: HalKhataPeriodEntity? = null,
    val alerts: List<ShopAlert> = emptyList()
)

data class MemoLineDraft(
    val localId: String,
    val productName: String,
    val quantityText: String,
    val unit: String,
    val unitPriceText: String,
    val quantityMillis: Long,
    val unitPricePaisa: Long,
    val lineTotalPaisa: Long
)

sealed class CustomerLedgerEntry {
    abstract val timestamp: Long
    abstract val referenceCode: String
    abstract val runningBalancePaisa: Long

    data class MemoEntry(
        val memoWithItems: MemoWithItems,
        override val timestamp: Long,
        override val referenceCode: String,
        override val runningBalancePaisa: Long
    ) : CustomerLedgerEntry()

    data class QuickCreditEntry(
        val credit: CreditTransactionEntity,
        override val timestamp: Long,
        override val referenceCode: String,
        override val runningBalancePaisa: Long
    ) : CustomerLedgerEntry()

    data class PaymentEntry(
        val payment: PaymentEntity,
        override val timestamp: Long,
        override val referenceCode: String,
        override val runningBalancePaisa: Long
    ) : CustomerLedgerEntry()
}

data class DailyReportData(
    val dateMillis: Long,
    val openingCashPaisa: Long,
    val cashSalesPaisa: Long,
    val creditSalesPaisa: Long,
    val customerPaymentsPaisa: Long,
    val otherIncomePaisa: Long,
    val totalInflowPaisa: Long,
    val expensesPaisa: Long,
    val supplierPaymentsPaisa: Long,
    val otherOutflowsPaisa: Long,
    val totalOutflowPaisa: Long,
    val expectedClosingCashPaisa: Long,
    val actualClosingCashPaisa: Long?,
    val cashDifferencePaisa: Long?,
    val totalOutstandingDebtPaisa: Long,
    val newDebtsPaisa: Long,
    val totalPaymentsPaisa: Long,
    val transactionCount: Int,
    val memos: List<MemoWithItems>,
    val payments: List<PaymentEntity>,
    val credits: List<CreditTransactionEntity>,
    val expenses: List<ExpenseEntity>,
    val cashboxTransactions: List<CashboxTransactionEntity>
)

data class CustomerReportRow(
    val customer: CustomerEntity,
    val periodSalesPaisa: Long,
    val periodCashPaidPaisa: Long,
    val periodCreditAddedPaisa: Long,
    val periodPaymentsPaisa: Long
)

data class PeriodReportData(
    val startMillis: Long,
    val endMillis: Long,
    val totalSalesPaisa: Long,
    val cashSalesPaisa: Long,
    val creditSalesPaisa: Long,
    val totalCustomerPaymentsPaisa: Long,
    val totalNewCreditsPaisa: Long,
    val totalExpensesPaisa: Long,
    val cashInflowPaisa: Long,
    val cashOutflowPaisa: Long,
    val totalOutstandingDebtPaisa: Long,
    val transactionCount: Int,
    val customerRows: List<CustomerReportRow>,
    val memos: List<MemoWithItems>,
    val payments: List<PaymentEntity>,
    val credits: List<CreditTransactionEntity>,
    val expenses: List<ExpenseEntity>,
    val cashboxTransactions: List<CashboxTransactionEntity>
)

data class DebtAgingRow(
    val customer: CustomerEntity,
    val outstandingPaisa: Long,
    val oldestUnpaidDate: Long,
    val debtAgeDays: Int,
    val bucket: DebtAgeBucket,
    val lastPaymentDate: Long?,
    val lastPurchaseDate: Long?
)

data class CashboxSummary(
    val openingCashPaisa: Long,
    val totalInflowPaisa: Long,
    val totalOutflowPaisa: Long,
    val expectedBalancePaisa: Long,
    val actualCountedCashPaisa: Long?,
    val differencePaisa: Long?
)

data class BackupPreviewInfo(
    val fileName: String,
    val createdAt: Long,
    val shopName: String,
    val customerCount: Int,
    val memoCount: Int,
    val paymentCount: Int,
    val creditCount: Int,
    val expenseCount: Int,
    val totalOutstandingDebtPaisa: Long,
    val checksumValid: Boolean,
    val formatLabel: String
)

sealed class OperationResult<out T> {
    data class Success<T>(val data: T, val messageBn: String = "") : OperationResult<T>()
    data class Error(val messageBn: String) : OperationResult<Nothing>()
}
