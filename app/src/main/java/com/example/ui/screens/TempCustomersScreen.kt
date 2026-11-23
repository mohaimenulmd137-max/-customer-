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
import java.io.File

@Composable
fun TempCustomersScreen(
    tempCustomers: List<TemporaryCustomerEntity>,
    settings: AppSettingsEntity,
    pdfGenerator: PdfGenerator,
    observeTempMemos: (Long) -> Flow<List<TemporaryMemoEntity>>,
    onCreateTempCustomer: (String, String, String, String, (TemporaryCustomerEntity) -> Unit) -> Unit,
    onAddTempEntry: (Long, String, String, Long, Long, String, () -> Unit) -> Unit,
    onConvertToPermanent: (Long, String, (CustomerEntity) -> Unit) -> Unit,
    onNavigateToPermanentLedger: (Long) -> Unit,
    onMessage: (String) -> Unit,
    onBack: () -> Unit
) {
    val cur = settings.currencySymbol
    var selectedTempId by remember { mutableStateOf<Long?>(null) }
    val selectedTemp = remember(tempCustomers, selectedTempId) { tempCustomers.find { it.id == selectedTempId } }
    var showCreateTemp by remember { mutableStateOf(false) }
    var showAddEntry by remember { mutableStateOf(false) }
    var convertTarget by remember { mutableStateOf<TemporaryCustomerEntity?>(null) }
    var previewPdfFile by remember { mutableStateOf<File?>(null) }

    Scaffold(
        topBar = {
            BakirKhataTopBar(
                title = if (selectedTemp != null) "অস্থায়ী: ${selectedTemp.name}" else "অস্থায়ী কাস্টমার খাতা",
                subtitle = if (selectedTemp != null) "${selectedTemp.tempCode} • বকেয়া: ${CurrencyUtils.formatBdt(selectedTemp.outstandingPaisa, cur)}"
                else "মোট অস্থায়ী বকেয়া: ${CurrencyUtils.formatBdt(tempCustomers.filter { !it.isConverted }.sumOf { it.outstandingPaisa }, cur)}",
                onBack = {
                    if (selectedTemp != null) selectedTempId = null else onBack()
                },
                actions = {
                    if (selectedTemp == null && tempCustomers.isNotEmpty()) {
                        IconButton(onClick = {
                            previewPdfFile = pdfGenerator.generateTempCustomerReportPdf(tempCustomers, settings)
                        }) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = "পিডিএফ")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (selectedTemp == null) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateTemp = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("নতুন অস্থায়ী খাতা") }
                )
            } else if (!selectedTemp.isConverted) {
                ExtendedFloatingActionButton(
                    onClick = { showAddEntry = true },
                    icon = { Icon(Icons.Default.AddCard, contentDescription = null) },
                    text = { Text("লেনদেন যোগ") }
                )
            }
        }
    ) { padding ->
        if (selectedTemp == null) {
            if (tempCustomers.isEmpty()) {
                EmptyLedgerState(
                    title = "কোনো অস্থায়ী কাস্টমার নেই",
                    subtitle = "যাদের স্থায়ী প্রোফাইল লাগবে না তাদের হিসাব এখানে আলাদা রাখুন, প্রয়োজনে স্থায়ী কাস্টমারে রূপান্তর করুন।",
                    actionLabel = "অস্থায়ী কাস্টমার যোগ করুন",
                    onAction = { showCreateTemp = true },
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(tempCustomers, key = { it.id }) { t ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { selectedTempId = t.id },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(t.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text(t.tempCode, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (t.address.isNotBlank()) Text(t.address, style = MaterialTheme.typography.bodySmall)
                                    if (t.isConverted) {
                                        Text("স্থায়ী কাস্টমারে রূপান্তরিত", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        CurrencyUtils.formatBdt(t.outstandingPaisa, cur),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (t.outstandingPaisa > 0 && !t.isConverted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val memos by observeTempMemos(selectedTemp.id).collectAsState(initial = emptyList())
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("মোট বাকি: ${CurrencyUtils.formatBdt(selectedTemp.totalCreditPaisa, cur)}")
                            Text("মোট জমা: ${CurrencyUtils.formatBdt(selectedTemp.totalPaidPaisa, cur)}")
                            Text(
                                "অবশিষ্ট বকেয়া: ${CurrencyUtils.formatBdt(selectedTemp.outstandingPaisa, cur)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            if (!selectedTemp.isConverted) {
                                Button(
                                    onClick = { convertTarget = selectedTemp },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                ) {
                                    Icon(Icons.Default.Upgrade, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("স্থায়ী কাস্টমারে রূপান্তর করুন")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { selectedTemp.convertedCustomerId?.let(onNavigateToPermanentLedger) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                ) {
                                    Text("স্থায়ী কাস্টমার খাতা দেখুন")
                                }
                            }
                        }
                    }
                }
                items(memos, key = { it.id }) { tm ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(tm.memoNumber, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text(CurrencyUtils.formatDateTimeBn(tm.createdAt), style = MaterialTheme.typography.labelSmall)
                            }
                            Text(tm.description, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("মোট: ${CurrencyUtils.formatBdt(tm.totalAmountPaisa, cur)} • জমা: ${CurrencyUtils.formatBdt(tm.paidPaisa, cur)} • বাকি: ${CurrencyUtils.formatBdt(tm.creditPaisa, cur)}")
                        }
                    }
                }
            }
        }
    }

    if (showCreateTemp) {
        var name by remember { mutableStateOf("") }
        var address by remember { mutableStateOf("") }
        var mobile by remember { mutableStateOf("") }
        var notes by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateTemp = false },
            title = { Text("নতুন অস্থায়ী কাস্টমার") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("নাম *") }, singleLine = true)
                    OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("ঠিকানা") }, singleLine = true)
                    OutlinedTextField(value = mobile, onValueChange = { mobile = it }, label = { Text("মোবাইল") }, singleLine = true)
                    OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("নোট") }, maxLines = 2)
                }
            },
            confirmButton = {
                Button(onClick = {
                    onCreateTempCustomer(name, address, mobile, notes) { created ->
                        selectedTempId = created.id
                        showCreateTemp = false
                    }
                }) { Text("তৈরি করুন") }
            },
            dismissButton = { TextButton(onClick = { showCreateTemp = false }) { Text("বাতিল") } }
        )
    }

    if (showAddEntry && selectedTemp != null) {
        var type by remember { mutableStateOf("CREDIT") }
        var desc by remember { mutableStateOf("") }
        var totalText by remember { mutableStateOf("") }
        var paidText by remember { mutableStateOf("") }
        var notes by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddEntry = false },
            title = { Text("অস্থায়ী লেনদেন যোগ করুন") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("CREDIT" to "বাকি", "PAYMENT" to "জমা", "MEMO" to "মেমো").forEach { (c, l) ->
                            FilterChip(selected = type == c, onClick = { type = c }, label = { Text(l) })
                        }
                    }
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("বিবরণ") }, singleLine = true)
                    OutlinedTextField(
                        value = totalText,
                        onValueChange = { totalText = it },
                        label = { Text(if (type == "PAYMENT") "জমার পরিমাণ ($cur) *" else "মোট পরিমাণ ($cur) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                    if (type == "MEMO") {
                        OutlinedTextField(
                            value = paidText,
                            onValueChange = { paidText = it },
                            label = { Text("নগদ জমা ($cur)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true
                        )
                    }
                    OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("নোট") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val total = CurrencyUtils.parseBdtToPaisa(totalText) ?: 0L
                    val paid = if (type == "PAYMENT") total else (CurrencyUtils.parseBdtToPaisa(paidText) ?: 0L)
                    onAddTempEntry(selectedTemp.id, type, desc, total, paid, notes) {
                        showAddEntry = false
                    }
                }) { Text("সংরক্ষণ") }
            },
            dismissButton = { TextButton(onClick = { showAddEntry = false }) { Text("বাতিল") } }
        )
    }

    convertTarget?.let { target ->
        var addr by remember { mutableStateOf(target.address) }
        AlertDialog(
            onDismissRequest = { convertTarget = null },
            title = { Text("স্থায়ী কাস্টমারে রূপান্তর") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("'${target.name}'-এর সকল মেমো, লেনদেন ও বকেয়া (${CurrencyUtils.formatBdt(target.outstandingPaisa, cur)}) হুবহু সংরক্ষণ করে একটি স্থায়ী কাস্টমার আইডি তৈরি করা হবে।")
                    OutlinedTextField(value = addr, onValueChange = { addr = it }, label = { Text("স্থায়ী ঠিকানা") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    onConvertToPermanent(target.id, addr) { perm ->
                        convertTarget = null
                        onNavigateToPermanentLedger(perm.id)
                    }
                }) { Text("রূপান্তর নিশ্চিত করুন") }
            },
            dismissButton = { TextButton(onClick = { convertTarget = null }) { Text("বাতিল") } }
        )
    }

    previewPdfFile?.let { file ->
        PdfPreviewAndActionsDialog(
            file = file,
            title = "অস্থায়ী কাস্টমার রিপোর্ট",
            pdfGenerator = pdfGenerator,
            onMessage = onMessage,
            onDismiss = { previewPdfFile = null }
        )
    }
}
