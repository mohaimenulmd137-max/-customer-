package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BakirKhataApp
import com.example.data.local.*
import com.example.domain.*
import com.example.util.BackupManager
import com.example.util.CurrencyUtils
import com.example.util.PinSecurityHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BakirKhataApp
    val db: BakirKhataDatabase = app.database
    val repo = app.repository
    val pdfGenerator = app.pdfGenerator
    val backupManager = app.backupManager

    val settings: StateFlow<AppSettingsEntity> = repo.settingsFlow
        .map { it ?: AppSettingsEntity() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettingsEntity())

    val dashboardSummary: StateFlow<DashboardSummary> = repo.observeDashboardSummary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardSummary())

    val customers: StateFlow<List<CustomerEntity>> = repo.customersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val memos: StateFlow<List<MemoWithItems>> = repo.memosFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val payments: StateFlow<List<PaymentEntity>> = repo.paymentsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val credits: StateFlow<List<CreditTransactionEntity>> = repo.creditsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val expenses: StateFlow<List<ExpenseEntity>> = repo.expensesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cashboxTransactions: StateFlow<List<CashboxTransactionEntity>> = repo.cashboxFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cashboxSummary: StateFlow<CashboxSummary> = repo.observeCashboxSummary()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            CashboxSummary(0, 0, 0, 0, null, null)
        )

    val halKhataPeriods: StateFlow<List<HalKhataPeriodEntity>> = repo.halKhataPeriodsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeHalKhata: StateFlow<HalKhataPeriodEntity?> = repo.activeHalKhataFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val tempCustomers: StateFlow<List<TemporaryCustomerEntity>> = repo.tempCustomersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val auditLogs: StateFlow<List<AuditLogEntity>> = repo.auditLogsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val backupHistory: StateFlow<List<BackupMetadataEntity>> = repo.backupMetadataFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val debtAgingRows: StateFlow<List<DebtAgingRow>> = repo.observeDebtAging()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _messageFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messageFlow: SharedFlow<String> = _messageFlow.asSharedFlow()

    // Security lock state
    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()
    private var lastBackgroundTimestamp: Long = 0L

    init {
        viewModelScope.launch {
            settings.first { s ->
                if (s.pinHash.isNotBlank()) {
                    _isLocked.value = true
                }
                true
            }
        }
    }

    fun onAppBackgrounded() {
        lastBackgroundTimestamp = System.currentTimeMillis()
    }

    fun onAppForegrounded() {
        val s = settings.value
        if (s.pinHash.isNotBlank() && lastBackgroundTimestamp > 0L) {
            val elapsedMinutes = (System.currentTimeMillis() - lastBackgroundTimestamp) / 60000L
            if (s.autoLockMinutes == 0 || elapsedMinutes >= s.autoLockMinutes) {
                _isLocked.value = true
            }
        }
    }

    fun unlockWithPin(pin: String): Boolean {
        val s = settings.value
        val ok = PinSecurityHelper.verifyPin(pin, s.pinSalt, s.pinHash)
        if (ok) _isLocked.value = false
        return ok
    }

    fun unlockWithBiometric() {
        _isLocked.value = false
    }

    fun lockNow() {
        if (settings.value.pinHash.isNotBlank()) {
            _isLocked.value = true
        }
    }

    fun postMessage(msg: String) {
        _messageFlow.tryEmit(msg)
    }

    fun observeCustomer(customerId: Long): Flow<CustomerEntity?> = repo.observeCustomer(customerId)
    fun observeCustomerLedger(customerId: Long): Flow<List<CustomerLedgerEntry>> =
        repo.observeCustomerLedger(customerId)
    fun observeHalKhataBalances(periodId: Long): Flow<List<HalKhataCustomerBalanceEntity>> =
        repo.observeHalKhataBalances(periodId)
    fun observeTempMemos(tempId: Long): Flow<List<TemporaryMemoEntity>> =
        repo.observeTempMemos(tempId)

    // region Transactional Actions

    fun createCustomer(
        name: String,
        address: String,
        mobile: String,
        nickname: String,
        notes: String,
        openingBalancePaisa: Long,
        onSuccess: (CustomerEntity) -> Unit = {}
    ) {
        if (_isSubmitting.value) return
        viewModelScope.launch {
            _isSubmitting.value = true
            try {
                when (val res = repo.createCustomer(name, address, mobile, nickname, notes, openingBalancePaisa)) {
                    is OperationResult.Success -> {
                        postMessage(res.messageBn)
                        onSuccess(res.data)
                    }
                    is OperationResult.Error -> postMessage(res.messageBn)
                }
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    fun updateCustomer(
        customerId: Long,
        name: String,
        address: String,
        mobile: String,
        nickname: String,
        notes: String,
        onSuccess: () -> Unit = {}
    ) {
        if (_isSubmitting.value) return
        viewModelScope.launch {
            _isSubmitting.value = true
            try {
                when (val res = repo.updateCustomer(customerId, name, address, mobile, nickname, notes)) {
                    is OperationResult.Success -> {
                        postMessage(res.messageBn)
                        onSuccess()
                    }
                    is OperationResult.Error -> postMessage(res.messageBn)
                }
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    fun recordQuickPayment(
        customerId: Long,
        amountPaisa: Long,
        notes: String,
        requestToken: String,
        onSuccess: (PaymentEntity) -> Unit
    ) {
        if (_isSubmitting.value) return
        viewModelScope.launch {
            _isSubmitting.value = true
            try {
                when (val res = repo.recordQuickPayment(customerId, amountPaisa, notes, requestToken)) {
                    is OperationResult.Success -> {
                        postMessage(res.messageBn)
                        onSuccess(res.data)
                    }
                    is OperationResult.Error -> postMessage(res.messageBn)
                }
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    fun recordQuickCredit(
        customerId: Long,
        amountPaisa: Long,
        description: String,
        notes: String,
        requestToken: String,
        onSuccess: (CreditTransactionEntity) -> Unit
    ) {
        if (_isSubmitting.value) return
        viewModelScope.launch {
            _isSubmitting.value = true
            try {
                when (val res = repo.recordQuickCredit(customerId, amountPaisa, description, notes, requestToken)) {
                    is OperationResult.Success -> {
                        postMessage(res.messageBn)
                        onSuccess(res.data)
                    }
                    is OperationResult.Error -> postMessage(res.messageBn)
                }
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    fun createSalesMemo(
        saleType: String,
        customerId: Long?,
        walkInName: String,
        walkInAddress: String,
        items: List<MemoLineDraft>,
        discountPaisa: Long,
        paidPaisaInput: Long,
        notes: String,
        requestToken: String,
        onSuccess: (MemoWithItems) -> Unit
    ) {
        if (_isSubmitting.value) return
        viewModelScope.launch {
            _isSubmitting.value = true
            try {
                when (
                    val res = repo.createSalesMemo(
                        saleType = saleType,
                        customerId = customerId,
                        walkInName = walkInName,
                        walkInAddress = walkInAddress,
                        items = items,
                        discountPaisa = discountPaisa,
                        paidPaisaInput = paidPaisaInput,
                        notes = notes,
                        requestToken = requestToken
                    )
                ) {
                    is OperationResult.Success -> {
                        postMessage(res.messageBn)
                        onSuccess(res.data)
                    }
                    is OperationResult.Error -> postMessage(res.messageBn)
                }
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    fun reversePayment(paymentId: Long, reason: String) {
        viewModelScope.launch {
            when (val res = repo.reversePayment(paymentId, reason)) {
                is OperationResult.Success -> postMessage(res.messageBn)
                is OperationResult.Error -> postMessage(res.messageBn)
            }
        }
    }

    fun correctPayment(paymentId: Long, newAmountPaisa: Long, reason: String) {
        viewModelScope.launch {
            when (val res = repo.correctPayment(paymentId, newAmountPaisa, reason)) {
                is OperationResult.Success -> postMessage(res.messageBn)
                is OperationResult.Error -> postMessage(res.messageBn)
            }
        }
    }

    fun reverseCredit(creditId: Long, reason: String) {
        viewModelScope.launch {
            when (val res = repo.reverseCredit(creditId, reason)) {
                is OperationResult.Success -> postMessage(res.messageBn)
                is OperationResult.Error -> postMessage(res.messageBn)
            }
        }
    }

    fun cancelOrCorrectMemo(
        memoId: Long,
        reason: String,
        replacementDiscountPaisa: Long? = null,
        replacementPaidPaisa: Long? = null
    ) {
        viewModelScope.launch {
            when (val res = repo.cancelOrCorrectMemo(memoId, reason, replacementDiscountPaisa, replacementPaidPaisa)) {
                is OperationResult.Success -> postMessage(res.messageBn)
                is OperationResult.Error -> postMessage(res.messageBn)
            }
        }
    }

    fun addExpense(
        category: String,
        amountPaisa: Long,
        description: String,
        notes: String,
        paidFromCashbox: Boolean,
        onSuccess: () -> Unit = {}
    ) {
        if (_isSubmitting.value) return
        viewModelScope.launch {
            _isSubmitting.value = true
            try {
                when (
                    val res = repo.addExpense(
                        category = category,
                        amountPaisa = amountPaisa,
                        description = description,
                        notes = notes,
                        paidFromCashbox = paidFromCashbox,
                        requestToken = UUID.randomUUID().toString()
                    )
                ) {
                    is OperationResult.Success -> {
                        postMessage(res.messageBn)
                        onSuccess()
                    }
                    is OperationResult.Error -> postMessage(res.messageBn)
                }
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    fun updateExpense(
        expenseId: Long,
        category: String,
        amountPaisa: Long,
        description: String,
        notes: String,
        paidFromCashbox: Boolean,
        reason: String,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            when (val res = repo.updateExpense(expenseId, category, amountPaisa, description, notes, paidFromCashbox, reason)) {
                is OperationResult.Success -> {
                    postMessage(res.messageBn)
                    onSuccess()
                }
                is OperationResult.Error -> postMessage(res.messageBn)
            }
        }
    }

    fun addCashboxManualEntry(
        type: String,
        direction: String,
        amountPaisa: Long,
        description: String,
        notes: String,
        onSuccess: () -> Unit = {}
    ) {
        if (_isSubmitting.value) return
        viewModelScope.launch {
            _isSubmitting.value = true
            try {
                when (
                    val res = repo.addCashboxManualEntry(
                        type = type,
                        direction = direction,
                        amountPaisa = amountPaisa,
                        description = description,
                        notes = notes
                    )
                ) {
                    is OperationResult.Success -> {
                        postMessage(res.messageBn)
                        onSuccess()
                    }
                    is OperationResult.Error -> postMessage(res.messageBn)
                }
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    fun recordActualCashCount(actualCashPaisa: Long) {
        viewModelScope.launch {
            when (val res = repo.recordActualCashCount(actualCashPaisa)) {
                is OperationResult.Success -> postMessage(res.messageBn)
                is OperationResult.Error -> postMessage(res.messageBn)
            }
        }
    }

    fun createTemporaryCustomer(name: String, address: String, mobile: String, notes: String, onSuccess: (TemporaryCustomerEntity) -> Unit = {}) {
        viewModelScope.launch {
            when (val res = repo.createTemporaryCustomer(name, address, mobile, notes)) {
                is OperationResult.Success -> {
                    postMessage(res.messageBn)
                    onSuccess(res.data)
                }
                is OperationResult.Error -> postMessage(res.messageBn)
            }
        }
    }

    fun addTemporaryEntry(
        tempCustomerId: Long,
        type: String,
        description: String,
        totalAmountPaisa: Long,
        paidPaisa: Long,
        notes: String,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            when (val res = repo.addTemporaryEntry(tempCustomerId, type, description, totalAmountPaisa, paidPaisa, notes)) {
                is OperationResult.Success -> {
                    postMessage(res.messageBn)
                    onSuccess()
                }
                is OperationResult.Error -> postMessage(res.messageBn)
            }
        }
    }

    fun convertTemporaryToPermanent(tempCustomerId: Long, addressOverride: String, onSuccess: (CustomerEntity) -> Unit = {}) {
        viewModelScope.launch {
            when (val res = repo.convertTemporaryToPermanent(tempCustomerId, addressOverride)) {
                is OperationResult.Success -> {
                    postMessage(res.messageBn)
                    onSuccess(res.data)
                }
                is OperationResult.Error -> postMessage(res.messageBn)
            }
        }
    }

    fun updateActiveHalKhataMeta(name: String, description: String, startDate: Long, endDate: Long?) {
        viewModelScope.launch {
            when (val res = repo.updateActiveHalKhataMeta(name, description, startDate, endDate)) {
                is OperationResult.Success -> postMessage(res.messageBn)
                is OperationResult.Error -> postMessage(res.messageBn)
            }
        }
    }

    fun closeHalKhataPeriod(
        newPeriodName: String,
        newPeriodDescription: String,
        newEndDate: Long?,
        onSuccess: () -> Unit = {}
    ) {
        if (_isSubmitting.value) return
        viewModelScope.launch {
            _isSubmitting.value = true
            try {
                val active = activeHalKhata.value
                if (active == null) {
                    postMessage("সক্রিয় হালখাতা পাওয়া যায়নি")
                    return@launch
                }
                // Step 1: Create & verify full backup BEFORE closing or archiving
                val backupRes = withContext(Dispatchers.IO) {
                    backupManager.createZipBackup(
                        backupType = "HAL_KHATA_ARCHIVE",
                        customFileName = "halkhata_period_${active.id}_backup_${System.currentTimeMillis()}.zip"
                    )
                }
                if (backupRes !is OperationResult.Success || !backupRes.data.verified) {
                    postMessage("হালখাতা ব্যাকআপ যাচাই ব্যর্থ হয়েছে। কোনো তথ্য মুছা হয়নি।")
                    return@launch
                }
                // Step 2: Generate Hal Khata Archive PDF
                val pdfFile = withContext(Dispatchers.IO) {
                    pdfGenerator.generateHalKhataArchivePdf(
                        period = active,
                        customers = customers.value,
                        balances = emptyList(),
                        settings = settings.value,
                        savePermanentArchive = true
                    )
                }
                // Step 3: Close period & carry forward unpaid debts atomically
                when (
                    val res = repo.closeHalKhataPeriod(
                        newPeriodName = newPeriodName,
                        newPeriodDescription = newPeriodDescription,
                        newStartDate = System.currentTimeMillis(),
                        newEndDate = newEndDate,
                        archivePdfPath = pdfFile.absolutePath,
                        archiveBackupPath = backupRes.data.filePath,
                        backupVerified = backupRes.data.verified
                    )
                ) {
                    is OperationResult.Success -> {
                        postMessage(res.messageBn)
                        onSuccess()
                    }
                    is OperationResult.Error -> postMessage(res.messageBn)
                }
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    // endregion

    // region Reports & PDF Helpers

    fun buildDailyReport(dateMillis: Long): DailyReportData {
        return repo.computeDailyReport(
            dateMillis = dateMillis,
            customers = customers.value,
            memos = memos.value,
            payments = payments.value,
            credits = credits.value,
            expenses = expenses.value,
            cashbox = cashboxTransactions.value,
            settings = settings.value
        )
    }

    fun buildPeriodReport(startMillis: Long, endMillis: Long): PeriodReportData {
        return repo.computePeriodReport(
            startMillis = startMillis,
            endMillis = endMillis,
            customers = customers.value,
            memos = memos.value,
            payments = payments.value,
            credits = credits.value,
            expenses = expenses.value,
            cashbox = cashboxTransactions.value
        )
    }

    suspend fun generateSingleCustomerLedgerPdf(customer: CustomerEntity): File = withContext(Dispatchers.IO) {
        val custMemos = memos.value.filter { it.memo.customerId == customer.id }
        val custCredits = credits.value.filter { it.customerId == customer.id }
        val custPayments = payments.value.filter { it.customerId == customer.id }
        val entries = repo.buildCustomerLedgerEntries(custMemos, custCredits, custPayments)
        pdfGenerator.generateCustomerLedgerPdf(customer, entries, settings.value)
    }

    suspend fun generateCustomersPdfZip(targetCustomers: List<CustomerEntity>, zipName: String): File =
        withContext(Dispatchers.IO) {
            val pairs = targetCustomers.map { c ->
                val cm = memos.value.filter { it.memo.customerId == c.id }
                val cc = credits.value.filter { it.customerId == c.id }
                val cp = payments.value.filter { it.customerId == c.id }
                c to repo.buildCustomerLedgerEntries(cm, cc, cp)
            }
            pdfGenerator.generateCustomerPdfZip(pairs, settings.value, zipName)
        }

    // endregion

    // region Backup, Restore, Settings & Security

    fun createBackupNow(type: String, onComplete: (BackupMetadataEntity?) -> Unit = {}) {
        if (_isSubmitting.value) return
        viewModelScope.launch {
            _isSubmitting.value = true
            try {
                val res = withContext(Dispatchers.IO) {
                    when (type) {
                        "JSON" -> backupManager.createJsonBackupFile()
                        "DB" -> backupManager.createRawDbBackupFile()
                        else -> backupManager.createZipBackup("LOCAL_ZIP")
                    }
                }
                when (res) {
                    is OperationResult.Success -> {
                        postMessage(res.messageBn)
                        onComplete(res.data)
                    }
                    is OperationResult.Error -> {
                        postMessage(res.messageBn)
                        onComplete(null)
                    }
                }
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    fun previewBackupUri(uri: Uri, fileName: String, onPreview: (OperationResult<BackupPreviewInfo>) -> Unit) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                val bytes = backupManager.readBytesFromUri(uri)
                    ?: return@withContext OperationResult.Error("ফাইল পড়া সম্ভব হয়নি")
                backupManager.previewBackupBytes(bytes, fileName)
            }
            onPreview(res)
        }
    }

    fun restoreFromUri(uri: Uri, fileName: String, onDone: (Boolean) -> Unit = {}) {
        if (_isSubmitting.value) return
        viewModelScope.launch {
            _isSubmitting.value = true
            try {
                val res = withContext(Dispatchers.IO) {
                    val bytes = backupManager.readBytesFromUri(uri)
                        ?: return@withContext OperationResult.Error("ফাইল পড়া সম্ভব হয়নি")
                    backupManager.restoreFromBytes(bytes, fileName)
                }
                when (res) {
                    is OperationResult.Success -> {
                        postMessage(res.data)
                        onDone(true)
                    }
                    is OperationResult.Error -> {
                        postMessage(res.messageBn)
                        onDone(false)
                    }
                }
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    fun verifyDatabaseIntegrity(onResult: (IntegrityCheckResult) -> Unit) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { db.verifyIntegrity() }
            onResult(res)
        }
    }

    fun updateSettings(transform: (AppSettingsEntity) -> AppSettingsEntity) {
        viewModelScope.launch {
            when (val res = repo.updateSettings(transform)) {
                is OperationResult.Success -> {
                    BackupManager.scheduleAutoBackup(app, res.data.backupFrequency)
                    postMessage(res.messageBn)
                }
                is OperationResult.Error -> postMessage(res.messageBn)
            }
        }
    }

    fun setAppPin(newPin: String) {
        if (newPin.length < 4) {
            postMessage("পিন অন্তত ৪ সংখ্যার হতে হবে")
            return
        }
        val salt = PinSecurityHelper.generateSalt()
        val hash = PinSecurityHelper.hashPin(newPin, salt)
        updateSettings { it.copy(pinSalt = salt, pinHash = hash) }
    }

    fun clearAppPin() {
        updateSettings { it.copy(pinSalt = "", pinHash = "", biometricEnabled = false) }
    }

    fun addCustomUnit(unit: String) {
        val clean = unit.trim()
        if (clean.isEmpty()) return
        val current = getCustomUnits()
        if (clean !in current) {
            val arr = JSONArray(current + clean)
            updateSettings { it.copy(customUnitsJson = arr.toString()) }
        }
    }

    fun getCustomUnits(): List<String> {
        return parseStringArray(settings.value.customUnitsJson)
    }

    fun addCustomExpenseCategory(category: String) {
        val clean = category.trim()
        if (clean.isEmpty()) return
        val current = getCustomExpenseCategories()
        if (clean !in current) {
            val arr = JSONArray(current + clean)
            updateSettings { it.copy(customExpenseCategoriesJson = arr.toString()) }
        }
    }

    fun getCustomExpenseCategories(): List<String> {
        return parseStringArray(settings.value.customExpenseCategoriesJson)
    }

    private fun parseStringArray(json: String): List<String> {
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // endregion
}
