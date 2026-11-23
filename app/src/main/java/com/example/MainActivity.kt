package com.example

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.MainViewModel
import com.example.ui.navigation.NavRoutes
import com.example.ui.screens.*
import com.example.ui.theme.BakirKhataTheme

class MainActivity : FragmentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val isLocked by viewModel.isLocked.collectAsStateWithLifecycle()

            BakirKhataTheme(themeMode = settings.themeMode) {
                if (isLocked && settings.pinHash.isNotBlank()) {
                    LockScreen(
                        shopName = settings.shopName,
                        biometricEnabled = settings.biometricEnabled,
                        onVerifyPin = { pin -> viewModel.unlockWithPin(pin) },
                        onBiometricSuccess = { viewModel.unlockWithBiometric() }
                    )
                } else {
                    BakirKhataRootScaffold(viewModel = viewModel)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegrounded()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onAppBackgrounded()
    }
}

@Composable
private fun BakirKhataRootScaffold(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val dashboardSummary by viewModel.dashboardSummary.collectAsStateWithLifecycle()
    val customers by viewModel.customers.collectAsStateWithLifecycle()
    val memos by viewModel.memos.collectAsStateWithLifecycle()
    val payments by viewModel.payments.collectAsStateWithLifecycle()
    val expenses by viewModel.expenses.collectAsStateWithLifecycle()
    val cashboxTransactions by viewModel.cashboxTransactions.collectAsStateWithLifecycle()
    val cashboxSummary by viewModel.cashboxSummary.collectAsStateWithLifecycle()
    val halKhataPeriods by viewModel.halKhataPeriods.collectAsStateWithLifecycle()
    val activeHalKhata by viewModel.activeHalKhata.collectAsStateWithLifecycle()
    val tempCustomers by viewModel.tempCustomers.collectAsStateWithLifecycle()
    val auditLogs by viewModel.auditLogs.collectAsStateWithLifecycle()
    val backupHistory by viewModel.backupHistory.collectAsStateWithLifecycle()
    val debtAgingRows by viewModel.debtAgingRows.collectAsStateWithLifecycle()
    val isSubmitting by viewModel.isSubmitting.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.messageFlow.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val topRoutes = setOf(
        NavRoutes.DASHBOARD,
        NavRoutes.CUSTOMERS,
        NavRoutes.SALES_MEMO,
        NavRoutes.DAILY_REPORT
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets.navigationBars,
        bottomBar = {
            if (currentRoute in topRoutes) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == NavRoutes.DASHBOARD,
                        onClick = {
                            navController.navigate(NavRoutes.DASHBOARD) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Default.Dashboard, contentDescription = "ড্যাশবোর্ড") },
                        label = { Text("ড্যাশবোর্ড") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == NavRoutes.CUSTOMERS,
                        onClick = {
                            navController.navigate(NavRoutes.CUSTOMERS) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Default.Groups, contentDescription = "কাস্টমার") },
                        label = { Text("কাস্টমার") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == NavRoutes.SALES_MEMO,
                        onClick = {
                            navController.navigate(NavRoutes.salesMemo()) {
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = "মেমো") },
                        label = { Text("বিক্রয় মেমো") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == NavRoutes.DAILY_REPORT,
                        onClick = {
                            navController.navigate(NavRoutes.DAILY_REPORT) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Default.Assessment, contentDescription = "রিপোর্ট") },
                        label = { Text("রিপোর্ট") }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            NavHost(
                navController = navController,
                startDestination = NavRoutes.DASHBOARD
            ) {
                composable(NavRoutes.DASHBOARD) {
                    DashboardScreen(
                        summary = dashboardSummary,
                        settings = settings,
                        onNavigate = { route -> navController.navigate(route) },
                        onCreateCustomer = { name, addr, mob, nick, notes, opening ->
                            viewModel.createCustomer(name, addr, mob, nick, notes, opening)
                        },
                        onLockApp = { viewModel.lockNow() }
                    )
                }

                composable(NavRoutes.CUSTOMERS) {
                    CustomersScreen(
                        customers = customers,
                        settings = settings,
                        onBack = { navController.popBackStack() },
                        onSelectCustomer = { id -> navController.navigate(NavRoutes.customerLedger(id)) },
                        onCreateCustomer = { name, addr, mob, nick, notes, opening ->
                            viewModel.createCustomer(name, addr, mob, nick, notes, opening)
                        },
                        onUpdateCustomer = { id, name, addr, mob, nick, notes ->
                            viewModel.updateCustomer(id, name, addr, mob, nick, notes)
                        }
                    )
                }

                composable(
                    route = NavRoutes.CUSTOMER_LEDGER,
                    arguments = listOf(navArgument("customerId") { type = NavType.LongType })
                ) { backStack ->
                    val customerId = backStack.arguments?.getLong("customerId") ?: 0L
                    val customer by viewModel.observeCustomer(customerId).collectAsStateWithLifecycle(initialValue = null)
                    val entries by viewModel.observeCustomerLedger(customerId).collectAsStateWithLifecycle(initialValue = emptyList())

                    CustomerLedgerScreen(
                        customer = customer,
                        entries = entries,
                        settings = settings,
                        pdfGenerator = viewModel.pdfGenerator,
                        onGenerateCustomerPdf = { viewModel.generateSingleCustomerLedgerPdf(it) },
                        onBack = { navController.popBackStack() },
                        onQuickPayment = { id -> navController.navigate(NavRoutes.quickPayment(id)) },
                        onQuickCredit = { id -> navController.navigate(NavRoutes.quickCredit(id)) },
                        onNewMemo = { id -> navController.navigate(NavRoutes.salesMemo(id)) },
                        onReversePayment = { id, reason -> viewModel.reversePayment(id, reason) },
                        onCorrectPayment = { id, newAmt, reason -> viewModel.correctPayment(id, newAmt, reason) },
                        onReverseCredit = { id, reason -> viewModel.reverseCredit(id, reason) },
                        onCancelMemo = { id, reason -> viewModel.cancelOrCorrectMemo(id, reason) },
                        onMessage = { viewModel.postMessage(it) }
                    )
                }

                composable(
                    route = NavRoutes.QUICK_PAYMENT,
                    arguments = listOf(navArgument("customerId") {
                        type = NavType.LongType
                        defaultValue = -1L
                    })
                ) { backStack ->
                    val argId = backStack.arguments?.getLong("customerId") ?: -1L
                    QuickPaymentScreen(
                        customers = customers,
                        initialCustomerId = argId.takeIf { it > 0L },
                        settings = settings,
                        isSubmitting = isSubmitting,
                        pdfGenerator = viewModel.pdfGenerator,
                        onCreateCustomerInline = { name, addr, mob, nick, notes, opening, onDone ->
                            viewModel.createCustomer(name, addr, mob, nick, notes, opening, onDone)
                        },
                        onConfirmPayment = { custId, amt, notes, token, onSuccess ->
                            viewModel.recordQuickPayment(custId, amt, notes, token, onSuccess)
                        },
                        onMessage = { viewModel.postMessage(it) },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(
                    route = NavRoutes.QUICK_CREDIT,
                    arguments = listOf(navArgument("customerId") {
                        type = NavType.LongType
                        defaultValue = -1L
                    })
                ) { backStack ->
                    val argId = backStack.arguments?.getLong("customerId") ?: -1L
                    QuickCreditScreen(
                        customers = customers,
                        initialCustomerId = argId.takeIf { it > 0L },
                        settings = settings,
                        isSubmitting = isSubmitting,
                        onCreateCustomerInline = { name, addr, mob, nick, notes, opening, onDone ->
                            viewModel.createCustomer(name, addr, mob, nick, notes, opening, onDone)
                        },
                        onConfirmCredit = { custId, amt, desc, notes, token, onSuccess ->
                            viewModel.recordQuickCredit(custId, amt, desc, notes, token, onSuccess)
                        },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(
                    route = NavRoutes.SALES_MEMO,
                    arguments = listOf(navArgument("customerId") {
                        type = NavType.LongType
                        defaultValue = -1L
                    })
                ) { backStack ->
                    val argId = backStack.arguments?.getLong("customerId") ?: -1L
                    SalesMemoScreen(
                        customers = customers,
                        initialCustomerId = argId.takeIf { it > 0L },
                        settings = settings,
                        customUnits = viewModel.getCustomUnits(),
                        isSubmitting = isSubmitting,
                        pdfGenerator = viewModel.pdfGenerator,
                        onCreateCustomerInline = { name, addr, mob, nick, notes, opening, onDone ->
                            viewModel.createCustomer(name, addr, mob, nick, notes, opening, onDone)
                        },
                        onCreateMemo = { type, custId, wName, wAddr, items, disc, paid, notes, token, onSuccess ->
                            viewModel.createSalesMemo(type, custId, wName, wAddr, items, disc, paid, notes, token, onSuccess)
                        },
                        onMessage = { viewModel.postMessage(it) },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(NavRoutes.TEMP_CUSTOMERS) {
                    TempCustomersScreen(
                        tempCustomers = tempCustomers,
                        settings = settings,
                        pdfGenerator = viewModel.pdfGenerator,
                        observeTempMemos = { viewModel.observeTempMemos(it) },
                        onCreateTempCustomer = { n, a, m, notes, onDone ->
                            viewModel.createTemporaryCustomer(n, a, m, notes, onDone)
                        },
                        onAddTempEntry = { tId, type, desc, total, paid, notes, onDone ->
                            viewModel.addTemporaryEntry(tId, type, desc, total, paid, notes, onDone)
                        },
                        onConvertToPermanent = { tId, addr, onDone ->
                            viewModel.convertTemporaryToPermanent(tId, addr, onDone)
                        },
                        onNavigateToPermanentLedger = { permId ->
                            navController.navigate(NavRoutes.customerLedger(permId))
                        },
                        onMessage = { viewModel.postMessage(it) },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(NavRoutes.CASHBOX) {
                    CashboxScreen(
                        summary = cashboxSummary,
                        transactions = cashboxTransactions,
                        settings = settings,
                        pdfGenerator = viewModel.pdfGenerator,
                        onAddManualEntry = { type, dir, amt, desc, notes, onDone ->
                            viewModel.addCashboxManualEntry(type, dir, amt, desc, notes, onDone)
                        },
                        onRecordActualCount = { viewModel.recordActualCashCount(it) },
                        onMessage = { viewModel.postMessage(it) },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(NavRoutes.EXPENSES) {
                    ExpensesScreen(
                        expenses = expenses,
                        settings = settings,
                        customCategories = viewModel.getCustomExpenseCategories(),
                        pdfGenerator = viewModel.pdfGenerator,
                        onAddExpense = { cat, amt, desc, notes, cash, onDone ->
                            viewModel.addExpense(cat, amt, desc, notes, cash, onDone)
                        },
                        onUpdateExpense = { id, cat, amt, desc, notes, cash, reason, onDone ->
                            viewModel.updateExpense(id, cat, amt, desc, notes, cash, reason, onDone)
                        },
                        onAddCustomCategory = { viewModel.addCustomExpenseCategory(it) },
                        onMessage = { viewModel.postMessage(it) },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(NavRoutes.DAILY_REPORT) {
                    DailyReportScreen(
                        settings = settings,
                        pdfGenerator = viewModel.pdfGenerator,
                        buildDailyReport = { date -> viewModel.buildDailyReport(date) },
                        onMessage = { viewModel.postMessage(it) },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(NavRoutes.MONTHLY_REPORT) {
                    MonthlyReportScreen(
                        settings = settings,
                        pdfGenerator = viewModel.pdfGenerator,
                        buildPeriodReport = { start, end -> viewModel.buildPeriodReport(start, end) },
                        onSelectCustomer = { id -> navController.navigate(NavRoutes.customerLedger(id)) },
                        onMessage = { viewModel.postMessage(it) },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(NavRoutes.HAL_KHATA) {
                    HalKhataScreen(
                        periods = halKhataPeriods,
                        activePeriod = activeHalKhata,
                        customers = customers,
                        settings = settings,
                        isSubmitting = isSubmitting,
                        pdfGenerator = viewModel.pdfGenerator,
                        observeBalances = { viewModel.observeHalKhataBalances(it) },
                        onUpdateActiveMeta = { n, d, s, e -> viewModel.updateActiveHalKhataMeta(n, d, s, e) },
                        onClosePeriod = { n, d, e, onDone -> viewModel.closeHalKhataPeriod(n, d, e, onDone) },
                        onMessage = { viewModel.postMessage(it) },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(NavRoutes.DEBT_AGING) {
                    DebtAgingScreen(
                        rows = debtAgingRows,
                        settings = settings,
                        onSelectCustomer = { id -> navController.navigate(NavRoutes.customerLedger(id)) },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(NavRoutes.REMINDERS) {
                    RemindersScreen(
                        rows = debtAgingRows,
                        summary = dashboardSummary,
                        settings = settings,
                        onSelectCustomer = { id -> navController.navigate(NavRoutes.customerLedger(id)) },
                        onNavigateBackup = { navController.navigate(NavRoutes.BACKUP_RESTORE) },
                        onNavigateDailyReport = { navController.navigate(NavRoutes.DAILY_REPORT) },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(NavRoutes.PRINT_PDF) {
                    PrintPdfCenterScreen(
                        customers = customers,
                        memos = memos,
                        payments = payments,
                        expenses = expenses,
                        cashboxTransactions = cashboxTransactions,
                        cashboxSummary = cashboxSummary,
                        tempCustomers = tempCustomers,
                        settings = settings,
                        pdfGenerator = viewModel.pdfGenerator,
                        buildDailyReport = { viewModel.buildDailyReport(it) },
                        buildPeriodReport = { s, e -> viewModel.buildPeriodReport(s, e) },
                        onGenerateSingleCustomerPdf = { viewModel.generateSingleCustomerLedgerPdf(it) },
                        onGenerateCustomersZip = { list, name -> viewModel.generateCustomersPdfZip(list, name) },
                        onMessage = { viewModel.postMessage(it) },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(NavRoutes.BACKUP_RESTORE) {
                    BackupRestoreScreen(
                        backupHistory = backupHistory,
                        settings = settings,
                        isSubmitting = isSubmitting,
                        backupManager = viewModel.backupManager,
                        pdfGenerator = viewModel.pdfGenerator,
                        onCreateBackupNow = { type, onDone -> viewModel.createBackupNow(type, onDone) },
                        onSetBackupFolderUri = { uri -> viewModel.updateSettings { it.copy(backupFolderUri = uri) } },
                        onSetBackupFrequency = { freq -> viewModel.updateSettings { it.copy(backupFrequency = freq) } },
                        onPreviewBackupUri = { uri, name, cb -> viewModel.previewBackupUri(uri, name, cb) },
                        onRestoreFromUri = { uri, name, cb -> viewModel.restoreFromUri(uri, name, cb) },
                        onMessage = { viewModel.postMessage(it) },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(NavRoutes.AUDIT_LOGS) {
                    AuditLogScreen(
                        logs = auditLogs,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(NavRoutes.SETTINGS) {
                    SettingsScreen(
                        settings = settings,
                        customUnits = viewModel.getCustomUnits(),
                        customCategories = viewModel.getCustomExpenseCategories(),
                        onUpdateSettings = { viewModel.updateSettings(it) },
                        onSetPin = { viewModel.setAppPin(it) },
                        onClearPin = { viewModel.clearAppPin() },
                        onAddCustomUnit = { viewModel.addCustomUnit(it) },
                        onAddCustomCategory = { viewModel.addCustomExpenseCategory(it) },
                        onVerifyIntegrity = { cb -> viewModel.verifyDatabaseIntegrity(cb) },
                        onNavigateHalKhata = { navController.navigate(NavRoutes.HAL_KHATA) },
                        onNavigateBackup = { navController.navigate(NavRoutes.BACKUP_RESTORE) },
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
