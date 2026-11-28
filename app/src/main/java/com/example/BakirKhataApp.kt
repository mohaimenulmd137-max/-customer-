package com.example

import android.app.Application
import com.example.data.local.BakirKhataDatabase
import com.example.data.repository.AccountingRepository
import com.example.util.BackupManager
import com.example.util.PdfGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BakirKhataApp : Application() {

    val database: BakirKhataDatabase by lazy { BakirKhataDatabase.getInstance(this) }
    val repository: AccountingRepository by lazy { AccountingRepository(database) }
    val pdfGenerator: PdfGenerator by lazy { PdfGenerator(this) }
    val backupManager: BackupManager by lazy { BackupManager(this, database) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            runCatching { repository.purgeRecordsOlderThanTwoYears() }
            val settings = database.appSettingsDao().getSettings()
            if (settings != null) {
                BackupManager.scheduleAutoBackup(this@BakirKhataApp, settings.backupFrequency)
            }
        }
    }
}
