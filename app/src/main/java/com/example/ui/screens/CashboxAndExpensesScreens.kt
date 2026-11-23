package com.example.ui.screens

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.local.*
import com.example.domain.CashboxSummary
import com.example.ui.components.BakirKhataTopBar
import com.example.ui.components.EmptyLedgerState
import com.example.ui.components.PdfPreviewAndActionsDialog
import com.example.util.CurrencyUtils
import com.example.util.PdfGenerator
import java.io.File

@Composable
fun CashboxScreen(
    summary: CashboxSummary,
    transactions: List<CashboxTransactionEntity>,
    settings: AppSettingsEntity,
    pdfGenerator: PdfGenerator,
    onAddManualEntry: (type: String, direction: String, amountPaisa: Long, description: String, notes: String, () -> Unit) -> Unit,
    onRecordActualCount: (Long) -> Unit,
    onMessage: (String) -> Unit,
    onBack: () -> Unit
) {
    val cur = settings.currencySymbol
    var showAddDialog by remember { mutableStateOf(false) }
    var showCountDialog by remember { mutableStateOf(false) }
    var previewPdfFile by remember { mutableStateOf<File?>(null) }

    Scaffold(
        topBar = {
            BakirKhataTopBar(
                title = "ক্যাশবক্স ব্যবস্থাপনা",
                subtitle = "প্রত্যাশিত ব্যালেন্স: ${CurrencyUtils.formatBdt(summary.expectedBalancePaisa, cur)}",
                onBack = onBack,
                actions = {
                    IconButton(onClick = {
                        previewPdfFile = pdfGenerator.generateCashboxReportPdf(summary, transactions, settings)
                    }) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = "ক্যাশবক্স পিডিএফ")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("ক্যাশ এন্ট্রি") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("ক্যাশবক্স সারসংক্ষেপ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("প্রারম্ভিক ক্যাশ:")
                            Text(CurrencyUtils.formatBdt(summary.openingCashPaisa, cur), fontWeight = FontWeight.SemiBold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("মোট নগদ আগমন:")
                            Text(CurrencyUtils.formatBdt(summary.totalInflowPaisa, cur), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("মোট নগদ নির্গমন:")
                            Text(CurrencyUtils.formatBdt(summary.totalOutflowPaisa, cur), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                        }
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("প্রত্যাশিত ক্যাশ ব্যালেন্স:", fontWeight = FontWeight.Bold)
                            Text(CurrencyUtils.formatBdt(summary.expectedBalancePaisa, cur), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                        summary.actualCountedCashPaisa?.let { actual ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("হাতে গোনা নগদ:")
                                Text(CurrencyUtils.formatBdt(actual, cur), fontWeight = FontWeight.Bold)
                            }
                            val diff = summary.differencePaisa ?: 0L
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("পার্থক্য:")
                                Text(
                                    CurrencyUtils.formatBdt(diff, cur),
                                    fontWeight = FontWeight.Bold,
                                    color = if (diff == 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = { showCountDialog = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.Calculate, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("হাতে গোনা নগদ টাকা মিলিয়ে নিন")
                        }
                    }
                }
            }

            item {
                Text("তারিখ অনুযায়ী ক্যাশবক্স লেনদেন", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            if (transactions.isEmpty()) {
                item {
                    EmptyLedgerState(
                        title = "ক্যাশবক্সে কোনো লেনদেন নেই",
                        subtitle = "নগদ বিক্রি, কাস্টমার জমা বা প্রারম্ভিক ক্যাশ যোগ করলে এখানে দেখাবে।"
                    )
                }
            } else {
                items(transactions, key = { it.id }) { tx ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${tx.transactionCode} • ${CurrencyUtils.formatDateTimeBn(tx.createdAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (tx.isReversed) "${tx.description} [বাতিল]" else tx.description,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (tx.notes.isNotBlank()) {
                                    Text(tx.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            val sign = if (tx.direction == CashboxDirection.IN) "+" else "-"
                            Text(
                                text = "$sign ${CurrencyUtils.formatBdt(tx.amountPaisa, cur)}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (tx.direction == CashboxDirection.IN) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var selectedType by remember { mutableStateOf(CashboxType.OPENING) }
        var amountText by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        var notes by remember { mutableStateOf("") }
        val options = listOf(
            Triple(CashboxType.OPENING, CashboxDirection.IN, "প্রারম্ভিক ক্যাশ"),
            Triple(CashboxType.OTHER_INCOME, CashboxDirection.IN, "অন্যান্য নগদ আয়"),
            Triple(CashboxType.SUPPLIER_PAYMENT, CashboxDirection.OUT, "মহাজন/সরবরাহকারী পরিশোধ"),
            Triple(CashboxType.OTHER_WITHDRAWAL, CashboxDirection.OUT, "নগদ উত্তোলন")
        )
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("ক্যাশবক্স এন্ট্রি") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { (typeCode, _, label) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { selectedType = typeCode },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selectedType == typeCode, onClick = { selectedType = typeCode })
                            Text(label)
                        }
                    }
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("টাকার পরিমাণ ($cur) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it },
                        label = { Text("বিবরণ *") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("নোট (ঐচ্ছিক)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val amt = CurrencyUtils.parseBdtToPaisa(amountText) ?: 0L
                    val dir = options.first { it.first == selectedType }.second
                    val finalDesc = desc.trim().ifEmpty { options.first { it.first == selectedType }.third }
                    onAddManualEntry(selectedType, dir, amt, finalDesc, notes) {
                        showAddDialog = false
                    }
                }) { Text("সংরক্ষণ") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("বাতিল") } }
        )
    }

    if (showCountDialog) {
        var actualText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCountDialog = false },
            title = { Text("হাতে গোনা নগদ ক্যাশ") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("হিসাব অনুযায়ী ক্যাশ: ${CurrencyUtils.formatBdt(summary.expectedBalancePaisa, cur)}")
                    OutlinedTextField(
                        value = actualText,
                        onValueChange = { actualText = it },
                        label = { Text("বাস্তবে ক্যাশবক্সে যত টাকা আছে ($cur)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val amt = CurrencyUtils.parseBdtToPaisa(actualText)
                    if (amt != null) {
                        onRecordActualCount(amt)
                        showCountDialog = false
                    } else {
                        onMessage("সঠিক টাকার পরিমাণ লিখুন")
                    }
                }) { Text("সংরক্ষণ করুন") }
            },
            dismissButton = { TextButton(onClick = { showCountDialog = false }) { Text("বাতিল") } }
        )
    }

    previewPdfFile?.let { file ->
        PdfPreviewAndActionsDialog(
            file = file,
            title = "ক্যাশবক্স রিপোর্ট",
            pdfGenerator = pdfGenerator,
            onMessage = onMessage,
            onDismiss = { previewPdfFile = null }
        )
    }
}

@Composable
fun ExpensesScreen(
    expenses: List<ExpenseEntity>,
    settings: AppSettingsEntity,
    customCategories: List<String>,
    pdfGenerator: PdfGenerator,
    onAddExpense: (category: String, amountPaisa: Long, description: String, notes: String, paidFromCashbox: Boolean, () -> Unit) -> Unit,
    onUpdateExpense: (id: Long, category: String, amountPaisa: Long, description: String, notes: String, paidFromCashbox: Boolean, reason: String, () -> Unit) -> Unit,
    onAddCustomCategory: (String) -> Unit,
    onMessage: (String) -> Unit,
    onBack: () -> Unit
) {
    val cur = settings.currencySymbol
    val allCategories = remember(customCategories) {
        listOf(
            "দোকান ভাড়া",
            "বিদ্যুৎ বিল",
            "পরিবহন",
            "শ্রমিক মজুরি",
            "মালামাল ও সরবরাহ",
            "মেরামত ও রক্ষণাবেক্ষণ",
            "নাশতা ও খাবার",
            "অন্যান্য"
        ) + customCategories
    }
    var search by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingExpense by remember { mutableStateOf<ExpenseEntity?>(null) }
    var previewPdfFile by remember { mutableStateOf<File?>(null) }

    val filtered = remember(expenses, search) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) expenses
        else expenses.filter {
            it.category.lowercase().contains(q) ||
                it.description.lowercase().contains(q) ||
                it.expenseCode.lowercase().contains(q)
        }
    }

    Scaffold(
        topBar = {
            BakirKhataTopBar(
                title = "খরচ ব্যবস্থাপনা",
                subtitle = "মোট খরচ: ${CurrencyUtils.formatBdt(expenses.filter { !it.isCancelled }.sumOf { it.amountPaisa }, cur)}",
                onBack = onBack,
                actions = {
                    IconButton(onClick = {
                        previewPdfFile = pdfGenerator.generateExpenseReportPdf(expenses, settings)
                    }) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = "খরচের পিডিএফ")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("নতুন খরচ") },
                modifier = Modifier.testTag("expenses_add_fab")
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
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("খাত বা বিবরণ দিয়ে খরচ খুঁজুন") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))

            if (filtered.isEmpty()) {
                EmptyLedgerState(
                    title = "কোনো খরচ পাওয়া যায়নি",
                    subtitle = "দোকান ভাড়া, বিদ্যুৎ বা অন্যান্য খরচ যোগ করুন।",
                    actionLabel = "নতুন খরচ যোগ করুন",
                    onAction = { showAddDialog = true },
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { exp ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "${exp.expenseCode} • ${CurrencyUtils.formatDateTimeBn(exp.createdAt)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(exp.category, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text(exp.description, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        if (exp.paidFromCashbox) "ক্যাশবক্স থেকে পরিশোধিত" else "বাইরে থেকে পরিশোধিত",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    CurrencyUtils.formatBdt(exp.amountPaisa, cur),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                                IconButton(onClick = { editingExpense = exp }) {
                                    Icon(Icons.Default.Edit, contentDescription = "সম্পাদনা")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        ExpenseFormDialog(
            title = "নতুন খরচ যোগ করুন",
            categories = allCategories,
            currencySymbol = cur,
            initial = null,
            requireReason = false,
            onDismiss = { showAddDialog = false },
            onAddCustomCategory = onAddCustomCategory,
            onSave = { cat, amt, desc, notes, fromCash, _ ->
                onAddExpense(cat, amt, desc, notes, fromCash) { showAddDialog = false }
            }
        )
    }

    editingExpense?.let { target ->
        ExpenseFormDialog(
            title = "খরচ সংশোধন (${target.expenseCode})",
            categories = allCategories,
            currencySymbol = cur,
            initial = target,
            requireReason = true,
            onDismiss = { editingExpense = null },
            onAddCustomCategory = onAddCustomCategory,
            onSave = { cat, amt, desc, notes, fromCash, reason ->
                onUpdateExpense(target.id, cat, amt, desc, notes, fromCash, reason) {
                    editingExpense = null
                }
            }
        )
    }

    previewPdfFile?.let { file ->
        PdfPreviewAndActionsDialog(
            file = file,
            title = "খরচের হিসাব রিপোর্ট",
            pdfGenerator = pdfGenerator,
            onMessage = onMessage,
            onDismiss = { previewPdfFile = null }
        )
    }
}

@Composable
private fun ExpenseFormDialog(
    title: String,
    categories: List<String>,
    currencySymbol: String,
    initial: ExpenseEntity?,
    requireReason: Boolean,
    onDismiss: () -> Unit,
    onAddCustomCategory: (String) -> Unit,
    onSave: (String, Long, String, String, Boolean, String) -> Unit
) {
    var category by remember { mutableStateOf(initial?.category ?: categories.first()) }
    var customCatText by remember { mutableStateOf("") }
    var showCatMenu by remember { mutableStateOf(false) }
    var amountText by remember { mutableStateOf(initial?.let { CurrencyUtils.formatPaisaToPlainInput(it.amountPaisa) } ?: "") }
    var desc by remember { mutableStateOf(initial?.description ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var paidFromCashbox by remember { mutableStateOf(initial?.paidFromCashbox ?: true) }
    var reason by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedTextField(
                        value = category,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("খরচের খাত") },
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Box(modifier = Modifier.matchParentSize().clickable { showCatMenu = true })
                    DropdownMenu(expanded = showCatMenu, onDismissRequest = { showCatMenu = false }) {
                        categories.forEach { cat ->
                            DropdownMenuItem(text = { Text(cat) }, onClick = { category = cat; showCatMenu = false })
                        }
                        DropdownMenuItem(text = { Text("+ নতুন খাত যোগ করুন") }, onClick = { category = "CUSTOM_NEW"; showCatMenu = false })
                    }
                }
                if (category == "CUSTOM_NEW") {
                    OutlinedTextField(
                        value = customCatText,
                        onValueChange = { customCatText = it },
                        label = { Text("নতুন খাতের নাম লিখুন *") },
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it; errorMsg = null },
                    label = { Text("টাকার পরিমাণ ($currencySymbol) *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("বিবরণ") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("নোট") },
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = paidFromCashbox, onCheckedChange = { paidFromCashbox = it })
                    Text("ক্যাশবক্সের নগদ টাকা থেকে খরচ হয়েছে", style = MaterialTheme.typography.bodySmall)
                }
                if (requireReason) {
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it; errorMsg = null },
                        label = { Text("সংশোধনের কারণ *") },
                        singleLine = true
                    )
                }
                errorMsg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val finalCat = if (category == "CUSTOM_NEW") {
                    val c = customCatText.trim()
                    if (c.isEmpty()) {
                        errorMsg = "নতুন খাতের নাম দিন"
                        return@Button
                    }
                    onAddCustomCategory(c)
                    c
                } else category
                val amt = CurrencyUtils.parseBdtToPaisa(amountText)
                if (amt == null || amt <= 0L) {
                    errorMsg = "সঠিক টাকার পরিমাণ লিখুন"
                    return@Button
                }
                if (requireReason && reason.trim().isEmpty()) {
                    errorMsg = "সংশোধনের কারণ উল্লেখ করুন"
                    return@Button
                }
                onSave(finalCat, amt, desc, notes, paidFromCashbox, reason)
            }) { Text("সংরক্ষণ করুন") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } }
    )
}
