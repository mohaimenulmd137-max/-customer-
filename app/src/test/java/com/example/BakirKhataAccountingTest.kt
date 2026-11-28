package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.repository.AccountingRepository
import com.example.domain.MemoLineDraft
import com.example.domain.OperationResult
import com.example.util.BackupManager
import com.example.util.CurrencyUtils
import com.example.util.PdfGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BakirKhataAccountingTest {

    private lateinit var context: Context
    private lateinit var db: BakirKhataDatabase
    private lateinit var repo: AccountingRepository
    private lateinit var backupManager: BackupManager
    private lateinit var pdfGenerator: PdfGenerator

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, BakirKhataDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(sqlDb: SupportSQLiteDatabase) {
                    super.onCreate(sqlDb)
                    val now = System.currentTimeMillis()
                    sqlDb.execSQL(
                        "INSERT INTO hal_khata_periods (id, name, description, startDate, endDate, status, totalSalesPaisa, totalCreditPaisa, totalPaymentsPaisa, totalClosingDebtPaisa, closedAt, archivePdfPath, archiveBackupPath, backupVerified, createdAt) VALUES (1, 'চলতি হালখাতা', '', $now, NULL, 'ACTIVE', 0, 0, 0, 0, NULL, '', '', 0, $now)"
                    )
                    sqlDb.execSQL(
                        "INSERT INTO app_settings (id, shopName, ownerName, shopAddress, shopPhone, currencySymbol, memoPrefix, customUnitsJson, customExpenseCategoriesJson, backupFrequency, backupFolderUri, pinHash, pinSalt, biometricEnabled, autoLockMinutes, themeMode, pdfFooterNote, highExpenseThresholdPaisa, overdueDaysThreshold, activeHalKhataPeriodId, lastActualCashCountPaisa, lastActualCashCountAt, updatedAt) VALUES (1, 'বিসমিল্লাহ স্টোর', 'রহিম', 'ঢাকা', '01700000000', '৳', 'MEMO', '[]', '[]', 'DAILY', '', '', '', 0, 5, 'SYSTEM', 'ধন্যবাদ', 500000, 30, 1, NULL, NULL, $now)"
                    )
                }
            })
            .build()
        repo = AccountingRepository(db)
        backupManager = BackupManager(context, db)
        pdfGenerator = PdfGenerator(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun bdt(amount: Long) = amount * 100L

    @Test
    fun test1_creditAndPartialSaleCalculations() = runBlocking {
        val cust = (repo.createCustomer("করিম মিয়া", "নবীনগর") as OperationResult.Success).data
        val item = MemoLineDraft("1", "মিনিকেট চাল", "5", "কেজি", "100", 5000L, bdt(100), bdt(500))

        val memoRes = repo.createSalesMemo(
            saleType = SaleType.PARTIAL,
            customerId = cust.id,
            items = listOf(item),
            discountPaisa = 0L,
            paidPaisaInput = bdt(200),
            requestToken = "tok-partial-1"
        )
        assertTrue(memoRes is OperationResult.Success)
        val updatedCust = db.customerDao().getById(cust.id)!!
        assertEquals(bdt(300), updatedCust.currentBalancePaisa)

        val cashbox = repo.computeCashboxSummary(db.cashboxDao().getAllOnce(), AppSettingsEntity())
        assertEquals(bdt(200), cashbox.expectedBalancePaisa)
    }

    @Test
    fun test2_cashSaleIncreasesCashboxWithoutDebt() = runBlocking {
        val cust = (repo.createCustomer("সালাম", "সাভার") as OperationResult.Success).data
        val item = MemoLineDraft("1", "সয়াবিন তেল", "4", "লিটার", "200", 4000L, bdt(200), bdt(800))

        val res = repo.createSalesMemo(
            saleType = SaleType.CASH,
            customerId = cust.id,
            items = listOf(item),
            requestToken = "tok-cash-1"
        )
        assertTrue(res is OperationResult.Success)

        val updatedCust = db.customerDao().getById(cust.id)!!
        assertEquals(0L, updatedCust.currentBalancePaisa)
        assertEquals(bdt(800), updatedCust.totalPurchasesPaisa)

        val cashbox = repo.computeCashboxSummary(db.cashboxDao().getAllOnce(), AppSettingsEntity())
        assertEquals(bdt(800), cashbox.expectedBalancePaisa)
    }

    @Test
    fun test3_and_test4_quickCreditAndQuickPaymentAndZeroDebtRejection() = runBlocking {
        val cust = (repo.createCustomer("জামাল", "কুমিল্লা", openingBalancePaisa = bdt(500)) as OperationResult.Success).data
        assertEquals(bdt(500), cust.currentBalancePaisa)

        // Test 3: Quick Credit 300 -> 800
        val crRes = repo.recordQuickCredit(cust.id, bdt(300), "ডাল বাকি", requestToken = "qc-1")
        assertTrue(crRes is OperationResult.Success)
        assertEquals(bdt(800), db.customerDao().getById(cust.id)!!.currentBalancePaisa)

        // Cashbox still 0 after credit
        val cashAfterCredit = repo.computeCashboxSummary(db.cashboxDao().getAllOnce(), AppSettingsEntity())
        assertEquals(0L, cashAfterCredit.expectedBalancePaisa)

        // Test 4: Quick Payment 200 -> 600, Cashbox +200
        val payRes = repo.recordQuickPayment(cust.id, bdt(200), requestToken = "qp-1")
        assertTrue(payRes is OperationResult.Success)
        assertEquals(bdt(600), db.customerDao().getById(cust.id)!!.currentBalancePaisa)
        val cashAfterPay = repo.computeCashboxSummary(db.cashboxDao().getAllOnce(), AppSettingsEntity())
        assertEquals(bdt(200), cashAfterPay.expectedBalancePaisa)

        // Zero-debt rejection check
        val zeroCust = (repo.createCustomer("নিরু", "গাজীপুর") as OperationResult.Success).data
        val invalidPay = repo.recordQuickPayment(zeroCust.id, bdt(100), requestToken = "qp-invalid")
        assertTrue(invalidPay is OperationResult.Error)
    }

    @Test
    fun test5_sameNameCustomersRemainCompletelyIsolated() = runBlocking {
        val c1 = (repo.createCustomer("মোঃ আব্দুল করিম", "পূর্বপাড়া", openingBalancePaisa = bdt(1000)) as OperationResult.Success).data
        val c2 = (repo.createCustomer("মোঃ আব্দুল করিম", "পশ্চিমপাড়া", openingBalancePaisa = bdt(400)) as OperationResult.Success).data

        assertNotEquals(c1.id, c2.id)
        assertNotEquals(c1.customerCode, c2.customerCode)

        repo.recordQuickPayment(c1.id, bdt(300), requestToken = "same-name-pay-1")
        repo.recordQuickCredit(c2.id, bdt(250), "বাকি", requestToken = "same-name-crd-1")

        val r1 = db.customerDao().getById(c1.id)!!
        val r2 = db.customerDao().getById(c2.id)!!
        assertEquals(bdt(700), r1.currentBalancePaisa)
        assertEquals(bdt(650), r2.currentBalancePaisa)
    }

    @Test
    fun test6_halKhataClosingAndCarryForward() = runBlocking {
        val c1 = (repo.createCustomer("রফিক", "মিরপুর", openingBalancePaisa = bdt(1200)) as OperationResult.Success).data
        repo.recordQuickPayment(c1.id, bdt(200), requestToken = "hk-pay-1")
        assertEquals(bdt(1000), db.customerDao().getById(c1.id)!!.currentBalancePaisa)

        val closeRes = repo.closeHalKhataPeriod(
            newPeriodName = "নতুন বাংলা বছর ১৪৩৪",
            archivePdfPath = "/tmp/hk1.pdf",
            archiveBackupPath = "/tmp/hk1.zip",
            backupVerified = true
        )
        assertTrue(closeRes is OperationResult.Success)
        val newPeriod = (closeRes as OperationResult.Success).data
        assertEquals(HalKhataStatus.ACTIVE, newPeriod.status)

        val oldPeriod = db.halKhataDao().getPeriodById(1L)!!
        assertEquals(HalKhataStatus.CLOSED, oldPeriod.status)

        val balances = db.halKhataDao().observeBalancesByPeriod(1L).first()
        assertEquals(1, balances.size)
        assertEquals(bdt(1000), balances.first().closingBalancePaisa)
        assertTrue(balances.first().carriedForward)

        // New transaction in new period keeps balance intact
        val custAfter = db.customerDao().getById(c1.id)!!
        assertEquals(bdt(1000), custAfter.currentBalancePaisa)
        assertEquals(newPeriod.id, custAfter.halKhataPeriodId)
    }

    @Test
    fun test7_backupAndRestorePreservesAllDataWithoutDuplicates() = runBlocking {
        val c1 = (repo.createCustomer("হাসান", "বগুড়া", openingBalancePaisa = bdt(900)) as OperationResult.Success).data
        repo.recordQuickPayment(c1.id, bdt(250), requestToken = "bak-pay-1")
        repo.addExpense("দোকান ভাড়া", bdt(150), "মাসিক", requestToken = "bak-exp-1")

        val zipRes = backupManager.createZipBackup("LOCAL_ZIP")
        assertTrue(zipRes is OperationResult.Success)
        val zipFile = File((zipRes as OperationResult.Success).data.filePath)
        assertTrue(zipFile.exists())

        val bytes = zipFile.readBytes()
        val preview = backupManager.previewBackupBytes(bytes, zipFile.name)
        assertTrue(preview is OperationResult.Success)
        assertTrue((preview as OperationResult.Success).data.checksumValid)
        assertEquals(bdt(650), preview.data.totalOutstandingDebtPaisa)

        // Wipe database completely to simulate clean new phone
        db.auditLogDao().deleteAll()
        db.expenseDao().deleteAll()
        db.cashboxDao().deleteAll()
        db.paymentDao().deleteAll()
        db.creditTransactionDao().deleteAll()
        db.memoDao().deleteAllMemos()
        db.customerDao().deleteAll()
        assertEquals(0, db.customerDao().getAllOnce().size)

        // Restore
        val restoreRes = backupManager.restoreFromBytes(bytes, zipFile.name)
        assertTrue(restoreRes is OperationResult.Success)

        val restoredCustomers = db.customerDao().getAllOnce()
        assertEquals(1, restoredCustomers.size)
        assertEquals(bdt(650), restoredCustomers.first().currentBalancePaisa)
        assertEquals(1, db.paymentDao().getAllOnce().size)
        assertEquals(1, db.expenseDao().getAllOnce().size)
    }

    @Test
    fun test8_pdfGenerationSeparateCustomerFilesAndZip() = runBlocking {
        val c1 = (repo.createCustomer("কাস্টমার ১", "গ্রাম ক", openingBalancePaisa = bdt(500)) as OperationResult.Success).data
        val c2 = (repo.createCustomer("কাস্টমার ২", "গ্রাম খ", openingBalancePaisa = bdt(700)) as OperationResult.Success).data
        val settings = db.appSettingsDao().getSettings()!!

        val l1 = repo.observeCustomerLedger(c1.id).first()
        val l2 = repo.observeCustomerLedger(c2.id).first()

        val pdf1 = pdfGenerator.generateCustomerLedgerPdf(c1, l1, settings)
        val pdf2 = pdfGenerator.generateCustomerLedgerPdf(c2, l2, settings)
        assertTrue(pdf1.exists() && pdf1.length() > 100)
        assertTrue(pdf2.exists() && pdf2.length() > 100)
        assertNotEquals(pdf1.absolutePath, pdf2.absolutePath)

        val zip = pdfGenerator.generateCustomerPdfZip(listOf(c1 to l1, c2 to l2), settings, "test_ledgers.zip")
        assertTrue(zip.exists())
        ZipFile(zip).use { zf ->
            assertEquals(2, zf.size())
        }
    }

    @Test
    fun test9_duplicatePreventionWithSameRequestToken() = runBlocking {
        val c = (repo.createCustomer("তাপস", "খুলনা", openingBalancePaisa = bdt(1000)) as OperationResult.Success).data
        val token = "double-tap-token-123"

        val first = repo.recordQuickPayment(c.id, bdt(400), requestToken = token)
        val second = repo.recordQuickPayment(c.id, bdt(400), requestToken = token)
        assertTrue(first is OperationResult.Success)
        assertTrue(second is OperationResult.Success)

        // Recorded ONLY ONCE
        assertEquals(1, db.paymentDao().getAllOnce().size)
        assertEquals(bdt(600), db.customerDao().getById(c.id)!!.currentBalancePaisa)
        val cashbox = repo.computeCashboxSummary(db.cashboxDao().getAllOnce(), AppSettingsEntity())
        assertEquals(bdt(400), cashbox.expectedBalancePaisa)
    }

    @Test
    fun test10_databaseProtectionOnFailedBackupOrRestore() = runBlocking {
        val c = (repo.createCustomer("নজরুল", "রাজশাহী", openingBalancePaisa = bdt(1500)) as OperationResult.Success).data

        // Attempt Hal Khata close with unverified backup -> must fail & preserve all data
        val failedClose = repo.closeHalKhataPeriod(
            newPeriodName = "ব্যর্থ হালখাতা",
            archivePdfPath = "",
            archiveBackupPath = "",
            backupVerified = false
        )
        assertTrue(failedClose is OperationResult.Error)
        assertEquals(HalKhataStatus.ACTIVE, db.halKhataDao().getPeriodById(1L)!!.status)
        assertEquals(bdt(1500), db.customerDao().getById(c.id)!!.currentBalancePaisa)

        // Attempt corrupted restore -> must fail & preserve existing data
        val badRestore = backupManager.restoreFromBytes("corrupted-json-bytes".toByteArray(), "bad.json")
        assertTrue(badRestore is OperationResult.Error)
        assertEquals(1, db.customerDao().getAllOnce().size)
        assertEquals(bdt(1500), db.customerDao().getById(c.id)!!.currentBalancePaisa)
    }

    @Test
    fun exactCurrencyAndTempConversionMath() = runBlocking {
        // BigDecimal exact line total: 1.500 kg * 75.50 BDT = 113.25 BDT (11325 paisa)
        val qtyMillis = CurrencyUtils.parseQuantityMillis("1.5")!!
        val pricePaisa = CurrencyUtils.parseBdtToPaisa("75.50")!!
        assertEquals(11325L, CurrencyUtils.calculateLineTotalPaisa(qtyMillis, pricePaisa))

        // Temporary customer conversion preserves exact balance & history
        val temp = (repo.createTemporaryCustomer("পথিক", "বাজার") as OperationResult.Success).data
        repo.addTemporaryEntry(temp.id, "MEMO", "চাল ও ডাল", bdt(600), bdt(200), "")
        val perm = (repo.convertTemporaryToPermanent(temp.id, "বাজার রোড") as OperationResult.Success).data
        assertEquals(bdt(400), perm.currentBalancePaisa)
        assertEquals(bdt(600), perm.totalPurchasesPaisa)
        assertTrue(db.temporaryCustomerDao().getById(temp.id)!!.isConverted)
    }

    @Test
    fun testHalKhataCollectionCloseVsPartialForPermanentAndTempCustomers() = runBlocking {
        // Permanent customer: partial payment leaves remaining, full payment closes to zero
        val perm = (repo.createCustomer("সোহেল", "চাঁদপুর", openingBalancePaisa = bdt(1000)) as OperationResult.Success).data
        val partialPerm = repo.recordQuickPayment(perm.id, bdt(400), "হালখাতা জমা", requestToken = "hk-coll-1")
        assertTrue(partialPerm is OperationResult.Success)
        assertEquals(bdt(600), db.customerDao().getById(perm.id)!!.currentBalancePaisa)

        val fullClosePerm = repo.recordQuickPayment(perm.id, bdt(600), "হালখাতা জমা", requestToken = "hk-coll-2")
        assertTrue(fullClosePerm is OperationResult.Success)
        assertEquals(0L, db.customerDao().getById(perm.id)!!.currentBalancePaisa)

        // Temporary customer: partial payment leaves remaining, full payment closes to zero
        val temp = (repo.createTemporaryCustomer("অস্থায়ী মনির", "বাজার") as OperationResult.Success).data
        repo.addTemporaryEntry(temp.id, "CREDIT", "মাল বাকি", bdt(500), 0L, "")
        assertEquals(bdt(500), db.temporaryCustomerDao().getById(temp.id)!!.outstandingPaisa)

        repo.addTemporaryEntry(temp.id, "PAYMENT", "হালখাতা জমা", bdt(200), bdt(200), "হালখাতা")
        assertEquals(bdt(300), db.temporaryCustomerDao().getById(temp.id)!!.outstandingPaisa)

        repo.addTemporaryEntry(temp.id, "PAYMENT", "হালখাতা জমা", bdt(300), bdt(300), "হালখাতা")
        assertEquals(0L, db.temporaryCustomerDao().getById(temp.id)!!.outstandingPaisa)

        // Cashbox reflects total collected cash (400 + 600 + 200 + 300 = 1500 BDT)
        val cashbox = repo.computeCashboxSummary(db.cashboxDao().getAllOnce(), AppSettingsEntity())
        assertEquals(bdt(1500), cashbox.expectedBalancePaisa)
    }

    @Test
    fun testHalKhataFreshResetVsCarryoverAndOldPeriodHistory() = runBlocking {
        // Customer A pays 100% -> 0 balance
        val custA = (repo.createCustomer("সালেহ", "বাড্ডা", openingBalancePaisa = bdt(700)) as OperationResult.Success).data
        repo.recordQuickPayment(custA.id, bdt(700), requestToken = "fresh-pay-a")
        assertEquals(0L, db.customerDao().getById(custA.id)!!.currentBalancePaisa)

        // Customer B keeps 300 BDT unpaid
        val custB = (repo.createCustomer("বশির", "উত্তরা", openingBalancePaisa = bdt(800)) as OperationResult.Success).data
        repo.recordQuickPayment(custB.id, bdt(500), requestToken = "fresh-pay-b")
        assertEquals(bdt(300), db.customerDao().getById(custB.id)!!.currentBalancePaisa)

        // Temp 1 cleared, Temp 2 keeps 250 BDT
        val tempCleared = (repo.createTemporaryCustomer("অস্থায়ী শোধ", "হাট") as OperationResult.Success).data
        repo.addTemporaryEntry(tempCleared.id, "CREDIT", "পণ্য", bdt(400), 0L, "")
        repo.addTemporaryEntry(tempCleared.id, "PAYMENT", "জমা", bdt(400), bdt(400), "")

        val tempDebtor = (repo.createTemporaryCustomer("অস্থায়ী বাকি", "হাট") as OperationResult.Success).data
        repo.addTemporaryEntry(tempDebtor.id, "CREDIT", "পণ্য", bdt(600), 0L, "")
        repo.addTemporaryEntry(tempDebtor.id, "PAYMENT", "জমা", bdt(350), bdt(350), "")

        val closeRes = repo.closeHalKhataPeriod(
            newPeriodName = "নতুন খাতা ২",
            archivePdfPath = "/tmp/hk_fresh_1.pdf",
            archiveBackupPath = "/tmp/hk_fresh_1.zip",
            backupVerified = true
        )
        assertTrue(closeRes is OperationResult.Success)
        val p2 = (closeRes as OperationResult.Success).data

        // Customer A starts active ledger completely blank at 0
        val custAAfter = db.customerDao().getById(custA.id)!!
        assertEquals(0L, custAAfter.currentBalancePaisa)
        assertEquals(0L, custAAfter.totalPurchasesPaisa)
        assertEquals(0L, custAAfter.totalPaymentsPaisa)
        assertEquals(0L, custAAfter.totalCreditPaisa)
        assertEquals(p2.id, custAAfter.halKhataPeriodId)
        assertTrue(repo.observeCustomerLedger(custA.id).first().isEmpty())
        // Yet Period 1 history is still accessible
        assertEquals(2, repo.observeCustomerLedger(custA.id, periodId = 1L).first().size)

        // Customer B carries 300 BDT as line 1 in active ledger + still has Period 1 history
        val custBLedgerActive = repo.observeCustomerLedger(custB.id).first()
        assertEquals(1, custBLedgerActive.size)
        val carryEntry = custBLedgerActive.first() as com.example.domain.CustomerLedgerEntry.QuickCreditEntry
        assertEquals(CreditSourceType.OPENING_CARRYOVER, carryEntry.credit.sourceType)
        assertEquals(bdt(300), carryEntry.runningBalancePaisa)
        assertEquals(2, repo.observeCustomerLedger(custB.id, periodId = 1L).first().size)

        // Settled temp customer removed; debtor temp carries 250 BDT cleanly
        assertNull(db.temporaryCustomerDao().getById(tempCleared.id))
        val keptTemp = db.temporaryCustomerDao().getById(tempDebtor.id)!!
        assertEquals(bdt(250), keptTemp.outstandingPaisa)
        assertEquals(1, db.temporaryCustomerDao().getMemosByTempCustomerOnce(tempDebtor.id).size)
    }

    @Test
    fun testHalKhataKeepsLatestTwoClosedPeriodsAndDeletesOlderEverywhere() = runBlocking {
        val cust = (repo.createCustomer("আলমগীর", "যশোর", openingBalancePaisa = bdt(1000)) as OperationResult.Success).data

        // Period 1: pay 200 (cash +200), add expense 50 (cash -50) -> net cash 150, debt 800
        repo.recordQuickPayment(cust.id, bdt(200), requestToken = "p1-pay")
        repo.addExpense("চা-নাস্তা", bdt(50), "দোকান খরচ", paidFromCashbox = true, requestToken = "p1-exp")
        val p1Pdf = File(context.cacheDir, "p1_archive_test.pdf").apply { writeText("pdf1") }
        val p1ZipRes = backupManager.createZipBackup("HAL_KHATA_ARCHIVE", "p1_archive_test.zip") as OperationResult.Success
        val p1Zip = File(p1ZipRes.data.filePath)
        assertTrue(p1Pdf.exists() && p1Zip.exists())

        val close1 = repo.closeHalKhataPeriod(
            newPeriodName = "খাতা ২",
            archivePdfPath = p1Pdf.absolutePath,
            archiveBackupPath = p1Zip.absolutePath,
            backupVerified = true
        ) as OperationResult.Success
        val p2 = close1.data

        // Period 2: pay 300 -> net cumulative cash 450, debt 500
        repo.recordQuickPayment(cust.id, bdt(300), requestToken = "p2-pay")
        val p2Pdf = File(context.cacheDir, "p2_archive_test.pdf").apply { writeText("pdf2") }
        val p2ZipRes = backupManager.createZipBackup("HAL_KHATA_ARCHIVE", "p2_archive_test.zip") as OperationResult.Success
        val close2 = repo.closeHalKhataPeriod(
            newPeriodName = "খাতা ৩",
            archivePdfPath = p2Pdf.absolutePath,
            archiveBackupPath = p2ZipRes.data.filePath,
            backupVerified = true
        ) as OperationResult.Success
        val p3 = close2.data

        // Period 3: pay 100 -> net cumulative cash 550, debt 400. Closing Period 3 creates Period 4 and MUST delete Period 1 everywhere!
        repo.recordQuickPayment(cust.id, bdt(100), requestToken = "p3-pay")
        val p3Pdf = File(context.cacheDir, "p3_archive_test.pdf").apply { writeText("pdf3") }
        val p3ZipRes = backupManager.createZipBackup("HAL_KHATA_ARCHIVE", "p3_archive_test.zip") as OperationResult.Success
        val close3 = repo.closeHalKhataPeriod(
            newPeriodName = "খাতা ৪",
            archivePdfPath = p3Pdf.absolutePath,
            archiveBackupPath = p3ZipRes.data.filePath,
            backupVerified = true
        ) as OperationResult.Success
        val p4 = close3.data

        val remainingPeriods = db.halKhataDao().getAllPeriodsOnce()
        // Strictly 3 periods remain: p2 (CLOSED), p3 (CLOSED), p4 (ACTIVE)
        assertEquals(3, remainingPeriods.size)
        assertNull(db.halKhataDao().getPeriodById(1L))
        assertEquals(HalKhataStatus.CLOSED, db.halKhataDao().getPeriodById(p2.id)!!.status)
        assertEquals(HalKhataStatus.CLOSED, db.halKhataDao().getPeriodById(p3.id)!!.status)
        assertEquals(HalKhataStatus.ACTIVE, db.halKhataDao().getPeriodById(p4.id)!!.status)

        // Period 1 transactions, balance snapshots, backup metadata, and archive files are ALL deleted
        assertTrue(db.halKhataDao().observeBalancesByPeriod(1L).first().isEmpty())
        assertTrue(db.paymentDao().getAllOnce().none { it.halKhataPeriodId == 1L })
        assertTrue(db.creditTransactionDao().getAllOnce().none { it.halKhataPeriodId == 1L })
        assertTrue(db.expenseDao().getAllOnce().none { it.halKhataPeriodId == 1L })
        assertTrue(db.cashboxDao().getAllOnce().none { it.halKhataPeriodId == 1L })
        assertTrue(db.backupMetadataDao().getAllOnce().none { it.filePath == p1Zip.absolutePath })
        assertFalse(p1Pdf.exists())
        assertFalse(p1Zip.exists())

        // Kept 2 old periods (p2 & p3) still have their snapshots, files, and ledger entries
        assertTrue(p2Pdf.exists() && File(p2ZipRes.data.filePath).exists())
        assertTrue(p3Pdf.exists() && File(p3ZipRes.data.filePath).exists())
        assertEquals(1, db.halKhataDao().observeBalancesByPeriod(p2.id).first().size)
        assertEquals(1, db.halKhataDao().observeBalancesByPeriod(p3.id).first().size)

        // Customer debt (400 BDT) and total Cashbox balance (550 BDT) are 100% preserved
        assertEquals(bdt(400), db.customerDao().getById(cust.id)!!.currentBalancePaisa)
        val cashbox = repo.computeCashboxSummary(db.cashboxDao().getAllOnce(), AppSettingsEntity())
        assertEquals(bdt(550), cashbox.expectedBalancePaisa)

        // Cleanup test cache files
        runCatching { p2Pdf.delete(); File(p2ZipRes.data.filePath).delete(); p3Pdf.delete(); File(p3ZipRes.data.filePath).delete() }
        Unit
    }

    @Test
    fun testJsonBackupAndSettingsCashboxRestore() = runBlocking {
        val cust = (repo.createCustomer("মামুন", "ফেনী", openingBalancePaisa = bdt(600)) as OperationResult.Success).data
        repo.recordQuickPayment(cust.id, bdt(250), requestToken = "json-pay-1")
        repo.recordActualCashCount(bdt(250))

        val jsonRes = backupManager.createJsonBackupFile() as OperationResult.Success
        val jsonFile = File(jsonRes.data.filePath)
        assertTrue(jsonFile.exists())
        val jsonBytes = jsonFile.readBytes()

        val preview = backupManager.previewBackupBytes(jsonBytes, "msf:99999")
        assertTrue(preview is OperationResult.Success)
        assertTrue((preview as OperationResult.Success).data.checksumValid)

        // Wipe database
        db.paymentDao().deleteAll()
        db.creditTransactionDao().deleteAll()
        db.cashboxDao().deleteAll()
        db.customerDao().deleteAll()
        repo.updateSettings { it.copy(lastActualCashCountPaisa = null, lastActualCashCountAt = null) }

        val restored = backupManager.restoreFromBytes(jsonBytes, "msf:99999")
        assertTrue(restored is OperationResult.Success)
        assertEquals(bdt(350), db.customerDao().getById(cust.id)!!.currentBalancePaisa)
        assertEquals(bdt(250), db.appSettingsDao().getSettings()!!.lastActualCashCountPaisa)
        runCatching { jsonFile.delete() }
        Unit
    }

    @Test
    fun testTwoYearPurgeDeletesOldDetailsAndPreservesExactBalances() = runBlocking {
        val now = 2_000_000_000_000L
        val oldTs = now - (800L * 24L * 60L * 60L * 1000L) // > 2 years ago
        val recentTs = now - (10L * 24L * 60L * 60L * 1000L) // 10 days ago

        val cust = (repo.createCustomer("করিম পুরোনো", "ঢাকা", openingBalancePaisa = 0L, createdAt = oldTs) as OperationResult.Success).data
        // > 2 year records: +1000 credit, -400 payment -> net 600; +100 expense (cash -100, +400 = net cash 300)
        repo.recordQuickCredit(cust.id, bdt(1000), "পুরোনো বাকি", requestToken = "old-crd-1", timestamp = oldTs)
        repo.recordQuickPayment(cust.id, bdt(400), "পুরোনো জমা", requestToken = "old-pay-1", timestamp = oldTs)
        repo.addExpense("বিদ্যুৎ বিল", bdt(100), "পুরোনো বিল", paidFromCashbox = true, requestToken = "old-exp-1", timestamp = oldTs)

        // Recent records: +200 credit -> net debt should be 800, cashbox should remain 300
        repo.recordQuickCredit(cust.id, bdt(200), "নতুন বাকি", requestToken = "new-crd-1", timestamp = recentTs)

        val purgeRes = repo.purgeRecordsOlderThanTwoYears(nowMillis = now)
        assertTrue(purgeRes is OperationResult.Success)
        assertTrue((purgeRes as OperationResult.Success).data > 0)

        // Detailed old records are purged (< cutoff), replaced only by compact carryover at cutoff where needed
        val creditsAfter = db.creditTransactionDao().getAllOnce()
        assertTrue(creditsAfter.none { it.requestToken == "old-crd-1" })
        assertTrue(creditsAfter.any { it.requestToken == "new-crd-1" })

        val paymentsAfter = db.paymentDao().getAllOnce()
        assertTrue(paymentsAfter.none { it.requestToken == "old-pay-1" })
        assertTrue(db.expenseDao().getAllOnce().none { it.requestToken == "old-exp-1" })

        // Customer current balance (800 BDT) and Ledger running balance and Cashbox balance (300 BDT) stay 100% exact
        assertEquals(bdt(800), db.customerDao().getById(cust.id)!!.currentBalancePaisa)
        val ledgerEntries = repo.observeCustomerLedger(cust.id).first()
        assertEquals(bdt(800), ledgerEntries.first().runningBalancePaisa)
        val cashbox = repo.computeCashboxSummary(db.cashboxDao().getAllOnce(), AppSettingsEntity())
        assertEquals(bdt(300), cashbox.expectedBalancePaisa)
    }

    @Test
    fun testCompactBackupCodeRoundTripAndNullableJsonFields() = runBlocking {
        val cust = (repo.createCustomer("সালমান", "সিলেট", openingBalancePaisa = bdt(1200)) as OperationResult.Success).data
        repo.recordQuickPayment(cust.id, bdt(450), "জমা", requestToken = "code-pay-1")
        // Also add a walk-in cash memo (customerId == null -> explicit JSON null!)
        val walkInItem = MemoLineDraft("1", "মোট হিসাব", "1", "পিস", "300", 1000L, bdt(300), bdt(300))
        repo.createSalesMemo(SaleType.CASH, null, "নগদ ক্রেতা", "", listOf(walkInItem), 0L, bdt(300), "", "code-walkin-1")

        val codeRes = backupManager.createCompactBackupCode()
        assertTrue(codeRes is OperationResult.Success)
        val code = (codeRes as OperationResult.Success).data
        assertTrue(code.startsWith("BK1:"))

        val previewRes = backupManager.previewCompactBackupCode(code)
        assertTrue(previewRes is OperationResult.Success)
        val preview = (previewRes as OperationResult.Success).data
        assertTrue(preview.checksumValid)
        assertEquals(bdt(750), preview.totalOutstandingDebtPaisa)

        // Wipe everything completely
        db.auditLogDao().deleteAll()
        db.expenseDao().deleteAll()
        db.cashboxDao().deleteAll()
        db.paymentDao().deleteAll()
        db.creditTransactionDao().deleteAll()
        db.memoDao().deleteAllMemos()
        db.customerDao().deleteAll()

        val restoreRes = backupManager.restoreFromCompactCode(code)
        assertTrue(restoreRes is OperationResult.Success)
        assertEquals(1, db.customerDao().getAllOnce().size)
        assertEquals(bdt(750), db.customerDao().getById(cust.id)!!.currentBalancePaisa)
        val restoredWalkInMemo = db.memoDao().getAllMemosOnce().first { it.requestToken == "code-walkin-1" }
        assertNull(restoredWalkInMemo.customerId)
        assertEquals(bdt(300), restoredWalkInMemo.grandTotalPaisa)
    }
}
