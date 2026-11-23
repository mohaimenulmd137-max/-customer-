package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.local.*
import com.example.domain.CustomerLedgerEntry
import com.example.ui.components.BakirKhataTopBar
import com.example.ui.components.EmptyLedgerState
import com.example.ui.components.PdfPreviewAndActionsDialog
import com.example.util.CurrencyUtils
import com.example.util.PdfGenerator
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun CustomerLedgerScreen(
    customer: CustomerEntity?,
    entries: List<CustomerLedgerEntry>,
    settings: AppSettingsEntity,
    pdfGenerator: PdfGenerator,
    onGenerateCustomerPdf: suspend (CustomerEntity) -> File,
    onBack: () -> Unit,
    onQuickPayment: (Long) -> Unit,
    onQuickCredit: (Long) -> Unit,
    onNewMemo: (Long) -> Unit,
    onReversePayment: (Long, String) -> Unit,
    onCorrectPayment: (Long, Long, String) -> Unit,
    onReverseCredit: (Long, String) -> Unit,
    onCancelMemo: (Long, String) -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var previewPdfFile by remember { mutableStateOf<File?>(null) }
    var correctionTarget by remember { mutableStateOf<CustomerLedgerEntry?>(null) }

    if (customer == null) {
        Scaffold(topBar = { BakirKhataTopBar(title = "কাস্টমার খাতা", onBack = onBack) }) { p ->
            Box(Modifier.fillMaxSize().padding(p), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }

    val cur = settings.currencySymbol
    val debtAgeDays = customer.oldestUnpaidTimestamp?.let {
        if (customer.currentBalancePaisa > 0L) CurrencyUtils.daysBetween(it) else 0
    } ?: 0

    Scaffold(
        topBar = {
            BakirKhataTopBar(
                title = customer.name,
                subtitle = "${customer.customerCode} • ${customer.address}",
                onBack = onBack,
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val pdf = onGenerateCustomerPdf(customer)
                            pdfGenerator.printPdfFile(context, pdf, "লেজার_${customer.customerCode}")
                        }
                    }) {
                        Icon(Icons.Default.Print, contentDescription = "প্রিন্ট লেজার")
                    }
                    IconButton(onClick = {
                        scope.launch {
                            previewPdfFile = onGenerateCustomerPdf(customer)
                        }
                    }) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = "পিডিএফ")
                    }
                    IconButton(onClick = {
                        scope.launch {
                            val pdf = onGenerateCustomerPdf(customer)
                            pdfGenerator.shareFile(context, pdf, "application/pdf", "${customer.name} লেজার")
                        }
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "শেয়ার")
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
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Summary Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("বর্তমান নিট বকেয়া", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    text = CurrencyUtils.formatBdt(customer.currentBalancePaisa, cur),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (customer.currentBalancePaisa > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            }
                            if (debtAgeDays > 0) {
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        text = "বকেয়ার বয়স: ${CurrencyUtils.toBengaliDigits(debtAgeDays.toString())} দিন",
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        if (customer.mobile.isNotBlank() || customer.nickname.isNotBlank()) {
                            Text(
                                text = listOfNotNull(
                                    customer.mobile.takeIf { it.isNotBlank() }?.let { "মোবাইল: $it" },
                                    customer.nickname.takeIf { it.isNotBlank() }?.let { "ডাকনাম: $it" }
                                ).joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            LedgerMetric("প্রারম্ভিক বাকি", CurrencyUtils.formatBdt(customer.openingBalancePaisa, cur))
                            LedgerMetric("মোট ক্রয়", CurrencyUtils.formatBdt(customer.totalPurchasesPaisa, cur))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            LedgerMetric("মোট বাকি", CurrencyUtils.formatBdt(customer.totalCreditPaisa, cur))
                            LedgerMetric("মোট জমা", CurrencyUtils.formatBdt(customer.totalPaymentsPaisa, cur))
                        }
                        customer.lastTransactionDate?.let {
                            Text(
                                text = "সর্বশেষ লেনদেন: ${CurrencyUtils.formatDateTimeBn(it)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Action Buttons Row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onQuickPayment(customer.id) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("জমা")
                    }
                    OutlinedButton(
                        onClick = { onQuickCredit(customer.id) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.AddCard, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("বাকি")
                    }
                    OutlinedButton(
                        onClick = { onNewMemo(customer.id) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("মেমো")
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("লেনদেনের সম্পূর্ণ ইতিহাস", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    TextButton(onClick = {
                        scope.launch { previewPdfFile = onGenerateCustomerPdf(customer) }
                    }) {
                        Text("প্রিভিউ ও পিডিএফ")
                    }
                }
            }

            if (entries.isEmpty()) {
                item {
                    EmptyLedgerState(
                        title = "এখনো কোনো লেনদেন নেই",
                        subtitle = "উপরের বাটন থেকে জমা, বাকি বা বিক্রয় মেমো যোগ করুন।"
                    )
                }
            } else {
                items(entries, key = { "${it.javaClass.simpleName}_${it.referenceCode}_${it.timestamp}" }) { entry ->
                    LedgerEntryCard(
                        entry = entry,
                        currencySymbol = cur,
                        onCorrectClick = { correctionTarget = entry }
                    )
                }
            }
        }
    }

    previewPdfFile?.let { file ->
        PdfPreviewAndActionsDialog(
            file = file,
            title = "কাস্টমার লেজার: ${customer.name}",
            pdfGenerator = pdfGenerator,
            onMessage = onMessage,
            onDismiss = { previewPdfFile = null }
        )
    }

    correctionTarget?.let { entry ->
        LedgerCorrectionDialog(
            entry = entry,
            currencySymbol = cur,
            onDismiss = { correctionTarget = null },
            onReversePayment = { id, reason ->
                onReversePayment(id, reason)
                correctionTarget = null
            },
            onCorrectPayment = { id, newPaisa, reason ->
                onCorrectPayment(id, newPaisa, reason)
                correctionTarget = null
            },
            onReverseCredit = { id, reason ->
                onReverseCredit(id, reason)
                correctionTarget = null
            },
            onCancelMemo = { id, reason ->
                onCancelMemo(id, reason)
                correctionTarget = null
            }
        )
    }
}

@Composable
private fun LedgerMetric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LedgerEntryCard(
    entry: CustomerLedgerEntry,
    currencySymbol: String,
    onCorrectClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${CurrencyUtils.formatDateBn(entry.timestamp)} (${CurrencyUtils.weekdayBn(entry.timestamp)}) • ${CurrencyUtils.formatTimeBn(entry.timestamp)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = entry.referenceCode,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            when (entry) {
                is CustomerLedgerEntry.MemoEntry -> {
                    val m = entry.memoWithItems.memo
                    val typeLabel = when (m.saleType) {
                        SaleType.CASH -> "নগদ মেমো"
                        SaleType.CREDIT -> "বাকি মেমো"
                        else -> "আংশিক জমা মেমো"
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = if (m.isCancelled) "$typeLabel [বাতিল]" else typeLabel,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "মোট: ${CurrencyUtils.formatBdt(m.grandTotalPaisa, currencySymbol)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    entry.memoWithItems.items.forEach { item ->
                        Text(
                            text = "• ${item.productName}: ${CurrencyUtils.formatQuantityBn(item.quantityMillis)} ${item.unit} × ${CurrencyUtils.formatBdt(item.unitPricePaisa, currencySymbol)} = ${CurrencyUtils.formatBdt(item.lineTotalPaisa, currencySymbol)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = "জমা: ${CurrencyUtils.formatBdt(m.paidPaisa, currencySymbol)} | বাকি: ${CurrencyUtils.formatBdt(m.outstandingPaisa, currencySymbol)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (m.outstandingPaisa > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "জের: ${CurrencyUtils.formatBdt(entry.runningBalancePaisa, currencySymbol)}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (!m.isCancelled) {
                        TextButton(onClick = onCorrectClick, contentPadding = PaddingValues(0.dp)) {
                            Text("সংশোধন / বাতিল", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                is CustomerLedgerEntry.QuickCreditEntry -> {
                    val c = entry.credit
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = if (c.isReversed) "${c.description} [বাতিল]" else c.description,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "+${CurrencyUtils.formatBdt(c.amountPaisa, currencySymbol)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (c.notes.isNotBlank()) Text("নোট: ${c.notes}", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (!c.isReversed && c.sourceType == CreditSourceType.QUICK_CREDIT) {
                            TextButton(onClick = onCorrectClick, contentPadding = PaddingValues(0.dp)) {
                                Text("রিভার্স করুন", style = MaterialTheme.typography.labelSmall)
                            }
                        } else Spacer(Modifier.width(1.dp))
                        Text(
                            text = "জের: ${CurrencyUtils.formatBdt(entry.runningBalancePaisa, currencySymbol)}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                is CustomerLedgerEntry.PaymentEntry -> {
                    val p = entry.payment
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = if (p.isReversed) "জমা গ্রহণ [বাতিল]" else "জমা গ্রহণ",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "-${CurrencyUtils.formatBdt(p.amountPaisa, currencySymbol)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (p.notes.isNotBlank()) Text("নোট: ${p.notes}", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (!p.isReversed) {
                            TextButton(onClick = onCorrectClick, contentPadding = PaddingValues(0.dp)) {
                                Text("সংশোধন / বাতিল", style = MaterialTheme.typography.labelSmall)
                            }
                        } else Spacer(Modifier.width(1.dp))
                        Text(
                            text = "জের: ${CurrencyUtils.formatBdt(entry.runningBalancePaisa, currencySymbol)}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LedgerCorrectionDialog(
    entry: CustomerLedgerEntry,
    currencySymbol: String,
    onDismiss: () -> Unit,
    onReversePayment: (Long, String) -> Unit,
    onCorrectPayment: (Long, Long, String) -> Unit,
    onReverseCredit: (Long, String) -> Unit,
    onCancelMemo: (Long, String) -> Unit
) {
    var reason by remember { mutableStateOf("") }
    var newAmountText by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("লেনদেন সংশোধন / রিভার্স (${entry.referenceCode})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "মূল রেকর্ড অক্ষুণ্ণ রেখে অডিট লগসহ হিসাব সমন্বয় করা হবে।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (entry is CustomerLedgerEntry.PaymentEntry) {
                    OutlinedTextField(
                        value = newAmountText,
                        onValueChange = { newAmountText = it; errorMsg = null },
                        label = { Text("নতুন সঠিক জমা ($currencySymbol) — শুধু বাতিল করলে খালি রাখুন") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it; errorMsg = null },
                    label = { Text("সংশোধন বা বাতিলের কারণ *") },
                    maxLines = 2
                )
                if (errorMsg != null) {
                    Text(errorMsg!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (reason.trim().isEmpty()) {
                    errorMsg = "কারণ লিখুন"
                    return@Button
                }
                when (entry) {
                    is CustomerLedgerEntry.PaymentEntry -> {
                        if (newAmountText.isNotBlank()) {
                            val paisa = CurrencyUtils.parseBdtToPaisa(newAmountText)
                            if (paisa == null || paisa <= 0L) {
                                errorMsg = "সঠিক টাকার পরিমাণ লিখুন"
                                return@Button
                            }
                            onCorrectPayment(entry.payment.id, paisa, reason)
                        } else {
                            onReversePayment(entry.payment.id, reason)
                        }
                    }
                    is CustomerLedgerEntry.QuickCreditEntry -> onReverseCredit(entry.credit.id, reason)
                    is CustomerLedgerEntry.MemoEntry -> onCancelMemo(entry.memoWithItems.memo.id, reason)
                }
            }) {
                Text("নিশ্চিত করুন")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("বন্ধ করুন") }
        }
    )
}
