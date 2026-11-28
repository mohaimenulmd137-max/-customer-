package com.example.ui.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import java.util.UUID

private sealed class HalKhataListEntry {
    abstract val key: String
    abstract val name: String
    abstract val code: String
    abstract val address: String
    abstract val mobile: String
    abstract val balancePaisa: Long
    abstract val totalPaidPaisa: Long
    abstract val isTemporary: Boolean

    data class Permanent(val customer: CustomerEntity) : HalKhataListEntry() {
        override val key: String = "perm_${customer.id}"
        override val name: String = customer.name
        override val code: String = customer.customerCode
        override val address: String = customer.address
        override val mobile: String = customer.mobile
        override val balancePaisa: Long = customer.currentBalancePaisa
        override val totalPaidPaisa: Long = customer.totalPaymentsPaisa
        override val isTemporary: Boolean = false
    }

    data class Temporary(val temp: TemporaryCustomerEntity) : HalKhataListEntry() {
        override val key: String = "temp_${temp.id}"
        override val name: String = temp.name
        override val code: String = temp.tempCode
        override val address: String = temp.address
        override val mobile: String = temp.mobile
        override val balancePaisa: Long = temp.outstandingPaisa
        override val totalPaidPaisa: Long = temp.totalPaidPaisa
        override val isTemporary: Boolean = true
    }
}

private enum class HalKhataFilter(val labelBn: String) {
    ALL("সব কাস্টমার"),
    WITH_DEBT("বাকি আছে"),
    ZERO_DEBT("শূন্য বাকি"),
    TEMPORARY("অস্থায়ী")
}

@Composable
fun HalKhataScreen(
    periods: List<HalKhataPeriodEntity>,
    activePeriod: HalKhataPeriodEntity?,
    customers: List<CustomerEntity>,
    tempCustomers: List<TemporaryCustomerEntity>,
    payments: List<PaymentEntity> = emptyList(),
    settings: AppSettingsEntity,
    isSubmitting: Boolean,
    pdfGenerator: PdfGenerator,
    observeBalances: (Long) -> Flow<List<HalKhataCustomerBalanceEntity>>,
    onRecordCustomerPayment: (customerId: Long, amountPaisa: Long, notes: String, requestToken: String, onSuccess: (PaymentEntity) -> Unit) -> Unit,
    onRecordTempPayment: (tempId: Long, amountPaisa: Long, notes: String, onSuccess: () -> Unit) -> Unit,
    onOpenPermanentLedger: (Long) -> Unit,
    onOpenTempCustomers: () -> Unit,
    onUpdateActiveMeta: (name: String, desc: String, startDate: Long, endDate: Long?) -> Unit,
    onClosePeriod: (newName: String, newDesc: String, newEndDate: Long?, onDone: () -> Unit) -> Unit,
    onMessage: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cur = settings.currencySymbol
    val todayMillis = remember { System.currentTimeMillis() }

    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(HalKhataFilter.ALL) }
    var paymentTarget by remember { mutableStateOf<HalKhataListEntry?>(null) }
    var paymentReceipt by remember { mutableStateOf<Pair<CustomerEntity, PaymentEntity>?>(null) }

    var showEditMeta by remember { mutableStateOf(false) }
    var showCloseDialog by remember { mutableStateOf(false) }
    var inspectedPeriod by remember { mutableStateOf<HalKhataPeriodEntity?>(null) }
    var previewPdfFile by remember { mutableStateOf<File?>(null) }

    val allEntries = remember(customers, tempCustomers) {
        val permEntries = customers.map { HalKhataListEntry.Permanent(it) }
        val tempEntries = tempCustomers
            .filter { !it.isConverted }
            .map { HalKhataListEntry.Temporary(it) }
        (permEntries + tempEntries)
            .sortedWith(compareByDescending<HalKhataListEntry> { it.balancePaisa }.thenBy { it.name })
    }

    val filteredEntries = remember(allEntries, searchQuery, selectedFilter) {
        val q = searchQuery.trim().lowercase()
        allEntries.filter { entry ->
            val matchesFilter = when (selectedFilter) {
                HalKhataFilter.ALL -> true
                HalKhataFilter.WITH_DEBT -> entry.balancePaisa > 0L
                HalKhataFilter.ZERO_DEBT -> entry.balancePaisa == 0L
                HalKhataFilter.TEMPORARY -> entry.isTemporary
            }
            val matchesSearch = q.isEmpty() ||
                entry.name.lowercase().contains(q) ||
                entry.address.lowercase().contains(q) ||
                entry.mobile.lowercase().contains(q)
            matchesFilter && matchesSearch
        }
    }

    val totalDuePaisa = remember(allEntries) { allEntries.sumOf { it.balancePaisa } }
    val debtorCount = remember(allEntries) { allEntries.count { it.balancePaisa > 0L } }
    val todayStart = remember(todayMillis) { CurrencyUtils.startOfDayMillis(todayMillis) }
    val activePeriodId = activePeriod?.id
    val todayHalKhataCollectedPaisa = remember(payments, todayStart, activePeriodId) {
        payments.filter {
            !it.isReversed &&
                it.createdAt >= todayStart &&
                (activePeriodId == null || it.halKhataPeriodId == activePeriodId) &&
                it.notes.contains("হালখাতা")
        }.sumOf { it.amountPaisa }
    }
    val periodCollectedPaisa = remember(payments, activePeriodId) {
        payments.filter {
            !it.isReversed && (activePeriodId == null || it.halKhataPeriodId == activePeriodId)
        }.sumOf { it.amountPaisa }
    }

    Scaffold(
        topBar = {
            BakirKhataTopBar(
                title = "হালখাতা",
                subtitle = "তারিখ: ${CurrencyUtils.formatDateBn(todayMillis)} • ${activePeriod?.name ?: "চলতি হিসাব কাল"}",
                onBack = onBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("কাস্টমার ও জমা (${CurrencyUtils.toBengaliDigits(allEntries.size.toString())})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("হিসাব কাল ও সমাপ্তি") }
                )
            }

            if (selectedTab == 0) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Summary strip
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column {
                                        Text("মোট প্রাপ্য বকেয়া (${CurrencyUtils.formatDateBn(todayMillis)})", style = MaterialTheme.typography.labelMedium)
                                        Text(
                                            CurrencyUtils.formatBdt(totalDuePaisa, cur),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = if (totalDuePaisa > 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            "মোট কাস্টমার: ${CurrencyUtils.toBengaliDigits(allEntries.size.toString())} জন",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "বাকি আছে: ${CurrencyUtils.toBengaliDigits(debtorCount.toString())} জন",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(
                                        "আজকের হালখাতা জমা: ${CurrencyUtils.formatBdt(todayHalKhataCollectedPaisa, cur)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        "মোট জমা: ${CurrencyUtils.formatBdt(periodCollectedPaisa, cur)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    // Search box
                    item {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("কাস্টমারের নাম, মোবাইল বা ঠিকানা লিখুন") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "মুছুন")
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("halkhata_search_input")
                        )
                    }

                    // Filter chips
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HalKhataFilter.entries.forEach { f ->
                                FilterChip(
                                    selected = selectedFilter == f,
                                    onClick = { selectedFilter = f },
                                    label = { Text(f.labelBn) }
                                )
                            }
                        }
                    }

                    if (filteredEntries.isEmpty()) {
                        item {
                            EmptyLedgerState(
                                title = if (allEntries.isEmpty()) "কোনো কাস্টমার নেই" else "কোনো কাস্টমার পাওয়া যায়নি",
                                subtitle = if (allEntries.isEmpty()) {
                                    "কাস্টমার তালিকা থেকে নতুন কাস্টমার যোগ করলে এখানে দেখাবে।"
                                } else {
                                    "সার্চ বা ফিল্টার পরিবর্তন করে দেখুন।"
                                }
                            )
                        }
                    } else {
                        items(filteredEntries, key = { it.key }) { entry ->
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    when (entry) {
                                        is HalKhataListEntry.Permanent -> onOpenPermanentLedger(entry.customer.id)
                                        is HalKhataListEntry.Temporary -> onOpenTempCustomers()
                                    }
                                },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                entry.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Surface(
                                                shape = MaterialTheme.shapes.small,
                                                color = if (entry.isTemporary) MaterialTheme.colorScheme.tertiaryContainer
                                                else MaterialTheme.colorScheme.secondaryContainer
                                            ) {
                                                Text(
                                                    text = if (entry.isTemporary) "অস্থায়ী" else "স্থায়ী",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        val subLine = listOfNotNull(
                                            entry.address.takeIf { it.isNotBlank() },
                                            entry.mobile.takeIf { it.isNotBlank() }
                                        ).joinToString(" • ")
                                        if (subLine.isNotBlank()) {
                                            Text(
                                                text = subLine,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = if (entry.balancePaisa > 0L) {
                                                "বাকি: ${CurrencyUtils.formatBdt(entry.balancePaisa, cur)}"
                                            } else {
                                                "বাকি: ${CurrencyUtils.formatBdt(0L, cur)} (পরিশোধিত)"
                                            },
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (entry.balancePaisa > 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    if (entry.balancePaisa > 0L) {
                                        Button(
                                            onClick = { paymentTarget = entry },
                                            modifier = Modifier.heightIn(min = 48.dp).testTag("halkhata_pay_${entry.key}")
                                        ) {
                                            Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("জমা নিন")
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = {
                                                when (entry) {
                                                    is HalKhataListEntry.Permanent -> onOpenPermanentLedger(entry.customer.id)
                                                    is HalKhataListEntry.Temporary -> onOpenTempCustomers()
                                                }
                                            },
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        ) {
                                            Text("খাতা দেখুন")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 36.dp),
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
                        Text("পূর্ববর্তী সংরক্ষিত হালখাতা (সর্বোচ্চ ২টি)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "নীতিমালা: চলতি খাতা + সর্বশেষ ২টি সমাপ্ত হালখাতা সংরক্ষিত থাকে। ২টির বেশি পুরোনো খাতা সব জায়গা থেকে স্বয়ংক্রিয়ভাবে ডিলিট হয়ে যায়।",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    val closedPeriods = periods.filter { it.status != HalKhataStatus.ACTIVE }
                    if (closedPeriods.isEmpty()) {
                        item {
                            EmptyLedgerState(
                                title = "পূর্ববর্তী কোনো হালখাতা নেই",
                                subtitle = "বর্তমান হিসাব কাল সমাপ্ত করলে সর্বশেষ ২টি সমাপ্ত খাতা এখানে সংরক্ষিত থাকবে।"
                            )
                        }
                    } else {
                        items(closedPeriods, key = { it.id }) { p ->
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
                                                text = "সমাপ্ত",
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
                                                try {
                                                    val bals = observeBalances(p.id).first()
                                                    previewPdfFile = pdfGenerator.generateHalKhataArchivePdf(p, customers, bals, settings)
                                                } catch (e: Exception) {
                                                    onMessage("পিডিএফ তৈরি করা যায়নি: ${e.message ?: "ত্রুটি"}")
                                                }
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
        }
    }

    // Inline Hal Khata payment dialog (handles full close AND partial remaining debt)
    paymentTarget?.let { target ->
        var amountText by remember(target.key) { mutableStateOf("") }
        var notes by remember(target.key) { mutableStateOf("হালখাতা জমা") }
        var requestToken by remember(target.key) { mutableStateOf(UUID.randomUUID().toString()) }
        val parsedAmount = CurrencyUtils.parseBdtToPaisa(amountText)
        val validAmount = parsedAmount != null && parsedAmount > 0L && parsedAmount <= target.balancePaisa
        val exceedsBalance = parsedAmount != null && parsedAmount > target.balancePaisa
        val remainingAfter = if (parsedAmount != null) (target.balancePaisa - parsedAmount).coerceAtLeast(0L) else target.balancePaisa

        AlertDialog(
            onDismissRequest = { paymentTarget = null },
            title = { Text("হালখাতা জমা: ${target.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${if (target.isTemporary) "অস্থায়ী কাস্টমার" else "স্থায়ী কাস্টমার"} • তারিখ: ${CurrencyUtils.formatDateBn(System.currentTimeMillis())}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "বর্তমান বাকি: ${CurrencyUtils.formatBdt(target.balancePaisa, cur)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("জমা টাকা ($cur) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = exceedsBalance,
                        modifier = Modifier.fillMaxWidth().testTag("halkhata_dialog_amount")
                    )
                    OutlinedButton(
                        onClick = {
                            amountText = CurrencyUtils.formatPaisaToPlainInput(target.balancePaisa)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("সব বাকি শোধ (${CurrencyUtils.formatBdt(target.balancePaisa, cur)}) — ক্লোজ")
                    }
                    if (exceedsBalance) {
                        Text(
                            "বকেয়ার (${CurrencyUtils.formatBdt(target.balancePaisa, cur)}) চেয়ে বেশি জমা নেওয়া যাবে না।",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else if (validAmount) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (remainingAfter == 0L) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (remainingAfter == 0L) {
                                    "পূর্ণ পরিশোধ — বাকি ৳ ০ হবে ও হিসাব ক্লোজ দেখাবে।"
                                } else {
                                    "কম দেওয়ায় অবশিষ্ট ${CurrencyUtils.formatBdt(remainingAfter, cur)} বাকি থেকেই যাবে।"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("নোট (ঐচ্ছিক)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = parsedAmount ?: return@Button
                        val cleanNote = notes.trim().ifEmpty { "হালখাতা জমা" }
                        when (target) {
                            is HalKhataListEntry.Permanent -> {
                                onRecordCustomerPayment(target.customer.id, amt, cleanNote, requestToken) { saved ->
                                    paymentTarget = null
                                    paymentReceipt = target.customer to saved
                                }
                            }
                            is HalKhataListEntry.Temporary -> {
                                onRecordTempPayment(target.temp.id, amt, cleanNote) {
                                    if (remainingAfter == 0L) {
                                        onMessage("${target.name}-এর সব বাকি শোধ ও হিসাব ক্লোজ হয়েছে")
                                    }
                                    paymentTarget = null
                                }
                            }
                        }
                    },
                    enabled = !isSubmitting && validAmount,
                    modifier = Modifier.testTag("halkhata_dialog_confirm")
                ) {
                    Text("জমা নিশ্চিত করুন")
                }
            },
            dismissButton = {
                TextButton(onClick = { paymentTarget = null }) { Text("বাতিল") }
            }
        )
    }

    paymentReceipt?.let { (cust, pay) ->
        AlertDialog(
            onDismissRequest = { paymentReceipt = null },
            title = { Text("হালখাতা জমা রসিদ") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("কাস্টমার: ${cust.name}", fontWeight = FontWeight.Bold)
                    Text("তারিখ: ${CurrencyUtils.formatDateBn(pay.createdAt)}")
                    Text("পূর্বের বকেয়া: ${CurrencyUtils.formatBdt(pay.previousBalancePaisa, cur)}")
                    Text("হালখাতা জমা: ${CurrencyUtils.formatBdt(pay.amountPaisa, cur)}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (pay.newBalancePaisa == 0L) "অবশিষ্ট বকেয়া: ৳ ০ (সব পরিশোধিত / ক্লোজ)"
                        else "অবশিষ্ট বকেয়া: ${CurrencyUtils.formatBdt(pay.newBalancePaisa, cur)}",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    try {
                        previewPdfFile = pdfGenerator.generatePaymentReceiptPdf(cust, pay, settings)
                        paymentReceipt = null
                    } catch (e: Exception) {
                        onMessage("রসিদ পিডিএফ তৈরি করা যায়নি: ${e.message ?: "ত্রুটি"}")
                    }
                }) {
                    Text("রসিদ পিডিএফ / প্রিন্ট")
                }
            },
            dismissButton = {
                TextButton(onClick = { paymentReceipt = null }) { Text("ঠিক আছে") }
            }
        )
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
                        "হালখাতা সমাপ্ত করলে শূন্য-বাকি কাস্টমারদের নতুন খাতা ৳ ০ থেকে ফাঁকা শুরু হবে এবং যাদের বাকি থাকবে তাদের জের স্বয়ংক্রিয়ভাবে নতুন খাতায় যুক্ত হবে। সর্বশেষ ২টি পুরোনো খাতা রেখে তার আগের খাতা সব জায়গা থেকে মুছে যাবে।",
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
                            Text(b.customerName, modifier = Modifier.weight(1f))
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
            title = "হালখাতা / রসিদ",
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
