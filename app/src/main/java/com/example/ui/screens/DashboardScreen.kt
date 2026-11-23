package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.AppSettingsEntity
import com.example.domain.AlertSeverity
import com.example.domain.DashboardSummary
import com.example.ui.components.QuickCreateCustomerDialog
import com.example.ui.navigation.NavRoutes
import com.example.util.CurrencyUtils

private data class QuickActionItem(
    val label: String,
    val icon: ImageVector,
    val containerColor: Color,
    val contentColor: Color,
    val testTag: String,
    val onClick: () -> Unit
)

@Composable
fun DashboardScreen(
    summary: DashboardSummary,
    settings: AppSettingsEntity,
    onNavigate: (String) -> Unit,
    onCreateCustomer: (
        name: String,
        address: String,
        mobile: String,
        nickname: String,
        notes: String,
        openingBalancePaisa: Long
    ) -> Unit,
    onLockApp: () -> Unit
) {
    var showCreateCustomer by remember { mutableStateOf(false) }
    var alertsExpanded by remember { mutableStateOf(true) }
    val cur = settings.currencySymbol

    val quickActions = listOf(
        QuickActionItem(
            "নতুন কাস্টমার", Icons.Default.PersonAdd,
            MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
            "qa_new_customer"
        ) { showCreateCustomer = true },
        QuickActionItem(
            "দ্রুত জমা", Icons.Default.Payments,
            Color(0xFFD1FAE5), Color(0xFF065F46),
            "qa_quick_payment"
        ) { onNavigate(NavRoutes.quickPayment()) },
        QuickActionItem(
            "দ্রুত বাকি", Icons.Default.AddCard,
            MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer,
            "qa_quick_credit"
        ) { onNavigate(NavRoutes.quickCredit()) },
        QuickActionItem(
            "নতুন বিক্রয় মেমো", Icons.AutoMirrored.Filled.ReceiptLong,
            MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer,
            "qa_new_memo"
        ) { onNavigate(NavRoutes.salesMemo()) },
        QuickActionItem(
            "খরচ যোগ", Icons.Default.MoneyOff,
            Color(0xFFFEE2E2), Color(0xFF991B1B),
            "qa_add_expense"
        ) { onNavigate(NavRoutes.EXPENSES) },
        QuickActionItem(
            "কাস্টমার তালিকা", Icons.Default.Groups,
            MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant,
            "qa_customer_list"
        ) { onNavigate(NavRoutes.CUSTOMERS) },
        QuickActionItem(
            "অস্থায়ী খাতা", Icons.Default.Badge,
            Color(0xFFE0E7FF), Color(0xFF1E3A8A),
            "qa_temp_ledger"
        ) { onNavigate(NavRoutes.TEMP_CUSTOMERS) },
        QuickActionItem(
            "ক্যাশবক্স", Icons.Default.AccountBalanceWallet,
            Color(0xFFCCFBF1), Color(0xFF115E59),
            "qa_cashbox"
        ) { onNavigate(NavRoutes.CASHBOX) },
        QuickActionItem(
            "দৈনিক রিপোর্ট", Icons.Default.Today,
            Color(0xFFFEF3C7), Color(0xFF92400E),
            "qa_daily_report"
        ) { onNavigate(NavRoutes.DAILY_REPORT) },
        QuickActionItem(
            "মাসিক রিপোর্ট", Icons.Default.Assessment,
            Color(0xFFE0F2FE), Color(0xFF075985),
            "qa_monthly_report"
        ) { onNavigate(NavRoutes.MONTHLY_REPORT) },
        QuickActionItem(
            "হালখাতা", Icons.Default.MenuBook,
            Color(0xFFFFE4E6), Color(0xFF9F1239),
            "qa_hal_khata"
        ) { onNavigate(NavRoutes.HAL_KHATA) },
        QuickActionItem(
            "প্রিন্ট ও পিডিএফ", Icons.Default.PictureAsPdf,
            Color(0xFFF3E8FF), Color(0xFF581C87),
            "qa_print_pdf"
        ) { onNavigate(NavRoutes.PRINT_PDF) },
        QuickActionItem(
            "ব্যাকআপ ও রিস্টোর", Icons.Default.Backup,
            Color(0xFFDCFCE7), Color(0xFF14532D),
            "qa_backup_restore"
        ) { onNavigate(NavRoutes.BACKUP_RESTORE) }
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            bottom = 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Hero Header Banner
        item {
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Image(
                        painter = painterResource(id = R.drawable.img_halkhata_banner),
                        contentDescription = "দোকানের ব্যানার",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(118.dp),
                        contentScale = ContentScale.Crop
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(118.dp)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xE60B6E4F),
                                        Color(0xBA0B6E4F),
                                        Color(0x660B6E4F)
                                    )
                                )
                            )
                            .padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.align(Alignment.CenterStart).fillMaxWidth(0.76f)
                        ) {
                            Text(
                                text = settings.shopName.ifBlank { "বাকির খাতা" },
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = summary.activeHalKhata?.name ?: "চলতি হিসাব কাল",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFD2F4E5)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "আজ: ${CurrencyUtils.formatDateBn(System.currentTimeMillis())} (${CurrencyUtils.weekdayBn(System.currentTimeMillis())})",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                        Row(
                            modifier = Modifier.align(Alignment.TopEnd),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (settings.pinHash.isNotBlank()) {
                                FilledIconButton(
                                    onClick = onLockApp,
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = Color.White.copy(alpha = 0.2f),
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(Icons.Default.Lock, contentDescription = "লক করুন", modifier = Modifier.size(20.dp))
                                }
                            }
                            FilledIconButton(
                                onClick = { onNavigate(NavRoutes.SETTINGS) },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = Color.White.copy(alpha = 0.2f),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.size(40.dp).testTag("dashboard_settings_button")
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = "সেটিংস", modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }

        // 2. Automatic Alerts Strip
        if (summary.alerts.isNotEmpty()) {
            item {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { alertsExpanded = !alertsExpanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "সতর্কতা ও তাগাদা (${CurrencyUtils.toBengaliDigits(summary.alerts.size.toString())})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Icon(
                                imageVector = if (alertsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null
                            )
                        }
                        AnimatedVisibility(visible = alertsExpanded) {
                            Column(
                                modifier = Modifier.padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                summary.alerts.forEach { alert ->
                                    val bg = when (alert.severity) {
                                        AlertSeverity.CRITICAL -> MaterialTheme.colorScheme.errorContainer
                                        AlertSeverity.WARNING -> MaterialTheme.colorScheme.secondaryContainer
                                        AlertSeverity.INFO -> MaterialTheme.colorScheme.surface
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = bg,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = alert.actionRoute != null) {
                                                alert.actionRoute?.let(onNavigate)
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(alert.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                                Text(alert.message, style = MaterialTheme.typography.bodySmall)
                                            }
                                            if (alert.actionRoute != null) {
                                                Icon(Icons.Default.ChevronRight, contentDescription = null)
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

        // 3. Total Receivable & Cashbox Key Balances
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigate(NavRoutes.CUSTOMERS) },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("মোট পাওনা / বকেয়া", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = CurrencyUtils.formatBdt(summary.totalOutstandingDebtPaisa, cur),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "বাকি: ${CurrencyUtils.toBengaliDigits(summary.customersWithDebt.toString())} • শূন্য: ${CurrencyUtils.toBengaliDigits(summary.customersWithZeroBalance.toString())}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigate(NavRoutes.CASHBOX) },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("বর্তমান ক্যাশবক্স", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = CurrencyUtils.formatBdt(summary.currentCashboxBalancePaisa, cur),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "+${CurrencyUtils.formatBdt(summary.todayCashInflowPaisa, cur)} / -${CurrencyUtils.formatBdt(summary.todayCashOutflowPaisa, cur)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // 4. Today's Complete Financial Summary Card
        item {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("আজকের হিসাব সারসংক্ষেপ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "লেনদেন: ${CurrencyUtils.toBengaliDigits(summary.todayTransactionCount.toString())} টি",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DashboardMiniStat("আজকের মোট বিক্রি", CurrencyUtils.formatBdt(summary.todayTotalSalesPaisa, cur), Modifier.weight(1f))
                        DashboardMiniStat("নগদ বিক্রি", CurrencyUtils.formatBdt(summary.todayCashSalesPaisa, cur), Modifier.weight(1f))
                        DashboardMiniStat("বাকি বিক্রি", CurrencyUtils.formatBdt(summary.todayCreditSalesPaisa, cur), Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DashboardMiniStat("আজকের জমা আদায়", CurrencyUtils.formatBdt(summary.todayCustomerPaymentsPaisa, cur), Modifier.weight(1f), valueColor = MaterialTheme.colorScheme.primary)
                        DashboardMiniStat("নতুন বাকি যোগ", CurrencyUtils.formatBdt(summary.todayNewlyAddedDebtPaisa, cur), Modifier.weight(1f), valueColor = MaterialTheme.colorScheme.error)
                        DashboardMiniStat("আজকের খরচ", CurrencyUtils.formatBdt(summary.todayExpensesPaisa, cur), Modifier.weight(1f))
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "মোট কাস্টমার: ${CurrencyUtils.toBengaliDigits(summary.totalCustomers.toString())} জন",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = summary.lastTransaction?.let {
                                    "সর্বশেষ লেনদেন: ${it.title} (${CurrencyUtils.formatBdt(it.amountPaisa, cur)})"
                                } ?: "আজ এখনো কোনো লেনদেন হয়নি",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "সর্বশেষ ব্যাকআপ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = summary.lastBackupAt?.let { CurrencyUtils.formatDateTimeBn(it) } ?: "নেই",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (summary.lastBackupStatus == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        // 5. All 13 Quick Action Buttons in a clean 3-column grid
        item {
            Text(
                text = "দ্রুত কার্যক্রম (Quick Actions)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                quickActions.chunked(3).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowItems.forEach { item ->
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 90.dp)
                                    .testTag(item.testTag)
                                    .clickable { item.onClick() },
                                shape = MaterialTheme.shapes.medium,
                                color = item.containerColor,
                                tonalElevation = 1.dp
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 6.dp, vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(item.contentColor.copy(alpha = 0.12f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = item.icon,
                                            contentDescription = item.label,
                                            tint = item.contentColor,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = item.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = item.contentColor,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        repeat(3 - rowItems.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // 6. Additional Tools Row (Debt Aging, Reminders, Audit Log, Settings)
        item {
            Text(
                text = "বিশ্লেষণ ও নিরাপত্তা",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondaryToolChip("বকেয়ার বয়স", Icons.Default.HourglassBottom, Modifier.weight(1f)) {
                    onNavigate(NavRoutes.DEBT_AGING)
                }
                SecondaryToolChip("তাগাদা", Icons.Default.Notifications, Modifier.weight(1f)) {
                    onNavigate(NavRoutes.REMINDERS)
                }
                SecondaryToolChip("অডিট লগ", Icons.Default.History, Modifier.weight(1f)) {
                    onNavigate(NavRoutes.AUDIT_LOGS)
                }
                SecondaryToolChip("সেটিংস", Icons.Default.Tune, Modifier.weight(1f)) {
                    onNavigate(NavRoutes.SETTINGS)
                }
            }
        }
    }

    if (showCreateCustomer) {
        QuickCreateCustomerDialog(
            showOpeningBalance = true,
            onDismiss = { showCreateCustomer = false },
            onCreate = { name, addr, mob, nick, notes, opening ->
                onCreateCustomer(name, addr, mob, nick, notes, opening)
                showCreateCustomer = false
            }
        )
    }
}

@Composable
private fun DashboardMiniStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SecondaryToolChip(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}
