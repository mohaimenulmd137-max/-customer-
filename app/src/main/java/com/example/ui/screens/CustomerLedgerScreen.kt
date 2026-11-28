package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
    halKhataPeriods: List<HalKhataPeriodEntity> = emptyList(),
    activePeriodId: Long? = null,
    selectedPeriodId: Long? = null,
    onSelectPeriod: (Long?) -> Unit = {},
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
    val effectivePeriodId = selectedPeriodId ?: activePeriodId
    val isViewingOldPeriod = activePeriodId != null && effectivePeriodId != null && effectivePeriodId != activePeriodId
    val oldPeriods = remember(halKhataPeriods, activePeriodId) {
        halKhataPeriods.filter { it.id != activePeriodId && it.status != HalKhataStatus.ACTIVE }
    }

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

    val displayBalance = remember(customer, entries, isViewingOldPeriod) {
        if (isViewingOldPeriod) entries.firstOrNull()?.runningBalancePaisa ?: 0L else customer.currentBalancePaisa
    }
    val displayOpening = remember(customer, entries, isViewingOldPeriod) {
        if (isViewingOldPeriod) {
            entries.filterIsInstance<CustomerLedgerEntry.QuickCreditEntry>()
                .filter { !it.credit.isReversed && it.credit.sourceType == CreditSourceType.OPENING_CARRYOVER }
                .sumOf { it.credit.amountPaisa }
        } else customer.openingBalancePaisa
    }
    val displayTotalCredit = remember(customer, entries, isViewingOldPeriod) {
        if (isViewingOldPeriod) {
            entries.filterIsInstance<CustomerLedgerEntry.QuickCreditEntry>()
                .filter { !it.credit.isReversed }.sumOf { it.credit.amountPaisa } +
                entries.filterIsInstance<CustomerLedgerEntry.MemoEntry>()
                    .filter { !it.memoWithItems.memo.isCancelled }.sumOf { it.memoWithItems.memo.outstandingPaisa }
        } else customer.totalCreditPaisa
    }
    val displayTotalPaid = remember(customer, entries, isViewingOldPeriod) {
        if (isViewingOldPeriod) {
            entries.filterIsInstance<CustomerLedgerEntry.PaymentEntry>()
                .filter { !it.payment.isReversed }.sumOf { it.payment.amountPaisa } +
                entries.filterIsInstance<CustomerLedgerEntry.MemoEntry>()
                    .filter { !it.memoWithItems.memo.isCancelled }.sumOf { it.memoWithItems.memo.paidPaisa }
        } else customer.totalPaymentsPaisa
    }

    Scaffold(
        topBar = {
            BakirKhataTopBar(
                title = customer.name,
                subtitle = listOfNotNull(
                    customer.address.takeIf { it.isNotBlank() },
                    customer.mobile.takeIf { it.isNotBlank() }
                ).joinToString(" • ").ifEmpty { "কাস্টমার খাতা" },
                onBack = onBack,
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            try {
                                val pdf = onGenerateCustomerPdf(customer)
                                pdfGenerator.printPdfFile(context, pdf, "খাতা_${customer.name}")
                            } catch (e: Exception) {
                                onMessage("প্রিন্ট করা যায়নি: ${e.message ?: "ত্রুটি"}")
                            }
                        }
                    }) {
                        Icon(Icons.Default.Print, contentDescription = "প্রিন্ট লেজার")
                    }
                    IconButton(onClick = {
                        scope.launch {
                            try {
                                previewPdfFile = onGenerateCustomerPdf(customer)
                            } catch (e: Exception) {
                                onMessage("পিডিএফ তৈরি করা যায়নি: ${e.message ?: "ত্রুটি"}")
                            }
                        }
                    }) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = "পিডিএফ")
                    }
                    IconButton(onClick = {
                        scope.launch {
                            try {
                                val pdf = onGenerateCustomerPdf(customer)
                                pdfGenerator.shareFile(context, pdf, "application/pdf", "${customer.name} খাতা")
                            } catch (e: Exception) {
                                onMessage("শেয়ার করা যায়নি: ${e.message ?: "ত্রুটি"}")
                            }
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
                                Text(if (isViewingOldPeriod) "পুরোনো খাতার সমাপনী বাকি" else "বর্তমান নিট বকেয়া", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    text = CurrencyUtils.formatBdt(displayBalance, cur),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (displayBalance > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            }
                            if (!isViewingOldPeriod && debtAgeDays > 0) {
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
                            LedgerMetric("পূর্বের বাকি", CurrencyUtils.formatBdt(displayOpening, cur))
                            LedgerMetric("মোট বাকি", CurrencyUtils.formatBdt(displayTotalCredit, cur))
                            LedgerMetric("মোট জমা", CurrencyUtils.formatBdt(displayTotalPaid, cur))
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

            if (oldPeriods.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("খাতা নির্বাচন করুন", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = !isViewingOldPeriod,
                                onClick = { onSelectPeriod(null) },
                                label = { Text("চলতি খাতা") }
                            )
                            oldPeriods.forEach { p ->
                                FilterChip(
                                    selected = effectivePeriodId == p.id,
                                    onClick = { onSelectPeriod(p.id) },
                                    label = { Text(p.name) }
                                )
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isViewingOldPeriod) "পুরোনো খাতার ইতিহাস (সংরক্ষিত)" else "চলতি খাতার লেনদেন",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                previewPdfFile = onGenerateCustomerPdf(customer)
                            } catch (e: Exception) {
                                onMessage("পিডিএফ তৈরি করা যায়নি: ${e.message ?: "ত্রুটি"}")
                            }
                        }
                    }) {
                        Text("প্রিভিউ ও পিডিএফ")
                    }
                }
            }

            if (entries.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EmptyLedgerState(
                            title = if (isViewingOldPeriod) {
                                "এই পুরোনো খাতায় কোনো লেনদেন নেই"
                            } else if (customer.currentBalancePaisa == 0L && oldPeriods.isNotEmpty()) {
                                "নতুন খাতায় কোনো বাকি/লেনদেন নেই (৳ ০ থেকে শুরু)"
                            } else {
                                "এখনো কোনো লেনদেন নেই"
                            },
                            subtitle = if (isViewingOldPeriod) {
                                "নির্বাচিত হালখাতা কালে এই কাস্টমারের কোনো লেনদেন ছিল না।"
                            } else {
                                "উপরের বাটন থেকে জমা, বাকি বা বিক্রয় মেমো যোগ করুন।"
                            }
                        )
                        if (!isViewingOldPeriod && oldPeriods.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { onSelectPeriod(oldPeriods.first().id) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("পূর্ববর্তী খাতা (${oldPeriods.first().name}) দেখুন")
                            }
                        }
                    }
                }
            } else {
                items(entries, key = { "${it.javaClass.simpleName}_${it.referenceCode}_${it.timestamp}" }) { entry ->
                    LedgerEntryCard(
                        entry = entry,
                        currencySymbol = cur,
                        allowCorrection = !isViewingOldPeriod,
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
    allowCorrection: Boolean = true,
    onCorrectClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "${CurrencyUtils.formatDateBn(entry.timestamp)} (${CurrencyUtils.weekdayBn(entry.timestamp)})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when (entry) {
                is CustomerLedgerEntry.MemoEntry -> {
                    val m = entry.memoWithItems.memo
                    val statusTag = if (m.isCancelled) " [বাতিল]" else ""
                    val title = when {
                        m.outstandingPaisa > 0L && m.paidPaisa > 0L -> "বাকি ও নগদ জমা$statusTag"
                        m.outstandingPaisa > 0L -> "বাকি$statusTag"
                        else -> "নগদ জমা$statusTag"
                    }
                    val headlineAmount = if (m.outstandingPaisa > 0L) {
                        "+${CurrencyUtils.formatBdt(m.outstandingPaisa, currencySymbol)}"
                    } else {
                        CurrencyUtils.formatBdt(m.paidPaisa, currencySymbol)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (m.outstandingPaisa > 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = headlineAmount,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (m.outstandingPaisa > 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                    if (m.paidPaisa > 0L && m.outstandingPaisa > 0L) {
                        Text(
                            text = "মোট হিসাব: ${CurrencyUtils.formatBdt(m.grandTotalPaisa, currencySymbol)} | নগদ জমা: ${CurrencyUtils.formatBdt(m.paidPaisa, currencySymbol)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (m.notes.isNotBlank()) {
                        Text("নোট: ${m.notes}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        if (!m.isCancelled && allowCorrection) {
                            TextButton(onClick = onCorrectClick, contentPadding = PaddingValues(0.dp)) {
                                Text("সংশোধন / বাতিল", style = MaterialTheme.typography.labelSmall)
                            }
                        } else Spacer(Modifier.width(1.dp))
                        Text(
                            text = "বাকি জের: ${CurrencyUtils.formatBdt(entry.runningBalancePaisa, currencySymbol)}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                is CustomerLedgerEntry.QuickCreditEntry -> {
                    val c = entry.credit
                    val label = if (c.sourceType == CreditSourceType.OPENING_CARRYOVER) "পূর্বের বাকি" else "বাকি"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = if (c.isReversed) "$label [বাতিল]" else label,
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
                    val noteText = listOfNotNull(
                        c.description.takeIf { it.isNotBlank() && it != "বাকি" && it != "পূর্বের / প্রারম্ভিক বাকি" && it != "পূর্বের হালখাতার জের" },
                        c.notes.takeIf { it.isNotBlank() }
                    ).joinToString(" • ")
                    if (noteText.isNotBlank()) Text("নোট: $noteText", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        if (!c.isReversed && c.sourceType == CreditSourceType.QUICK_CREDIT && allowCorrection) {
                            TextButton(onClick = onCorrectClick, contentPadding = PaddingValues(0.dp)) {
                                Text("রিভার্স করুন", style = MaterialTheme.typography.labelSmall)
                            }
                        } else Spacer(Modifier.width(1.dp))
                        Text(
                            text = "বাকি জের: ${CurrencyUtils.formatBdt(entry.runningBalancePaisa, currencySymbol)}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                is CustomerLedgerEntry.PaymentEntry -> {
                    val p = entry.payment
                    val label = if (p.notes.contains("হালখাতা")) "হালখাতা জমা" else "জমা"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = if (p.isReversed) "$label [বাতিল]" else label,
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
                    if (p.notes.isNotBlank() && p.notes.trim() != "হালখাতা জমা") {
                        Text("নোট: ${p.notes}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        if (!p.isReversed && allowCorrection) {
                            TextButton(onClick = onCorrectClick, contentPadding = PaddingValues(0.dp)) {
                                Text("সংশোধন / বাতিল", style = MaterialTheme.typography.labelSmall)
                            }
                        } else Spacer(Modifier.width(1.dp))
                        Text(
                            text = "বাকি জের: ${CurrencyUtils.formatBdt(entry.runningBalancePaisa, currencySymbol)}",
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
        title = { Text("লেনদেন সংশোধন / রিভার্স") },
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
