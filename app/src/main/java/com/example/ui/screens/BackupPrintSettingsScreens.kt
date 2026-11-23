package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.local.*
import com.example.domain.*
import com.example.ui.components.BakirKhataTopBar
import com.example.ui.components.CustomerPickerDialog
import com.example.ui.components.PdfPreviewAndActionsDialog
import com.example.util.BackupManager
import com.example.util.CurrencyUtils
import com.example.util.PdfGenerator
import kotlinx.coroutines.launch
import java.io.File

// region Print & PDF Center Screen

@Composable
fun PrintPdfCenterScreen(
    customers: List<CustomerEntity>,
    memos: List<MemoWithItems>,
    payments: List<PaymentEntity>,
    expenses: List<ExpenseEntity>,
    cashboxTransactions: List<CashboxTransactionEntity>,
    cashboxSummary: CashboxSummary,
    tempCustomers: List<TemporaryCustomerEntity>,
    settings: AppSettingsEntity,
    pdfGenerator: PdfGenerator,
    buildDailyReport: (Long) -> DailyReportData,
    buildPeriodReport: (Long, Long) -> PeriodReportData,
    onGenerateSingleCustomerPdf: suspend (CustomerEntity) -> File,
    onGenerateCustomersZip: suspend (List<CustomerEntity>, String) -> File,
    onMessage: (String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var showSingleCustomerPicker by remember { mutableStateOf(false) }
    var showMultiCustomerDialog by remember { mutableStateOf(false) }
    var previewFile by remember { mutableStateOf<Pair<File, String>?>(null) }

    Scaffold(
        topBar = {
            BakirKhataTopBar(
                title = "প্রিন্ট ও পিডিএফ কেন্দ্র",
                subtitle = "প্রতিটি কাস্টমারের জন্য আলাদা ও সুরক্ষিত পিডিএফ",
                onBack = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("কাস্টমার লেজার ও তালিকা", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            item {
                PdfOptionCard(
                    title = "নির্দিষ্ট ১ জন কাস্টমারের লেজার পিডিএফ",
                    subtitle = "সম্পূর্ণ আইটেম, জমা, বাকি ও রানিং ব্যালেন্সসহ",
                    icon = Icons.Default.Person
                ) { showSingleCustomerPicker = true }
            }
            item {
                PdfOptionCard(
                    title = "বাছাইকৃত একাধিক কাস্টমারের আলাদা পিডিএফ (ZIP)",
                    subtitle = "প্রতিটি কাস্টমারের ফাইল আলাদা থাকবে, কোনো মিশ্রণ হবে না",
                    icon = Icons.Default.Checklist
                ) { showMultiCustomerDialog = true }
            }
            item {
                PdfOptionCard(
                    title = "সকল কাস্টমারের আলাদা লেজার পিডিএফ (ZIP আর্কাইভ)",
                    subtitle = "${CurrencyUtils.toBengaliDigits(customers.size.toString())} জন কাস্টমারের আলাদা আলাদা পিডিএফ",
                    icon = Icons.Default.FolderZip
                ) {
                    if (customers.isEmpty()) {
                        onMessage("কোনো কাস্টমার নেই")
                    } else {
                        scope.launch {
                            val zip = onGenerateCustomersZip(customers, "all_customers_ledgers.zip")
                            previewFile = zip to "সকল কাস্টমার লেজার (ZIP)"
                        }
                    }
                }
            }
            item {
                PdfOptionCard(
                    title = "শুধু বকেয়া কাস্টমারদের আলাদা পিডিএফ (ZIP)",
                    subtitle = "যাদের বকেয়া আছে তাদের প্রত্যেকের আলাদা লেজার ফাইল",
                    icon = Icons.Default.MoneyOff
                ) {
                    val debtors = customers.filter { it.currentBalancePaisa > 0L }
                    if (debtors.isEmpty()) {
                        onMessage("কোনো বকেয়া কাস্টমার নেই")
                    } else {
                        scope.launch {
                            val zip = onGenerateCustomersZip(debtors, "debt_customers_ledgers.zip")
                            previewFile = zip to "বকেয়া কাস্টমারদের লেজার (ZIP)"
                        }
                    }
                }
            }
            item {
                PdfOptionCard(
                    title = "কাস্টমার তালিকা পিডিএফ (সকল / বকেয়া)",
                    subtitle = "এক নজরে সকল কাস্টমার ও বকেয়ার তালিকা",
                    icon = Icons.Default.Groups
                ) {
                    val f = pdfGenerator.generateCustomerListPdf("কাস্টমার বকেয়া তালিকা", customers, settings)
                    previewFile = f to "কাস্টমার তালিকা"
                }
            }

            item {
                Spacer(Modifier.height(6.dp))
                Text("দোকানের হিসাব ও রিপোর্ট পিডিএফ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            item {
                PdfOptionCard(
                    title = "আজকের দৈনিক রিপোর্ট পিডিএফ",
                    subtitle = "আজকের ক্যাশ, বিক্রি, জমা ও খরচের সম্পূর্ণ বিবরণ",
                    icon = Icons.Default.Today
                ) {
                    val rep = buildDailyReport(System.currentTimeMillis())
                    previewFile = pdfGenerator.generateDailyReportPdf(rep, settings) to "দৈনিক রিপোর্ট"
                }
            }
            item {
                PdfOptionCard(
                    title = "চলতি মাসের রিপোর্ট পিডিএফ",
                    subtitle = "মাসিক বিক্রি, বাকি, জমা ও খরচের সারসংক্ষেপ",
                    icon = Icons.Default.Assessment
                ) {
                    val cal = java.util.Calendar.getInstance()
                    val rep = buildPeriodReport(
                        CurrencyUtils.startOfMonthMillis(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH)),
                        CurrencyUtils.endOfMonthMillis(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH))
                    )
                    previewFile = pdfGenerator.generatePeriodReportPdf("মাসিক হিসাব রিপোর্ট", rep, settings) to "মাসিক রিপোর্ট"
                }
            }
            item {
                PdfOptionCard(
                    title = "ক্যাশবক্স রিপোর্ট পিডিএফ",
                    subtitle = "নগদ আগমন, নির্গমন ও প্রত্যাশিত ব্যালেন্স",
                    icon = Icons.Default.AccountBalanceWallet
                ) {
                    previewFile = pdfGenerator.generateCashboxReportPdf(cashboxSummary, cashboxTransactions, settings) to "ক্যাশবক্স রিপোর্ট"
                }
            }
            item {
                PdfOptionCard(
                    title = "খরচের হিসাব পিডিএফ",
                    subtitle = "দোকানের সকল খরচের খাতভিত্তিক তালিকা",
                    icon = Icons.Default.Receipt
                ) {
                    previewFile = pdfGenerator.generateExpenseReportPdf(expenses, settings) to "খরচের রিপোর্ট"
                }
            }
            item {
                PdfOptionCard(
                    title = "অস্থায়ী কাস্টমার খাতা পিডিএফ",
                    subtitle = "অস্থায়ী কাস্টমারদের বকেয়া ও অবস্থা",
                    icon = Icons.Default.Badge
                ) {
                    previewFile = pdfGenerator.generateTempCustomerReportPdf(tempCustomers, settings) to "অস্থায়ী খাতা রিপোর্ট"
                }
            }
            item {
                PdfOptionCard(
                    title = "সম্পূর্ণ দোকানের লেনদেন ইতিহাস পিডিএফ",
                    subtitle = "মেমো, জমা ও সার্বিক দোকানের সম্পূর্ণ ইতিহাস",
                    icon = Icons.Default.HistoryEdu
                ) {
                    previewFile = pdfGenerator.generateFullShopHistoryPdf(customers, memos, payments, expenses, settings) to "সম্পূর্ণ দোকান ইতিহাস"
                }
            }
        }
    }

    if (showSingleCustomerPicker) {
        CustomerPickerDialog(
            customers = customers,
            currencySymbol = settings.currencySymbol,
            title = "পিডিএফের জন্য কাস্টমার নির্বাচন",
            onDismiss = { showSingleCustomerPicker = false },
            onSelect = { c ->
                showSingleCustomerPicker = false
                scope.launch {
                    val file = onGenerateSingleCustomerPdf(c)
                    previewFile = file to "লেজার: ${c.name}"
                }
            },
            onRequestCreateCustomer = { showSingleCustomerPicker = false }
        )
    }

    if (showMultiCustomerDialog) {
        var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
        AlertDialog(
            onDismissRequest = { showMultiCustomerDialog = false },
            title = { Text("একাধিক কাস্টমার নির্বাচন করুন") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(customers, key = { it.id }) { c ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedIds = if (c.id in selectedIds) selectedIds - c.id else selectedIds + c.id
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = c.id in selectedIds,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked) selectedIds + c.id else selectedIds - c.id
                                }
                            )
                            Column {
                                Text(c.name, fontWeight = FontWeight.Bold)
                                Text("${c.customerCode} • ${CurrencyUtils.formatBdt(c.currentBalancePaisa, settings.currencySymbol)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val chosen = customers.filter { it.id in selectedIds }
                        if (chosen.isEmpty()) {
                            onMessage("অন্তত ১ জন কাস্টমার নির্বাচন করুন")
                        } else {
                            showMultiCustomerDialog = false
                            scope.launch {
                                val zip = onGenerateCustomersZip(chosen, "selected_customers_ledgers.zip")
                                previewFile = zip to "নির্বাচিত কাস্টমার লেজার (ZIP)"
                            }
                        }
                    }
                ) { Text("ZIP তৈরি করুন") }
            },
            dismissButton = { TextButton(onClick = { showMultiCustomerDialog = false }) { Text("বাতিল") } }
        )
    }

    previewFile?.let { (file, title) ->
        val mime = if (file.name.endsWith(".zip", true)) "application/zip" else "application/pdf"
        PdfPreviewAndActionsDialog(
            file = file,
            title = title,
            pdfGenerator = pdfGenerator,
            mimeType = mime,
            onMessage = onMessage,
            onDismiss = { previewFile = null }
        )
    }
}

@Composable
private fun PdfOptionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

// endregion

// region Backup & Restore Screen

@Composable
fun BackupRestoreScreen(
    backupHistory: List<BackupMetadataEntity>,
    settings: AppSettingsEntity,
    isSubmitting: Boolean,
    backupManager: BackupManager,
    pdfGenerator: PdfGenerator,
    onCreateBackupNow: (type: String, onDone: (BackupMetadataEntity?) -> Unit) -> Unit,
    onSetBackupFolderUri: (String) -> Unit,
    onSetBackupFrequency: (String) -> Unit,
    onPreviewBackupUri: (Uri, String, (OperationResult<BackupPreviewInfo>) -> Unit) -> Unit,
    onRestoreFromUri: (Uri, String, (Boolean) -> Unit) -> Unit,
    onMessage: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var latestCreatedBackup by remember { mutableStateOf<BackupMetadataEntity?>(null) }
    var pendingRestore by remember { mutableStateOf<Pair<Uri, BackupPreviewInfo>?>(null) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { }
            onSetBackupFolderUri(uri.toString())
        }
    }

    val restoreFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val fileName = uri.lastPathSegment ?: "backup.zip"
            onPreviewBackupUri(uri, fileName) { res ->
                when (res) {
                    is OperationResult.Success -> pendingRestore = uri to res.data
                    is OperationResult.Error -> onMessage(res.messageBn)
                }
            }
        }
    }

    val saveFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { destUri ->
        val src = latestCreatedBackup?.let { File(it.filePath) }
        if (destUri != null && src != null && src.exists()) {
            val ok = backupManager.copyFileToUri(src, destUri)
            onMessage(if (ok) "নির্বাচিত স্থানে ব্যাকআপ সংরক্ষিত হয়েছে" else "ফাইল সংরক্ষণ ব্যর্থ হয়েছে")
        }
    }

    Scaffold(
        topBar = {
            BakirKhataTopBar(
                title = "ব্যাকআপ ও রিস্টোর",
                subtitle = "আপনার দোকানের বছরের পর বছরের হিসাব সুরক্ষা",
                onBack = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Manual Backup Section
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("তাৎক্ষণিক ব্যাকআপ (Backup Now)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        val lastSuccess = backupHistory.firstOrNull { it.status == "SUCCESS" }
                        Text(
                            text = lastSuccess?.let { "সর্বশেষ সফল ব্যাকআপ: ${CurrencyUtils.formatDateTimeBn(it.createdAt)} (${it.fileName})" }
                                ?: "এখনো কোনো সফল ব্যাকআপ নেওয়া হয়নি",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    onCreateBackupNow("ZIP") { meta -> latestCreatedBackup = meta }
                                },
                                enabled = !isSubmitting,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("backup_now_zip_button")
                            ) {
                                Text("ZIP ব্যাকআপ")
                            }
                            OutlinedButton(
                                onClick = {
                                    onCreateBackupNow("JSON") { meta -> latestCreatedBackup = meta }
                                },
                                enabled = !isSubmitting,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            ) {
                                Text("JSON")
                            }
                            OutlinedButton(
                                onClick = {
                                    onCreateBackupNow("DB") { meta -> latestCreatedBackup = meta }
                                },
                                enabled = !isSubmitting,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            ) {
                                Text(".db ফাইল")
                            }
                        }

                        latestCreatedBackup?.let { meta ->
                            if (meta.status == "SUCCESS") {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { saveFilePickerLauncher.launch(meta.fileName) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("ফোল্ডারে সেভ")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            pdfGenerator.shareFile(context, File(meta.filePath), "application/octet-stream", meta.fileName)
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("শেয়ার করুন")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Automatic Backup & Folder Section
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("স্বয়ংক্রিয় ব্যাকআপ ও সংরক্ষণ স্থান", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("DAILY" to "দৈনিক", "WEEKLY" to "সাপ্তাহিক", "OFF" to "বন্ধ").forEach { (code, label) ->
                                FilterChip(
                                    selected = settings.backupFrequency == code,
                                    onClick = { onSetBackupFrequency(code) },
                                    label = { Text(label) }
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = { folderPickerLauncher.launch(null) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (settings.backupFolderUri.isBlank()) "ফোনের ব্যাকআপ ফোল্ডার নির্বাচন করুন" else "ব্যাকআপ ফোল্ডার পরিবর্তন করুন")
                        }
                    }
                }
            }

            // Restore Section
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("ব্যাকআপ থেকে পুনরুদ্ধার (Restore Backup)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "নতুন ফোনে বা রিসেটের পর পূর্বের ZIP বা JSON ব্যাকআপ ফাইল নির্বাচন করে সম্পূর্ণ হিসাব পুনরুদ্ধার করুন। রিস্টোরের পূর্বে ব্যাকআপ যাচাই ও প্রিভিউ দেখানো হবে।",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                restoreFilePickerLauncher.launch(arrayOf("application/zip", "application/json", "*/*"))
                            },
                            enabled = !isSubmitting,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("restore_backup_button")
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("ব্যাকআপ ফাইল নির্বাচন করে রিস্টোর করুন")
                        }
                    }
                }
            }

            // Backup History
            item {
                Text("ব্যাকআপের ইতিহাস", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            items(backupHistory, key = { it.id }) { b ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.fileName, fontWeight = FontWeight.Bold)
                            Text("${b.backupType} • ${CurrencyUtils.formatDateTimeBn(b.createdAt)}", style = MaterialTheme.typography.labelSmall)
                            if (b.errorMessage.isNotBlank()) {
                                Text(b.errorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (b.status == "SUCCESS") "সফল" else "ব্যর্থ",
                                color = if (b.status == "SUCCESS") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                            if (b.status == "SUCCESS" && b.filePath.isNotBlank() && File(b.filePath).exists()) {
                                IconButton(onClick = {
                                    pdfGenerator.shareFile(context, File(b.filePath), "application/octet-stream", b.fileName)
                                }) {
                                    Icon(Icons.Default.Share, contentDescription = "শেয়ার")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingRestore?.let { (uri, preview) ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("ব্যাকআপ প্রিভিউ ও নিশ্চিতকরণ") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("দোকানের নাম: ${preview.shopName}", fontWeight = FontWeight.Bold)
                    Text("ব্যাকআপ তারিখ: ${CurrencyUtils.formatDateTimeBn(preview.createdAt)}")
                    Text("ফরম্যাট: ${preview.formatLabel}")
                    Text("কাস্টমার: ${CurrencyUtils.toBengaliDigits(preview.customerCount.toString())} জন")
                    Text("মেমো: ${CurrencyUtils.toBengaliDigits(preview.memoCount.toString())} টি")
                    Text("জমা: ${CurrencyUtils.toBengaliDigits(preview.paymentCount.toString())} টি")
                    Text("মোট বকেয়া: ${CurrencyUtils.formatBdt(preview.totalOutstandingDebtPaisa, settings.currencySymbol)}")
                    Text(
                        text = if (preview.checksumValid) "ফাইল চেকসাম: যাচাইকৃত (অক্ষত)" else "ফাইল চেকসাম: ত্রুটিপূর্ণ!",
                        color = if (preview.checksumValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "সতর্কতা: রিস্টোর নিশ্চিত করলে বর্তমান ডাটাবেসের পরিবর্তে এই ব্যাকআপের সমস্ত তথ্য বসানো হবে।",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRestoreFromUri(uri, preview.fileName) {
                            pendingRestore = null
                        }
                    },
                    enabled = preview.checksumValid && !isSubmitting
                ) { Text("রিস্টোর নিশ্চিত করুন") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("বাতিল") } }
        )
    }
}

// endregion

// region Settings Screen

@Composable
fun SettingsScreen(
    settings: AppSettingsEntity,
    customUnits: List<String>,
    customCategories: List<String>,
    onUpdateSettings: ((AppSettingsEntity) -> AppSettingsEntity) -> Unit,
    onSetPin: (String) -> Unit,
    onClearPin: () -> Unit,
    onAddCustomUnit: (String) -> Unit,
    onAddCustomCategory: (String) -> Unit,
    onVerifyIntegrity: ((IntegrityCheckResult) -> Unit) -> Unit,
    onNavigateHalKhata: () -> Unit,
    onNavigateBackup: () -> Unit,
    onBack: () -> Unit
) {
    var shopName by remember(settings.shopName) { mutableStateOf(settings.shopName) }
    var ownerName by remember(settings.ownerName) { mutableStateOf(settings.ownerName) }
    var shopAddress by remember(settings.shopAddress) { mutableStateOf(settings.shopAddress) }
    var shopPhone by remember(settings.shopPhone) { mutableStateOf(settings.shopPhone) }
    var memoPrefix by remember(settings.memoPrefix) { mutableStateOf(settings.memoPrefix) }
    var pdfFooter by remember(settings.pdfFooterNote) { mutableStateOf(settings.pdfFooterNote) }
    var newUnit by remember { mutableStateOf("") }
    var newCategory by remember { mutableStateOf("") }
    var showPinDialog by remember { mutableStateOf(false) }
    var integrityMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            BakirKhataTopBar(
                title = "সেটিংস",
                subtitle = settings.shopName,
                onBack = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("দোকানের তথ্য ও পিডিএফ সেটিংস", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        OutlinedTextField(value = shopName, onValueChange = { shopName = it }, label = { Text("দোকানের নাম") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = ownerName, onValueChange = { ownerName = it }, label = { Text("মালিকের নাম") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = shopAddress, onValueChange = { shopAddress = it }, label = { Text("দোকানের ঠিকানা") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = shopPhone, onValueChange = { shopPhone = it }, label = { Text("মোবাইল নম্বর") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = memoPrefix, onValueChange = { memoPrefix = it }, label = { Text("মেমো প্রিফিক্স (যেমন: MEMO)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = pdfFooter, onValueChange = { pdfFooter = it }, label = { Text("পিডিএফ ফুটার বার্তা") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(
                            onClick = {
                                onUpdateSettings {
                                    it.copy(
                                        shopName = shopName.trim().ifEmpty { "আমার দোকান" },
                                        ownerName = ownerName.trim(),
                                        shopAddress = shopAddress.trim(),
                                        shopPhone = shopPhone.trim(),
                                        memoPrefix = memoPrefix.trim().ifEmpty { "MEMO" },
                                        pdfFooterNote = pdfFooter.trim()
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) { Text("দোকানের তথ্য সংরক্ষণ করুন") }
                    }
                }
            }

            // Theme selector
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("থিম ও প্রদর্শন (বাংলা ভাষা)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("SYSTEM" to "সিস্টেম", "LIGHT" to "লাইট মোড", "DARK" to "ডার্ক মোড").forEach { (mode, label) ->
                                FilterChip(
                                    selected = settings.themeMode == mode,
                                    onClick = { onUpdateSettings { it.copy(themeMode = mode) } },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }
            }

            // Security (PIN & Biometric & Auto-Lock)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("অ্যাপ নিরাপত্তা ও গোপনীয়তা", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (settings.pinHash.isNotBlank()) "পিন লক চালু আছে" else "পিন লক বন্ধ আছে")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { showPinDialog = true }) {
                                    Text(if (settings.pinHash.isNotBlank()) "পিন বদলান" else "পিন সেট করুন")
                                }
                                if (settings.pinHash.isNotBlank()) {
                                    TextButton(onClick = onClearPin) { Text("বন্ধ") }
                                }
                            }
                        }
                        if (settings.pinHash.isNotBlank()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("ফিঙ্গারপ্রিন্ট আনলক")
                                Switch(
                                    checked = settings.biometricEnabled,
                                    onCheckedChange = { enabled -> onUpdateSettings { it.copy(biometricEnabled = enabled) } }
                                )
                            }
                            Text("অটো-লক সময় (মিনিট): ${CurrencyUtils.toBengaliDigits(settings.autoLockMinutes.toString())}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(0, 1, 5, 15).forEach { mins ->
                                    FilterChip(
                                        selected = settings.autoLockMinutes == mins,
                                        onClick = { onUpdateSettings { it.copy(autoLockMinutes = mins) } },
                                        label = { Text(if (mins == 0) "তাৎক্ষণিক" else "${CurrencyUtils.toBengaliDigits(mins.toString())} মি.") }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Custom Units & Categories
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("কাস্টম একক ও খরচের খাত", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = newUnit, onValueChange = { newUnit = it }, label = { Text("নতুন একক") }, singleLine = true, modifier = Modifier.weight(1f))
                            Button(onClick = { onAddCustomUnit(newUnit); newUnit = "" }, modifier = Modifier.align(Alignment.CenterVertically)) {
                                Text("যোগ")
                            }
                        }
                        if (customUnits.isNotEmpty()) {
                            Text("যুক্ত একক: ${customUnits.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = newCategory, onValueChange = { newCategory = it }, label = { Text("নতুন খরচের খাত") }, singleLine = true, modifier = Modifier.weight(1f))
                            Button(onClick = { onAddCustomCategory(newCategory); newCategory = "" }, modifier = Modifier.align(Alignment.CenterVertically)) {
                                Text("যোগ")
                            }
                        }
                        if (customCategories.isNotEmpty()) {
                            Text("যুক্ত খাত: ${customCategories.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // Hal Khata, Backup, & DB Integrity Check
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("ডাটাবেস সুরক্ষা ও হালখাতা", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onNavigateHalKhata, modifier = Modifier.weight(1f)) {
                                Text("হালখাতা তারিখ")
                            }
                            OutlinedButton(onClick = onNavigateBackup, modifier = Modifier.weight(1f)) {
                                Text("ব্যাকআপ ও রিস্টোর")
                            }
                        }
                        Button(
                            onClick = { onVerifyIntegrity { res -> integrityMessage = res.message } },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("ডাটাবেস সুস্থতা যাচাই করুন (Integrity Check)")
                        }
                        integrityMessage?.let { msg ->
                            Text(msg, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    if (showPinDialog) {
        var pinInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text("নতুন পিন সেট করুন (৪–৮ সংখ্যা)") },
            text = {
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { if (it.length <= 8) pinInput = it },
                    label = { Text("নতুন পিন") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    onSetPin(pinInput)
                    showPinDialog = false
                }) { Text("সংরক্ষণ") }
            },
            dismissButton = { TextButton(onClick = { showPinDialog = false }) { Text("বাতিল") } }
        )
    }
}

// endregion
