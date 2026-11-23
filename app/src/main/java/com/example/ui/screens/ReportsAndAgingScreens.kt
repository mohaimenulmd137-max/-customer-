package com.example.ui.screens

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.*
import com.example.domain.*
import com.example.ui.components.BakirKhataTopBar
import com.example.ui.components.EmptyLedgerState
import com.example.ui.components.PdfPreviewAndActionsDialog
import com.example.util.CurrencyUtils
import com.example.util.PdfGenerator
import java.io.File
import java.util.Calendar

@Composable
fun DailyReportScreen(
    settings: AppSettingsEntity,
    pdfGenerator: PdfGenerator,
    buildDailyReport: (Long) -> DailyReportData,
    onMessage: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val cur = settings.currencySymbol
    var selectedDateMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    val report = remember(selectedDateMillis, buildDailyReport) { buildDailyReport(selectedDateMillis) }
    var previewPdfFile by remember { mutableStateOf<File?>(null) }

    fun openDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDateMillis }
        DatePickerDialog(
            context,
            { _, y, m, d ->
                cal.set(y, m, d, 12, 0, 0)
                selectedDateMillis = cal.timeInMillis
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    Scaffold(
        topBar = {
            BakirKhataTopBar(
                title = "দৈনিক রিপোর্ট",
                subtitle = "${CurrencyUtils.formatDateBn(selectedDateMillis)} (${CurrencyUtils.weekdayBn(selectedDateMillis)})",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { openDatePicker() }) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = "তারিখ নির্বাচন")
                    }
                    IconButton(onClick = {
                        previewPdfFile = pdfGenerator.generateDailyReportPdf(report, settings)
                    }) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = "পিডিএফ")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { selectedDateMillis = System.currentTimeMillis() }, modifier = Modifier.weight(1f)) {
                        Text("আজ")
                    }
                    OutlinedButton(onClick = { selectedDateMillis = System.currentTimeMillis() - 86400000L }, modifier = Modifier.weight(1f)) {
                        Text("গতকাল")
                    }
                    Button(onClick = { openDatePicker() }, modifier = Modifier.weight(1f)) {
                        Text("তারিখ বদলান")
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("ক্যাশ ও বিক্রয় সারসংক্ষেপ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        ReportRow("প্রারম্ভিক ক্যাশ", CurrencyUtils.formatBdt(report.openingCashPaisa, cur))
                        ReportRow("নগদ বিক্রি", CurrencyUtils.formatBdt(report.cashSalesPaisa, cur))
                        ReportRow("বাকি বিক্রি", CurrencyUtils.formatBdt(report.creditSalesPaisa, cur))
                        ReportRow("কাস্টমার জমা প্রাপ্তি", CurrencyUtils.formatBdt(report.customerPaymentsPaisa, cur))
                        ReportRow("অন্যান্য নগদ আয়", CurrencyUtils.formatBdt(report.otherIncomePaisa, cur))
                        ReportRow("মোট নগদ আগমন", CurrencyUtils.formatBdt(report.totalInflowPaisa, cur), bold = true)
                        HorizontalDivider()
                        ReportRow("দোকান খরচ", CurrencyUtils.formatBdt(report.expensesPaisa, cur))
                        ReportRow("সরবরাহকারী পরিশোধ", CurrencyUtils.formatBdt(report.supplierPaymentsPaisa, cur))
                        ReportRow("অন্যান্য নির্গমন", CurrencyUtils.formatBdt(report.otherOutflowsPaisa, cur))
                        ReportRow("মোট নগদ নির্গমন", CurrencyUtils.formatBdt(report.totalOutflowPaisa, cur), bold = true)
                        HorizontalDivider()
                        ReportRow("প্রত্যাশিত সমাপনী ক্যাশ", CurrencyUtils.formatBdt(report.expectedClosingCashPaisa, cur), bold = true)
                        report.actualClosingCashPaisa?.let {
                            ReportRow("হাতে গোনা সমাপনী ক্যাশ", CurrencyUtils.formatBdt(it, cur))
                            ReportRow("ক্যাশ পার্থক্য", CurrencyUtils.formatBdt(report.cashDifferencePaisa ?: 0L, cur), bold = true)
                        }
                        HorizontalDivider()
                        ReportRow("আজকের নতুন বাকি", CurrencyUtils.formatBdt(report.newDebtsPaisa, cur))
                        ReportRow("মোট কাস্টমার বকেয়া", CurrencyUtils.formatBdt(report.totalOutstandingDebtPaisa, cur), bold = true)
                        ReportRow("মোট লেনদেন সংখ্যা", "${CurrencyUtils.toBengaliDigits(report.transactionCount.toString())} টি")
                    }
                }
            }
        }
    }

    previewPdfFile?.let { file ->
        PdfPreviewAndActionsDialog(
            file = file,
            title = "দৈনিক রিপোর্ট",
            pdfGenerator = pdfGenerator,
            onMessage = onMessage,
            onDismiss = { previewPdfFile = null }
        )
    }
}

@Composable
fun MonthlyReportScreen(
    settings: AppSettingsEntity,
    pdfGenerator: PdfGenerator,
    buildPeriodReport: (Long, Long) -> PeriodReportData,
    onSelectCustomer: (Long) -> Unit,
    onMessage: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val cur = settings.currencySymbol
    val nowCal = remember { Calendar.getInstance() }
    var startMillis by remember {
        mutableStateOf(CurrencyUtils.startOfMonthMillis(nowCal.get(Calendar.YEAR), nowCal.get(Calendar.MONTH)))
    }
    var endMillis by remember {
        mutableStateOf(CurrencyUtils.endOfMonthMillis(nowCal.get(Calendar.YEAR), nowCal.get(Calendar.MONTH)))
    }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("সারসংক্ষেপ", "বিক্রয়", "জমা ও বাকি", "খরচ", "কাস্টমার ভিত্তিক")
    val report = remember(startMillis, endMillis, buildPeriodReport) { buildPeriodReport(startMillis, endMillis) }
    var previewPdfFile by remember { mutableStateOf<File?>(null) }

    fun pickDate(isStart: Boolean) {
        val cal = Calendar.getInstance().apply { timeInMillis = if (isStart) startMillis else endMillis }
        DatePickerDialog(
            context,
            { _, y, m, d ->
                cal.set(y, m, d)
                if (isStart) startMillis = CurrencyUtils.startOfDayMillis(cal.timeInMillis)
                else endMillis = CurrencyUtils.endOfDayMillis(cal.timeInMillis)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    Scaffold(
        topBar = {
            BakirKhataTopBar(
                title = "মাসিক ও কাস্টম রিপোর্ট",
                subtitle = "${CurrencyUtils.formatDateBn(startMillis)} – ${CurrencyUtils.formatDateBn(endMillis)}",
                onBack = onBack,
                actions = {
                    IconButton(onClick = {
                        previewPdfFile = pdfGenerator.generatePeriodReportPdf("মাসিক ও কাস্টম রিপোর্ট", report, settings)
                    }) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = "পিডিএফ")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate(true) }, modifier = Modifier.weight(1f)) {
                    Text("শুরু: ${CurrencyUtils.formatDateBn(startMillis)}")
                }
                OutlinedButton(onClick = { pickDate(false) }, modifier = Modifier.weight(1f)) {
                    Text("শেষ: ${CurrencyUtils.formatDateBn(endMillis)}")
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tabs.forEachIndexed { idx, label ->
                    FilterChip(selected = selectedTab == idx, onClick = { selectedTab = idx }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (selectedTab) {
                    0 -> {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ReportRow("মোট বিক্রি", CurrencyUtils.formatBdt(report.totalSalesPaisa, cur), bold = true)
                                    ReportRow("নগদ বিক্রি", CurrencyUtils.formatBdt(report.cashSalesPaisa, cur))
                                    ReportRow("বাকি বিক্রি", CurrencyUtils.formatBdt(report.creditSalesPaisa, cur))
                                    ReportRow("মোট কাস্টমার জমা", CurrencyUtils.formatBdt(report.totalCustomerPaymentsPaisa, cur))
                                    ReportRow("নতুন বাকি (কুইক+মেমো)", CurrencyUtils.formatBdt(report.totalNewCreditsPaisa, cur))
                                    ReportRow("মোট খরচ", CurrencyUtils.formatBdt(report.totalExpensesPaisa, cur))
                                    ReportRow("মোট ক্যাশ আগমন", CurrencyUtils.formatBdt(report.cashInflowPaisa, cur))
                                    ReportRow("মোট ক্যাশ নির্গমন", CurrencyUtils.formatBdt(report.cashOutflowPaisa, cur))
                                    HorizontalDivider()
                                    ReportRow("বর্তমান মোট পাওনা", CurrencyUtils.formatBdt(report.totalOutstandingDebtPaisa, cur), bold = true)
                                    ReportRow("মোট লেনদেন", "${CurrencyUtils.toBengaliDigits(report.transactionCount.toString())} টি")
                                }
                            }
                        }
                    }
                    1 -> {
                        items(report.memos, key = { it.memo.id }) { mwi ->
                            val m = mwi.memo
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                            ) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${m.memoNumber} • ${m.customerNameSnapshot}", fontWeight = FontWeight.Bold)
                                        Text(CurrencyUtils.formatDateTimeBn(m.createdAt), style = MaterialTheme.typography.labelSmall)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(CurrencyUtils.formatBdt(m.grandTotalPaisa, cur), fontWeight = FontWeight.Bold)
                                        Text("জমা: ${CurrencyUtils.formatBdt(m.paidPaisa, cur)}", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                    2 -> {
                        items(report.payments, key = { "p_${it.id}" }) { p ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                            ) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text("জমা: ${p.receiptNumber}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                        Text(CurrencyUtils.formatDateTimeBn(p.createdAt), style = MaterialTheme.typography.labelSmall)
                                    }
                                    Text(CurrencyUtils.formatBdt(p.amountPaisa, cur), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        items(report.credits, key = { "c_${it.id}" }) { c ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                            ) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text("বাকি: ${c.transactionCode} (${c.description})", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                        Text(CurrencyUtils.formatDateTimeBn(c.createdAt), style = MaterialTheme.typography.labelSmall)
                                    }
                                    Text(CurrencyUtils.formatBdt(c.amountPaisa, cur), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    3 -> {
                        items(report.expenses, key = { it.id }) { e ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                            ) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text("${e.category} — ${e.description}", fontWeight = FontWeight.Bold)
                                        Text(CurrencyUtils.formatDateTimeBn(e.createdAt), style = MaterialTheme.typography.labelSmall)
                                    }
                                    Text(CurrencyUtils.formatBdt(e.amountPaisa, cur), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    4 -> {
                        items(report.customerRows, key = { it.customer.id }) { row ->
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { onSelectCustomer(row.customer.id) },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                            ) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${row.customer.name} (${row.customer.customerCode})", fontWeight = FontWeight.Bold)
                                        Text(
                                            "বিক্রি: ${CurrencyUtils.formatBdt(row.periodSalesPaisa, cur)} • জমা: ${CurrencyUtils.formatBdt(row.periodPaymentsPaisa, cur)}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Text(
                                        CurrencyUtils.formatBdt(row.customer.currentBalancePaisa, cur),
                                        fontWeight = FontWeight.Bold,
                                        color = if (row.customer.currentBalancePaisa > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    previewPdfFile?.let { file ->
        PdfPreviewAndActionsDialog(
            file = file,
            title = "মাসিক ও কাস্টম রিপোর্ট",
            pdfGenerator = pdfGenerator,
            onMessage = onMessage,
            onDismiss = { previewPdfFile = null }
        )
    }
}

@Composable
fun DebtAgingScreen(
    rows: List<DebtAgingRow>,
    settings: AppSettingsEntity,
    onSelectCustomer: (Long) -> Unit,
    onBack: () -> Unit
) {
    val cur = settings.currencySymbol
    var selectedBucket by remember { mutableStateOf<DebtAgeBucket?>(null) }
    var sortOption by remember { mutableStateOf(DebtSortOption.HIGHEST_AMOUNT) }

    val filtered = remember(rows, selectedBucket, sortOption) {
        val base = if (selectedBucket == null) rows else rows.filter { it.bucket == selectedBucket }
        when (sortOption) {
            DebtSortOption.HIGHEST_AMOUNT -> base.sortedByDescending { it.outstandingPaisa }
            DebtSortOption.OLDEST_AGE -> base.sortedByDescending { it.debtAgeDays }
            DebtSortOption.CUSTOMER_NAME -> base.sortedBy { it.customer.name }
        }
    }

    Scaffold(
        topBar = {
            BakirKhataTopBar(
                title = "বকেয়ার বয়স বিশ্লেষণ",
                subtitle = "বকেয়া কাস্টমার: ${CurrencyUtils.toBengaliDigits(rows.size.toString())} জন",
                onBack = onBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(selected = selectedBucket == null, onClick = { selectedBucket = null }, label = { Text("সব বয়স") })
                DebtAgeBucket.entries.forEach { bucket ->
                    val count = rows.count { it.bucket == bucket }
                    FilterChip(
                        selected = selectedBucket == bucket,
                        onClick = { selectedBucket = bucket },
                        label = { Text("${bucket.labelBn} (${CurrencyUtils.toBengaliDigits(count.toString())})") }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DebtSortOption.entries.forEach { s ->
                    AssistChip(
                        onClick = { sortOption = s },
                        label = { Text(s.labelBn) },
                        leadingIcon = if (sortOption == s) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            if (filtered.isEmpty()) {
                EmptyLedgerState(
                    title = "এই শ্রেণিতে কোনো বকেয়া নেই",
                    subtitle = "সকল কাস্টমারের বকেয়া পরিশোধিত অথবা অন্য সময়কাল নির্বাচন করুন।",
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.customer.id }) { row ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { onSelectCustomer(row.customer.id) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${row.customer.name} (${row.customer.customerCode})", fontWeight = FontWeight.Bold)
                                    Text(
                                        CurrencyUtils.formatBdt(row.outstandingPaisa, cur),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                Text(
                                    "বকেয়ার বয়স: ${CurrencyUtils.toBengaliDigits(row.debtAgeDays.toString())} দিন (${row.bucket.labelBn}) • পুরোনো বাকি তারিখ: ${CurrencyUtils.formatDateBn(row.oldestUnpaidDate)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "সর্বশেষ জমা: ${row.lastPaymentDate?.let { CurrencyUtils.formatDateBn(it) } ?: "নেই"} • সর্বশেষ ক্রয়: ${row.lastPurchaseDate?.let { CurrencyUtils.formatDateBn(it) } ?: "নেই"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RemindersScreen(
    rows: List<DebtAgingRow>,
    summary: DashboardSummary,
    settings: AppSettingsEntity,
    onSelectCustomer: (Long) -> Unit,
    onNavigateBackup: () -> Unit,
    onNavigateDailyReport: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val cur = settings.currencySymbol
    val overdueRows = remember(rows, settings.overdueDaysThreshold) {
        rows.filter { it.debtAgeDays >= settings.overdueDaysThreshold }
            .sortedByDescending { it.debtAgeDays }
    }

    fun sendManualSms(customer: CustomerEntity) {
        val text = "আসসালামু আলাইকুম ${customer.name}, ${settings.shopName}-এ আপনার বকেয়া ${CurrencyUtils.formatBdt(customer.currentBalancePaisa, cur)}। অনুগ্রহ করে সুবিধামতো পরিশোধ করুন।"
        val uri = Uri.parse("smsto:${customer.mobile}")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply { putExtra("sms_body", text) }
        context.startActivity(intent)
    }

    fun shareManualMessage(customer: CustomerEntity) {
        val text = "আসসালামু আলাইকুম ${customer.name}, ${settings.shopName}-এ আপনার বকেয়া ${CurrencyUtils.formatBdt(customer.currentBalancePaisa, cur)}। অনুগ্রহ করে সুবিধামতো পরিশোধ করুন।"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "তাগাদা বার্তা পাঠান"))
    }

    Scaffold(
        topBar = {
            BakirKhataTopBar(
                title = "তাগাদা ও অভ্যন্তরীণ রিমাইন্ডার",
                subtitle = "স্বয়ংক্রিয়ভাবে কোনো কাস্টমারকে মেসেজ পাঠানো হয় না",
                onBack = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("দোকানদারের করণীয় রিমাইন্ডার", fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = summary.lastBackupAt?.let { "সর্বশেষ ব্যাকআপ: ${CurrencyUtils.formatDateBn(it)}" } ?: "ব্যাকআপ এখনো নেওয়া হয়নি",
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = onNavigateBackup) { Text("ব্যাকআপ দেখুন") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("আজকের দৈনিক হিসাব ক্লোজিং পরীক্ষা করুন", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onNavigateDailyReport) { Text("দৈনিক রিপোর্ট") }
                        }
                    }
                }
            }

            item {
                Text(
                    "দীর্ঘদিনের বকেয়া (${CurrencyUtils.toBengaliDigits(settings.overdueDaysThreshold.toString())}+ দিন)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (overdueRows.isEmpty()) {
                item {
                    EmptyLedgerState(
                        title = "দীর্ঘদিনের কোনো বকেয়া নেই",
                        subtitle = "সমস্ত বকেয়া নির্ধারিত সময়সীমার মধ্যেই রয়েছে।"
                    )
                }
            } else {
                items(overdueRows, key = { it.customer.id }) { row ->
                    val c = row.customer
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onSelectCustomer(c.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${c.name} (${c.customerCode})", fontWeight = FontWeight.Bold)
                                Text(CurrencyUtils.formatBdt(row.outstandingPaisa, cur), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            }
                            Text(
                                "বকেয়ার বয়স: ${CurrencyUtils.toBengaliDigits(row.debtAgeDays.toString())} দিন • ঠিকানা: ${c.address}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (c.mobile.isNotBlank()) {
                                    OutlinedButton(onClick = { sendManualSms(c) }) {
                                        Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("SMS পাঠান")
                                    }
                                }
                                OutlinedButton(onClick = { shareManualMessage(c) }) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("বার্তা শেয়ার")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportRow(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Text(value, fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold)
    }
}
