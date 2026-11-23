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
}
