package com.example.ui.screens

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.AppSettingsEntity
import com.example.data.local.CustomerEntity
import com.example.domain.CustomerFilter
import com.example.ui.components.BakirKhataTopBar
import com.example.ui.components.EmptyLedgerState
import com.example.ui.components.QuickCreateCustomerDialog
import com.example.util.CurrencyUtils

@Composable
fun CustomersScreen(
    customers: List<CustomerEntity>,
    settings: AppSettingsEntity,
    onBack: () -> Unit,
    onSelectCustomer: (Long) -> Unit,
    onCreateCustomer: (String, String, String, String, String, Long) -> Unit,
    onUpdateCustomer: (Long, String, String, String, String, String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(CustomerFilter.ALL) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingCustomer by remember { mutableStateOf<CustomerEntity?>(null) }

    val filtered = remember(customers, searchQuery, activeFilter) {
        val q = searchQuery.trim().lowercase()
        val searched = if (q.isEmpty()) customers else customers.filter { c ->
            c.name.lowercase().contains(q) ||
                c.customerCode.lowercase().contains(q) ||
                c.mobile.lowercase().contains(q) ||
                c.address.lowercase().contains(q) ||
                c.nickname.lowercase().contains(q)
        }
        when (activeFilter) {
            CustomerFilter.ALL -> searched
            CustomerFilter.WITH_DEBT -> searched.filter { it.currentBalancePaisa > 0L }
            CustomerFilter.ZERO_BALANCE -> searched.filter { it.currentBalancePaisa == 0L }
            CustomerFilter.RECENTLY_ADDED -> searched.sortedByDescending { it.createdAt }
            CustomerFilter.OLDEST_DEBT -> searched.filter { it.currentBalancePaisa > 0L }
                .sortedBy { it.oldestUnpaidTimestamp ?: it.createdAt }
            CustomerFilter.HIGHEST_DEBT -> searched.sortedByDescending { it.currentBalancePaisa }
        }
    }

    Scaffold(
        topBar = {
            BakirKhataTopBar(
                title = "কাস্টমার তালিকা",
                subtitle = "মোট ${CurrencyUtils.toBengaliDigits(customers.size.toString())} জন • বকেয়া ${CurrencyUtils.formatBdt(customers.sumOf { it.currentBalancePaisa }, settings.currencySymbol)}",
                onBack = onBack
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                text = { Text("নতুন কাস্টমার") },
                modifier = Modifier.testTag("customers_add_fab")
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
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("নাম, আইডি, মোবাইল, ঠিকানা বা ডাকনাম খুঁজুন") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "পরিষ্কার করুন")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("customers_search_input")
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CustomerFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = activeFilter == filter,
                        onClick = { activeFilter = filter },
                        label = { Text(filter.labelBn) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            if (filtered.isEmpty()) {
                EmptyLedgerState(
                    title = "কোনো কাস্টমার পাওয়া যায়নি",
                    subtitle = "নতুন কাস্টমার যোগ করুন অথবা অনুসন্ধান পরিবর্তন করুন।",
                    actionLabel = "নতুন কাস্টমার যোগ করুন",
                    onAction = { showCreateDialog = true },
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { c ->
                        CustomerCardRow(
                            customer = c,
                            currencySymbol = settings.currencySymbol,
                            onClick = { onSelectCustomer(c.id) },
                            onEdit = { editingCustomer = c }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        QuickCreateCustomerDialog(
            showOpeningBalance = true,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, addr, mob, nick, notes, opening ->
                onCreateCustomer(name, addr, mob, nick, notes, opening)
                showCreateDialog = false
            }
        )
    }

    editingCustomer?.let { target ->
        EditCustomerDialog(
            customer = target,
            onDismiss = { editingCustomer = null },
            onSave = { name, addr, mob, nick, notes ->
                onUpdateCustomer(target.id, name, addr, mob, nick, notes)
                editingCustomer = null
            }
        )
    }
}

@Composable
private fun CustomerCardRow(
    customer: CustomerEntity,
    currencySymbol: String,
    onClick: () -> Unit,
    onEdit: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = customer.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = customer.customerCode,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (customer.nickname.isNotBlank()) {
                    Text(
                        text = "ডাকনাম: ${customer.nickname}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "${customer.address}${if (customer.mobile.isNotBlank()) " • ${customer.mobile}" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                customer.lastTransactionDate?.let {
                    Text(
                        text = "সর্বশেষ লেনদেন: ${CurrencyUtils.formatDateBn(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = CurrencyUtils.formatBdt(customer.currentBalancePaisa, currencySymbol),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (customer.currentBalancePaisa > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (customer.currentBalancePaisa > 0) "বকেয়া" else "পরিশোধিত",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "তথ্য সম্পাদনা")
            }
        }
    }
}

@Composable
fun EditCustomerDialog(
    customer: CustomerEntity,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(customer.name) }
    var address by remember { mutableStateOf(customer.address) }
    var mobile by remember { mutableStateOf(customer.mobile) }
    var nickname by remember { mutableStateOf(customer.nickname) }
    var notes by remember { mutableStateOf(customer.notes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("কাস্টমার সম্পাদনা (${customer.customerCode})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("নাম *") }, singleLine = true)
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("ঠিকানা *") }, singleLine = true)
                OutlinedTextField(value = mobile, onValueChange = { mobile = it }, label = { Text("মোবাইল") }, singleLine = true)
                OutlinedTextField(value = nickname, onValueChange = { nickname = it }, label = { Text("ডাকনাম") }, singleLine = true)
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("নোট") }, maxLines = 2)
                Text(
                    text = "নোট: নাম বা ঠিকানা পরিবর্তন করলেও কাস্টমারের সকল লেনদেনের ইতিহাস অক্ষুণ্ণ থাকবে।",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, address, mobile, nickname, notes) }) {
                Text("আপডেট করুন")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("বাতিল") }
        }
    )
}
