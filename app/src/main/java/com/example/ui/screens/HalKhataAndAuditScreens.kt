package com.example.ui.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.ui.components.BakirKhataTopBar
import com.example.ui.components.EmptyLedgerState
import com.example.ui.components.PdfPreviewAndActionsDialog
import com.example.util.CurrencyUtils
import com.example.util.PdfGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

@Composable
fun HalKhataScreen(
    periods: List<HalKhataPeriodEntity>,
    activePeriod: HalKhataPeriodEntity?,
    customers: List<CustomerEntity>,
    settings: AppSettingsEntity,
    isSubmitting: Boolean,
    pdfGenerator: PdfGenerator,
    observeBalances: (Long) -> Flow<List<HalKhataCustomerBalanceEntity>>,
    onUpdateActiveMeta: (name: String, desc: String, startDate: Long, endDate: Long?) -> Unit,
    onClosePeriod: (newName: String, newDesc: String, newEndDate: Long?, onDone: () -> Unit) -> Unit,
    onMessage: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cur = settings.currencySymbol
    var showEditMeta by remember { mutableStateOf(false) }
    var showCloseDialog by remember { mutableStateOf(false) }
    var inspectedPeriod by remember { mutableStateOf<HalKhataPeriodEntity?>(null) }
    var previewPdfFile by remember { mutableStateOf<File?>(null) }

    Scaffold(
        topBar = {
            BakirKhataTopBar(
                title = "হালখাতা ব্যবস্থাপনা",
                subtitle = activePeriod?.name ?: "চলতি হিসাব কাল",
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
            if (activePeriod != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("সক্রিয় হালখাতা: ${activePeriod.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                IconButton(onClick = { showEditMeta = true }) {
                                    Icon(Icons.Default.Edit, contentDescription = "সময়কাল সম্পাদনা")
                                }
                            }
                            if (activePeriod.description.isNotBlank()) {
                                Text(activePeriod.description, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                "শুরুর তারিখ: ${CurrencyUtils.formatDateBn(activePeriod.startDate)} • নির্ধারিত সমাপনী: ${activePeriod.endDate?.let { CurrencyUtils.formatDateBn(it) } ?: "নির্ধারিত নয়"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "বর্তমান মোট বকেয়া (জের): ${CurrencyUtils.formatBdt(customers.sumOf { it.currentBalancePaisa }, cur)}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(6.dp))
                            Button(
                                onClick = { showCloseDialog = true },
                                enabled = !isSubmitting,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
                            ) {
                                Icon(Icons.Default.MenuBook, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("হালখাতা সমাপ্ত করুন ও নতুন কাল শুরু করুন")
                            }
                        }
                    }
                }
            }

            item {
                Text("পূর্ববর্তী ও সংরক্ষিত হালখাতা সমূহ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "নীতিমালা: চলতি কাল + সর্বশেষ ২টি সমাপ্ত হালখাতার বিস্তারিত সক্রিয় থাকে; পুরোনো কাল যাচাইকৃত ব্যাকআপ ও পিডিএফ আর্কাইভ শেষে সংরক্ষিত হয়। কাস্টমার ও বকেয়া কখনো মুছা হয় না।",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val closedOrArchived = periods.filter { it.status != HalKhataStatus.ACTIVE }
            if (closedOrArchived.isEmpty()) {
                item {
                    EmptyLedgerState(
                        title = "পূর্ববর্তী কোনো হালখাতা নেই",
                        subtitle = "বর্তমান হিসাব কাল সমাপ্ত করলে তার সমাপনী রিপোর্ট ও ব্যালেন্স এখানে সংরক্ষিত থাকবে।"
                    )
                }
            } else {
                items(closedOrArchived, key = { it.id }) { p ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(p.name, fontWeight = FontWeight.Bold)
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = if (p.status == HalKhataStatus.ARCHIVED) "আর্কাইভড" else "সমাপ্ত",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                "${CurrencyUtils.formatDateBn(p.startDate)} – ${p.endDate?.let { CurrencyUtils.formatDateBn(it) } ?: "-"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "বিক্রি: ${CurrencyUtils.formatBdt(p.totalSalesPaisa, cur)} • জমা: ${CurrencyUtils.formatBdt(p.totalPaymentsPaisa, cur)} • সমাপনী জের: ${CurrencyUtils.formatBdt(p.totalClosingDebtPaisa, cur)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { inspectedPeriod = p }) {
                                    Text("সমাপনী জের তালিকা")
                                }
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        val bals = observeBalances(p.id).first()
                                        previewPdfFile = pdfGenerator.generateHalKhataArchivePdf(p, customers, bals, settings)
                                    }
                                }) {
                                    Text("পিডিএফ রিপোর্ট")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditMeta && activePeriod != null) {
        var name by remember { mutableStateOf(activePeriod.name) }
        var desc by remember { mutableStateOf(activePeriod.description) }
        var startDate by remember { mutableStateOf(activePeriod.startDate) }
        var endDate by remember { mutableStateOf(activePeriod.endDate) }

        fun pick(isStart: Boolean) {
            val cal = Calendar.getInstance().apply { timeInMillis = (if (isStart) startDate else endDate) ?: System.currentTimeMillis() }
            DatePickerDialog(
                context,
                { _, y, m, d ->
                    cal.set(y, m, d)
                    if (isStart) startDate = CurrencyUtils.startOfDayMillis(cal.timeInMillis)
                    else endDate = CurrencyUtils.endOfDayMillis(cal.timeInMillis)
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        AlertDialog(
            onDismissRequest = { showEditMeta = false },
            title = { Text("হালখাতার সময়কাল সম্পাদনা") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("হালখাতার নাম *") }, singleLine = true)
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("বিবরণ") }, maxLines = 2)
                    OutlinedButton(onClick = { pick(true) }, modifier = Modifier.fillMaxWidth()) {
                        Text("শুরু: ${CurrencyUtils.formatDateBn(startDate)}")
                    }
                    OutlinedButton(onClick = { pick(false) }, modifier = Modifier.fillMaxWidth()) {
                        Text("সমাপনী: ${endDate?.let { CurrencyUtils.formatDateBn(it) } ?: "তারিখ নির্ধারণ করুন"}")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onUpdateActiveMeta(name, desc, startDate, endDate)
                    showEditMeta = false
                }) { Text("সংরক্ষণ") }
            },
            dismissButton = { TextButton(onClick = { showEditMeta = false }) { Text("বাতিল") } }
        )
    }

    if (showCloseDialog && activePeriod != null) {
        var newName by remember { mutableStateOf("নতুন হালখাতা ${CurrencyUtils.formatDateBn(System.currentTimeMillis())}") }
        var newDesc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCloseDialog = false },
            title = { Text("হালখাতা সমাপ্ত করবেন?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "সমাপ্তির পূর্বে স্বয়ংক্রিয়ভাবে সম্পূর্ণ ডাটাবেস ব্যাকআপ ও পিডিএফ আর্কাইভ তৈরি করে যাচাই করা হবে। অপরিশোধিত সকল বকেয়া নতুন হিসাব কালে স্বয়ংক্রিয়ভাবে জের হিসেবে স্থানান্তর হবে।",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("নতুন হালখাতার নাম *") }, singleLine = true)
                    OutlinedTextField(value = newDesc, onValueChange = { newDesc = it }, label = { Text("বিবরণ (ঐচ্ছিক)") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClosePeriod(newName, newDesc, null) { showCloseDialog = false }
                    },
                    enabled = !isSubmitting
                ) { Text("ব্যাকআপ যাচাই করে সমাপ্ত করুন") }
            },
            dismissButton = { TextButton(onClick = { showCloseDialog = false }) { Text("বাতিল") } }
        )
    }

    inspectedPeriod?.let { period ->
        val balances by observeBalances(period.id).collectAsState(initial = emptyList())
        AlertDialog(
            onDismissRequest = { inspectedPeriod = null },
            title = { Text("${period.name} — সমাপনী জের") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(balances, key = { it.id }) { b ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${b.customerName} (${b.customerCode})", modifier = Modifier.weight(1f))
                            Text(CurrencyUtils.formatBdt(b.closingBalancePaisa, cur), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { inspectedPeriod = null }) { Text("বন্ধ করুন") }
            }
        )
    }

    previewPdfFile?.let { file ->
        PdfPreviewAndActionsDialog(
            file = file,
            title = "হালখাতা রিপোর্ট",
            pdfGenerator = pdfGenerator,
            onMessage = onMessage,
            onDismiss = { previewPdfFile = null }
        )
    }
}

@Composable
fun AuditLogScreen(
    logs: List<AuditLogEntity>,
    onBack: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(logs, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) logs
        else logs.filter {
            it.transactionRef.lowercase().contains(q) ||
                it.entityType.lowercase().contains(q) ||
                it.actionType.lowercase().contains(q) ||
                it.reason.lowercase().contains(q)
        }
    }

    Scaffold(
        topBar = {
            BakirKhataTopBar(
                title = "অডিট ও সংশোধন ইতিহাস",
                subtitle = "মোট রেকর্ড: ${CurrencyUtils.toBengaliDigits(logs.size.toString())} টি",
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
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("লেনদেন নম্বর, ধরন বা কারণ দিয়ে খুঁজুন") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            if (filtered.isEmpty()) {
                EmptyLedgerState(
                    title = "কোনো অডিট লগ পাওয়া যায়নি",
                    subtitle = "সকল লেনদেন, সংশোধন, রিভার্সাল ও ব্যাকআপ রিস্টোরের স্থায়ী হিসাব এখানে সংরক্ষিত থাকে।",
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { log ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${log.entityType} • ${log.actionType}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Text(log.transactionRef, style = MaterialTheme.typography.labelSmall)
                                }
                                Text(CurrencyUtils.formatDateTimeBn(log.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (log.reason.isNotBlank()) Text("কারণ: ${log.reason}", style = MaterialTheme.typography.bodySmall)
                                if (log.previousValueJson.isNotBlank()) {
                                    Text("পূর্বের মান: ${log.previousValueJson}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (log.newValueJson.isNotBlank()) {
                                    Text("নতুন মান: ${log.newValueJson}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
