package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import com.example.domain.MemoLineDraft
import com.example.ui.components.BakirKhataTopBar
import com.example.ui.components.CustomerPickerDialog
import com.example.ui.components.PdfPreviewAndActionsDialog
import com.example.ui.components.QuickCreateCustomerDialog
import com.example.util.CurrencyUtils
import com.example.util.PdfGenerator
import java.io.File
import java.util.UUID

// region Quick Payment / জমা Screen

@Composable
fun QuickPaymentScreen(
    customers: List<CustomerEntity>,
    initialCustomerId: Long?,
    settings: AppSettingsEntity,
    isSubmitting: Boolean,
    pdfGenerator: PdfGenerator,
    onCreateCustomerInline: (String, String, String, String, String, Long, (CustomerEntity) -> Unit) -> Unit,
    onConfirmPayment: (customerId: Long, amountPaisa: Long, notes: String, requestToken: String, onSuccess: (PaymentEntity) -> Unit) -> Unit,
    onMessage: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedCustomerId by remember(initialCustomerId) { mutableStateOf(initialCustomerId) }
    val selectedCustomer = remember(customers, selectedCustomerId) {
        customers.find { it.id == selectedCustomerId }
    }
    var amountText by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var requestToken by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var showPicker by remember { mutableStateOf(false) }
    var showQuickCreate by remember { mutableStateOf(false) }
    var completedReceipt by remember { mutableStateOf<Pair<CustomerEntity, PaymentEntity>?>(null) }
    var previewPdfFile by remember { mutableStateOf<File?>(null) }

    val parsedAmountPaisa = remember(amountText) { CurrencyUtils.parseBdtToPaisa(amountText) }
    val cur = settings.currencySymbol
    val hasNoDebt = selectedCustomer != null && selectedCustomer.currentBalancePaisa <= 0L
    val exceedsDebt = selectedCustomer != null && parsedAmountPaisa != null && parsedAmountPaisa > selectedCustomer.currentBalancePaisa

    Scaffold(
        topBar = {
            BakirKhataTopBar(
                title = "দ্রুত জমা গ্রহণ",
                subtitle = CurrencyUtils.formatDateTimeBn(System.currentTimeMillis()),
                onBack = onBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Customer selector card
            Card(
                modifier = Modifier.fillMaxWidth().clickable { showPicker = true }.testTag("payment_select_customer"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("কাস্টমার নির্বাচন করুন *", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        if (selectedCustomer == null) {
                            Text("কাস্টমার বাছাই করতে ট্যাপ করুন", style = MaterialTheme.typography.titleMedium)
                        } else {
                            Text("${selectedCustomer.name} (${selectedCustomer.customerCode})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(selectedCustomer.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
            }

            OutlinedButton(
                onClick = { showQuickCreate = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("নতুন কাস্টমার যোগ করে এখানে ফিরুন")
            }

            if (selectedCustomer != null) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (hasNoDebt) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("বর্তমান বকেয়া:", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = CurrencyUtils.formatBdt(selectedCustomer.currentBalancePaisa, cur),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (hasNoDebt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                }

                if (hasNoDebt) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "এই কাস্টমারের কোনো বকেয়া নেই। শূন্য বাকিতে জমা গ্রহণ করা যাবে না।",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("জমার পরিমাণ ($cur) *") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                isError = exceedsDebt,
                modifier = Modifier.fillMaxWidth().testTag("payment_amount_input")
            )

            if (exceedsDebt && selectedCustomer != null) {
                Text(
                    text = "বকেয়ার (${CurrencyUtils.formatBdt(selectedCustomer.currentBalancePaisa, cur)}) চেয়ে বেশি জমা নেওয়া যাবে না।",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("নোট / মন্তব্য (ঐচ্ছিক)") },
                maxLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            if (selectedCustomer != null && parsedAmountPaisa != null && parsedAmountPaisa > 0L && !hasNoDebt && !exceedsDebt) {
                val newDebt = selectedCustomer.currentBalancePaisa - parsedAmountPaisa
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("হিসাব প্রিভিউ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text("পূর্বের বাকি: ${CurrencyUtils.formatBdt(selectedCustomer.currentBalancePaisa, cur)}")
                        Text("জমা গ্রহণ: -${CurrencyUtils.formatBdt(parsedAmountPaisa, cur)}")
                        Text(
                            "নতুন বকেয়া হবে: ${CurrencyUtils.formatBdt(newDebt, cur)}",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Button(
                onClick = {
                    val cust = selectedCustomer ?: return@Button
                    val amt = parsedAmountPaisa ?: return@Button
                    onConfirmPayment(cust.id, amt, notes, requestToken) { savedPayment ->
                        completedReceipt = cust to savedPayment
                        amountText = ""
                        notes = ""
                        requestToken = UUID.randomUUID().toString()
                    }
                },
                enabled = !isSubmitting && selectedCustomer != null && !hasNoDebt && !exceedsDebt && (parsedAmountPaisa ?: 0L) > 0L,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("payment_confirm_button")
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("জমা নিশ্চিত করুন ও রসিদ তৈরি করুন")
            }
        }
    }

    if (showPicker) {
        CustomerPickerDialog(
            customers = customers,
            currencySymbol = cur,
            title = "জমার জন্য কাস্টমার নির্বাচন",
            onDismiss = { showPicker = false },
            onSelect = {
                selectedCustomerId = it.id
                showPicker = false
            },
            onRequestCreateCustomer = {
                showPicker = false
                showQuickCreate = true
            }
        )
    }

    if (showQuickCreate) {
        QuickCreateCustomerDialog(
            showOpeningBalance = true,
            onDismiss = { showQuickCreate = false },
            onCreate = { name, addr, mob, nick, cNotes, opening ->
                onCreateCustomerInline(name, addr, mob, nick, cNotes, opening) { created ->
                    selectedCustomerId = created.id
                    showQuickCreate = false
                }
            }
        )
    }

    completedReceipt?.let { (cust, pay) ->
        AlertDialog(
            onDismissRequest = { completedReceipt = null },
            title = { Text("পেমেন্ট রসিদ (${pay.receiptNumber})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("কাস্টমার: ${cust.name} (${cust.customerCode})", fontWeight = FontWeight.Bold)
                    Text("পূর্বের বকেয়া: ${CurrencyUtils.formatBdt(pay.previousBalancePaisa, cur)}")
                    Text("জমা গ্রহণ: ${CurrencyUtils.formatBdt(pay.amountPaisa, cur)}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("অবশিষ্ট বকেয়া: ${CurrencyUtils.formatBdt(pay.newBalancePaisa, cur)}", fontWeight = FontWeight.Bold)
                    Text("তারিখ: ${CurrencyUtils.formatDateTimeBn(pay.createdAt)}", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val file = pdfGenerator.generatePaymentReceiptPdf(cust, pay, settings)
                    previewPdfFile = file
                    completedReceipt = null
                }) {
                    Text("পিডিএফ / প্রিন্ট / শেয়ার")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val file = pdfGenerator.generatePaymentReceiptPdf(cust, pay, settings)
                    pdfGenerator.printPdfFile(context, file, "রসিদ_${pay.receiptNumber}")
                }) {
                    Text("সরাসরি প্রিন্ট")
                }
            }
        )
    }

    previewPdfFile?.let { file ->
        PdfPreviewAndActionsDialog(
            file = file,
            title = "পেমেন্ট রসিদ",
            pdfGenerator = pdfGenerator,
            onMessage = onMessage,
            onDismiss = { previewPdfFile = null }
        )
    }
}

// endregion

// region Quick Credit / বাকি Screen

@Composable
fun QuickCreditScreen(
    customers: List<CustomerEntity>,
    initialCustomerId: Long?,
    settings: AppSettingsEntity,
    isSubmitting: Boolean,
    onCreateCustomerInline: (String, String, String, String, String, Long, (CustomerEntity) -> Unit) -> Unit,
    onConfirmCredit: (customerId: Long, amountPaisa: Long, description: String, notes: String, requestToken: String, onSuccess: (CreditTransactionEntity) -> Unit) -> Unit,
    onBack: () -> Unit
) {
    var selectedCustomerId by remember(initialCustomerId) { mutableStateOf(initialCustomerId) }
    val selectedCustomer = remember(customers, selectedCustomerId) {
        customers.find { it.id == selectedCustomerId }
    }
    var amountText by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var requestToken by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var showPicker by remember { mutableStateOf(false) }
    var showQuickCreate by remember { mutableStateOf(false) }

    val parsedAmountPaisa = remember(amountText) { CurrencyUtils.parseBdtToPaisa(amountText) }
    val cur = settings.currencySymbol

    Scaffold(
        topBar = {
            BakirKhataTopBar(
                title = "দ্রুত বাকি এন্ট্রি",
                subtitle = CurrencyUtils.formatDateTimeBn(System.currentTimeMillis()),
                onBack = onBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().clickable { showPicker = true }.testTag("credit_select_customer"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("কাস্টমার নির্বাচন করুন *", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        if (selectedCustomer == null) {
                            Text("কাস্টমার বাছাই করতে ট্যাপ করুন", style = MaterialTheme.typography.titleMedium)
                        } else {
                            Text("${selectedCustomer.name} (${selectedCustomer.customerCode})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(selectedCustomer.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
            }

            OutlinedButton(
                onClick = { showQuickCreate = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("নতুন কাস্টমার যোগ করে এখানে ফিরুন")
            }

            if (selectedCustomer != null) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("বর্তমান বকেয়া:", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = CurrencyUtils.formatBdt(selectedCustomer.currentBalancePaisa, cur),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("নতুন বাকির পরিমাণ ($cur) *") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("credit_amount_input")
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("বিবরণ (যেমন: চাল, ডাল বা নগদ ধার)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("নোট (ঐচ্ছিক)") },
                maxLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            if (selectedCustomer != null && parsedAmountPaisa != null && parsedAmountPaisa > 0L) {
                val totalAfter = selectedCustomer.currentBalancePaisa + parsedAmountPaisa
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f))
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("বাকি হিসাব প্রিভিউ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text("পূর্বের বাকি: ${CurrencyUtils.formatBdt(selectedCustomer.currentBalancePaisa, cur)}")
                        Text("নতুন বাকি: +${CurrencyUtils.formatBdt(parsedAmountPaisa, cur)}")
                        Text(
                            "মোট বকেয়া হবে: ${CurrencyUtils.formatBdt(totalAfter, cur)}",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Button(
                onClick = {
                    val cust = selectedCustomer ?: return@Button
                    val amt = parsedAmountPaisa ?: return@Button
                    onConfirmCredit(cust.id, amt, description, notes, requestToken) {
                        amountText = ""
                        description = ""
                        notes = ""
                        requestToken = UUID.randomUUID().toString()
                    }
                },
                enabled = !isSubmitting && selectedCustomer != null && (parsedAmountPaisa ?: 0L) > 0L,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("credit_confirm_button")
            ) {
                Text("বাকি নিশ্চিত করুন")
            }
        }
    }

    if (showPicker) {
        CustomerPickerDialog(
            customers = customers,
            currencySymbol = cur,
            title = "বাকির জন্য কাস্টমার নির্বাচন",
            onDismiss = { showPicker = false },
            onSelect = {
                selectedCustomerId = it.id
                showPicker = false
            },
            onRequestCreateCustomer = {
                showPicker = false
                showQuickCreate = true
            }
        )
    }

    if (showQuickCreate) {
        QuickCreateCustomerDialog(
            showOpeningBalance = false,
            onDismiss = { showQuickCreate = false },
            onCreate = { name, addr, mob, nick, cNotes, opening ->
                onCreateCustomerInline(name, addr, mob, nick, cNotes, opening) { created ->
                    selectedCustomerId = created.id
                    showQuickCreate = false
                }
            }
        )
    }
}

// endregion

// region Sales Memo System

@Composable
fun SalesMemoScreen(
    customers: List<CustomerEntity>,
    initialCustomerId: Long?,
    settings: AppSettingsEntity,
    customUnits: List<String>,
    isSubmitting: Boolean,
    pdfGenerator: PdfGenerator,
    onCreateCustomerInline: (String, String, String, String, String, Long, (CustomerEntity) -> Unit) -> Unit,
    onCreateMemo: (
        saleType: String,
        customerId: Long?,
        walkInName: String,
        walkInAddress: String,
        items: List<MemoLineDraft>,
        discountPaisa: Long,
        paidPaisaInput: Long,
        notes: String,
        requestToken: String,
        onSuccess: (MemoWithItems) -> Unit
    ) -> Unit,
    onMessage: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val cur = settings.currencySymbol
    val defaultUnits = remember(customUnits) {
        listOf("কেজি", "গ্রাম", "লিটার", "মিলি", "পিস", "বস্তা", "প্যাকেট", "ডজন", "বোতল") + customUnits
    }

    var saleType by remember { mutableStateOf(if (initialCustomerId != null) SaleType.CREDIT else SaleType.CASH) }
    var selectedCustomerId by remember(initialCustomerId) { mutableStateOf(initialCustomerId) }
    val selectedCustomer = remember(customers, selectedCustomerId) { customers.find { it.id == selectedCustomerId } }
    var walkInName by remember { mutableStateOf("নগদ ক্রেতা") }
    var walkInAddress by remember { mutableStateOf("") }

    var items by remember { mutableStateOf<List<MemoLineDraft>>(emptyList()) }
    var productName by remember { mutableStateOf("") }
    var quantityText by remember { mutableStateOf("1") }
    var selectedUnit by remember { mutableStateOf("কেজি") }
    var customUnitText by remember { mutableStateOf("") }
    var unitPriceText by remember { mutableStateOf("") }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    var discountText by remember { mutableStateOf("") }
    var paidText by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var requestToken by remember { mutableStateOf(UUID.randomUUID().toString()) }

    var showCustomerPicker by remember { mutableStateOf(false) }
    var showQuickCreate by remember { mutableStateOf(false) }
    var showUnitDropdown by remember { mutableStateOf(false) }
    var createdMemo by remember { mutableStateOf<MemoWithItems?>(null) }
    var previewPdfFile by remember { mutableStateOf<File?>(null) }

    val subtotalPaisa = remember(items) { items.sumOf { it.lineTotalPaisa } }
    val discountPaisa = remember(discountText) { CurrencyUtils.parseBdtToPaisa(discountText) ?: 0L }
    val grandTotalPaisa = (subtotalPaisa - discountPaisa).coerceAtLeast(0L)
    val paidInputPaisa = remember(paidText) { CurrencyUtils.parseBdtToPaisa(paidText) ?: 0L }
    val previewPaid = when (saleType) {
        SaleType.CASH -> grandTotalPaisa
        SaleType.CREDIT -> 0L
        else -> paidInputPaisa.coerceIn(0L, grandTotalPaisa)
    }
    val previewOutstanding = (grandTotalPaisa - previewPaid).coerceAtLeast(0L)

    Scaffold(
        topBar = {
            BakirKhataTopBar(
                title = "নতুন বিক্রয় মেমো",
                subtitle = CurrencyUtils.formatDateTimeBn(System.currentTimeMillis()),
                onBack = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Sale type selector
            item {
                Text("বিক্রয়ের ধরন", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val types = listOf(
                        SaleType.CASH to "নগদ বিক্রি",
                        SaleType.CREDIT to "বাকি বিক্রি",
                        SaleType.PARTIAL to "আংশিক জমা"
                    )
                    types.forEach { (code, label) ->
                        FilterChip(
                            selected = saleType == code,
                            onClick = { saleType = code },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f).testTag("memo_type_$code")
                        )
                    }
                }
            }

            // Customer picker
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { showCustomerPicker = true },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (saleType == SaleType.CASH) "কাস্টমার (ঐচ্ছিক)" else "কাস্টমার নির্বাচন করুন *",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = selectedCustomer?.let { "${it.name} (${it.customerCode}) • বাকি: ${CurrencyUtils.formatBdt(it.currentBalancePaisa, cur)}" }
                                    ?: "ট্যাপ করে কাস্টমার বাছাই করুন",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (selectedCustomer != null && saleType == SaleType.CASH) {
                            IconButton(onClick = { selectedCustomerId = null }) {
                                Icon(Icons.Default.Clear, contentDescription = "সরান")
                            }
                        } else {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                    }
                }
                if (selectedCustomer == null && saleType == SaleType.CASH) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = walkInName,
                            onValueChange = { walkInName = it },
                            label = { Text("ক্রেতার নাম") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = walkInAddress,
                            onValueChange = { walkInAddress = it },
                            label = { Text("ঠিকানা") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Item builder card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (editingIndex != null) "পণ্য সম্পাদনা করুন" else "পণ্য যোগ করুন",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedTextField(
                            value = productName,
                            onValueChange = { productName = it },
                            label = { Text("পণ্যের নাম *") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("memo_product_input")
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = quantityText,
                                onValueChange = { quantityText = it },
                                label = { Text("পরিমাণ") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("memo_qty_input")
                            )
                            Box(modifier = Modifier.weight(1f)) {
                                OutlinedTextField(
                                    value = selectedUnit,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("একক") },
                                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                                    modifier = Modifier.fillMaxWidth().clickable { showUnitDropdown = true }
                                )
                                Box(modifier = Modifier.matchParentSize().clickable { showUnitDropdown = true })
                                DropdownMenu(expanded = showUnitDropdown, onDismissRequest = { showUnitDropdown = false }) {
                                    (defaultUnits + "কাস্টম").forEach { u ->
                                        DropdownMenuItem(
                                            text = { Text(u) },
                                            onClick = {
                                                selectedUnit = u
                                                showUnitDropdown = false
                                            }
                                        )
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = unitPriceText,
                                onValueChange = { unitPriceText = it },
                                label = { Text("দর ($cur)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("memo_price_input")
                            )
                        }
                        if (selectedUnit == "কাস্টম") {
                            OutlinedTextField(
                                value = customUnitText,
                                onValueChange = { customUnitText = it },
                                label = { Text("কাস্টম এককের নাম লিখুন") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        val qMillis = CurrencyUtils.parseQuantityMillis(quantityText)
                        val pPaisa = CurrencyUtils.parseBdtToPaisa(unitPriceText)
                        val liveLineTotal = if (qMillis != null && pPaisa != null) {
                            CurrencyUtils.calculateLineTotalPaisa(qMillis, pPaisa)
                        } else 0L

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "মোট: ${CurrencyUtils.formatBdt(liveLineTotal, cur)}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Button(
                                onClick = {
                                    val cleanName = productName.trim()
                                    val unitFinal = if (selectedUnit == "কাস্টম") customUnitText.trim().ifEmpty { "পিস" } else selectedUnit
                                    if (cleanName.isEmpty() || qMillis == null || pPaisa == null || pPaisa <= 0L) {
                                        onMessage("পণ্যের নাম, সঠিক পরিমাণ ও দর লিখুন")
                                        return@Button
                                    }
                                    val draft = MemoLineDraft(
                                        localId = UUID.randomUUID().toString(),
                                        productName = cleanName,
                                        quantityText = quantityText,
                                        unit = unitFinal,
                                        unitPriceText = unitPriceText,
                                        quantityMillis = qMillis,
                                        unitPricePaisa = pPaisa,
                                        lineTotalPaisa = liveLineTotal
                                    )
                                    items = if (editingIndex != null) {
                                        items.toMutableList().apply { set(editingIndex!!, draft) }
                                    } else {
                                        items + draft
                                    }
                                    editingIndex = null
                                    productName = ""
                                    quantityText = "1"
                                    unitPriceText = ""
                                },
                                modifier = Modifier.testTag("memo_add_item_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text(if (editingIndex != null) "আপডেট" else "যোগ করুন")
                            }
                        }
                    }
                }
            }

            // Added items list
            itemsIndexed(items, key = { _, it -> it.localId }) { idx, item ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.productName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                "${CurrencyUtils.formatQuantityBn(item.quantityMillis)} ${item.unit} × ${CurrencyUtils.formatBdt(item.unitPricePaisa, cur)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            CurrencyUtils.formatBdt(item.lineTotalPaisa, cur),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = {
                            editingIndex = idx
                            productName = item.productName
                            quantityText = CurrencyUtils.formatQuantityPlain(item.quantityMillis)
                            selectedUnit = item.unit
                            unitPriceText = CurrencyUtils.formatPaisaToPlainInput(item.unitPricePaisa)
                        }) {
                            Icon(Icons.Default.Edit, contentDescription = "সম্পাদনা")
                        }
                        IconButton(onClick = {
                            items = items.toMutableList().apply { removeAt(idx) }
                            if (editingIndex == idx) editingIndex = null
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "মুছুন", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // Totals, discount, paid, and submit
            if (items.isNotEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("সাবটোটাল:")
                                Text(CurrencyUtils.formatBdt(subtotalPaisa, cur), fontWeight = FontWeight.Bold)
                            }
                            OutlinedTextField(
                                value = discountText,
                                onValueChange = { discountText = it },
                                label = { Text("ছাড় ($cur) — ঐচ্ছিক") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (saleType == SaleType.PARTIAL) {
                                OutlinedTextField(
                                    value = paidText,
                                    onValueChange = { paidText = it },
                                    label = { Text("নগদ জমা ($cur) *") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().testTag("memo_partial_paid_input")
                                )
                            }
                            OutlinedTextField(
                                value = notes,
                                onValueChange = { notes = it },
                                label = { Text("মেমো নোট (ঐচ্ছিক)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("সর্বমোট বিল:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(CurrencyUtils.formatBdt(grandTotalPaisa, cur), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("নগদ ক্যাশবক্সে যাবে:", color = MaterialTheme.colorScheme.primary)
                                Text(CurrencyUtils.formatBdt(previewPaid, cur), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("বকেয়া যুক্ত হবে:", color = MaterialTheme.colorScheme.error)
                                Text(CurrencyUtils.formatBdt(previewOutstanding, cur), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            }
                            Spacer(Modifier.height(4.dp))
                            Button(
                                onClick = {
                                    onCreateMemo(
                                        saleType,
                                        selectedCustomer?.id,
                                        walkInName,
                                        walkInAddress,
                                        items,
                                        discountPaisa,
                                        paidInputPaisa,
                                        notes,
                                        requestToken
                                    ) { saved ->
                                        createdMemo = saved
                                        items = emptyList()
                                        discountText = ""
                                        paidText = ""
                                        notes = ""
                                        requestToken = UUID.randomUUID().toString()
                                    }
                                },
                                enabled = !isSubmitting,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("memo_confirm_button")
                            ) {
                                Text("মেমো সংরক্ষণ করুন")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCustomerPicker) {
        CustomerPickerDialog(
            customers = customers,
            currencySymbol = cur,
            title = "মেমোর জন্য কাস্টমার নির্বাচন",
            onDismiss = { showCustomerPicker = false },
            onSelect = {
                selectedCustomerId = it.id
                showCustomerPicker = false
            },
            onRequestCreateCustomer = {
                showCustomerPicker = false
                showQuickCreate = true
            }
        )
    }

    if (showQuickCreate) {
        QuickCreateCustomerDialog(
            showOpeningBalance = false,
            onDismiss = { showQuickCreate = false },
            onCreate = { name, addr, mob, nick, cNotes, opening ->
                onCreateCustomerInline(name, addr, mob, nick, cNotes, opening) { created ->
                    selectedCustomerId = created.id
                    showQuickCreate = false
                }
            }
        )
    }

    createdMemo?.let { mwi ->
        AlertDialog(
            onDismissRequest = { createdMemo = null },
            title = { Text("বিক্রয় মেমো (${mwi.memo.memoNumber})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("ক্রেতা: ${mwi.memo.customerNameSnapshot}", fontWeight = FontWeight.Bold)
                    Text("মোট বিল: ${CurrencyUtils.formatBdt(mwi.memo.grandTotalPaisa, cur)}")
                    Text("নগদ জমা: ${CurrencyUtils.formatBdt(mwi.memo.paidPaisa, cur)}", color = MaterialTheme.colorScheme.primary)
                    Text("বকেয়া: ${CurrencyUtils.formatBdt(mwi.memo.outstandingPaisa, cur)}", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                Button(onClick = {
                    previewPdfFile = pdfGenerator.generateMemoPdf(mwi, settings)
                    createdMemo = null
                }) {
                    Text("পিডিএফ / প্রিন্ট / শেয়ার")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val f = pdfGenerator.generateMemoPdf(mwi, settings)
                    pdfGenerator.printPdfFile(context, f, "মেমো_${mwi.memo.memoNumber}")
                }) {
                    Text("প্রিন্ট")
                }
            }
        )
    }

    previewPdfFile?.let { file ->
        PdfPreviewAndActionsDialog(
            file = file,
            title = "বিক্রয় মেমো",
            pdfGenerator = pdfGenerator,
            onMessage = onMessage,
            onDismiss = { previewPdfFile = null }
        )
    }
}

// endregion
