package com.example.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.R
import com.example.data.local.*
import com.example.data.repository.AccountingRepository
import com.example.domain.BackupPreviewInfo
import com.example.domain.OperationResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupManager(
    private val context: Context,
    private val db: BakirKhataDatabase
) {

    fun getLocalBackupDir(): File {
        val dir = File(context.filesDir, "backups")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun createJsonSnapshot(): Pair<String, String> {
        val settings = db.appSettingsDao().getSettings() ?: AppSettingsEntity()
        val customers = db.customerDao().getAllOnce()
        val memos = db.memoDao().getAllMemosOnce()
        val memoItems = db.memoDao().getAllMemoItemsOnce()
        val payments = db.paymentDao().getAllOnce()
        val credits = db.creditTransactionDao().getAllOnce()
        val expenses = db.expenseDao().getAllOnce()
        val cashbox = db.cashboxDao().getAllOnce()
        val periods = db.halKhataDao().getAllPeriodsOnce()
        val balances = db.halKhataDao().getAllBalancesOnce()
        val tempCustomers = db.temporaryCustomerDao().getAllOnce()
        val tempMemos = db.temporaryCustomerDao().getAllMemosOnce()
        val auditLogs = db.auditLogDao().getAllOnce()

        val payload = JSONObject().apply {
            put("schemaVersion", 1)
            put("createdAt", System.currentTimeMillis())
            put("shopName", settings.shopName)
            put("settings", settingsToJson(settings))
            put("customers", JSONArray().apply { customers.forEach { put(customerToJson(it)) } })
            put("memos", JSONArray().apply { memos.forEach { put(memoToJson(it)) } })
            put("memoItems", JSONArray().apply { memoItems.forEach { put(memoItemToJson(it)) } })
            put("payments", JSONArray().apply { payments.forEach { put(paymentToJson(it)) } })
            put("credits", JSONArray().apply { credits.forEach { put(creditToJson(it)) } })
            put("expenses", JSONArray().apply { expenses.forEach { put(expenseToJson(it)) } })
            put("cashbox", JSONArray().apply { cashbox.forEach { put(cashboxToJson(it)) } })
            put("halKhataPeriods", JSONArray().apply { periods.forEach { put(periodToJson(it)) } })
            put("halKhataBalances", JSONArray().apply { balances.forEach { put(balanceToJson(it)) } })
            put("tempCustomers", JSONArray().apply { tempCustomers.forEach { put(tempCustomerToJson(it)) } })
            put("tempMemos", JSONArray().apply { tempMemos.forEach { put(tempMemoToJson(it)) } })
            put("auditLogs", JSONArray().apply { auditLogs.forEach { put(auditToJson(it)) } })
        }

        val payloadString = canonicalJson(payload)
        val checksum = PinSecurityHelper.sha256(payloadString.toByteArray(Charsets.UTF_8))
        val envelope = JSONObject().apply {
            put("checksumSha256", checksum)
            put("payload", payload)
        }
        return envelope.toString(2) to checksum
    }

    suspend fun createZipBackup(
        backupType: String = "LOCAL_ZIP",
        customFileName: String? = null
    ): OperationResult<BackupMetadataEntity> {
        return try {
            val integrity = db.verifyIntegrity()
            if (!integrity.isValid) {
                throw IllegalStateException("ডাটাবেস যাচাই ব্যর্থ: ${integrity.message}")
            }

            // Flush WAL completely so raw SQLite .db copy is self-contained
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { }

            val (jsonText, checksum) = createJsonSnapshot()
            val fileName = customFileName ?: "bakir_khata_backup_${System.currentTimeMillis()}.zip"
            val outFile = File(getLocalBackupDir(), fileName)

            val dbFile = context.getDatabasePath(BakirKhataDatabase.DB_NAME)

            ZipOutputStream(FileOutputStream(outFile)).use { zos ->
                zos.putNextEntry(ZipEntry("backup_data.json"))
                zos.write(jsonText.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                if (dbFile.exists()) {
                    zos.putNextEntry(ZipEntry(BakirKhataDatabase.DB_NAME))
                    FileInputStream(dbFile).use { fis -> fis.copyTo(zos) }
                    zos.closeEntry()
                }
            }

            // Verify ZIP readability & checksum immediately
            val verified = verifyBackupFile(outFile)
            if (!verified) {
                outFile.delete()
                throw IllegalStateException("তৈরিকৃত ব্যাকআপ ফাইল যাচাই করা যায়নি")
            }

            val meta = BackupMetadataEntity(
                backupType = backupType,
                fileName = fileName,
                filePath = outFile.absolutePath,
                checksumSha256 = checksum,
                fileSizeBytes = outFile.length(),
                status = "SUCCESS",
                verified = true
            )
            val id = db.backupMetadataDao().insert(meta)

            // Also copy to SAF folder if configured
            val settings = db.appSettingsDao().getSettings()
            if (settings != null && settings.backupFolderUri.isNotBlank()) {
                copyBackupToSafFolder(outFile, settings.backupFolderUri, "application/zip")
            }

            OperationResult.Success(meta.copy(id = id), "ব্যাকআপ সফলভাবে তৈরি ও যাচাই হয়েছে: $fileName")
        } catch (e: Exception) {
            val failMeta = BackupMetadataEntity(
                backupType = backupType,
                fileName = customFileName ?: "failed_backup",
                filePath = "",
                checksumSha256 = "",
                fileSizeBytes = 0L,
                status = "FAILED",
                errorMessage = e.message ?: "অজানা ত্রুটি",
                verified = false
            )
            db.backupMetadataDao().insert(failMeta)
            OperationResult.Error("ব্যাকআপ ব্যর্থ হয়েছে: ${e.message ?: "ত্রুটি"}")
        }
    }

    suspend fun createJsonBackupFile(): OperationResult<BackupMetadataEntity> {
        return try {
            val (jsonText, checksum) = createJsonSnapshot()
            val fileName = "bakir_khata_backup_${System.currentTimeMillis()}.json"
            val outFile = File(getLocalBackupDir(), fileName)
            outFile.writeText(jsonText, Charsets.UTF_8)
            val verified = verifyBackupFile(outFile)
            if (!verified) {
                outFile.delete()
                throw IllegalStateException("JSON ব্যাকআপ যাচাই ব্যর্থ হয়েছে")
            }
            val meta = BackupMetadataEntity(
                backupType = "LOCAL_JSON",
                fileName = fileName,
                filePath = outFile.absolutePath,
                checksumSha256 = checksum,
                fileSizeBytes = outFile.length(),
                status = "SUCCESS",
                verified = true
            )
            val id = db.backupMetadataDao().insert(meta)
            val settings = db.appSettingsDao().getSettings()
            if (settings != null && settings.backupFolderUri.isNotBlank()) {
                copyBackupToSafFolder(outFile, settings.backupFolderUri, "application/json")
            }
            OperationResult.Success(meta.copy(id = id), "JSON ব্যাকআপ সংরক্ষিত হয়েছে")
        } catch (e: Exception) {
            val failMeta = BackupMetadataEntity(
                backupType = "LOCAL_JSON",
                fileName = "failed.json",
                filePath = "",
                checksumSha256 = "",
                fileSizeBytes = 0L,
                status = "FAILED",
                errorMessage = e.message ?: "ত্রুটি",
                verified = false
            )
            db.backupMetadataDao().insert(failMeta)
            OperationResult.Error("JSON ব্যাকআপ ব্যর্থ: ${e.message}")
        }
    }

    suspend fun createRawDbBackupFile(): OperationResult<BackupMetadataEntity> {
        return try {
            val integrity = db.verifyIntegrity()
            if (!integrity.isValid) throw IllegalStateException(integrity.message)
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { }
            val dbFile = context.getDatabasePath(BakirKhataDatabase.DB_NAME)
            if (!dbFile.exists()) throw IllegalStateException("ডাটাবেস ফাইল পাওয়া যায়নি")
            val fileName = "bakir_khata_${System.currentTimeMillis()}.db"
            val outFile = File(getLocalBackupDir(), fileName)
            dbFile.copyTo(outFile, overwrite = true)
            val checksum = PinSecurityHelper.sha256(outFile.readBytes())
            val meta = BackupMetadataEntity(
                backupType = "LOCAL_DB",
                fileName = fileName,
                filePath = outFile.absolutePath,
                checksumSha256 = checksum,
                fileSizeBytes = outFile.length(),
                status = "SUCCESS",
                verified = true
            )
            val id = db.backupMetadataDao().insert(meta)
            val settings = db.appSettingsDao().getSettings()
            if (settings != null && settings.backupFolderUri.isNotBlank()) {
                copyBackupToSafFolder(outFile, settings.backupFolderUri, "application/octet-stream")
            }
            OperationResult.Success(meta.copy(id = id), "ডাটাবেস (.db) ব্যাকআপ সম্পন্ন হয়েছে")
        } catch (e: Exception) {
            OperationResult.Error("ডাটাবেস ব্যাকআপ ব্যর্থ: ${e.message}")
        }
    }

    suspend fun createCompactBackupCode(): OperationResult<String> {
        return try {
            val (jsonText, _) = createJsonSnapshot()
            val gzipped = ByteArrayOutputStream().use { baos ->
                GZIPOutputStream(baos).use { gz ->
                    gz.write(jsonText.toByteArray(Charsets.UTF_8))
                }
                baos.toByteArray()
            }
            val encoded = Base64.encodeToString(gzipped, Base64.NO_WRAP)
            OperationResult.Success(
                "$COMPACT_CODE_PREFIX$encoded",
                "কমপ্যাক্ট ব্যাকআপ কোড তৈরি হয়েছে (${encoded.length} অক্ষর)"
            )
        } catch (e: Exception) {
            OperationResult.Error("ব্যাকআপ কোড তৈরি করা যায়নি: ${e.message ?: "ত্রুটি"}")
        }
    }

    fun previewCompactBackupCode(code: String): OperationResult<BackupPreviewInfo> {
        val bytes = decodeCompactBackupCode(code)
            ?: return OperationResult.Error("ব্যাকআপ কোডটি সঠিক নয়। পুরো কোড (BK1:... সহ) কপি করে দিন।")
        return previewBackupBytes(bytes, "ব্যাকআপ কোড")
    }

    suspend fun restoreFromCompactCode(code: String): OperationResult<String> {
        val bytes = decodeCompactBackupCode(code)
            ?: return OperationResult.Error("ব্যাকআপ কোডটি সঠিক নয়। পুরো কোড (BK1:... সহ) কপি করে দিন।")
        return restoreFromBytes(bytes, "ব্যাকআপ কোড")
    }

    fun previewBackupFile(file: File): OperationResult<BackupPreviewInfo> {
        if (!file.exists() || !file.canRead()) {
            return OperationResult.Error("ব্যাকআপ ফাইলটি ফোনে খুঁজে পাওয়া যায়নি")
        }
        return previewBackupBytes(file.readBytes(), file.name)
    }

    suspend fun restoreFromFile(file: File): OperationResult<String> {
        if (!file.exists() || !file.canRead()) {
            return OperationResult.Error("ব্যাকআপ ফাইলটি ফোনে খুঁজে পাওয়া যায়নি")
        }
        return restoreFromBytes(file.readBytes(), file.name)
    }

    fun verifyBackupFile(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return try {
            val resolved = resolveBackupPayload(file.readBytes(), file.name) ?: return false
            resolved.checksumValid
        } catch (_: Exception) {
            false
        }
    }

    fun previewBackupBytes(bytes: ByteArray, fileName: String): OperationResult<BackupPreviewInfo> {
        return try {
            val resolved = resolveBackupPayload(bytes, fileName)
                ?: return OperationResult.Error("সঠিক বাকির খাতা ব্যাকআপ ফাইল নয় (.zip, .json বা .db)")
            val payload = resolved.payload
            val customersArr = payload.optJSONArray("customers") ?: JSONArray()
            var totalDebt = 0L
            for (i in 0 until customersArr.length()) {
                totalDebt += customersArr.getJSONObject(i).optLong("currentBalancePaisa", 0L)
            }

            OperationResult.Success(
                BackupPreviewInfo(
                    fileName = fileName,
                    createdAt = payload.optLong("createdAt", System.currentTimeMillis()),
                    shopName = payload.optString("shopName", "অজানা দোকান"),
                    customerCount = customersArr.length(),
                    memoCount = (payload.optJSONArray("memos") ?: JSONArray()).length(),
                    paymentCount = (payload.optJSONArray("payments") ?: JSONArray()).length(),
                    creditCount = (payload.optJSONArray("credits") ?: JSONArray()).length(),
                    expenseCount = (payload.optJSONArray("expenses") ?: JSONArray()).length(),
                    totalOutstandingDebtPaisa = totalDebt,
                    checksumValid = resolved.checksumValid,
                    formatLabel = resolved.formatLabel
                )
            )
        } catch (e: Exception) {
            OperationResult.Error("ব্যাকআপ ফাইল পড়া যায়নি: ${e.message}")
        }
    }

    fun readBytesFromUri(uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun restoreFromBytes(bytes: ByteArray, fileName: String): OperationResult<String> {
        val resolved = try {
            resolveBackupPayload(bytes, fileName)
        } catch (e: Exception) {
            return OperationResult.Error("ব্যাকআপ ফাইল যাচাই ব্যর্থ: ${e.message ?: "অবৈধ ফাইল"}")
        } ?: return OperationResult.Error("অবৈধ ব্যাকআপ ফাইল")

        return try {
            if (!resolved.checksumValid) {
                return OperationResult.Error("ব্যাকআপ ফাইল ক্ষতিগ্রস্ত বা চেকসাম মিলছে না! পূর্বের ডাটা নিরাপদ রাখা হয়েছে।")
            }

            // Safety backup before overwriting
            val preRestoreSnapshot = createJsonSnapshot().first

            try {
                restorePayloadAtomically(resolved.payload)
            } catch (e: Exception) {
                // Attempt rollback from pre-restore snapshot
                try {
                    val rollbackPayload = JSONObject(preRestoreSnapshot).getJSONObject("payload")
                    restorePayloadAtomically(rollbackPayload)
                } catch (_: Exception) { }
                throw e
            }

            val integrity = db.verifyIntegrity()
            if (!integrity.isValid) {
                throw IllegalStateException("পুনরুদ্ধারের পর ডাটাবেস যাচাই ব্যর্থ: ${integrity.message}")
            }

            OperationResult.Success("সকল ডাটা সফলভাবে পুনরুদ্ধার হয়েছে ও যাচাই সম্পন্ন হয়েছে")
        } catch (e: Exception) {
            OperationResult.Error("রিস্টোর ব্যর্থ হয়েছে: ${e.message ?: "ত্রুটি"}")
        }
    }

    private suspend fun restorePayloadAtomically(payload: JSONObject) {
        val rawSettings = if (!payload.isNull("settings")) {
            jsonToSettings(payload.getJSONObject("settings"))
        } else {
            AppSettingsEntity()
        }
        val customers = parseArray(payload.optJSONArray("customers")) { jsonToCustomer(it) }
            .distinctBy { it.id }
            .distinctBy { it.customerCode }
        val survivingCustomerIds = customers.map { it.id }.toSet()

        val memos = parseArray(payload.optJSONArray("memos")) { jsonToMemo(it) }
            .distinctBy { it.id }
            .distinctBy { it.requestToken }
        val survivingMemoIds = memos.map { it.id }.toSet()

        val memoItems = parseArray(payload.optJSONArray("memoItems")) { jsonToMemoItem(it) }
            .filter { it.memoId in survivingMemoIds }
        val payments = parseArray(payload.optJSONArray("payments")) { jsonToPayment(it) }
            .distinctBy { it.requestToken }
            .filter { it.customerId in survivingCustomerIds }
        val credits = parseArray(payload.optJSONArray("credits")) { jsonToCredit(it) }
            .distinctBy { it.requestToken }
            .filter { it.customerId in survivingCustomerIds }
        val expenses = parseArray(payload.optJSONArray("expenses")) { jsonToExpense(it) }
            .distinctBy { it.requestToken }
        val cashbox = parseArray(payload.optJSONArray("cashbox")) { jsonToCashbox(it) }
            .distinctBy { it.requestToken }

        val rawPeriods = parseArray(payload.optJSONArray("halKhataPeriods")) { jsonToPeriod(it) }
            .distinctBy { it.id }
        val periods = if (rawPeriods.any { it.status == HalKhataStatus.ACTIVE }) {
            rawPeriods
        } else if (rawPeriods.isNotEmpty()) {
            val lastIdx = rawPeriods.lastIndex
            rawPeriods.mapIndexed { idx, p -> if (idx == lastIdx) p.copy(status = HalKhataStatus.ACTIVE) else p }
        } else {
            listOf(
                HalKhataPeriodEntity(
                    id = 1L,
                    name = "প্রথম হালখাতা",
                    startDate = System.currentTimeMillis(),
                    status = HalKhataStatus.ACTIVE
                )
            )
        }
        val survivingPeriodIds = periods.map { it.id }.toSet()
        val activePeriod = periods.last { it.status == HalKhataStatus.ACTIVE }
        val settings = rawSettings.copy(activeHalKhataPeriodId = activePeriod.id)

        val balances = parseArray(payload.optJSONArray("halKhataBalances")) { jsonToBalance(it) }
            .filter { it.periodId in survivingPeriodIds && it.customerId in survivingCustomerIds }
        val tempCustomers = parseArray(payload.optJSONArray("tempCustomers")) { jsonToTempCustomer(it) }
            .distinctBy { it.id }
        val survivingTempIds = tempCustomers.map { it.id }.toSet()
        val tempMemos = parseArray(payload.optJSONArray("tempMemos")) { jsonToTempMemo(it) }
            .filter { it.tempCustomerId in survivingTempIds }
        val auditLogs = parseArray(payload.optJSONArray("auditLogs")) { jsonToAudit(it) }

        db.withTransaction {
            // Clear child tables before parent tables respecting foreign keys
            db.auditLogDao().deleteAll()
            db.temporaryCustomerDao().deleteAllMemos()
            db.temporaryCustomerDao().deleteAllCustomers()
            db.halKhataDao().deleteAllBalances()
            db.halKhataDao().deleteAllPeriods()
            db.cashboxDao().deleteAll()
            db.expenseDao().deleteAll()
            db.creditTransactionDao().deleteAll()
            db.paymentDao().deleteAll()
            db.memoDao().deleteAllMemos()
            db.customerDao().deleteAll()

            db.appSettingsDao().upsert(settings)
            if (periods.isNotEmpty()) db.halKhataDao().insertAllPeriods(periods)
            if (customers.isNotEmpty()) db.customerDao().insertAll(customers)
            if (memos.isNotEmpty()) db.memoDao().insertAllMemos(memos)
            if (memoItems.isNotEmpty()) db.memoDao().insertAllItems(memoItems)
            if (payments.isNotEmpty()) db.paymentDao().insertAll(payments)
            if (credits.isNotEmpty()) db.creditTransactionDao().insertAll(credits)
            if (expenses.isNotEmpty()) db.expenseDao().insertAll(expenses)
            if (cashbox.isNotEmpty()) db.cashboxDao().insertAll(cashbox)
            if (balances.isNotEmpty()) db.halKhataDao().insertBalances(balances)
            if (tempCustomers.isNotEmpty()) db.temporaryCustomerDao().insertAllCustomers(tempCustomers)
            if (tempMemos.isNotEmpty()) db.temporaryCustomerDao().insertAllMemos(tempMemos)
            if (auditLogs.isNotEmpty()) db.auditLogDao().insertAll(auditLogs)

            db.auditLogDao().insert(
                AuditLogEntity(
                    transactionRef = "RESTORE-${System.currentTimeMillis()}",
                    entityType = "BACKUP",
                    entityId = 0L,
                    actionType = "RESTORE",
                    previousValueJson = "",
                    newValueJson = JSONObject().put("customers", customers.size).put("memos", memos.size).toString(),
                    reason = "ব্যাকআপ থেকে ডাটাবেস পুনরুদ্ধার"
                )
            )
        }
    }

    private data class ResolvedBackupPayload(
        val payload: JSONObject,
        val checksumValid: Boolean,
        val formatLabel: String
    )

    private fun looksLikeZip(bytes: ByteArray, fileName: String): Boolean {
        return (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) ||
            fileName.endsWith(".zip", ignoreCase = true)
    }

    private fun looksLikeSqlite(bytes: ByteArray): Boolean {
        val header = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        if (bytes.size < header.size) return false
        for (i in header.indices) {
            if (bytes[i] != header[i]) return false
        }
        return true
    }

    private fun decodeCompactBackupCode(code: String): ByteArray? {
        val trimmed = code.trim()
        if (!trimmed.startsWith(COMPACT_CODE_PREFIX, ignoreCase = true)) return null
        val b64 = trimmed.substring(COMPACT_CODE_PREFIX.length).replace("\\s+".toRegex(), "")
        if (b64.isEmpty()) return null
        return try {
            val compressed = Base64.decode(b64, Base64.DEFAULT)
            GZIPInputStream(ByteArrayInputStream(compressed)).use { gz ->
                gz.readBytes()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveBackupPayload(bytes: ByteArray, fileName: String): ResolvedBackupPayload? {
        if (looksLikeSqlite(bytes)) {
            return resolveSqliteBackup(bytes)
        }
        val isZip = looksLikeZip(bytes, fileName)
        if (isZip) {
            var jsonEntryText: String? = null
            var dbEntryBytes: ByteArray? = null
            try {
                ZipInputStream(bytes.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val nameLower = entry.name.lowercase()
                        if (!entry.isDirectory) {
                            if (nameLower == "backup_data.json" || (jsonEntryText == null && nameLower.endsWith(".json"))) {
                                val baos = ByteArrayOutputStream()
                                zis.copyTo(baos)
                                jsonEntryText = baos.toString(Charsets.UTF_8.name())
                            } else if (nameLower.endsWith(".db") || entry.name == BakirKhataDatabase.DB_NAME) {
                                val baos = ByteArrayOutputStream()
                                zis.copyTo(baos)
                                dbEntryBytes = baos.toByteArray()
                            }
                        }
                        entry = zis.nextEntry
                    }
                }
            } catch (_: Exception) {
                return null
            }
            if (jsonEntryText != null) {
                val parsed = parseJsonEnvelope(jsonEntryText!!, "ZIP প্যাকেজ")
                if (parsed != null && parsed.checksumValid) return parsed
                if (dbEntryBytes != null) {
                    resolveSqliteBackup(dbEntryBytes!!)?.let { return it }
                }
                return parsed
            }
            if (dbEntryBytes != null) {
                return resolveSqliteBackup(dbEntryBytes!!)
            }
            return null
        }

        val rawText = bytes.toString(Charsets.UTF_8).trim()
        if (rawText.startsWith(COMPACT_CODE_PREFIX, ignoreCase = true)) {
            val decoded = decodeCompactBackupCode(rawText) ?: return null
            return parseJsonEnvelope(decoded.toString(Charsets.UTF_8), "ব্যাকআপ কোড")
        }
        return parseJsonEnvelope(rawText, "JSON স্ন্যাপশট")
    }

    private fun parseJsonEnvelope(jsonText: String, formatLabel: String): ResolvedBackupPayload? {
        val env = JSONObject(jsonText)
        val expectedChecksum = env.optString("checksumSha256", "")
        val payload = env.optJSONObject("payload") ?: return null
        val directChecksum = PinSecurityHelper.sha256(payload.toString().toByteArray(Charsets.UTF_8))
        val canonicalChecksum = PinSecurityHelper.sha256(canonicalJson(payload).toByteArray(Charsets.UTF_8))
        val checksumValid = expectedChecksum.isNotBlank() &&
            (expectedChecksum.equals(directChecksum, ignoreCase = true) ||
                expectedChecksum.equals(canonicalChecksum, ignoreCase = true))
        return ResolvedBackupPayload(
            payload = payload,
            checksumValid = checksumValid,
            formatLabel = formatLabel
        )
    }

    private fun canonicalJson(value: Any?): String {
        return when (value) {
            is JSONObject -> {
                val keys = value.keys().asSequence().toList().sorted()
                buildString {
                    append('{')
                    keys.forEachIndexed { index, k ->
                        if (index > 0) append(',')
                        append(JSONObject.quote(k))
                        append(':')
                        val v = value.opt(k)
                        append(canonicalJson(v))
                    }
                    append('}')
                }
            }
            is JSONArray -> {
                buildString {
                    append('[')
                    for (i in 0 until value.length()) {
                        if (i > 0) append(',')
                        append(canonicalJson(value.opt(i)))
                    }
                    append(']')
                }
            }
            null, JSONObject.NULL -> "null"
            is String -> JSONObject.quote(value)
            else -> value.toString()
        }
    }

    private fun resolveSqliteBackup(bytes: ByteArray): ResolvedBackupPayload? {
        val tmpFile = File(context.cacheDir, "inspect_bakirkhata_${System.currentTimeMillis()}.db")
        return try {
            tmpFile.writeBytes(bytes)
            val sqliteDb = SQLiteDatabase.openDatabase(tmpFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            sqliteDb.use { sdb ->
                var integrityOk = false
                sdb.rawQuery("PRAGMA integrity_check", null).use { c ->
                    if (c.moveToFirst()) {
                        integrityOk = c.getString(0).equals("ok", ignoreCase = true)
                    }
                }
                if (!integrityOk) return null

                var hasCustomersTable = false
                sdb.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='customers'", null).use { c ->
                    hasCustomersTable = c.moveToFirst()
                }
                if (!hasCustomersTable) return null

                fun Cursor.str(col: String, def: String = ""): String {
                    val i = getColumnIndex(col)
                    return if (i >= 0 && !isNull(i)) getString(i) else def
                }
                fun Cursor.lng(col: String, def: Long = 0L): Long {
                    val i = getColumnIndex(col)
                    return if (i >= 0 && !isNull(i)) getLong(i) else def
                }
                fun Cursor.nullableLng(col: String): Long? {
                    val i = getColumnIndex(col)
                    return if (i >= 0 && !isNull(i)) getLong(i) else null
                }
                fun Cursor.bool(col: String, def: Boolean = false): Boolean {
                    val i = getColumnIndex(col)
                    return if (i >= 0 && !isNull(i)) getInt(i) != 0 else def
                }
                fun readTable(sql: String, mapper: (Cursor) -> JSONObject): JSONArray {
                    val arr = JSONArray()
                    sdb.rawQuery(sql, null).use { c ->
                        while (c.moveToNext()) arr.put(mapper(c))
                    }
                    return arr
                }

                var settingsJson = settingsToJson(AppSettingsEntity())
                sdb.rawQuery("SELECT * FROM app_settings WHERE id = 1 LIMIT 1", null).use { c ->
                    if (c.moveToFirst()) {
                        settingsJson = settingsToJson(
                            AppSettingsEntity(
                                id = 1,
                                shopName = c.str("shopName", "আমার দোকান"),
                                ownerName = c.str("ownerName"),
                                shopAddress = c.str("shopAddress"),
                                shopPhone = c.str("shopPhone"),
                                currencySymbol = c.str("currencySymbol", "৳"),
                                memoPrefix = c.str("memoPrefix", "MEMO"),
                                customUnitsJson = c.str("customUnitsJson", "[]"),
                                customExpenseCategoriesJson = c.str("customExpenseCategoriesJson", "[]"),
                                backupFrequency = c.str("backupFrequency", "DAILY"),
                                backupFolderUri = c.str("backupFolderUri"),
                                themeMode = c.str("themeMode", "SYSTEM"),
                                pdfFooterNote = c.str("pdfFooterNote", "ধন্যবাদ, আবার আসবেন।"),
                                highExpenseThresholdPaisa = c.lng("highExpenseThresholdPaisa", 500000L),
                                overdueDaysThreshold = c.lng("overdueDaysThreshold", 30L).toInt(),
                                activeHalKhataPeriodId = c.lng("activeHalKhataPeriodId", 1L),
                                lastActualCashCountPaisa = c.nullableLng("lastActualCashCountPaisa"),
                                lastActualCashCountAt = c.nullableLng("lastActualCashCountAt"),
                                updatedAt = c.lng("updatedAt", System.currentTimeMillis())
                            )
                        )
                    }
                }

                val payload = JSONObject().apply {
                    put("schemaVersion", 1)
                    put("createdAt", System.currentTimeMillis())
                    put("shopName", settingsJson.optString("shopName", "আমার দোকান"))
                    put("settings", settingsJson)
                    put("customers", readTable("SELECT * FROM customers ORDER BY id ASC") { c ->
                        customerToJson(
                            CustomerEntity(
                                id = c.lng("id"), customerCode = c.str("customerCode"), name = c.str("name"),
                                address = c.str("address"), mobile = c.str("mobile"), nickname = c.str("nickname"),
                                notes = c.str("notes"), openingBalancePaisa = c.lng("openingBalancePaisa"),
                                currentBalancePaisa = c.lng("currentBalancePaisa"), totalPurchasesPaisa = c.lng("totalPurchasesPaisa"),
                                totalCreditPaisa = c.lng("totalCreditPaisa"), totalPaymentsPaisa = c.lng("totalPaymentsPaisa"),
                                halKhataPeriodId = c.lng("halKhataPeriodId", 1L),
                                oldestUnpaidTimestamp = c.nullableLng("oldestUnpaidTimestamp"),
                                lastTransactionDate = c.nullableLng("lastTransactionDate"),
                                lastPaymentDate = c.nullableLng("lastPaymentDate"),
                                createdAt = c.lng("createdAt")
                            )
                        )
                    })
                    put("memos", readTable("SELECT * FROM memos ORDER BY id ASC") { c ->
                        memoToJson(
                            MemoEntity(
                                id = c.lng("id"), memoNumber = c.str("memoNumber"), requestToken = c.str("requestToken"),
                                customerId = c.nullableLng("customerId"), customerNameSnapshot = c.str("customerNameSnapshot"),
                                customerAddressSnapshot = c.str("customerAddressSnapshot"), saleType = c.str("saleType"),
                                subtotalPaisa = c.lng("subtotalPaisa"), discountPaisa = c.lng("discountPaisa"),
                                grandTotalPaisa = c.lng("grandTotalPaisa"), paidPaisa = c.lng("paidPaisa"),
                                outstandingPaisa = c.lng("outstandingPaisa"), notes = c.str("notes"),
                                halKhataPeriodId = c.lng("halKhataPeriodId", 1L),
                                isCancelled = c.bool("isCancelled"), needsReview = c.bool("needsReview"),
                                createdAt = c.lng("createdAt"), updatedAt = c.lng("updatedAt")
                            )
                        )
                    })
                    put("memoItems", readTable("SELECT * FROM memo_items ORDER BY id ASC") { c ->
                        memoItemToJson(
                            MemoItemEntity(
                                id = c.lng("id"), memoId = c.lng("memoId"), productName = c.str("productName"),
                                quantityMillis = c.lng("quantityMillis"), unit = c.str("unit"),
                                unitPricePaisa = c.lng("unitPricePaisa"), lineTotalPaisa = c.lng("lineTotalPaisa")
                            )
                        )
                    })
                    put("payments", readTable("SELECT * FROM payments ORDER BY id ASC") { c ->
                        paymentToJson(
                            PaymentEntity(
                                id = c.lng("id"), receiptNumber = c.str("receiptNumber"), requestToken = c.str("requestToken"),
                                customerId = c.lng("customerId"), amountPaisa = c.lng("amountPaisa"),
                                previousBalancePaisa = c.lng("previousBalancePaisa"), newBalancePaisa = c.lng("newBalancePaisa"),
                                memoId = c.nullableLng("memoId"), notes = c.str("notes"),
                                halKhataPeriodId = c.lng("halKhataPeriodId", 1L),
                                isReversed = c.bool("isReversed"), reversedById = c.nullableLng("reversedById"),
                                needsReview = c.bool("needsReview"), createdAt = c.lng("createdAt")
                            )
                        )
                    })
                    put("credits", readTable("SELECT * FROM credit_transactions ORDER BY id ASC") { c ->
                        creditToJson(
                            CreditTransactionEntity(
                                id = c.lng("id"), transactionCode = c.str("transactionCode"), requestToken = c.str("requestToken"),
                                customerId = c.lng("customerId"), amountPaisa = c.lng("amountPaisa"),
                                previousBalancePaisa = c.lng("previousBalancePaisa"), newBalancePaisa = c.lng("newBalancePaisa"),
                                description = c.str("description"), notes = c.str("notes"),
                                sourceType = c.str("sourceType", CreditSourceType.QUICK_CREDIT),
                                memoId = c.nullableLng("memoId"), halKhataPeriodId = c.lng("halKhataPeriodId", 1L),
                                isReversed = c.bool("isReversed"), needsReview = c.bool("needsReview"),
                                createdAt = c.lng("createdAt")
                            )
                        )
                    })
                    put("expenses", readTable("SELECT * FROM expenses ORDER BY id ASC") { c ->
                        expenseToJson(
                            ExpenseEntity(
                                id = c.lng("id"), expenseCode = c.str("expenseCode"), requestToken = c.str("requestToken"),
                                category = c.str("category"), amountPaisa = c.lng("amountPaisa"),
                                description = c.str("description"), notes = c.str("notes"),
                                paidFromCashbox = c.bool("paidFromCashbox", true), halKhataPeriodId = c.lng("halKhataPeriodId", 1L),
                                isCancelled = c.bool("isCancelled"), needsReview = c.bool("needsReview"),
                                createdAt = c.lng("createdAt"), updatedAt = c.lng("updatedAt")
                            )
                        )
                    })
                    put("cashbox", readTable("SELECT * FROM cashbox_transactions ORDER BY id ASC") { c ->
                        cashboxToJson(
                            CashboxTransactionEntity(
                                id = c.lng("id"), transactionCode = c.str("transactionCode"), requestToken = c.str("requestToken"),
                                type = c.str("type"), direction = c.str("direction"), amountPaisa = c.lng("amountPaisa"),
                                sourceType = c.str("sourceType"), sourceId = c.nullableLng("sourceId"),
                                description = c.str("description"), notes = c.str("notes"),
                                halKhataPeriodId = c.lng("halKhataPeriodId", 1L), isReversed = c.bool("isReversed"),
                                createdAt = c.lng("createdAt")
                            )
                        )
                    })
                    put("halKhataPeriods", readTable("SELECT * FROM hal_khata_periods ORDER BY id ASC") { c ->
                        periodToJson(
                            HalKhataPeriodEntity(
                                id = c.lng("id"), name = c.str("name"), description = c.str("description"),
                                startDate = c.lng("startDate"), endDate = c.nullableLng("endDate"),
                                status = c.str("status", HalKhataStatus.ACTIVE),
                                totalSalesPaisa = c.lng("totalSalesPaisa"), totalCreditPaisa = c.lng("totalCreditPaisa"),
                                totalPaymentsPaisa = c.lng("totalPaymentsPaisa"), totalClosingDebtPaisa = c.lng("totalClosingDebtPaisa"),
                                closedAt = c.nullableLng("closedAt"), archivePdfPath = c.str("archivePdfPath"),
                                archiveBackupPath = c.str("archiveBackupPath"), backupVerified = c.bool("backupVerified"),
                                createdAt = c.lng("createdAt")
                            )
                        )
                    })
                    put("halKhataBalances", readTable("SELECT * FROM hal_khata_customer_balances ORDER BY id ASC") { c ->
                        balanceToJson(
                            HalKhataCustomerBalanceEntity(
                                id = c.lng("id"), periodId = c.lng("periodId"), customerId = c.lng("customerId"),
                                customerCode = c.str("customerCode"), customerName = c.str("customerName"),
                                openingBalancePaisa = c.lng("openingBalancePaisa"), totalPurchasesPaisa = c.lng("totalPurchasesPaisa"),
                                totalPaymentsPaisa = c.lng("totalPaymentsPaisa"), closingBalancePaisa = c.lng("closingBalancePaisa"),
                                carriedForward = c.bool("carriedForward"), recordedAt = c.lng("recordedAt")
                            )
                        )
                    })
                    put("tempCustomers", readTable("SELECT * FROM temporary_customers ORDER BY id ASC") { c ->
                        tempCustomerToJson(
                            TemporaryCustomerEntity(
                                id = c.lng("id"), tempCode = c.str("tempCode"), name = c.str("name"),
                                address = c.str("address"), mobile = c.str("mobile"), notes = c.str("notes"),
                                totalCreditPaisa = c.lng("totalCreditPaisa"), totalPaidPaisa = c.lng("totalPaidPaisa"),
                                outstandingPaisa = c.lng("outstandingPaisa"), convertedCustomerId = c.nullableLng("convertedCustomerId"),
                                isConverted = c.bool("isConverted"), createdAt = c.lng("createdAt"), updatedAt = c.lng("updatedAt")
                            )
                        )
                    })
                    put("tempMemos", readTable("SELECT * FROM temporary_memos ORDER BY id ASC") { c ->
                        tempMemoToJson(
                            TemporaryMemoEntity(
                                id = c.lng("id"), tempCustomerId = c.lng("tempCustomerId"), memoNumber = c.str("memoNumber"),
                                type = c.str("type"), description = c.str("description"), itemsJson = c.str("itemsJson"),
                                totalAmountPaisa = c.lng("totalAmountPaisa"), paidPaisa = c.lng("paidPaisa"),
                                creditPaisa = c.lng("creditPaisa"), notes = c.str("notes"), createdAt = c.lng("createdAt")
                            )
                        )
                    })
                    put("auditLogs", readTable("SELECT * FROM audit_logs ORDER BY id ASC") { c ->
                        auditToJson(
                            AuditLogEntity(
                                id = c.lng("id"), transactionRef = c.str("transactionRef"), entityType = c.str("entityType"),
                                entityId = c.lng("entityId"), actionType = c.str("actionType"),
                                previousValueJson = c.str("previousValueJson"), newValueJson = c.str("newValueJson"),
                                reason = c.str("reason"), relatedCustomerId = c.nullableLng("relatedCustomerId"),
                                relatedMemoId = c.nullableLng("relatedMemoId"), relatedPaymentId = c.nullableLng("relatedPaymentId"),
                                createdAt = c.lng("createdAt")
                            )
                        )
                    })
                }
                ResolvedBackupPayload(
                    payload = payload,
                    checksumValid = true,
                    formatLabel = "SQLite ডাটাবেস (.db)"
                )
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { tmpFile.delete() }
        }
    }

    private fun extractJsonFromBytes(bytes: ByteArray, fileName: String): String? {
        if (looksLikeZip(bytes, fileName)) {
            ZipInputStream(bytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory &&
                        (entry.name == "backup_data.json" || entry.name.endsWith(".json", ignoreCase = true))
                    ) {
                        val baos = ByteArrayOutputStream()
                        zis.copyTo(baos)
                        return baos.toString(Charsets.UTF_8.name())
                    }
                    entry = zis.nextEntry
                }
            }
            return null
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun copyBackupToSafFolder(file: File, treeUriStr: String, mimeType: String) {
        try {
            val treeUri = Uri.parse(treeUriStr)
            val dir = DocumentFile.fromTreeUri(context, treeUri) ?: return
            val doc = dir.createFile(mimeType, file.name) ?: return
            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                FileInputStream(file).use { inp -> inp.copyTo(out) }
            }
        } catch (_: Exception) {
        }
    }

    fun copyFileToUri(sourceFile: File, destUri: Uri): Boolean {
        return try {
            context.contentResolver.openOutputStream(destUri)?.use { out ->
                FileInputStream(sourceFile).use { inp -> inp.copyTo(out) }
            } != null
        } catch (_: Exception) {
            false
        }
    }

    // region JSON Serializers

    private fun <T> parseArray(arr: JSONArray?, mapper: (JSONObject) -> T): List<T> {
        if (arr == null) return emptyList()
        val list = ArrayList<T>(arr.length())
        for (i in 0 until arr.length()) {
            list.add(mapper(arr.getJSONObject(i)))
        }
        return list
    }

    private fun settingsToJson(s: AppSettingsEntity) = JSONObject().apply {
        put("id", s.id)
        put("shopName", s.shopName)
        put("ownerName", s.ownerName)
        put("shopAddress", s.shopAddress)
        put("shopPhone", s.shopPhone)
        put("currencySymbol", s.currencySymbol)
        put("memoPrefix", s.memoPrefix)
        put("customUnitsJson", s.customUnitsJson)
        put("customExpenseCategoriesJson", s.customExpenseCategoriesJson)
        put("backupFrequency", s.backupFrequency)
        put("backupFolderUri", s.backupFolderUri)
        put("themeMode", s.themeMode)
        put("pdfFooterNote", s.pdfFooterNote)
        put("highExpenseThresholdPaisa", s.highExpenseThresholdPaisa)
        put("overdueDaysThreshold", s.overdueDaysThreshold)
        put("activeHalKhataPeriodId", s.activeHalKhataPeriodId)
        s.lastActualCashCountPaisa?.let { put("lastActualCashCountPaisa", it) }
        s.lastActualCashCountAt?.let { put("lastActualCashCountAt", it) }
        put("updatedAt", s.updatedAt)
    }

    private fun jsonToSettings(j: JSONObject) = AppSettingsEntity(
        id = 1,
        shopName = j.optString("shopName", "আমার দোকান"),
        ownerName = j.optString("ownerName", ""),
        shopAddress = j.optString("shopAddress", ""),
        shopPhone = j.optString("shopPhone", ""),
        currencySymbol = j.optString("currencySymbol", "৳"),
        memoPrefix = j.optString("memoPrefix", "MEMO"),
        customUnitsJson = j.optString("customUnitsJson", "[]"),
        customExpenseCategoriesJson = j.optString("customExpenseCategoriesJson", "[]"),
        backupFrequency = j.optString("backupFrequency", "DAILY"),
        backupFolderUri = j.optString("backupFolderUri", ""),
        themeMode = j.optString("themeMode", "SYSTEM"),
        pdfFooterNote = j.optString("pdfFooterNote", "ধন্যবাদ, আবার আসবেন।"),
        highExpenseThresholdPaisa = j.optLong("highExpenseThresholdPaisa", 500000L),
        overdueDaysThreshold = j.optInt("overdueDaysThreshold", 30),
        activeHalKhataPeriodId = j.optLong("activeHalKhataPeriodId", 1L),
        lastActualCashCountPaisa = if (!j.isNull("lastActualCashCountPaisa")) j.getLong("lastActualCashCountPaisa") else null,
        lastActualCashCountAt = if (!j.isNull("lastActualCashCountAt")) j.getLong("lastActualCashCountAt") else null,
        updatedAt = j.optLong("updatedAt", System.currentTimeMillis())
    )

    private fun customerToJson(c: CustomerEntity) = JSONObject().apply {
        put("id", c.id); put("customerCode", c.customerCode); put("name", c.name)
        put("address", c.address); put("mobile", c.mobile); put("nickname", c.nickname)
        put("notes", c.notes); put("openingBalancePaisa", c.openingBalancePaisa)
        put("currentBalancePaisa", c.currentBalancePaisa); put("totalPurchasesPaisa", c.totalPurchasesPaisa)
        put("totalCreditPaisa", c.totalCreditPaisa); put("totalPaymentsPaisa", c.totalPaymentsPaisa)
        put("halKhataPeriodId", c.halKhataPeriodId)
        c.oldestUnpaidTimestamp?.let { put("oldestUnpaidTimestamp", it) }
        c.lastTransactionDate?.let { put("lastTransactionDate", it) }
        c.lastPaymentDate?.let { put("lastPaymentDate", it) }
        put("createdAt", c.createdAt)
    }

    private fun jsonToCustomer(j: JSONObject) = CustomerEntity(
        id = j.getLong("id"), customerCode = j.getString("customerCode"), name = j.getString("name"),
        address = j.optString("address", ""), mobile = j.optString("mobile", ""), nickname = j.optString("nickname", ""),
        notes = j.optString("notes", ""), openingBalancePaisa = j.optLong("openingBalancePaisa", 0L),
        currentBalancePaisa = j.optLong("currentBalancePaisa", 0L), totalPurchasesPaisa = j.optLong("totalPurchasesPaisa", 0L),
        totalCreditPaisa = j.optLong("totalCreditPaisa", 0L), totalPaymentsPaisa = j.optLong("totalPaymentsPaisa", 0L),
        halKhataPeriodId = j.optLong("halKhataPeriodId", 1L),
        oldestUnpaidTimestamp = if (!j.isNull("oldestUnpaidTimestamp")) j.getLong("oldestUnpaidTimestamp") else null,
        lastTransactionDate = if (!j.isNull("lastTransactionDate")) j.getLong("lastTransactionDate") else null,
        lastPaymentDate = if (!j.isNull("lastPaymentDate")) j.getLong("lastPaymentDate") else null,
        createdAt = j.optLong("createdAt", System.currentTimeMillis())
    )

    private fun memoToJson(m: MemoEntity) = JSONObject().apply {
        put("id", m.id); put("memoNumber", m.memoNumber); put("requestToken", m.requestToken)
        m.customerId?.let { put("customerId", it) }
        put("customerNameSnapshot", m.customerNameSnapshot); put("customerAddressSnapshot", m.customerAddressSnapshot)
        put("saleType", m.saleType); put("subtotalPaisa", m.subtotalPaisa); put("discountPaisa", m.discountPaisa)
        put("grandTotalPaisa", m.grandTotalPaisa); put("paidPaisa", m.paidPaisa); put("outstandingPaisa", m.outstandingPaisa)
        put("notes", m.notes); put("halKhataPeriodId", m.halKhataPeriodId); put("isCancelled", m.isCancelled)
        put("needsReview", m.needsReview); put("createdAt", m.createdAt); put("updatedAt", m.updatedAt)
    }

    private fun jsonToMemo(j: JSONObject) = MemoEntity(
        id = j.getLong("id"), memoNumber = j.getString("memoNumber"), requestToken = j.getString("requestToken"),
        customerId = if (!j.isNull("customerId")) j.getLong("customerId") else null,
        customerNameSnapshot = j.getString("customerNameSnapshot"),
        customerAddressSnapshot = j.optString("customerAddressSnapshot", ""),
        saleType = j.getString("saleType"), subtotalPaisa = j.getLong("subtotalPaisa"),
        discountPaisa = j.optLong("discountPaisa", 0L), grandTotalPaisa = j.getLong("grandTotalPaisa"),
        paidPaisa = j.getLong("paidPaisa"), outstandingPaisa = j.getLong("outstandingPaisa"),
        notes = j.optString("notes", ""), halKhataPeriodId = j.optLong("halKhataPeriodId", 1L),
        isCancelled = j.optBoolean("isCancelled", false), needsReview = j.optBoolean("needsReview", false),
        createdAt = j.getLong("createdAt"), updatedAt = j.optLong("updatedAt", j.getLong("createdAt"))
    )

    private fun memoItemToJson(i: MemoItemEntity) = JSONObject().apply {
        put("id", i.id); put("memoId", i.memoId); put("productName", i.productName)
        put("quantityMillis", i.quantityMillis); put("unit", i.unit)
        put("unitPricePaisa", i.unitPricePaisa); put("lineTotalPaisa", i.lineTotalPaisa)
    }

    private fun jsonToMemoItem(j: JSONObject) = MemoItemEntity(
        id = j.getLong("id"), memoId = j.getLong("memoId"), productName = j.getString("productName"),
        quantityMillis = j.getLong("quantityMillis"), unit = j.getString("unit"),
        unitPricePaisa = j.getLong("unitPricePaisa"), lineTotalPaisa = j.getLong("lineTotalPaisa")
    )

    private fun paymentToJson(p: PaymentEntity) = JSONObject().apply {
        put("id", p.id); put("receiptNumber", p.receiptNumber); put("requestToken", p.requestToken)
        put("customerId", p.customerId); put("amountPaisa", p.amountPaisa)
        put("previousBalancePaisa", p.previousBalancePaisa); put("newBalancePaisa", p.newBalancePaisa)
        p.memoId?.let { put("memoId", it) }; put("notes", p.notes)
        put("halKhataPeriodId", p.halKhataPeriodId); put("isReversed", p.isReversed)
        p.reversedById?.let { put("reversedById", it) }; put("needsReview", p.needsReview); put("createdAt", p.createdAt)
    }

    private fun jsonToPayment(j: JSONObject) = PaymentEntity(
        id = j.getLong("id"), receiptNumber = j.getString("receiptNumber"), requestToken = j.getString("requestToken"),
        customerId = j.getLong("customerId"), amountPaisa = j.getLong("amountPaisa"),
        previousBalancePaisa = j.getLong("previousBalancePaisa"), newBalancePaisa = j.getLong("newBalancePaisa"),
        memoId = if (!j.isNull("memoId")) j.getLong("memoId") else null, notes = j.optString("notes", ""),
        halKhataPeriodId = j.optLong("halKhataPeriodId", 1L), isReversed = j.optBoolean("isReversed", false),
        reversedById = if (!j.isNull("reversedById")) j.getLong("reversedById") else null,
        needsReview = j.optBoolean("needsReview", false), createdAt = j.getLong("createdAt")
    )

    private fun creditToJson(c: CreditTransactionEntity) = JSONObject().apply {
        put("id", c.id); put("transactionCode", c.transactionCode); put("requestToken", c.requestToken)
        put("customerId", c.customerId); put("amountPaisa", c.amountPaisa)
        put("previousBalancePaisa", c.previousBalancePaisa); put("newBalancePaisa", c.newBalancePaisa)
        put("description", c.description); put("notes", c.notes); put("sourceType", c.sourceType)
        c.memoId?.let { put("memoId", it) }; put("halKhataPeriodId", c.halKhataPeriodId)
        put("isReversed", c.isReversed); put("needsReview", c.needsReview); put("createdAt", c.createdAt)
    }

    private fun jsonToCredit(j: JSONObject) = CreditTransactionEntity(
        id = j.getLong("id"), transactionCode = j.getString("transactionCode"), requestToken = j.getString("requestToken"),
        customerId = j.getLong("customerId"), amountPaisa = j.getLong("amountPaisa"),
        previousBalancePaisa = j.getLong("previousBalancePaisa"), newBalancePaisa = j.getLong("newBalancePaisa"),
        description = j.optString("description", ""), notes = j.optString("notes", ""),
        sourceType = j.optString("sourceType", CreditSourceType.QUICK_CREDIT),
        memoId = if (!j.isNull("memoId")) j.getLong("memoId") else null,
        halKhataPeriodId = j.optLong("halKhataPeriodId", 1L), isReversed = j.optBoolean("isReversed", false),
        needsReview = j.optBoolean("needsReview", false), createdAt = j.getLong("createdAt")
    )

    private fun expenseToJson(e: ExpenseEntity) = JSONObject().apply {
        put("id", e.id); put("expenseCode", e.expenseCode); put("requestToken", e.requestToken)
        put("category", e.category); put("amountPaisa", e.amountPaisa); put("description", e.description)
        put("notes", e.notes); put("paidFromCashbox", e.paidFromCashbox)
        put("halKhataPeriodId", e.halKhataPeriodId); put("isCancelled", e.isCancelled)
        put("needsReview", e.needsReview); put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
    }

    private fun jsonToExpense(j: JSONObject) = ExpenseEntity(
        id = j.getLong("id"), expenseCode = j.getString("expenseCode"), requestToken = j.getString("requestToken"),
        category = j.getString("category"), amountPaisa = j.getLong("amountPaisa"),
        description = j.optString("description", ""), notes = j.optString("notes", ""),
        paidFromCashbox = j.optBoolean("paidFromCashbox", true), halKhataPeriodId = j.optLong("halKhataPeriodId", 1L),
        isCancelled = j.optBoolean("isCancelled", false), needsReview = j.optBoolean("needsReview", false),
        createdAt = j.getLong("createdAt"), updatedAt = j.optLong("updatedAt", j.getLong("createdAt"))
    )

    private fun cashboxToJson(c: CashboxTransactionEntity) = JSONObject().apply {
        put("id", c.id); put("transactionCode", c.transactionCode); put("requestToken", c.requestToken)
        put("type", c.type); put("direction", c.direction); put("amountPaisa", c.amountPaisa)
        put("sourceType", c.sourceType); c.sourceId?.let { put("sourceId", it) }
        put("description", c.description); put("notes", c.notes)
        put("halKhataPeriodId", c.halKhataPeriodId); put("isReversed", c.isReversed); put("createdAt", c.createdAt)
    }

    private fun jsonToCashbox(j: JSONObject) = CashboxTransactionEntity(
        id = j.getLong("id"), transactionCode = j.getString("transactionCode"), requestToken = j.getString("requestToken"),
        type = j.getString("type"), direction = j.getString("direction"), amountPaisa = j.getLong("amountPaisa"),
        sourceType = j.getString("sourceType"), sourceId = if (!j.isNull("sourceId")) j.getLong("sourceId") else null,
        description = j.optString("description", ""), notes = j.optString("notes", ""),
        halKhataPeriodId = j.optLong("halKhataPeriodId", 1L), isReversed = j.optBoolean("isReversed", false),
        createdAt = j.getLong("createdAt")
    )

    private fun periodToJson(p: HalKhataPeriodEntity) = JSONObject().apply {
        put("id", p.id); put("name", p.name); put("description", p.description)
        put("startDate", p.startDate); p.endDate?.let { put("endDate", it) }; put("status", p.status)
        put("totalSalesPaisa", p.totalSalesPaisa); put("totalCreditPaisa", p.totalCreditPaisa)
        put("totalPaymentsPaisa", p.totalPaymentsPaisa); put("totalClosingDebtPaisa", p.totalClosingDebtPaisa)
        p.closedAt?.let { put("closedAt", it) }; put("archivePdfPath", p.archivePdfPath)
        put("archiveBackupPath", p.archiveBackupPath); put("backupVerified", p.backupVerified); put("createdAt", p.createdAt)
    }

    private fun jsonToPeriod(j: JSONObject) = HalKhataPeriodEntity(
        id = j.getLong("id"), name = j.getString("name"), description = j.optString("description", ""),
        startDate = j.getLong("startDate"), endDate = if (!j.isNull("endDate")) j.getLong("endDate") else null,
        status = j.optString("status", HalKhataStatus.ACTIVE),
        totalSalesPaisa = j.optLong("totalSalesPaisa", 0L), totalCreditPaisa = j.optLong("totalCreditPaisa", 0L),
        totalPaymentsPaisa = j.optLong("totalPaymentsPaisa", 0L), totalClosingDebtPaisa = j.optLong("totalClosingDebtPaisa", 0L),
        closedAt = if (!j.isNull("closedAt")) j.getLong("closedAt") else null,
        archivePdfPath = j.optString("archivePdfPath", ""), archiveBackupPath = j.optString("archiveBackupPath", ""),
        backupVerified = j.optBoolean("backupVerified", false), createdAt = j.optLong("createdAt", System.currentTimeMillis())
    )

    private fun balanceToJson(b: HalKhataCustomerBalanceEntity) = JSONObject().apply {
        put("id", b.id); put("periodId", b.periodId); put("customerId", b.customerId)
        put("customerCode", b.customerCode); put("customerName", b.customerName)
        put("openingBalancePaisa", b.openingBalancePaisa); put("totalPurchasesPaisa", b.totalPurchasesPaisa)
        put("totalPaymentsPaisa", b.totalPaymentsPaisa); put("closingBalancePaisa", b.closingBalancePaisa)
        put("carriedForward", b.carriedForward); put("recordedAt", b.recordedAt)
    }

    private fun jsonToBalance(j: JSONObject) = HalKhataCustomerBalanceEntity(
        id = j.getLong("id"), periodId = j.getLong("periodId"), customerId = j.getLong("customerId"),
        customerCode = j.getString("customerCode"), customerName = j.getString("customerName"),
        openingBalancePaisa = j.getLong("openingBalancePaisa"), totalPurchasesPaisa = j.getLong("totalPurchasesPaisa"),
        totalPaymentsPaisa = j.getLong("totalPaymentsPaisa"), closingBalancePaisa = j.getLong("closingBalancePaisa"),
        carriedForward = j.optBoolean("carriedForward", false), recordedAt = j.optLong("recordedAt")
    )

    private fun tempCustomerToJson(t: TemporaryCustomerEntity) = JSONObject().apply {
        put("id", t.id); put("tempCode", t.tempCode); put("name", t.name); put("address", t.address)
        put("mobile", t.mobile); put("notes", t.notes); put("totalCreditPaisa", t.totalCreditPaisa)
        put("totalPaidPaisa", t.totalPaidPaisa); put("outstandingPaisa", t.outstandingPaisa)
        t.convertedCustomerId?.let { put("convertedCustomerId", it) }
        put("isConverted", t.isConverted); put("createdAt", t.createdAt); put("updatedAt", t.updatedAt)
    }

    private fun jsonToTempCustomer(j: JSONObject) = TemporaryCustomerEntity(
        id = j.getLong("id"), tempCode = j.getString("tempCode"), name = j.getString("name"),
        address = j.optString("address", ""), mobile = j.optString("mobile", ""),
        notes = j.optString("notes", ""), totalCreditPaisa = j.optLong("totalCreditPaisa", 0L),
        totalPaidPaisa = j.optLong("totalPaidPaisa", 0L), outstandingPaisa = j.optLong("outstandingPaisa", 0L),
        convertedCustomerId = if (!j.isNull("convertedCustomerId")) j.getLong("convertedCustomerId") else null,
        isConverted = j.optBoolean("isConverted", false),
        createdAt = j.optLong("createdAt", System.currentTimeMillis()),
        updatedAt = j.optLong("updatedAt", System.currentTimeMillis())
    )

    private fun tempMemoToJson(m: TemporaryMemoEntity) = JSONObject().apply {
        put("id", m.id); put("tempCustomerId", m.tempCustomerId); put("memoNumber", m.memoNumber)
        put("type", m.type); put("description", m.description); put("itemsJson", m.itemsJson)
        put("totalAmountPaisa", m.totalAmountPaisa); put("paidPaisa", m.paidPaisa)
        put("creditPaisa", m.creditPaisa); put("notes", m.notes); put("createdAt", m.createdAt)
    }

    private fun jsonToTempMemo(j: JSONObject) = TemporaryMemoEntity(
        id = j.getLong("id"), tempCustomerId = j.getLong("tempCustomerId"), memoNumber = j.getString("memoNumber"),
        type = j.getString("type"), description = j.optString("description", ""), itemsJson = j.optString("itemsJson", ""),
        totalAmountPaisa = j.getLong("totalAmountPaisa"), paidPaisa = j.getLong("paidPaisa"),
        creditPaisa = j.getLong("creditPaisa"), notes = j.optString("notes", ""), createdAt = j.getLong("createdAt")
    )

    private fun auditToJson(a: AuditLogEntity) = JSONObject().apply {
        put("id", a.id); put("transactionRef", a.transactionRef); put("entityType", a.entityType)
        put("entityId", a.entityId); put("actionType", a.actionType)
        put("previousValueJson", a.previousValueJson); put("newValueJson", a.newValueJson)
        put("reason", a.reason)
        a.relatedCustomerId?.let { put("relatedCustomerId", it) }
        a.relatedMemoId?.let { put("relatedMemoId", it) }
        a.relatedPaymentId?.let { put("relatedPaymentId", it) }
        put("createdAt", a.createdAt)
    }

    private fun jsonToAudit(j: JSONObject) = AuditLogEntity(
        id = j.getLong("id"), transactionRef = j.getString("transactionRef"), entityType = j.getString("entityType"),
        entityId = j.getLong("entityId"), actionType = j.getString("actionType"),
        previousValueJson = j.optString("previousValueJson", ""), newValueJson = j.optString("newValueJson", ""),
        reason = j.optString("reason", ""),
        relatedCustomerId = if (!j.isNull("relatedCustomerId")) j.getLong("relatedCustomerId") else null,
        relatedMemoId = if (!j.isNull("relatedMemoId")) j.getLong("relatedMemoId") else null,
        relatedPaymentId = if (!j.isNull("relatedPaymentId")) j.getLong("relatedPaymentId") else null,
        createdAt = j.optLong("createdAt", System.currentTimeMillis())
    )

    // endregion

    companion object {
        private const val WORK_NAME = "bakir_khata_auto_backup"
        private const val COMPACT_CODE_PREFIX = "BK1:"

        fun scheduleAutoBackup(context: Context, frequency: String) {
            try {
                val wm = WorkManager.getInstance(context)
                when (frequency) {
                    "OFF" -> wm.cancelUniqueWork(WORK_NAME)
                    "WEEKLY" -> {
                        val req = PeriodicWorkRequestBuilder<AutoBackupWorker>(7, TimeUnit.DAYS).build()
                        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
                    }
                    else -> {
                        val req = PeriodicWorkRequestBuilder<AutoBackupWorker>(24, TimeUnit.HOURS).build()
                        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
                    }
                }
            } catch (_: IllegalStateException) {
                // WorkManager may not be initialized in unit test environments
            }
        }
    }
}

class AutoBackupWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = BakirKhataDatabase.getInstance(appContext)
        runCatching { AccountingRepository(db).purgeRecordsOlderThanTwoYears() }
        val manager = BackupManager(appContext, db)
        val res = manager.createZipBackup(backupType = "AUTO")
        if (res is OperationResult.Error) {
            showFailureNotification(res.messageBn)
            return Result.retry()
        }
        return Result.success()
    }

    private fun showFailureNotification(message: String) {
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channelId = "bakir_khata_backup_alerts"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "ব্যাকআপ সতর্কতা", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val notification = NotificationCompat.Builder(appContext, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("বাকির খাতা — স্বয়ংক্রিয় ব্যাকআপ ব্যর্থ")
                .setContentText(message)
                .setAutoCancel(true)
                .build()
            nm.notify(1001, notification)
        } catch (_: SecurityException) {
            // Notification permission not granted; dashboard alert still surfaces the failure clearly
        }
    }
}
