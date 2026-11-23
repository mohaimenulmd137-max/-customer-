package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.*
import com.example.domain.*
import com.example.util.CurrencyUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AccountingRepository(private val db: BakirKhataDatabase) {

    private val customerDao = db.customerDao()
    private val memoDao = db.memoDao()
    private val paymentDao = db.paymentDao()
    private val creditDao = db.creditTransactionDao()
    private val expenseDao = db.expenseDao()
    private val cashboxDao = db.cashboxDao()
    private val halKhataDao = db.halKhataDao()
    private val tempDao = db.temporaryCustomerDao()
    private val auditDao = db.auditLogDao()
    private val backupDao = db.backupMetadataDao()
    private val settingsDao = db.appSettingsDao()

    val settingsFlow: Flow<AppSettingsEntity?> = settingsDao.observeSettings()
    val customersFlow: Flow<List<CustomerEntity>> = customerDao.observeAll()
    val memosFlow: Flow<List<MemoWithItems>> = memoDao.observeAllWithItems()
    val paymentsFlow: Flow<List<PaymentEntity>> = paymentDao.observeAll()
    val creditsFlow: Flow<List<CreditTransactionEntity>> = creditDao.observeAll()
    val expensesFlow: Flow<List<ExpenseEntity>> = expenseDao.observeAll()
    val cashboxFlow: Flow<List<CashboxTransactionEntity>> = cashboxDao.observeAll()
    val halKhataPeriodsFlow: Flow<List<HalKhataPeriodEntity>> = halKhataDao.observeAllPeriods()
    val activeHalKhataFlow: Flow<HalKhataPeriodEntity?> = halKhataDao.observeActivePeriod()
    val tempCustomersFlow: Flow<List<TemporaryCustomerEntity>> = tempDao.observeAll()
    val auditLogsFlow: Flow<List<AuditLogEntity>> = auditDao.observeAll()
    val backupMetadataFlow: Flow<List<BackupMetadataEntity>> = backupDao.observeAll()

    fun observeCustomer(customerId: Long): Flow<CustomerEntity?> = customerDao.observeById(customerId)
    fun observeHalKhataBalances(periodId: Long): Flow<List<HalKhataCustomerBalanceEntity>> =
        halKhataDao.observeBalancesByPeriod(periodId)
    fun observeTempMemos(tempCustomerId: Long): Flow<List<TemporaryMemoEntity>> =
        tempDao.observeMemosByTempCustomer(tempCustomerId)

    fun observeCustomerLedger(customerId: Long): Flow<List<CustomerLedgerEntry>> {
        return combine(
            memoDao.observeByCustomerWithItems(customerId),
            creditDao.observeByCustomer(customerId),
            paymentDao.observeByCustomer(customerId)
        ) { memos, credits, payments ->
            buildCustomerLedgerEntries(memos, credits, payments)
        }
    }

    fun observeDashboardSummary(): Flow<DashboardSummary> {
        val coreFlow = combine(
            customersFlow,
            memosFlow,
            paymentsFlow,
            creditsFlow,
            expensesFlow
        ) { customers, memos, payments, credits, expenses ->
            FiveTuple(customers, memos, payments, credits, expenses)
        }
        val metaFlow = combine(
            cashboxFlow,
            backupMetadataFlow,
            activeHalKhataFlow,
            settingsFlow
        ) { cashbox, backups, activePeriod, settings ->
            FourTuple(cashbox, backups, activePeriod, settings)
        }
        return combine(coreFlow, metaFlow) { core, meta ->
            computeDashboardSummary(
                customers = core.first,
                memos = core.second,
                payments = core.third,
                credits = core.fourth,
                expenses = core.fifth,
                cashbox = meta.first,
                backups = meta.second,
                activePeriod = meta.third,
                settings = meta.fourth ?: AppSettingsEntity()
            )
        }
    }

    fun observeCashboxSummary(): Flow<CashboxSummary> {
        return combine(cashboxFlow, settingsFlow) { txs, settings ->
            computeCashboxSummary(txs, settings ?: AppSettingsEntity())
        }
    }

    fun observeDebtAging(): Flow<List<DebtAgingRow>> {
        return customersFlow.combine(settingsFlow) { customers, _ ->
            computeDebtAging(customers)
        }
    }

    // region Customer Management

    suspend fun createCustomer(
        name: String,
        address: String,
        mobile: String = "",
        nickname: String = "",
        notes: String = "",
        openingBalancePaisa: Long = 0L,
        createdAt: Long = System.currentTimeMillis()
    ): OperationResult<CustomerEntity> {
        val cleanName = name.trim()
        val cleanAddress = address.trim()
        if (cleanName.isEmpty()) return OperationResult.Error("কাস্টমারের নাম লিখুন")
        if (cleanAddress.isEmpty()) return OperationResult.Error("ঠিকানা বা গ্রামের নাম লিখুন")
        if (openingBalancePaisa < 0L) return OperationResult.Error("প্রারম্ভিক বাকি শূন্যের কম হতে পারে না")

        return try {
            val saved = db.withTransaction {
                val periodId = getActivePeriodIdLocked()
                val nextNum = customerDao.getMaxId() + 1L
                val code = "CUST-${nextNum.toString().padStart(4, '0')}"
                val customer = CustomerEntity(
                    customerCode = code,
                    name = cleanName,
                    address = cleanAddress,
                    mobile = mobile.trim(),
                    nickname = nickname.trim(),
                    notes = notes.trim(),
                    openingBalancePaisa = openingBalancePaisa,
                    currentBalancePaisa = openingBalancePaisa,
                    totalPurchasesPaisa = 0L,
                    totalCreditPaisa = openingBalancePaisa,
                    totalPaymentsPaisa = 0L,
                    halKhataPeriodId = periodId,
                    oldestUnpaidTimestamp = if (openingBalancePaisa > 0L) createdAt else null,
                    lastTransactionDate = if (openingBalancePaisa > 0L) createdAt else null,
                    lastPaymentDate = null,
                    createdAt = createdAt
                )
                val newId = customerDao.insert(customer)
                val inserted = customer.copy(id = newId)

                if (openingBalancePaisa > 0L) {
                    val crNum = creditDao.getMaxId() + 1L
                    val crCode = "CRD-${crNum.toString().padStart(5, '0')}"
                    creditDao.insert(
                        CreditTransactionEntity(
                            transactionCode = crCode,
                            requestToken = "OPENING-$newId-${UUID.randomUUID()}",
                            customerId = newId,
                            amountPaisa = openingBalancePaisa,
                            previousBalancePaisa = 0L,
                            newBalancePaisa = openingBalancePaisa,
                            description = "পূর্বের / প্রারম্ভিক বাকি",
                            notes = notes.trim(),
                            sourceType = CreditSourceType.OPENING_CARRYOVER,
                            halKhataPeriodId = periodId,
                            createdAt = createdAt
                        )
                    )
                }

                auditDao.insert(
                    AuditLogEntity(
                        transactionRef = code,
                        entityType = "CUSTOMER",
                        entityId = newId,
                        actionType = "CREATE",
                        previousValueJson = "",
                        newValueJson = JSONObject().apply {
                            put("name", cleanName)
                            put("address", cleanAddress)
                            put("openingBalancePaisa", openingBalancePaisa)
                        }.toString(),
                        reason = "নতুন কাস্টমার নিবন্ধন",
                        relatedCustomerId = newId,
                        createdAt = createdAt
                    )
                )
                inserted
            }
            OperationResult.Success(saved, "নতুন কাস্টমার '${saved.name}' (${saved.customerCode}) যুক্ত হয়েছে")
        } catch (e: Exception) {
            OperationResult.Error("কাস্টমার যোগ করা যায়নি: ${e.message ?: "ত্রুটি"}")
        }
    }

    suspend fun updateCustomer(
        customerId: Long,
        name: String,
        address: String,
        mobile: String,
        nickname: String,
        notes: String
    ): OperationResult<CustomerEntity> {
        val cleanName = name.trim()
        val cleanAddress = address.trim()
        if (cleanName.isEmpty()) return OperationResult.Error("কাস্টমারের নাম খালি রাখা যাবে না")
        if (cleanAddress.isEmpty()) return OperationResult.Error("ঠিকানা খালি রাখা যাবে না")

        return try {
            val updated = db.withTransaction {
                val existing = customerDao.getById(customerId)
                    ?: throw IllegalStateException("কাস্টমার পাওয়া যায়নি")
                val next = existing.copy(
                    name = cleanName,
                    address = cleanAddress,
                    mobile = mobile.trim(),
                    nickname = nickname.trim(),
                    notes = notes.trim()
                )
                customerDao.update(next)
                auditDao.insert(
                    AuditLogEntity(
                        transactionRef = existing.customerCode,
                        entityType = "CUSTOMER",
                        entityId = customerId,
                        actionType = "UPDATE",
                        previousValueJson = JSONObject().apply {
                            put("name", existing.name)
                            put("address", existing.address)
                            put("mobile", existing.mobile)
                            put("nickname", existing.nickname)
                        }.toString(),
                        newValueJson = JSONObject().apply {
                            put("name", next.name)
                            put("address", next.address)
                            put("mobile", next.mobile)
                            put("nickname", next.nickname)
                        }.toString(),
                        reason = "কাস্টমারের তথ্য সংশোধন",
                        relatedCustomerId = customerId
                    )
                )
                next
            }
            OperationResult.Success(updated, "কাস্টমারের তথ্য সফলভাবে হালনাগাদ হয়েছে")
        } catch (e: Exception) {
            OperationResult.Error(e.message ?: "তথ্য হালনাগাদ ব্যর্থ হয়েছে")
        }
    }

    // endregion

    // region Quick Payment / জমা

    suspend fun recordQuickPayment(
        customerId: Long,
        amountPaisa: Long,
        notes: String = "",
        requestToken: String = UUID.randomUUID().toString(),
        timestamp: Long = System.currentTimeMillis()
    ): OperationResult<PaymentEntity> {
        if (amountPaisa <= 0L) {
            return OperationResult.Error("জমার পরিমাণ শূন্যের চেয়ে বেশি হতে হবে")
        }

        return try {
            val result = db.withTransaction {
                // Idempotency guard against duplicate taps
                val existingByToken = paymentDao.findByRequestToken(requestToken)
                if (existingByToken != null) {
                    return@withTransaction existingByToken
                }

                val customer = customerDao.getById(customerId)
                    ?: throw IllegalStateException("নির্বাচিত কাস্টমার খুঁজে পাওয়া যায়নি")

                if (customer.currentBalancePaisa <= 0L) {
                    throw IllegalStateException("এই কাস্টমারের কোনো বকেয়া নেই। শূন্য বাকিতে জমা নেওয়া যাবে না।")
                }
                if (amountPaisa > customer.currentBalancePaisa) {
                    throw IllegalStateException(
                        "বকেয়ার (${CurrencyUtils.formatBdt(customer.currentBalancePaisa)}) চেয়ে বেশি টাকা জমা নেওয়া যাবে না।"
                    )
                }

                val periodId = getActivePeriodIdLocked()
                val nextPayNum = paymentDao.getMaxId() + 1L
                val receiptNumber = "PAY-${nextPayNum.toString().padStart(5, '0')}"
                val prevBalance = customer.currentBalancePaisa
                val newBalance = prevBalance - amountPaisa

                val payment = PaymentEntity(
                    receiptNumber = receiptNumber,
                    requestToken = requestToken,
                    customerId = customerId,
                    amountPaisa = amountPaisa,
                    previousBalancePaisa = prevBalance,
                    newBalancePaisa = newBalance,
                    memoId = null,
                    notes = notes.trim(),
                    halKhataPeriodId = periodId,
                    createdAt = timestamp
                )
                val paymentId = paymentDao.insert(payment)
                val savedPayment = payment.copy(id = paymentId)

                // Add to Cashbox atomically
                val cbxNum = cashboxDao.getMaxId() + 1L
                val cbxCode = "CBX-${cbxNum.toString().padStart(5, '0')}"
                cashboxDao.insert(
                    CashboxTransactionEntity(
                        transactionCode = cbxCode,
                        requestToken = "CBX-PAY-$requestToken",
                        type = CashboxType.CUSTOMER_PAYMENT,
                        direction = CashboxDirection.IN,
                        amountPaisa = amountPaisa,
                        sourceType = "PAYMENT",
                        sourceId = paymentId,
                        description = "কাস্টমার জমা: ${customer.name} ($receiptNumber)",
                        notes = notes.trim(),
                        halKhataPeriodId = periodId,
                        createdAt = timestamp
                    )
                )

                // Recalculate customer ledger balances safely
                recalculateCustomerLocked(customerId, lastPaymentAt = timestamp, lastTxAt = timestamp)

                auditDao.insert(
                    AuditLogEntity(
                        transactionRef = receiptNumber,
                        entityType = "PAYMENT",
                        entityId = paymentId,
                        actionType = "CREATE",
                        previousValueJson = JSONObject().put("balancePaisa", prevBalance).toString(),
                        newValueJson = JSONObject().put("balancePaisa", newBalance).put("paidPaisa", amountPaisa).toString(),
                        reason = "দ্রুত জমা গ্রহণ",
                        relatedCustomerId = customerId,
                        relatedPaymentId = paymentId,
                        createdAt = timestamp
                    )
                )

                savedPayment
            }
            OperationResult.Success(result, "জমা ${CurrencyUtils.formatBdt(result.amountPaisa)} সফলভাবে সংরক্ষিত হয়েছে")
        } catch (e: Exception) {
            OperationResult.Error(e.message ?: "জমা সংরক্ষণ করা যায়নি")
        }
    }

    // endregion

    // region Quick Credit / বাকি

    suspend fun recordQuickCredit(
        customerId: Long,
        amountPaisa: Long,
        description: String = "",
        notes: String = "",
        requestToken: String = UUID.randomUUID().toString(),
        timestamp: Long = System.currentTimeMillis()
    ): OperationResult<CreditTransactionEntity> {
        if (amountPaisa <= 0L) {
            return OperationResult.Error("বাকির পরিমাণ শূন্যের চেয়ে বেশি হতে হবে")
        }

        return try {
            val saved = db.withTransaction {
                val existingByToken = creditDao.findByRequestToken(requestToken)
                if (existingByToken != null) {
                    return@withTransaction existingByToken
                }

                val customer = customerDao.getById(customerId)
                    ?: throw IllegalStateException("নির্বাচিত কাস্টমার পাওয়া যায়নি")

                val periodId = getActivePeriodIdLocked()
                val nextNum = creditDao.getMaxId() + 1L
                val code = "CRD-${nextNum.toString().padStart(5, '0')}"
                val prevBalance = customer.currentBalancePaisa
                val newBalance = prevBalance + amountPaisa

                val credit = CreditTransactionEntity(
                    transactionCode = code,
                    requestToken = requestToken,
                    customerId = customerId,
                    amountPaisa = amountPaisa,
                    previousBalancePaisa = prevBalance,
                    newBalancePaisa = newBalance,
                    description = description.trim().ifEmpty { "দ্রুত বাকি" },
                    notes = notes.trim(),
                    sourceType = CreditSourceType.QUICK_CREDIT,
                    memoId = null,
                    halKhataPeriodId = periodId,
                    createdAt = timestamp
                )
                val creditId = creditDao.insert(credit)
                val inserted = credit.copy(id = creditId)

                recalculateCustomerLocked(customerId, lastTxAt = timestamp)

                auditDao.insert(
                    AuditLogEntity(
                        transactionRef = code,
                        entityType = "CREDIT",
                        entityId = creditId,
                        actionType = "CREATE",
                        previousValueJson = JSONObject().put("balancePaisa", prevBalance).toString(),
                        newValueJson = JSONObject().put("balancePaisa", newBalance).put("creditPaisa", amountPaisa).toString(),
                        reason = description.trim().ifEmpty { "দ্রুত বাকি যোগ" },
                        relatedCustomerId = customerId,
                        createdAt = timestamp
                    )
                )

                inserted
            }
            OperationResult.Success(saved, "বাকি ${CurrencyUtils.formatBdt(saved.amountPaisa)} যুক্ত হয়েছে")
        } catch (e: Exception) {
            OperationResult.Error(e.message ?: "বাকি সংরক্ষণ করা যায়নি")
        }
    }

    // endregion

    // region Sales Memo System

    suspend fun createSalesMemo(
        saleType: String,
        customerId: Long?,
        walkInName: String = "নগদ ক্রেতা",
        walkInAddress: String = "",
        items: List<MemoLineDraft>,
        discountPaisa: Long = 0L,
        paidPaisaInput: Long = 0L,
        notes: String = "",
        requestToken: String = UUID.randomUUID().toString(),
        timestamp: Long = System.currentTimeMillis()
    ): OperationResult<MemoWithItems> {
        if (items.isEmpty()) {
            return OperationResult.Error("অন্তত একটি পণ্য যোগ করুন")
        }
        if (discountPaisa < 0L) {
            return OperationResult.Error("ছাড় ঋণাত্মক হতে পারে না")
        }

        val subtotalPaisa = items.sumOf { it.lineTotalPaisa }
        if (subtotalPaisa <= 0L) {
            return OperationResult.Error("মোট বিক্রয় মূল্য শূন্যের চেয়ে বেশি হতে হবে")
        }
        if (discountPaisa > subtotalPaisa) {
            return OperationResult.Error("ছাড় মোট মূল্যের চেয়ে বেশি হতে পারে না")
        }

        val grandTotalPaisa = subtotalPaisa - discountPaisa
        val paidPaisa: Long
        val outstandingPaisa: Long

        when (saleType) {
            SaleType.CASH -> {
                paidPaisa = grandTotalPaisa
                outstandingPaisa = 0L
            }
            SaleType.CREDIT -> {
                if (customerId == null) {
                    return OperationResult.Error("বাকি বিক্রির জন্য কাস্টমার নির্বাচন করা আবশ্যক")
                }
                paidPaisa = 0L
                outstandingPaisa = grandTotalPaisa
            }
            SaleType.PARTIAL -> {
                if (customerId == null) {
                    return OperationResult.Error("আংশিক জমার মেমোর জন্য কাস্টমার নির্বাচন করুন")
                }
                if (paidPaisaInput <= 0L || paidPaisaInput >= grandTotalPaisa) {
                    return OperationResult.Error("আংশিক জমা অবশ্যই শূন্যের বেশি এবং মোট বিলের চেয়ে কম হতে হবে")
                }
                paidPaisa = paidPaisaInput
                outstandingPaisa = grandTotalPaisa - paidPaisa
            }
            else -> return OperationResult.Error("অবৈধ বিক্রির ধরন")
        }

        return try {
            val saved = db.withTransaction {
                val existingMemo = memoDao.findByRequestToken(requestToken)
                if (existingMemo != null) {
                    return@withTransaction memoDao.getWithItemsById(existingMemo.id)!!
                }

                val customer = if (customerId != null) {
                    customerDao.getById(customerId)
                        ?: throw IllegalStateException("নির্বাচিত কাস্টমার খুঁজে পাওয়া যায়নি")
                } else null

                val settings = settingsDao.getSettings() ?: AppSettingsEntity()
                val prefix = settings.memoPrefix.ifBlank { "MEMO" }
                val periodId = getActivePeriodIdLocked()
                val nextMemoNum = memoDao.getMaxId() + 1L
                val memoNumber = "$prefix-${nextMemoNum.toString().padStart(5, '0')}"

                val memo = MemoEntity(
                    memoNumber = memoNumber,
                    requestToken = requestToken,
                    customerId = customer?.id,
                    customerNameSnapshot = customer?.name ?: walkInName.trim().ifEmpty { "নগদ ক্রেতা" },
                    customerAddressSnapshot = customer?.address ?: walkInAddress.trim(),
                    saleType = saleType,
                    subtotalPaisa = subtotalPaisa,
                    discountPaisa = discountPaisa,
                    grandTotalPaisa = grandTotalPaisa,
                    paidPaisa = paidPaisa,
                    outstandingPaisa = outstandingPaisa,
                    notes = notes.trim(),
                    halKhataPeriodId = periodId,
                    createdAt = timestamp,
                    updatedAt = timestamp
                )
                val memoId = memoDao.insertMemo(memo)

                val itemEntities = items.map { draft ->
                    MemoItemEntity(
                        memoId = memoId,
                        productName = draft.productName.trim(),
                        quantityMillis = draft.quantityMillis,
                        unit = draft.unit.trim(),
                        unitPricePaisa = draft.unitPricePaisa,
                        lineTotalPaisa = draft.lineTotalPaisa
                    )
                }
                memoDao.insertItems(itemEntities)

                // Record cash inflow ONLY for actually received cash (paidPaisa)
                if (paidPaisa > 0L) {
                    val cbxNum = cashboxDao.getMaxId() + 1L
                    cashboxDao.insert(
                        CashboxTransactionEntity(
                            transactionCode = "CBX-${cbxNum.toString().padStart(5, '0')}",
                            requestToken = "CBX-MEMO-$requestToken",
                            type = CashboxType.CASH_SALE,
                            direction = CashboxDirection.IN,
                            amountPaisa = paidPaisa,
                            sourceType = "MEMO",
                            sourceId = memoId,
                            description = "নগদ বিক্রয় মেমো: ${memo.customerNameSnapshot} ($memoNumber)",
                            notes = notes.trim(),
                            halKhataPeriodId = periodId,
                            createdAt = timestamp
                        )
                    )
                }

                // If customer selected and partial/credit sale, record credit & payment accurately
                if (customer != null) {
                    val prevBal = customer.currentBalancePaisa
                    if (outstandingPaisa > 0L) {
                        val crNum = creditDao.getMaxId() + 1L
                        creditDao.insert(
                            CreditTransactionEntity(
                                transactionCode = "CRD-${crNum.toString().padStart(5, '0')}",
                                requestToken = "CRD-MEMO-$requestToken",
                                customerId = customer.id,
                                amountPaisa = outstandingPaisa,
                                previousBalancePaisa = prevBal,
                                newBalancePaisa = prevBal + outstandingPaisa,
                                description = "বিক্রয় মেমো বকেয়া ($memoNumber)",
                                notes = notes.trim(),
                                sourceType = CreditSourceType.MEMO,
                                memoId = memoId,
                                halKhataPeriodId = periodId,
                                createdAt = timestamp
                            )
                        )
                    }
                    recalculateCustomerLocked(
                        customerId = customer.id,
                        lastTxAt = timestamp
                    )
                }

                auditDao.insert(
                    AuditLogEntity(
                        transactionRef = memoNumber,
                        entityType = "MEMO",
                        entityId = memoId,
                        actionType = "CREATE",
                        previousValueJson = "",
                        newValueJson = JSONObject().apply {
                            put("saleType", saleType)
                            put("grandTotalPaisa", grandTotalPaisa)
                            put("paidPaisa", paidPaisa)
                            put("outstandingPaisa", outstandingPaisa)
                        }.toString(),
                        reason = "নতুন বিক্রয় মেমো তৈরি",
                        relatedCustomerId = customer?.id,
                        relatedMemoId = memoId,
                        createdAt = timestamp
                    )
                )

                MemoWithItems(memo.copy(id = memoId), itemEntities)
            }
            OperationResult.Success(saved, "মেমো ${saved.memo.memoNumber} সফলভাবে সংরক্ষিত হয়েছে")
        } catch (e: Exception) {
            OperationResult.Error(e.message ?: "মেমো সংরক্ষণ করা যায়নি")
        }
    }

    // endregion

    // region Error Correction & Reversals (Non-destructive + Audit Trail)

    suspend fun reversePayment(paymentId: Long, reason: String): OperationResult<Unit> {
        val cleanReason = reason.trim()
        if (cleanReason.isEmpty()) return OperationResult.Error("বাতিল/সংশোধনের কারণ উল্লেখ করুন")

        return try {
            db.withTransaction {
                val payment = paymentDao.getById(paymentId)
                    ?: throw IllegalStateException("জমা লেনদেন পাওয়া যায়নি")
                if (payment.isReversed) throw IllegalStateException("এই জমা আগেই বাতিল/রিভার্স করা হয়েছে")

                val reversedPayment = payment.copy(isReversed = true)
                paymentDao.update(reversedPayment)

                // Reverse linked cashbox transaction non-destructively
                val cbx = cashboxDao.findActiveBySource("PAYMENT", paymentId)
                if (cbx != null) {
                    cashboxDao.update(cbx.copy(isReversed = true))
                    val corrNum = cashboxDao.getMaxId() + 1L
                    cashboxDao.insert(
                        CashboxTransactionEntity(
                            transactionCode = "CBX-${corrNum.toString().padStart(5, '0')}",
                            requestToken = "REV-CBX-PAY-$paymentId-${UUID.randomUUID()}",
                            type = CashboxType.CORRECTION,
                            direction = CashboxDirection.OUT,
                            amountPaisa = cbx.amountPaisa,
                            sourceType = "PAYMENT_REVERSAL",
                            sourceId = paymentId,
                            description = "জমা বাতিল সমন্বয় (${payment.receiptNumber})",
                            notes = cleanReason,
                            halKhataPeriodId = payment.halKhataPeriodId
                        )
                    )
                }

                recalculateCustomerLocked(payment.customerId)

                auditDao.insert(
                    AuditLogEntity(
                        transactionRef = payment.receiptNumber,
                        entityType = "PAYMENT",
                        entityId = paymentId,
                        actionType = "REVERSAL",
                        previousValueJson = JSONObject().put("amountPaisa", payment.amountPaisa).put("isReversed", false).toString(),
                        newValueJson = JSONObject().put("amountPaisa", payment.amountPaisa).put("isReversed", true).toString(),
                        reason = cleanReason,
                        relatedCustomerId = payment.customerId,
                        relatedPaymentId = paymentId
                    )
                )
            }
            OperationResult.Success(Unit, "জমা রিভার্সাল সম্পন্ন হয়েছে এবং অডিট লগ সংরক্ষিত হয়েছে")
        } catch (e: Exception) {
            OperationResult.Error(e.message ?: "জমা রিভার্সাল ব্যর্থ হয়েছে")
        }
    }

    suspend fun correctPayment(paymentId: Long, newAmountPaisa: Long, reason: String): OperationResult<PaymentEntity> {
        val cleanReason = reason.trim()
        if (cleanReason.isEmpty()) return OperationResult.Error("সংশোধনের কারণ উল্লেখ করুন")
        if (newAmountPaisa <= 0L) return OperationResult.Error("নতুন পরিমাণ শূন্যের চেয়ে বেশি হতে হবে")

        return try {
            val newPayment = db.withTransaction {
                val old = paymentDao.getById(paymentId)
                    ?: throw IllegalStateException("জমা পাওয়া যায়নি")
                if (old.isReversed) throw IllegalStateException("রিভার্স হওয়া জমা সংশোধন করা যায় না")

                val customer = customerDao.getById(old.customerId)
                    ?: throw IllegalStateException("কাস্টমার পাওয়া যায়নি")
                val restoredDebt = customer.currentBalancePaisa + old.amountPaisa
                if (newAmountPaisa > restoredDebt) {
                    throw IllegalStateException("সংশোধিত জমা বকেয়ার চেয়ে বেশি হতে পারে না")
                }

                // Mark old payment reversed, preserve history, create corrected payment
                paymentDao.update(old.copy(isReversed = true))
                val oldCbx = cashboxDao.findActiveBySource("PAYMENT", paymentId)
                if (oldCbx != null) {
                    cashboxDao.update(oldCbx.copy(isReversed = true))
                }

                val nextPayNum = paymentDao.getMaxId() + 1L
                val newReceipt = "PAY-${nextPayNum.toString().padStart(5, '0')}"
                val replacement = PaymentEntity(
                    receiptNumber = newReceipt,
                    requestToken = "CORR-PAY-$paymentId-${UUID.randomUUID()}",
                    customerId = old.customerId,
                    amountPaisa = newAmountPaisa,
                    previousBalancePaisa = restoredDebt,
                    newBalancePaisa = restoredDebt - newAmountPaisa,
                    memoId = old.memoId,
                    notes = "${old.notes} [সংশোধিত: $cleanReason]".trim(),
                    halKhataPeriodId = old.halKhataPeriodId,
                    createdAt = old.createdAt
                )
                val newId = paymentDao.insert(replacement)
                paymentDao.update(old.copy(isReversed = true, reversedById = newId))

                val cbxNum = cashboxDao.getMaxId() + 1L
                cashboxDao.insert(
                    CashboxTransactionEntity(
                        transactionCode = "CBX-${cbxNum.toString().padStart(5, '0')}",
                        requestToken = "CBX-CORR-PAY-$newId",
                        type = CashboxType.CUSTOMER_PAYMENT,
                        direction = CashboxDirection.IN,
                        amountPaisa = newAmountPaisa,
                        sourceType = "PAYMENT",
                        sourceId = newId,
                        description = "সংশোধিত জমা: ${customer.name} ($newReceipt)",
                        notes = cleanReason,
                        halKhataPeriodId = old.halKhataPeriodId,
                        createdAt = old.createdAt
                    )
                )

                recalculateCustomerLocked(old.customerId)

                auditDao.insert(
                    AuditLogEntity(
                        transactionRef = "${old.receiptNumber} -> $newReceipt",
                        entityType = "PAYMENT",
                        entityId = paymentId,
                        actionType = "CORRECTION",
                        previousValueJson = JSONObject().put("amountPaisa", old.amountPaisa).toString(),
                        newValueJson = JSONObject().put("amountPaisa", newAmountPaisa).toString(),
                        reason = cleanReason,
                        relatedCustomerId = old.customerId,
                        relatedPaymentId = newId
                    )
                )
                replacement.copy(id = newId)
            }
            OperationResult.Success(newPayment, "জমা সংশোধন সম্পন্ন হয়েছে")
        } catch (e: Exception) {
            OperationResult.Error(e.message ?: "জমা সংশোধন ব্যর্থ হয়েছে")
        }
    }

    suspend fun reverseCredit(creditId: Long, reason: String): OperationResult<Unit> {
        val cleanReason = reason.trim()
        if (cleanReason.isEmpty()) return OperationResult.Error("বাতিলের কারণ লিখুন")

        return try {
            db.withTransaction {
                val credit = creditDao.getById(creditId)
                    ?: throw IllegalStateException("বাকি লেনদেন পাওয়া যায়নি")
                if (credit.isReversed) throw IllegalStateException("এই বাকি আগেই রিভার্স করা হয়েছে")

                creditDao.update(credit.copy(isReversed = true))
                recalculateCustomerLocked(credit.customerId)

                auditDao.insert(
                    AuditLogEntity(
                        transactionRef = credit.transactionCode,
                        entityType = "CREDIT",
                        entityId = creditId,
                        actionType = "REVERSAL",
                        previousValueJson = JSONObject().put("amountPaisa", credit.amountPaisa).put("isReversed", false).toString(),
                        newValueJson = JSONObject().put("amountPaisa", credit.amountPaisa).put("isReversed", true).toString(),
                        reason = cleanReason,
                        relatedCustomerId = credit.customerId
                    )
                )
            }
            OperationResult.Success(Unit, "বাকি লেনদেন বাতিল (রিভার্স) করা হয়েছে")
        } catch (e: Exception) {
            OperationResult.Error(e.message ?: "বাকি বাতিল ব্যর্থ হয়েছে")
        }
    }

    suspend fun cancelOrCorrectMemo(
        memoId: Long,
        reason: String,
        replacementDiscountPaisa: Long? = null,
        replacementPaidPaisa: Long? = null
    ): OperationResult<Unit> {
        val cleanReason = reason.trim()
        if (cleanReason.isEmpty()) return OperationResult.Error("মেমো সংশোধন/বাতিলের কারণ লিখুন")

        return try {
            db.withTransaction {
                val memoWithItems = memoDao.getWithItemsById(memoId)
                    ?: throw IllegalStateException("মেমো পাওয়া যায়নি")
                val memo = memoWithItems.memo
                if (memo.isCancelled) throw IllegalStateException("এই মেমো আগেই বাতিল করা হয়েছে")

                if (replacementDiscountPaisa == null && replacementPaidPaisa == null) {
                    // Cancel memo non-destructively
                    memoDao.updateMemo(memo.copy(isCancelled = true, updatedAt = System.currentTimeMillis()))

                    val cbx = cashboxDao.findActiveBySource("MEMO", memoId)
                    if (cbx != null) {
                        cashboxDao.update(cbx.copy(isReversed = true))
                    }
                    // Mark any credit linked to this memo reversed
                    creditDao.getAllOnce()
                        .filter { it.memoId == memoId && !it.isReversed }
                        .forEach { creditDao.update(it.copy(isReversed = true)) }

                    memo.customerId?.let { recalculateCustomerLocked(it) }

                    auditDao.insert(
                        AuditLogEntity(
                            transactionRef = memo.memoNumber,
                            entityType = "MEMO",
                            entityId = memoId,
                            actionType = "REVERSAL",
                            previousValueJson = JSONObject().put("grandTotalPaisa", memo.grandTotalPaisa).put("isCancelled", false).toString(),
                            newValueJson = JSONObject().put("isCancelled", true).toString(),
                            reason = cleanReason,
                            relatedCustomerId = memo.customerId,
                            relatedMemoId = memoId
                        )
                    )
                } else {
                    val newDiscount = replacementDiscountPaisa ?: memo.discountPaisa
                    if (newDiscount < 0 || newDiscount > memo.subtotalPaisa) {
                        throw IllegalStateException("অবৈধ ছাড়ের পরিমাণ")
                    }
                    val newGrand = memo.subtotalPaisa - newDiscount
                    val newPaid = when (memo.saleType) {
                        SaleType.CASH -> newGrand
                        SaleType.CREDIT -> 0L
                        else -> (replacementPaidPaisa ?: memo.paidPaisa).coerceIn(0L, newGrand)
                    }
                    val newOutstanding = newGrand - newPaid
                    if (newOutstanding > 0L && memo.customerId == null) {
                        throw IllegalStateException("বকেয়া রাখার জন্য কাস্টমার আবশ্যক")
                    }

                    memoDao.updateMemo(
                        memo.copy(
                            discountPaisa = newDiscount,
                            grandTotalPaisa = newGrand,
                            paidPaisa = newPaid,
                            outstandingPaisa = newOutstanding,
                            notes = "${memo.notes} [সংশোধিত: $cleanReason]".trim(),
                            updatedAt = System.currentTimeMillis()
                        )
                    )

                    val existingCbx = cashboxDao.findActiveBySource("MEMO", memoId)
                    if (existingCbx != null) {
                        cashboxDao.update(existingCbx.copy(isReversed = true))
                    }
                    if (newPaid > 0L) {
                        val cbxNum = cashboxDao.getMaxId() + 1L
                        cashboxDao.insert(
                            CashboxTransactionEntity(
                                transactionCode = "CBX-${cbxNum.toString().padStart(5, '0')}",
                                requestToken = "CBX-MEMO-CORR-$memoId-${UUID.randomUUID()}",
                                type = CashboxType.CASH_SALE,
                                direction = CashboxDirection.IN,
                                amountPaisa = newPaid,
                                sourceType = "MEMO",
                                sourceId = memoId,
                                description = "সংশোধিত বিক্রয় মেমো (${memo.memoNumber})",
                                notes = cleanReason,
                                halKhataPeriodId = memo.halKhataPeriodId,
                                createdAt = memo.createdAt
                            )
                        )
                    }

                    val existingCredits = creditDao.getAllOnce().filter { it.memoId == memoId && !it.isReversed }
                    existingCredits.forEach { creditDao.update(it.copy(isReversed = true)) }
                    if (newOutstanding > 0L && memo.customerId != null) {
                        val crNum = creditDao.getMaxId() + 1L
                        val cust = customerDao.getById(memo.customerId)!!
                        creditDao.insert(
                            CreditTransactionEntity(
                                transactionCode = "CRD-${crNum.toString().padStart(5, '0')}",
                                requestToken = "CRD-MEMO-CORR-$memoId-${UUID.randomUUID()}",
                                customerId = memo.customerId,
                                amountPaisa = newOutstanding,
                                previousBalancePaisa = cust.currentBalancePaisa,
                                newBalancePaisa = cust.currentBalancePaisa + newOutstanding,
                                description = "সংশোধিত মেমো বকেয়া (${memo.memoNumber})",
                                notes = cleanReason,
                                sourceType = CreditSourceType.MEMO,
                                memoId = memoId,
                                halKhataPeriodId = memo.halKhataPeriodId,
                                createdAt = memo.createdAt
                            )
                        )
                    }

                    memo.customerId?.let { recalculateCustomerLocked(it) }

                    auditDao.insert(
                        AuditLogEntity(
                            transactionRef = memo.memoNumber,
                            entityType = "MEMO",
                            entityId = memoId,
                            actionType = "CORRECTION",
                            previousValueJson = JSONObject()
                                .put("grandTotalPaisa", memo.grandTotalPaisa)
                                .put("paidPaisa", memo.paidPaisa)
                                .put("outstandingPaisa", memo.outstandingPaisa)
                                .toString(),
                            newValueJson = JSONObject()
                                .put("grandTotalPaisa", newGrand)
                                .put("paidPaisa", newPaid)
                                .put("outstandingPaisa", newOutstanding)
                                .toString(),
                            reason = cleanReason,
                            relatedCustomerId = memo.customerId,
                            relatedMemoId = memoId
                        )
                    )
                }
            }
            OperationResult.Success(Unit, "মেমো সংশোধন সম্পন্ন হয়েছে")
        } catch (e: Exception) {
            OperationResult.Error(e.message ?: "মেমো সংশোধন ব্যর্থ হয়েছে")
        }
    }

    // endregion

    // region Expense Management

    suspend fun addExpense(
        category: String,
        amountPaisa: Long,
        description: String,
        notes: String = "",
        paidFromCashbox: Boolean = true,
        requestToken: String = UUID.randomUUID().toString(),
        timestamp: Long = System.currentTimeMillis()
    ): OperationResult<ExpenseEntity> {
        val cleanCat = category.trim()
        val cleanDesc = description.trim()
        if (cleanCat.isEmpty()) return OperationResult.Error("খরচের খাত নির্বাচন করুন")
        if (amountPaisa <= 0L) return OperationResult.Error("খরচের পরিমাণ শূন্যের চেয়ে বেশি হতে হবে")

        return try {
            val saved = db.withTransaction {
                val existing = expenseDao.findByRequestToken(requestToken)
                if (existing != null) return@withTransaction existing

                val periodId = getActivePeriodIdLocked()
                val nextNum = expenseDao.getMaxId() + 1L
                val code = "EXP-${nextNum.toString().padStart(5, '0')}"
                val expense = ExpenseEntity(
                    expenseCode = code,
                    requestToken = requestToken,
                    category = cleanCat,
                    amountPaisa = amountPaisa,
                    description = cleanDesc.ifEmpty { cleanCat },
                    notes = notes.trim(),
                    paidFromCashbox = paidFromCashbox,
                    halKhataPeriodId = periodId,
                    createdAt = timestamp,
                    updatedAt = timestamp
                )
                val expenseId = expenseDao.insert(expense)

                if (paidFromCashbox) {
                    val cbxNum = cashboxDao.getMaxId() + 1L
                    cashboxDao.insert(
                        CashboxTransactionEntity(
                            transactionCode = "CBX-${cbxNum.toString().padStart(5, '0')}",
                            requestToken = "CBX-EXP-$requestToken",
                            type = CashboxType.EXPENSE,
                            direction = CashboxDirection.OUT,
                            amountPaisa = amountPaisa,
                            sourceType = "EXPENSE",
                            sourceId = expenseId,
                            description = "খরচ ($cleanCat): ${expense.description}",
                            notes = notes.trim(),
                            halKhataPeriodId = periodId,
                            createdAt = timestamp
                        )
                    )
                }

                auditDao.insert(
                    AuditLogEntity(
                        transactionRef = code,
                        entityType = "EXPENSE",
                        entityId = expenseId,
                        actionType = "CREATE",
                        previousValueJson = "",
                        newValueJson = JSONObject().put("category", cleanCat).put("amountPaisa", amountPaisa).toString(),
                        reason = expense.description,
                        createdAt = timestamp
                    )
                )
                expense.copy(id = expenseId)
            }
            OperationResult.Success(saved, "খরচ ${CurrencyUtils.formatBdt(saved.amountPaisa)} সংরক্ষিত হয়েছে")
        } catch (e: Exception) {
            OperationResult.Error(e.message ?: "খরচ সংরক্ষণ করা যায়নি")
        }
    }

    suspend fun updateExpense(
        expenseId: Long,
        category: String,
        amountPaisa: Long,
        description: String,
        notes: String,
        paidFromCashbox: Boolean,
        reason: String
    ): OperationResult<ExpenseEntity> {
        val cleanReason = reason.trim()
        if (cleanReason.isEmpty()) return OperationResult.Error("সংশোধনের কারণ লিখুন")
        if (amountPaisa <= 0L) return OperationResult.Error("খরচের পরিমাণ শূন্যের চেয়ে বেশি হতে হবে")

        return try {
            val updated = db.withTransaction {
                val old = expenseDao.getById(expenseId) ?: throw IllegalStateException("খরচ পাওয়া যায়নি")
                val next = old.copy(
                    category = category.trim(),
                    amountPaisa = amountPaisa,
                    description = description.trim().ifEmpty { category.trim() },
                    notes = notes.trim(),
                    paidFromCashbox = paidFromCashbox,
                    updatedAt = System.currentTimeMillis()
                )
                expenseDao.update(next)

                val existingCbx = cashboxDao.findActiveBySource("EXPENSE", expenseId)
                if (existingCbx != null) {
                    cashboxDao.update(existingCbx.copy(isReversed = true))
                }
                if (paidFromCashbox) {
                    val cbxNum = cashboxDao.getMaxId() + 1L
                    cashboxDao.insert(
                        CashboxTransactionEntity(
                            transactionCode = "CBX-${cbxNum.toString().padStart(5, '0')}",
                            requestToken = "CBX-EXP-EDIT-$expenseId-${UUID.randomUUID()}",
                            type = CashboxType.EXPENSE,
                            direction = CashboxDirection.OUT,
                            amountPaisa = amountPaisa,
                            sourceType = "EXPENSE",
                            sourceId = expenseId,
                            description = "সংশোধিত খরচ (${next.category}): ${next.description}",
                            notes = cleanReason,
                            halKhataPeriodId = old.halKhataPeriodId,
                            createdAt = old.createdAt
                        )
                    )
                }

                auditDao.insert(
                    AuditLogEntity(
                        transactionRef = old.expenseCode,
                        entityType = "EXPENSE",
                        entityId = expenseId,
                        actionType = "CORRECTION",
                        previousValueJson = JSONObject().put("category", old.category).put("amountPaisa", old.amountPaisa).toString(),
                        newValueJson = JSONObject().put("category", next.category).put("amountPaisa", next.amountPaisa).toString(),
                        reason = cleanReason
                    )
                )
                next
            }
            OperationResult.Success(updated, "খরচ হালনাগাদ হয়েছে")
        } catch (e: Exception) {
            OperationResult.Error(e.message ?: "খরচ সংশোধন ব্যর্থ হয়েছে")
        }
    }

    // endregion

    // region Cashbox Manual Entries

    suspend fun addCashboxManualEntry(
        type: String,
        direction: String,
        amountPaisa: Long,
        description: String,
        notes: String = "",
        requestToken: String = UUID.randomUUID().toString(),
        timestamp: Long = System.currentTimeMillis()
    ): OperationResult<CashboxTransactionEntity> {
        if (amountPaisa <= 0L) return OperationResult.Error("টাকার পরিমাণ শূন্যের চেয়ে বেশি হতে হবে")
        val cleanDesc = description.trim()
        if (cleanDesc.isEmpty()) return OperationResult.Error("বিবরণ লিখুন")

        return try {
            val saved = db.withTransaction {
                val existing = cashboxDao.findByRequestToken(requestToken)
                if (existing != null) return@withTransaction existing

                val periodId = getActivePeriodIdLocked()
                val cbxNum = cashboxDao.getMaxId() + 1L
                val code = "CBX-${cbxNum.toString().padStart(5, '0')}"
                val entry = CashboxTransactionEntity(
                    transactionCode = code,
                    requestToken = requestToken,
                    type = type,
                    direction = direction,
                    amountPaisa = amountPaisa,
                    sourceType = "MANUAL",
                    sourceId = null,
                    description = cleanDesc,
                    notes = notes.trim(),
                    halKhataPeriodId = periodId,
                    createdAt = timestamp
                )
                val id = cashboxDao.insert(entry)
                auditDao.insert(
                    AuditLogEntity(
                        transactionRef = code,
                        entityType = "CASHBOX",
                        entityId = id,
                        actionType = "CREATE",
                        previousValueJson = "",
                        newValueJson = JSONObject().put("type", type).put("direction", direction).put("amountPaisa", amountPaisa).toString(),
                        reason = cleanDesc,
                        createdAt = timestamp
                    )
                )
                entry.copy(id = id)
            }
            OperationResult.Success(saved, "ক্যাশবক্সে লেনদেন সংরক্ষিত হয়েছে")
        } catch (e: Exception) {
            OperationResult.Error(e.message ?: "ক্যাশবক্স লেনদেন ব্যর্থ হয়েছে")
        }
    }

    suspend fun recordActualCashCount(actualCashPaisa: Long): OperationResult<Unit> {
        if (actualCashPaisa < 0L) return OperationResult.Error("নগদ পরিমাণ ঋণাত্মক হতে পারে না")
        return try {
            db.withTransaction {
                val settings = settingsDao.getSettings() ?: AppSettingsEntity()
                val now = System.currentTimeMillis()
                settingsDao.upsert(
                    settings.copy(
                        lastActualCashCountPaisa = actualCashPaisa,
                        lastActualCashCountAt = now,
                        updatedAt = now
                    )
                )
            }
            OperationResult.Success(Unit, "হাতে গোনা নগদ ক্যাশ সংরক্ষিত হয়েছে")
        } catch (e: Exception) {
            OperationResult.Error("সংরক্ষণ ব্যর্থ: ${e.message}")
        }
    }

    // endregion

    // region Temporary Customer Ledger & Conversion

    suspend fun createTemporaryCustomer(
        name: String,
        address: String = "",
        mobile: String = "",
        notes: String = ""
    ): OperationResult<TemporaryCustomerEntity> {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return OperationResult.Error("অস্থায়ী কাস্টমারের নাম লিখুন")

        return try {
            val saved = db.withTransaction {
                val nextNum = tempDao.getMaxId() + 1L
                val code = "TMP-${nextNum.toString().padStart(4, '0')}"
                val entity = TemporaryCustomerEntity(
                    tempCode = code,
                    name = cleanName,
                    address = address.trim(),
                    mobile = mobile.trim(),
                    notes = notes.trim()
                )
                val id = tempDao.insertCustomer(entity)
                entity.copy(id = id)
            }
            OperationResult.Success(saved, "অস্থায়ী খাতা '${saved.name}' তৈরি হয়েছে")
        } catch (e: Exception) {
            OperationResult.Error("অস্থায়ী কাস্টমার তৈরি করা যায়নি: ${e.message}")
        }
    }

    suspend fun addTemporaryEntry(
        tempCustomerId: Long,
        type: String, // CREDIT, PAYMENT, MEMO
        description: String,
        totalAmountPaisa: Long,
        paidPaisa: Long,
        notes: String = "",
        timestamp: Long = System.currentTimeMillis()
    ): OperationResult<TemporaryMemoEntity> {
        if (totalAmountPaisa <= 0L) return OperationResult.Error("টাকার পরিমাণ শূন্যের চেয়ে বেশি হতে হবে")
        if (paidPaisa < 0L || paidPaisa > totalAmountPaisa) {
            return OperationResult.Error("জমার পরিমাণ সঠিক নয়")
        }

        return try {
            val saved = db.withTransaction {
                val tempCust = tempDao.getById(tempCustomerId)
                    ?: throw IllegalStateException("অস্থায়ী কাস্টমার পাওয়া যায়নি")
                if (tempCust.isConverted) {
                    throw IllegalStateException("এই কাস্টমারকে ইতিমধ্যে স্থায়ী কাস্টমারে রূপান্তর করা হয়েছে")
                }

                val creditPaisa = when (type) {
                    "PAYMENT" -> 0L
                    else -> totalAmountPaisa - paidPaisa
                }
                if (type == "PAYMENT" && totalAmountPaisa > tempCust.outstandingPaisa) {
                    throw IllegalStateException("বকেয়ার চেয়ে বেশি জমা নেওয়া যাবে না")
                }

                val nextMemoNum = tempDao.getMaxMemoId() + 1L
                val memoNo = "TMEMO-${nextMemoNum.toString().padStart(5, '0')}"
                val memo = TemporaryMemoEntity(
                    tempCustomerId = tempCustomerId,
                    memoNumber = memoNo,
                    type = type,
                    description = description.trim().ifEmpty {
                        when (type) {
                            "PAYMENT" -> "অস্থায়ী জমা"
                            "CREDIT" -> "অস্থায়ী বাকি"
                            else -> "অস্থায়ী মেমো"
                        }
                    },
                    totalAmountPaisa = totalAmountPaisa,
                    paidPaisa = if (type == "PAYMENT") totalAmountPaisa else paidPaisa,
                    creditPaisa = creditPaisa,
                    notes = notes.trim(),
                    createdAt = timestamp
                )
                val memoId = tempDao.insertMemo(memo)

                // Cash received updates central cashbox
                val actualCashIn = if (type == "PAYMENT") totalAmountPaisa else paidPaisa
                if (actualCashIn > 0L) {
                    val cbxNum = cashboxDao.getMaxId() + 1L
                    cashboxDao.insert(
                        CashboxTransactionEntity(
                            transactionCode = "CBX-${cbxNum.toString().padStart(5, '0')}",
                            requestToken = "CBX-TEMP-$memoId-${UUID.randomUUID()}",
                            type = if (type == "PAYMENT") CashboxType.CUSTOMER_PAYMENT else CashboxType.CASH_SALE,
                            direction = CashboxDirection.IN,
                            amountPaisa = actualCashIn,
                            sourceType = "TEMP_MEMO",
                            sourceId = memoId,
                            description = "অস্থায়ী খাতা ($memoNo): ${tempCust.name}",
                            notes = notes.trim(),
                            halKhataPeriodId = getActivePeriodIdLocked(),
                            createdAt = timestamp
                        )
                    )
                }

                val newCredit = tempCust.totalCreditPaisa + creditPaisa
                val newPaid = tempCust.totalPaidPaisa + if (type == "PAYMENT") totalAmountPaisa else 0L
                val newOutstanding = (newCredit - newPaid).coerceAtLeast(0L)
                tempDao.updateCustomer(
                    tempCust.copy(
                        totalCreditPaisa = newCredit,
                        totalPaidPaisa = newPaid,
                        outstandingPaisa = newOutstanding,
                        updatedAt = timestamp
                    )
                )

                memo.copy(id = memoId)
            }
            OperationResult.Success(saved, "অস্থায়ী লেনদেন সংরক্ষিত হয়েছে")
        } catch (e: Exception) {
            OperationResult.Error(e.message ?: "অস্থায়ী লেনদেন সংরক্ষণ ব্যর্থ")
        }
    }

    suspend fun convertTemporaryToPermanent(
        tempCustomerId: Long,
        addressOverride: String = ""
    ): OperationResult<CustomerEntity> {
        return try {
            val permanent = db.withTransaction {
                val temp = tempDao.getById(tempCustomerId)
                    ?: throw IllegalStateException("অস্থায়ী কাস্টমার পাওয়া যায়নি")
                if (temp.isConverted && temp.convertedCustomerId != null) {
                    val already = customerDao.getById(temp.convertedCustomerId)
                    if (already != null) return@withTransaction already
                }

                val addr = addressOverride.trim().ifEmpty { temp.address.ifBlank { "অনির্ধারিত" } }
                val periodId = getActivePeriodIdLocked()
                val nextCustNum = customerDao.getMaxId() + 1L
                val custCode = "CUST-${nextCustNum.toString().padStart(4, '0')}"

                val newCustomer = CustomerEntity(
                    customerCode = custCode,
                    name = temp.name,
                    address = addr,
                    mobile = temp.mobile,
                    notes = temp.notes,
                    openingBalancePaisa = 0L,
                    currentBalancePaisa = 0L,
                    halKhataPeriodId = periodId,
                    createdAt = temp.createdAt
                )
                val permanentId = customerDao.insert(newCustomer)

                val tempMemos = tempDao.getMemosByTempCustomerOnce(tempCustomerId)
                var runningBal = 0L
                for (tm in tempMemos) {
                    when (tm.type) {
                        "PAYMENT" -> {
                            val payNum = paymentDao.getMaxId() + 1L
                            val prev = runningBal
                            runningBal = (runningBal - tm.paidPaisa).coerceAtLeast(0L)
                            paymentDao.insert(
                                PaymentEntity(
                                    receiptNumber = "PAY-${payNum.toString().padStart(5, '0')}",
                                    requestToken = "CONV-TMP-PAY-${tm.id}",
                                    customerId = permanentId,
                                    amountPaisa = tm.paidPaisa,
                                    previousBalancePaisa = prev,
                                    newBalancePaisa = runningBal,
                                    notes = "অস্থায়ী খাতা থেকে রূপান্তরিত (${tm.memoNumber}): ${tm.notes}".trim(),
                                    halKhataPeriodId = periodId,
                                    createdAt = tm.createdAt
                                )
                            )
                        }
                        "CREDIT" -> {
                            val crNum = creditDao.getMaxId() + 1L
                            val prev = runningBal
                            runningBal += tm.creditPaisa
                            creditDao.insert(
                                CreditTransactionEntity(
                                    transactionCode = "CRD-${crNum.toString().padStart(5, '0')}",
                                    requestToken = "CONV-TMP-CRD-${tm.id}",
                                    customerId = permanentId,
                                    amountPaisa = tm.creditPaisa,
                                    previousBalancePaisa = prev,
                                    newBalancePaisa = runningBal,
                                    description = tm.description,
                                    notes = "অস্থায়ী খাতা থেকে রূপান্তরিত (${tm.memoNumber})",
                                    sourceType = CreditSourceType.QUICK_CREDIT,
                                    halKhataPeriodId = periodId,
                                    createdAt = tm.createdAt
                                )
                            )
                        }
                        else -> { // MEMO
                            val memoNum = memoDao.getMaxId() + 1L
                            val newMemoNo = "MEMO-${memoNum.toString().padStart(5, '0')}"
                            val saleType = when {
                                tm.creditPaisa == 0L -> SaleType.CASH
                                tm.paidPaisa == 0L -> SaleType.CREDIT
                                else -> SaleType.PARTIAL
                            }
                            val memoId = memoDao.insertMemo(
                                MemoEntity(
                                    memoNumber = newMemoNo,
                                    requestToken = "CONV-TMP-MEMO-${tm.id}",
                                    customerId = permanentId,
                                    customerNameSnapshot = temp.name,
                                    customerAddressSnapshot = addr,
                                    saleType = saleType,
                                    subtotalPaisa = tm.totalAmountPaisa,
                                    discountPaisa = 0L,
                                    grandTotalPaisa = tm.totalAmountPaisa,
                                    paidPaisa = tm.paidPaisa,
                                    outstandingPaisa = tm.creditPaisa,
                                    notes = "অস্থায়ী মেমো ${tm.memoNumber}: ${tm.notes}".trim(),
                                    halKhataPeriodId = periodId,
                                    createdAt = tm.createdAt,
                                    updatedAt = tm.createdAt
                                )
                            )
                            memoDao.insertItems(
                                listOf(
                                    MemoItemEntity(
                                        memoId = memoId,
                                        productName = tm.description,
                                        quantityMillis = 1000L,
                                        unit = "পিস",
                                        unitPricePaisa = tm.totalAmountPaisa,
                                        lineTotalPaisa = tm.totalAmountPaisa
                                    )
                                )
                            )
                            if (tm.creditPaisa > 0L) {
                                val crNum = creditDao.getMaxId() + 1L
                                val prev = runningBal
                                runningBal += tm.creditPaisa
                                creditDao.insert(
                                    CreditTransactionEntity(
                                        transactionCode = "CRD-${crNum.toString().padStart(5, '0')}",
                                        requestToken = "CONV-TMP-MCRD-${tm.id}",
                                        customerId = permanentId,
                                        amountPaisa = tm.creditPaisa,
                                        previousBalancePaisa = prev,
                                        newBalancePaisa = runningBal,
                                        description = "রূপান্তরিত মেমো ($newMemoNo)",
                                        notes = tm.notes,
                                        sourceType = CreditSourceType.MEMO,
                                        memoId = memoId,
                                        halKhataPeriodId = periodId,
                                        createdAt = tm.createdAt
                                    )
                                )
                            }
                        }
                    }
                }

                recalculateCustomerLocked(permanentId)

                // Mark temporary customer converted to prevent duplicate conversion or double counting
                tempDao.updateCustomer(
                    temp.copy(
                        convertedCustomerId = permanentId,
                        isConverted = true,
                        updatedAt = System.currentTimeMillis()
                    )
                )

                auditDao.insert(
                    AuditLogEntity(
                        transactionRef = "${temp.tempCode} -> $custCode",
                        entityType = "CUSTOMER",
                        entityId = permanentId,
                        actionType = "CREATE",
                        previousValueJson = JSONObject().put("tempCode", temp.tempCode).put("outstandingPaisa", temp.outstandingPaisa).toString(),
                        newValueJson = JSONObject().put("customerCode", custCode).put("balancePaisa", runningBal).toString(),
                        reason = "অস্থায়ী কাস্টমার থেকে স্থায়ী কাস্টমারে রূপান্তর",
                        relatedCustomerId = permanentId
                    )
                )

                customerDao.getById(permanentId)!!
            }
            OperationResult.Success(permanent, "'${permanent.name}' স্থায়ী কাস্টমার হিসেবে (${permanent.customerCode}) রূপান্তরিত হয়েছে")
        } catch (e: Exception) {
            OperationResult.Error(e.message ?: "রূপান্তর ব্যর্থ হয়েছে")
        }
    }

    // endregion

    // region Hal Khata System & Retention Policy

    suspend fun updateActiveHalKhataMeta(
        name: String,
        description: String,
        startDate: Long,
        endDate: Long?
    ): OperationResult<HalKhataPeriodEntity> {
        if (name.trim().isEmpty()) return OperationResult.Error("হালখাতার নাম লিখুন")
        return try {
            val updated = db.withTransaction {
                val active = halKhataDao.getActivePeriod()
                    ?: throw IllegalStateException("সক্রিয় হালখাতা পাওয়া যায়নি")
                val next = active.copy(
                    name = name.trim(),
                    description = description.trim(),
                    startDate = startDate,
                    endDate = endDate
                )
                halKhataDao.updatePeriod(next)
                next
            }
            OperationResult.Success(updated, "হালখাতার তথ্য হালনাগাদ হয়েছে")
        } catch (e: Exception) {
            OperationResult.Error(e.message ?: "হালনাগাদ ব্যর্থ হয়েছে")
        }
    }

    /**
     * Closes the current Hal Khata period, snapshots every customer's closing balance,
     * carries forward unpaid debts to a fresh period, records verified PDF & backup paths,
     * and enforces the retention policy (keep active + latest 2 completed periods; archive older ONLY if backup is verified).
     */
    suspend fun closeHalKhataPeriod(
        newPeriodName: String,
        newPeriodDescription: String = "",
        newStartDate: Long = System.currentTimeMillis(),
        newEndDate: Long? = null,
        archivePdfPath: String,
        archiveBackupPath: String,
        backupVerified: Boolean
    ): OperationResult<HalKhataPeriodEntity> {
        if (newPeriodName.trim().isEmpty()) {
            return OperationResult.Error("নতুন হালখাতার নাম দিন")
        }
        if (!backupVerified || archiveBackupPath.isBlank()) {
            return OperationResult.Error("ব্যাকআপ যাচাই ব্যর্থ হয়েছে। তথ্য সুরক্ষার জন্য হালখাতা সমাপ্ত করা হয়নি।")
        }

        return try {
            val newPeriod = db.withTransaction {
                val active = halKhataDao.getActivePeriod()
                    ?: throw IllegalStateException("কোনো সক্রিয় হালখাতা পাওয়া যায়নি")

                val now = System.currentTimeMillis()
                val allCustomers = customerDao.getAllOnce()
                val periodMemos = memoDao.getAllMemosOnce().filter { it.halKhataPeriodId == active.id && !it.isCancelled }
                val periodCredits = creditDao.getAllOnce().filter { it.halKhataPeriodId == active.id && !it.isReversed }
                val periodPayments = paymentDao.getAllOnce().filter { it.halKhataPeriodId == active.id && !it.isReversed }

                val totalSales = periodMemos.sumOf { it.grandTotalPaisa }
                val totalCredit = periodCredits.sumOf { it.amountPaisa }
                val totalPayments = periodPayments.sumOf { it.amountPaisa }
                val totalClosingDebt = allCustomers.sumOf { it.currentBalancePaisa }

                // Snapshot every customer's closing balance for this Hal Khata period
                val balanceSnapshots = allCustomers.map { c ->
                    val custPurchases = periodMemos.filter { it.customerId == c.id }.sumOf { it.grandTotalPaisa }
                    val custPayments = periodPayments.filter { it.customerId == c.id }.sumOf { it.amountPaisa } +
                        periodMemos.filter { it.customerId == c.id }.sumOf { it.paidPaisa }
                    HalKhataCustomerBalanceEntity(
                        periodId = active.id,
                        customerId = c.id,
                        customerCode = c.customerCode,
                        customerName = c.name,
                        openingBalancePaisa = c.openingBalancePaisa,
                        totalPurchasesPaisa = custPurchases,
                        totalPaymentsPaisa = custPayments,
                        closingBalancePaisa = c.currentBalancePaisa,
                        carriedForward = c.currentBalancePaisa > 0L,
                        recordedAt = now
                    )
                }
                halKhataDao.insertBalances(balanceSnapshots)

                // Mark old period CLOSED with verified archive metadata
                halKhataDao.updatePeriod(
                    active.copy(
                        endDate = active.endDate ?: now,
                        status = HalKhataStatus.CLOSED,
                        totalSalesPaisa = totalSales,
                        totalCreditPaisa = totalCredit,
                        totalPaymentsPaisa = totalPayments,
                        totalClosingDebtPaisa = totalClosingDebt,
                        closedAt = now,
                        archivePdfPath = archivePdfPath,
                        archiveBackupPath = archiveBackupPath,
                        backupVerified = true
                    )
                )

                // Create fresh active Hal Khata period
                val createdPeriod = HalKhataPeriodEntity(
                    name = newPeriodName.trim(),
                    description = newPeriodDescription.trim(),
                    startDate = newStartDate,
                    endDate = newEndDate,
                    status = HalKhataStatus.ACTIVE,
                    createdAt = now
                )
                val newPeriodId = halKhataDao.insertPeriod(createdPeriod)

                // Carry forward unpaid balances cleanly to new period while preserving customer identity
                for (c in allCustomers) {
                    val carryBalance = c.currentBalancePaisa
                    customerDao.update(
                        c.copy(
                            openingBalancePaisa = carryBalance,
                            halKhataPeriodId = newPeriodId
                        )
                    )
                    if (carryBalance > 0L) {
                        val crNum = creditDao.getMaxId() + 1L
                        creditDao.insert(
                            CreditTransactionEntity(
                                transactionCode = "CRD-${crNum.toString().padStart(5, '0')}",
                                requestToken = "CARRY-${active.id}-TO-$newPeriodId-${c.id}",
                                customerId = c.id,
                                amountPaisa = carryBalance,
                                previousBalancePaisa = 0L,
                                newBalancePaisa = carryBalance,
                                description = "পূর্ববর্তী হালখাতা (${active.name}) থেকে জের",
                                notes = "স্বয়ংক্রিয় হালখাতা জের স্থানান্তর",
                                sourceType = CreditSourceType.OPENING_CARRYOVER,
                                halKhataPeriodId = newPeriodId,
                                createdAt = now
                            )
                        )
                    }
                }

                // Update settings active period
                val settings = settingsDao.getSettings() ?: AppSettingsEntity()
                settingsDao.upsert(settings.copy(activeHalKhataPeriodId = newPeriodId, updatedAt = now))

                // Enforce retention policy: keep active + latest 2 completed periods in active detailed history.
                // Archive older detailed transactions ONLY if their backupVerified == true.
                val completedPeriods = halKhataDao.getAllPeriodsOnce()
                    .filter { it.status == HalKhataStatus.CLOSED }
                    .sortedByDescending { it.closedAt ?: it.id }

                if (completedPeriods.size > 2) {
                    val toArchive = completedPeriods.drop(2)
                    for (oldPeriod in toArchive) {
                        if (oldPeriod.backupVerified && oldPeriod.archiveBackupPath.isNotBlank()) {
                            memoDao.deleteMemosByPeriod(oldPeriod.id)
                            paymentDao.deleteByPeriod(oldPeriod.id)
                            creditDao.deleteByPeriod(oldPeriod.id)
                            expenseDao.deleteByPeriod(oldPeriod.id)
                            cashboxDao.deleteByPeriod(oldPeriod.id)
                            halKhataDao.updatePeriod(oldPeriod.copy(status = HalKhataStatus.ARCHIVED))
                        }
                    }
                }

                auditDao.insert(
                    AuditLogEntity(
                        transactionRef = "HK-${active.id}->HK-$newPeriodId",
                        entityType = "HAL_KHATA",
                        entityId = active.id,
                        actionType = "HAL_KHATA_CLOSE",
                        previousValueJson = JSONObject().put("periodName", active.name).put("closingDebtPaisa", totalClosingDebt).toString(),
                        newValueJson = JSONObject().put("newPeriodName", newPeriodName.trim()).put("archiveBackup", archiveBackupPath).toString(),
                        reason = "হালখাতা সমাপ্তি ও নতুন হিসাব কাল শুরু",
                        createdAt = now
                    )
                )

                createdPeriod.copy(id = newPeriodId)
            }
            OperationResult.Success(newPeriod, "হালখাতা সফলভাবে সমাপ্ত হয়েছে এবং নতুন হিসাব কাল '${newPeriod.name}' শুরু হয়েছে")
        } catch (e: Exception) {
            OperationResult.Error(e.message ?: "হালখাতা সমাপ্ত করা যায়নি")
        }
    }

    // endregion

    // region Settings & Backup Metadata

    suspend fun updateSettings(transform: (AppSettingsEntity) -> AppSettingsEntity): OperationResult<AppSettingsEntity> {
        return try {
            val saved = db.withTransaction {
                val current = settingsDao.getSettings() ?: AppSettingsEntity()
                val next = transform(current).copy(id = 1, updatedAt = System.currentTimeMillis())
                settingsDao.upsert(next)
                next
            }
            OperationResult.Success(saved, "সেটিংস সংরক্ষিত হয়েছে")
        } catch (e: Exception) {
            OperationResult.Error("সেটিংস সংরক্ষণ ব্যর্থ: ${e.message}")
        }
    }

    suspend fun recordBackupMetadata(metadata: BackupMetadataEntity) {
        backupDao.insert(metadata)
    }

    // endregion

    // region Unified Calculations & Reports

    private suspend fun getActivePeriodIdLocked(): Long {
        return halKhataDao.getActivePeriod()?.id
            ?: settingsDao.getSettings()?.activeHalKhataPeriodId
            ?: 1L
    }

    /**
     * Authoritative unified balance recalculation for a customer within the active Hal Khata period
     * (or across active detailed records). Guarantees zero drift across edits, reversals, and conversions.
     */
    private suspend fun recalculateCustomerLocked(
        customerId: Long,
        lastPaymentAt: Long? = null,
        lastTxAt: Long? = null
    ) {
        val customer = customerDao.getById(customerId) ?: return
        val activePeriodId = getActivePeriodIdLocked()

        val activeMemos = memoDao.getAllMemosOnce()
            .filter { it.customerId == customerId && it.halKhataPeriodId == activePeriodId && !it.isCancelled }
        val activeCredits = creditDao.getAllOnce()
            .filter { it.customerId == customerId && it.halKhataPeriodId == activePeriodId && !it.isReversed }
            .sortedWith(compareBy<CreditTransactionEntity> { it.createdAt }.thenBy { it.id })
        val activePayments = paymentDao.getAllOnce()
            .filter { it.customerId == customerId && it.halKhataPeriodId == activePeriodId && !it.isReversed }

        val totalCredits = activeCredits.sumOf { it.amountPaisa }
        val totalPayments = activePayments.sumOf { it.amountPaisa }
        val totalPurchases = activeMemos.sumOf { it.grandTotalPaisa }
        val currentBalance = (totalCredits - totalPayments).coerceAtLeast(0L)

        // FIFO debt aging: apply payments against oldest credit entries chronologically
        var remainingPaymentsToAllocate = totalPayments
        var oldestUnpaidTs: Long? = null
        if (currentBalance > 0L) {
            for (cr in activeCredits) {
                if (remainingPaymentsToAllocate >= cr.amountPaisa) {
                    remainingPaymentsToAllocate -= cr.amountPaisa
                } else {
                    oldestUnpaidTs = cr.createdAt
                    break
                }
            }
            if (oldestUnpaidTs == null) {
                oldestUnpaidTs = activeCredits.lastOrNull()?.createdAt ?: customer.createdAt
            }
        }

        val latestPayment = activePayments.maxOfOrNull { it.createdAt } ?: lastPaymentAt ?: customer.lastPaymentDate
        val latestTx = listOfNotNull(
            activeMemos.maxOfOrNull { it.createdAt },
            activeCredits.maxOfOrNull { it.createdAt },
            activePayments.maxOfOrNull { it.createdAt },
            lastTxAt
        ).maxOrNull() ?: customer.lastTransactionDate

        customerDao.update(
            customer.copy(
                currentBalancePaisa = currentBalance,
                totalPurchasesPaisa = totalPurchases,
                totalCreditPaisa = totalCredits,
                totalPaymentsPaisa = totalPayments,
                oldestUnpaidTimestamp = oldestUnpaidTs,
                lastTransactionDate = latestTx,
                lastPaymentDate = latestPayment
            )
        )
    }

    fun buildCustomerLedgerEntries(
        memos: List<MemoWithItems>,
        credits: List<CreditTransactionEntity>,
        payments: List<PaymentEntity>
    ): List<CustomerLedgerEntry> {
        val raw = mutableListOf<Any>()
        raw.addAll(memos)
        // Quick credits and opening carryovers are shown directly; memo-linked credits are represented by the memo itself
        raw.addAll(credits.filter { it.sourceType != CreditSourceType.MEMO })
        raw.addAll(payments)

        fun ts(item: Any): Long = when (item) {
            is MemoWithItems -> item.memo.createdAt
            is CreditTransactionEntity -> item.createdAt
            is PaymentEntity -> item.createdAt
            else -> 0L
        }

        val sortedAsc = raw.sortedWith(compareBy<Any> { ts(it) }.thenBy {
            when (it) {
                is CreditTransactionEntity -> 0
                is MemoWithItems -> 1
                is PaymentEntity -> 2
                else -> 3
            }
        })

        var running = 0L
        val entries = mutableListOf<CustomerLedgerEntry>()
        for (item in sortedAsc) {
            when (item) {
                is MemoWithItems -> {
                    if (!item.memo.isCancelled) {
                        running += item.memo.outstandingPaisa
                    }
                    entries.add(
                        CustomerLedgerEntry.MemoEntry(
                            memoWithItems = item,
                            timestamp = item.memo.createdAt,
                            referenceCode = item.memo.memoNumber,
                            runningBalancePaisa = running
                        )
                    )
                }
                is CreditTransactionEntity -> {
                    if (!item.isReversed) {
                        running += item.amountPaisa
                    }
                    entries.add(
                        CustomerLedgerEntry.QuickCreditEntry(
                            credit = item,
                            timestamp = item.createdAt,
                            referenceCode = item.transactionCode,
                            runningBalancePaisa = running
                        )
                    )
                }
                is PaymentEntity -> {
                    if (!item.isReversed) {
                        running = (running - item.amountPaisa).coerceAtLeast(0L)
                    }
                    entries.add(
                        CustomerLedgerEntry.PaymentEntry(
                            payment = item,
                            timestamp = item.createdAt,
                            referenceCode = item.receiptNumber,
                            runningBalancePaisa = running
                        )
                    )
                }
            }
        }
        return entries.reversed() // newest first for UI readability, while runningBalance is accurate chronologically
    }

    fun computeCashboxSummary(
        txs: List<CashboxTransactionEntity>,
        settings: AppSettingsEntity
    ): CashboxSummary {
        val active = txs.filter { !it.isReversed }
        val opening = active.filter { it.type == CashboxType.OPENING }.sumOf { it.amountPaisa }
        val inflow = active.filter { it.direction == CashboxDirection.IN }.sumOf { it.amountPaisa }
        val outflow = active.filter { it.direction == CashboxDirection.OUT }.sumOf { it.amountPaisa }
        val expected = inflow - outflow
        val actual = settings.lastActualCashCountPaisa
        val diff = actual?.let { it - expected }
        return CashboxSummary(
            openingCashPaisa = opening,
            totalInflowPaisa = inflow,
            totalOutflowPaisa = outflow,
            expectedBalancePaisa = expected,
            actualCountedCashPaisa = actual,
            differencePaisa = diff
        )
    }

    fun computeDashboardSummary(
        customers: List<CustomerEntity>,
        memos: List<MemoWithItems>,
        payments: List<PaymentEntity>,
        credits: List<CreditTransactionEntity>,
        expenses: List<ExpenseEntity>,
        cashbox: List<CashboxTransactionEntity>,
        backups: List<BackupMetadataEntity>,
        activePeriod: HalKhataPeriodEntity?,
        settings: AppSettingsEntity,
        nowMillis: Long = System.currentTimeMillis()
    ): DashboardSummary {
        val dayStart = CurrencyUtils.startOfDayMillis(nowMillis)
        val dayEnd = CurrencyUtils.endOfDayMillis(nowMillis)

        val todayMemos = memos.filter { !it.memo.isCancelled && it.memo.createdAt in dayStart..dayEnd }
        val todayPayments = payments.filter { !it.isReversed && it.createdAt in dayStart..dayEnd }
        val todayCredits = credits.filter {
            !it.isReversed && it.createdAt in dayStart..dayEnd && it.sourceType != CreditSourceType.OPENING_CARRYOVER
        }
        val todayExpenses = expenses.filter { !it.isCancelled && it.createdAt in dayStart..dayEnd }
        val todayCashbox = cashbox.filter { !it.isReversed && it.createdAt in dayStart..dayEnd }

        val todayTotalSales = todayMemos.sumOf { it.memo.grandTotalPaisa }
        val todayCashSales = todayMemos.sumOf { it.memo.paidPaisa }
        val todayCreditSales = todayMemos.sumOf { it.memo.outstandingPaisa }
        val todayPaymentTotal = todayPayments.sumOf { it.amountPaisa }
        val todayNewDebt = todayCredits.sumOf { it.amountPaisa }
        val todayExpenseTotal = todayExpenses.sumOf { it.amountPaisa }

        val activeCashbox = cashbox.filter { !it.isReversed }
        val cashboxBalance = activeCashbox.filter { it.direction == CashboxDirection.IN }.sumOf { it.amountPaisa } -
            activeCashbox.filter { it.direction == CashboxDirection.OUT }.sumOf { it.amountPaisa }
        val todayInflow = todayCashbox.filter { it.direction == CashboxDirection.IN }.sumOf { it.amountPaisa }
        val todayOutflow = todayCashbox.filter { it.direction == CashboxDirection.OUT }.sumOf { it.amountPaisa }

        val totalOutstandingDebt = customers.sumOf { it.currentBalancePaisa }
        val withDebtCount = customers.count { it.currentBalancePaisa > 0L }
        val zeroBalCount = customers.count { it.currentBalancePaisa == 0L }
        val txCount = todayMemos.size + todayPayments.size +
            todayCredits.count { it.sourceType == CreditSourceType.QUICK_CREDIT } + todayExpenses.size

        // Determine last transaction
        val candidates = mutableListOf<LastTransactionInfo>()
        memos.firstOrNull { !it.memo.isCancelled }?.let {
            candidates.add(
                LastTransactionInfo(
                    title = "মেমো: ${it.memo.customerNameSnapshot}",
                    subtitle = it.memo.memoNumber,
                    amountPaisa = it.memo.grandTotalPaisa,
                    timestamp = it.memo.createdAt
                )
            )
        }
        payments.firstOrNull { !it.isReversed }?.let { p ->
            val custName = customers.find { it.id == p.customerId }?.name ?: "কাস্টমার"
            candidates.add(
                LastTransactionInfo(
                    title = "জমা: $custName",
                    subtitle = p.receiptNumber,
                    amountPaisa = p.amountPaisa,
                    timestamp = p.createdAt
                )
            )
        }
        credits.firstOrNull { !it.isReversed && it.sourceType == CreditSourceType.QUICK_CREDIT }?.let { c ->
            val custName = customers.find { it.id == c.customerId }?.name ?: "কাস্টমার"
            candidates.add(
                LastTransactionInfo(
                    title = "দ্রুত বাকি: $custName",
                    subtitle = c.transactionCode,
                    amountPaisa = c.amountPaisa,
                    timestamp = c.createdAt
                )
            )
        }
        expenses.firstOrNull { !it.isCancelled }?.let { e ->
            candidates.add(
                LastTransactionInfo(
                    title = "খরচ: ${e.category}",
                    subtitle = e.expenseCode,
                    amountPaisa = e.amountPaisa,
                    timestamp = e.createdAt
                )
            )
        }
        val lastTx = candidates.maxByOrNull { it.timestamp }
        val lastBackup = backups.firstOrNull()
        val lastSuccessBackup = backups.firstOrNull { it.status == "SUCCESS" }

        val alerts = buildShopAlerts(
            customers = customers,
            memos = memos,
            payments = payments,
            credits = credits,
            expenses = expenses,
            cashboxBalance = cashboxBalance,
            lastBackup = lastBackup,
            lastSuccessBackup = lastSuccessBackup,
            settings = settings,
            nowMillis = nowMillis
        )

        return DashboardSummary(
            todayTotalSalesPaisa = todayTotalSales,
            todayCashSalesPaisa = todayCashSales,
            todayCreditSalesPaisa = todayCreditSales,
            todayCustomerPaymentsPaisa = todayPaymentTotal,
            todayNewlyAddedDebtPaisa = todayNewDebt,
            todayExpensesPaisa = todayExpenseTotal,
            totalOutstandingDebtPaisa = totalOutstandingDebt,
            totalCustomers = customers.size,
            customersWithDebt = withDebtCount,
            customersWithZeroBalance = zeroBalCount,
            todayTransactionCount = txCount,
            currentCashboxBalancePaisa = cashboxBalance,
            todayCashInflowPaisa = todayInflow,
            todayCashOutflowPaisa = todayOutflow,
            lastTransaction = lastTx,
            lastBackupAt = lastSuccessBackup?.createdAt,
            lastBackupStatus = lastBackup?.status,
            activeHalKhata = activePeriod,
            alerts = alerts
        )
    }

    private fun buildShopAlerts(
        customers: List<CustomerEntity>,
        memos: List<MemoWithItems>,
        payments: List<PaymentEntity>,
        credits: List<CreditTransactionEntity>,
        expenses: List<ExpenseEntity>,
        cashboxBalance: Long,
        lastBackup: BackupMetadataEntity?,
        lastSuccessBackup: BackupMetadataEntity?,
        settings: AppSettingsEntity,
        nowMillis: Long
    ): List<ShopAlert> {
        val alerts = mutableListOf<ShopAlert>()

        // 1. Overdue customers
        val overdue = customers.filter { c ->
            c.currentBalancePaisa > 0L && c.oldestUnpaidTimestamp != null &&
                CurrencyUtils.daysBetween(c.oldestUnpaidTimestamp, nowMillis) >= settings.overdueDaysThreshold
        }
        if (overdue.isNotEmpty()) {
            alerts.add(
                ShopAlert(
                    id = "overdue_customers",
                    title = "বকেয়া তাগাদা প্রয়োজন (${CurrencyUtils.toBengaliDigits(overdue.size.toString())} জন)",
                    message = "${overdue.first().name} সহ ${CurrencyUtils.toBengaliDigits(overdue.size.toString())} জন কাস্টমারের বকেয়া ${CurrencyUtils.toBengaliDigits(settings.overdueDaysThreshold.toString())}+ দিন অতিক্রম করেছে।",
                    severity = AlertSeverity.WARNING,
                    actionRoute = "reminders"
                )
            )
        }

        // 2. Increasing debt customers (recent 7 days credit > recent 7 days payments & balance > 0)
        val sevenDaysAgo = nowMillis - 7L * 24L * 60L * 60L * 1000L
        val increasingDebtCount = customers.count { c ->
            if (c.currentBalancePaisa <= 0L) return@count false
            val recentCredits = credits.filter { !it.isReversed && it.customerId == c.id && it.createdAt >= sevenDaysAgo }
                .sumOf { it.amountPaisa }
            val recentPays = payments.filter { !it.isReversed && it.customerId == c.id && it.createdAt >= sevenDaysAgo }
                .sumOf { it.amountPaisa }
            recentCredits > recentPays && recentCredits > 0L
        }
        if (increasingDebtCount > 0) {
            alerts.add(
                ShopAlert(
                    id = "increasing_debt",
                    title = "বাকি বাড়ছে (${CurrencyUtils.toBengaliDigits(increasingDebtCount.toString())} জন)",
                    message = "গত ৭ দিনে জমার চেয়ে নতুন বাকি বেশি হয়েছে এমন কাস্টমার রয়েছে।",
                    severity = AlertSeverity.INFO,
                    actionRoute = "debt_aging"
                )
            )
        }

        // 3. Unusually high expenses today
        val dayStart = CurrencyUtils.startOfDayMillis(nowMillis)
        val highExpenses = expenses.filter {
            !it.isCancelled && it.createdAt >= dayStart && it.amountPaisa >= settings.highExpenseThresholdPaisa
        }
        if (highExpenses.isNotEmpty()) {
            alerts.add(
                ShopAlert(
                    id = "high_expense",
                    title = "অস্বাভাবিক বেশি খরচ",
                    message = "আজ ${CurrencyUtils.formatBdt(highExpenses.maxOf { it.amountPaisa })} টাকার বড় খরচ এন্ট্রি হয়েছে।",
                    severity = AlertSeverity.WARNING,
                    actionRoute = "expenses"
                )
            )
        }

        // 4. Cashbox balance discrepancy
        val actualCount = settings.lastActualCashCountPaisa
        if (actualCount != null && actualCount != cashboxBalance) {
            alerts.add(
                ShopAlert(
                    id = "cashbox_diff",
                    title = "ক্যাশবক্সে গরমিল",
                    message = "হিসাব অনুযায়ী ${CurrencyUtils.formatBdt(cashboxBalance)}, হাতে গোনা ${CurrencyUtils.formatBdt(actualCount)} (পার্থক্য ${CurrencyUtils.formatBdt(actualCount - cashboxBalance)})।",
                    severity = AlertSeverity.CRITICAL,
                    actionRoute = "cashbox"
                )
            )
        }

        // 5. Failed backup or overdue backup
        if (lastBackup?.status == "FAILED") {
            alerts.add(
                ShopAlert(
                    id = "backup_failed",
                    title = "সর্বশেষ ব্যাকআপ ব্যর্থ হয়েছে",
                    message = lastBackup.errorMessage.ifBlank { "অনুগ্রহ করে পুনরায় ব্যাকআপ নিন।" },
                    severity = AlertSeverity.CRITICAL,
                    actionRoute = "backup_restore"
                )
            )
        } else if (lastSuccessBackup == null && (customers.isNotEmpty() || memos.isNotEmpty())) {
            alerts.add(
                ShopAlert(
                    id = "no_backup",
                    title = "এখনো কোনো ব্যাকআপ নেওয়া হয়নি",
                    message = "দোকানের হিসাব নিরাপদ রাখতে এখনই একটি ব্যাকআপ তৈরি করুন।",
                    severity = AlertSeverity.WARNING,
                    actionRoute = "backup_restore"
                )
            )
        }

        // 6. Transactions flagged for review
        val reviewCount = memos.count { it.memo.needsReview } +
            payments.count { it.needsReview } +
            credits.count { it.needsReview } +
            expenses.count { it.needsReview }
        if (reviewCount > 0) {
            alerts.add(
                ShopAlert(
                    id = "review_needed",
                    title = "পর্যালোচনার জন্য লেনদেন (${CurrencyUtils.toBengaliDigits(reviewCount.toString())})",
                    message = "কিছু লেনদেন যাচাই বা সংশোধনের জন্য চিহ্নিত রয়েছে।",
                    severity = AlertSeverity.WARNING,
                    actionRoute = "audit_logs"
                )
            )
        }

        return alerts
    }

    fun computeDailyReport(
        dateMillis: Long,
        customers: List<CustomerEntity>,
        memos: List<MemoWithItems>,
        payments: List<PaymentEntity>,
        credits: List<CreditTransactionEntity>,
        expenses: List<ExpenseEntity>,
        cashbox: List<CashboxTransactionEntity>,
        settings: AppSettingsEntity
    ): DailyReportData {
        val start = CurrencyUtils.startOfDayMillis(dateMillis)
        val end = CurrencyUtils.endOfDayMillis(dateMillis)

        // Opening cash = net cashbox balance strictly before start of selected day + explicit OPENING entries on that day
        val priorCashbox = cashbox.filter { !it.isReversed && it.createdAt < start }
        val carriedOpening = priorCashbox.filter { it.direction == CashboxDirection.IN }.sumOf { it.amountPaisa } -
            priorCashbox.filter { it.direction == CashboxDirection.OUT }.sumOf { it.amountPaisa }

        val dayCashbox = cashbox.filter { !it.isReversed && it.createdAt in start..end }
        val explicitOpening = dayCashbox.filter { it.type == CashboxType.OPENING }.sumOf { it.amountPaisa }
        val openingCash = carriedOpening + explicitOpening

        val dayMemos = memos.filter { !it.memo.isCancelled && it.memo.createdAt in start..end }
        val dayPayments = payments.filter { !it.isReversed && it.createdAt in start..end }
        val dayCredits = credits.filter {
            !it.isReversed && it.createdAt in start..end && it.sourceType != CreditSourceType.OPENING_CARRYOVER
        }
        val dayExpenses = expenses.filter { !it.isCancelled && it.createdAt in start..end }

        val cashSales = dayMemos.sumOf { it.memo.paidPaisa }
        val creditSales = dayMemos.sumOf { it.memo.outstandingPaisa }
        val customerPayments = dayPayments.sumOf { it.amountPaisa }
        val otherIncome = dayCashbox.filter { it.type == CashboxType.OTHER_INCOME }.sumOf { it.amountPaisa }
        val totalInflow = cashSales + customerPayments + otherIncome

        val cashExpenses = dayCashbox.filter { it.type == CashboxType.EXPENSE }.sumOf { it.amountPaisa }
        val supplierPayments = dayCashbox.filter { it.type == CashboxType.SUPPLIER_PAYMENT }.sumOf { it.amountPaisa }
        val otherOutflows = dayCashbox.filter {
            it.type == CashboxType.OTHER_WITHDRAWAL || it.type == CashboxType.CORRECTION
        }.sumOf { it.amountPaisa }
        val totalOutflow = cashExpenses + supplierPayments + otherOutflows

        val expectedClosing = openingCash + totalInflow - totalOutflow
        val actualToday = settings.lastActualCashCountAt?.let {
            if (it in start..end) settings.lastActualCashCountPaisa else null
        }

        val txCount = dayMemos.size + dayPayments.size +
            dayCredits.count { it.sourceType == CreditSourceType.QUICK_CREDIT } + dayExpenses.size

        return DailyReportData(
            dateMillis = dateMillis,
            openingCashPaisa = openingCash,
            cashSalesPaisa = cashSales,
            creditSalesPaisa = creditSales,
            customerPaymentsPaisa = customerPayments,
            otherIncomePaisa = otherIncome,
            totalInflowPaisa = totalInflow,
            expensesPaisa = dayExpenses.sumOf { it.amountPaisa },
            supplierPaymentsPaisa = supplierPayments,
            otherOutflowsPaisa = otherOutflows,
            totalOutflowPaisa = totalOutflow,
            expectedClosingCashPaisa = expectedClosing,
            actualClosingCashPaisa = actualToday,
            cashDifferencePaisa = actualToday?.let { it - expectedClosing },
            totalOutstandingDebtPaisa = customers.sumOf { it.currentBalancePaisa },
            newDebtsPaisa = dayCredits.sumOf { it.amountPaisa },
            totalPaymentsPaisa = customerPayments,
            transactionCount = txCount,
            memos = dayMemos,
            payments = dayPayments,
            credits = dayCredits,
            expenses = dayExpenses,
            cashboxTransactions = dayCashbox
        )
    }

    fun computePeriodReport(
        startMillis: Long,
        endMillis: Long,
        customers: List<CustomerEntity>,
        memos: List<MemoWithItems>,
        payments: List<PaymentEntity>,
        credits: List<CreditTransactionEntity>,
        expenses: List<ExpenseEntity>,
        cashbox: List<CashboxTransactionEntity>
    ): PeriodReportData {
        val rangeStart = CurrencyUtils.startOfDayMillis(startMillis)
        val rangeEnd = CurrencyUtils.endOfDayMillis(endMillis)

        val rangeMemos = memos.filter { !it.memo.isCancelled && it.memo.createdAt in rangeStart..rangeEnd }
        val rangePayments = payments.filter { !it.isReversed && it.createdAt in rangeStart..rangeEnd }
        val rangeCredits = credits.filter {
            !it.isReversed && it.createdAt in rangeStart..rangeEnd && it.sourceType != CreditSourceType.OPENING_CARRYOVER
        }
        val rangeExpenses = expenses.filter { !it.isCancelled && it.createdAt in rangeStart..rangeEnd }
        val rangeCashbox = cashbox.filter { !it.isReversed && it.createdAt in rangeStart..rangeEnd }

        val customerRows = customers.map { c ->
            val cm = rangeMemos.filter { it.memo.customerId == c.id }
            val cp = rangePayments.filter { it.customerId == c.id }
            val cc = rangeCredits.filter { it.customerId == c.id }
            CustomerReportRow(
                customer = c,
                periodSalesPaisa = cm.sumOf { it.memo.grandTotalPaisa },
                periodCashPaidPaisa = cm.sumOf { it.memo.paidPaisa },
                periodCreditAddedPaisa = cc.sumOf { it.amountPaisa },
                periodPaymentsPaisa = cp.sumOf { it.amountPaisa }
            )
        }

        return PeriodReportData(
            startMillis = rangeStart,
            endMillis = rangeEnd,
            totalSalesPaisa = rangeMemos.sumOf { it.memo.grandTotalPaisa },
            cashSalesPaisa = rangeMemos.sumOf { it.memo.paidPaisa },
            creditSalesPaisa = rangeMemos.sumOf { it.memo.outstandingPaisa },
            totalCustomerPaymentsPaisa = rangePayments.sumOf { it.amountPaisa },
            totalNewCreditsPaisa = rangeCredits.sumOf { it.amountPaisa },
            totalExpensesPaisa = rangeExpenses.sumOf { it.amountPaisa },
            cashInflowPaisa = rangeCashbox.filter { it.direction == CashboxDirection.IN }.sumOf { it.amountPaisa },
            cashOutflowPaisa = rangeCashbox.filter { it.direction == CashboxDirection.OUT }.sumOf { it.amountPaisa },
            totalOutstandingDebtPaisa = customers.sumOf { it.currentBalancePaisa },
            transactionCount = rangeMemos.size + rangePayments.size +
                rangeCredits.count { it.sourceType == CreditSourceType.QUICK_CREDIT } + rangeExpenses.size,
            customerRows = customerRows,
            memos = rangeMemos,
            payments = rangePayments,
            credits = rangeCredits,
            expenses = rangeExpenses,
            cashboxTransactions = rangeCashbox
        )
    }

    fun computeDebtAging(
        customers: List<CustomerEntity>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<DebtAgingRow> {
        return customers
            .filter { it.currentBalancePaisa > 0L }
            .map { c ->
                val oldest = c.oldestUnpaidTimestamp ?: c.lastTransactionDate ?: c.createdAt
                val days = CurrencyUtils.daysBetween(oldest, nowMillis)
                DebtAgingRow(
                    customer = c,
                    outstandingPaisa = c.currentBalancePaisa,
                    oldestUnpaidDate = oldest,
                    debtAgeDays = days,
                    bucket = DebtAgeBucket.fromDays(days),
                    lastPaymentDate = c.lastPaymentDate,
                    lastPurchaseDate = c.lastTransactionDate
                )
            }
    }

    // endregion

    private data class FiveTuple<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E
    )

    private data class FourTuple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )
}
