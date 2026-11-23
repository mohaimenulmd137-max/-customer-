package com.example.ui.navigation

object NavRoutes {
    const val DASHBOARD = "dashboard"
    const val CUSTOMERS = "customers"
    const val CUSTOMER_LEDGER = "customer_ledger/{customerId}"
    const val QUICK_PAYMENT = "quick_payment?customerId={customerId}"
    const val QUICK_CREDIT = "quick_credit?customerId={customerId}"
    const val SALES_MEMO = "sales_memo?customerId={customerId}"
    const val TEMP_CUSTOMERS = "temp_customers"
    const val CASHBOX = "cashbox"
    const val EXPENSES = "expenses"
    const val DAILY_REPORT = "daily_report"
    const val MONTHLY_REPORT = "monthly_report"
    const val HAL_KHATA = "hal_khata"
    const val DEBT_AGING = "debt_aging"
    const val REMINDERS = "reminders"
    const val PRINT_PDF = "print_pdf"
    const val BACKUP_RESTORE = "backup_restore"
    const val AUDIT_LOGS = "audit_logs"
    const val SETTINGS = "settings"

    fun customerLedger(customerId: Long) = "customer_ledger/$customerId"
    fun quickPayment(customerId: Long? = null) =
        if (customerId != null) "quick_payment?customerId=$customerId" else "quick_payment"
    fun quickCredit(customerId: Long? = null) =
        if (customerId != null) "quick_credit?customerId=$customerId" else "quick_credit"
    fun salesMemo(customerId: Long? = null) =
        if (customerId != null) "sales_memo?customerId=$customerId" else "sales_memo"
}
