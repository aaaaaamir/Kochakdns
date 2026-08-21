package com.example.kochakdns

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

// ===================================================================
//  مدل داده یک برنامه (معادل dto/AppInfo در v2rayNG)
// ===================================================================
data class AppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean
)

// ===================================================================
//  ViewModel — معادل PerAppProxyViewModel در v2rayNG
//  کل state با StateFlow نگهداری می‌شود و UI فقط آن را می‌خواند.
// ===================================================================
class TunnelAppsViewModel(application: Application) : AndroidViewModel(application) {

    // برنامه‌های انتخاب‌شده (معادل blacklist در v2rayNG)
    private val _selectedPackages = MutableStateFlow<Set<String>>(emptySet())
    val selectedPackages: StateFlow<Set<String>> = _selectedPackages.asStateFlow()

    // لیست نمایشی
    private val _displayedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val displayedApps: StateFlow<List<AppInfo>> = _displayedApps.asStateFlow()

    // وضعیت بارگذاری
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // پیام خطا (یک‌بار مصرف)
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var appsAll: List<AppInfo>? = null
    private var isAppListLoading = false

    /** فقط یک بار لیست را بارگذاری می‌کند (مثل loadApps در v2rayNG) */
    fun loadApps() {
        if (appsAll != null || isAppListLoading) return

        val context = getApplication<Application>()
        isAppListLoading = true
        _isLoading.value = true

        viewModelScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) {
                    val list = loadNetworkAppList(context)

                    // null یعنی هنوز چیزی ذخیره نشده → پیش‌فرض: همه برنامه‌ها انتخاب باشند
                    val stored = loadStoredSelection()
                    val selection = stored ?: list.map { it.packageName }.toSet()
                    _selectedPackages.value = selection

                    sortApps(list, selection)
                }
                appsAll = apps
                _displayedApps.value = apps
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "خطا در دریافت لیست برنامه‌ها"
            } finally {
                isAppListLoading = false
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun toggle(packageName: String) {
        val current = _selectedPackages.value
        val newSelection = if (packageName in current) current - packageName else current + packageName
        replaceSelection(newSelection)
    }

    fun selectAll() {
        val displayed = _displayedApps.value.map { it.packageName }.toSet()
        replaceSelection(_selectedPackages.value + displayed)
    }

    fun clearAll() {
        val displayed = _displayedApps.value.map { it.packageName }.toSet()
        replaceSelection(_selectedPackages.value - displayed)
    }

    private fun replaceSelection(newSelection: Set<String>) {
        if (newSelection == _selectedPackages.value) return
        _selectedPackages.value = newSelection
        persistSelection(newSelection)
    }

    /** ذخیره فوری (مثل replaceBlacklist در v2rayNG که بلافاصله در MMKV می‌نویسد) */
    private fun persistSelection(newSelection: Set<String>) {
        val context = getApplication<Application>()
        val all = appsAll
        if (all != null && newSelection.containsAll(all.map { it.packageName })) {
            // همه انتخاب شده = حالت پیش‌فرض (همان منطق saveAndFinish قدیمی)
            TunnelAppsStore.clearSelection(context)
        } else {
            TunnelAppsStore.saveSelectedPackages(context, newSelection)
        }
    }

    /** مقدار ذخیره‌شده از استور؛ null یعنی هنوز ذخیره‌ای انجام نشده */
    private fun loadStoredSelection(): Set<String>? {
        return try {
            TunnelAppsStore.getSelectedPackages(getApplication<Application>())
        } catch (e: Exception) {
            null
        }
    }

    /** مرتب‌سازی مثل v2rayNG: انتخاب‌شده‌ها اول، غیرسیستمی قبل از سیستمی، سپس حروف الفبا */
    private fun sortApps(apps: List<AppInfo>, selected: Set<String>): List<AppInfo> {
        val collator = Collator.getInstance()
        return apps.sortedWith { p1, p2 ->
            val s1 = p1.packageName in selected
            val s2 = p2.packageName in selected
            when {
                s1 && !s2 -> -1
                !s1 && s2 -> 1
                p1.isSystemApp && !p2.isSystemApp -> 1
                !p1.isSystemApp && p2.isSystemApp -> -1
                else -> collator.compare(p1.appName, p2.appName)
            }
        }
    }

    /**
     * فقط برنامه‌های دارای دسترسی اینترنت (مثل AppManagerUtil.loadNetworkAppList در v2rayNG).
     * اگر همه برنامه‌ها را می‌خواهی، شرط checkPermission را حذف کن.
     */
    private fun loadNetworkAppList(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val result = mutableListOf<AppInfo>()

        val installed = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            return result
        }

        for (appInfo in installed) {
            if (pm.checkPermission(Manifest.permission.INTERNET, appInfo.packageName) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                continue
            }

            val appName = try {
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                appInfo.packageName
            }
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            result.add(AppInfo(appInfo.packageName, appName, isSystem))
        }
        return result
    }
}

// ===================================================================
//  Activity — معادل PerAppProxyActivity در v2rayNG
// ===================================================================
class TunnelAppsActivity : AppCompatActivity() {

    private val viewModel by lazy {
        ViewModelProvider(this)[TunnelAppsViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        viewModel.loadApps()

        setContent {
            KochakTheme {
                val apps by viewModel.displayedApps.collectAsStateWithLifecycle()
                val selected by viewModel.selectedPackages.collectAsStateWithLifecycle()
                val loading by viewModel.isLoading.collectAsStateWithLifecycle()
                val error by viewModel.errorMessage.collectAsStateWithLifecycle()

                LaunchedEffect(error) {
                    error?.let {
                        Toast.makeText(this@TunnelAppsActivity, it, Toast.LENGTH_LONG).show()
                        viewModel.clearError()
                    }
                }

                TunnelAppsScreen(
                    apps = apps,
                    selected = selected,
                    loading = loading,
                    onBack = { finish() },
                    onToggle = { viewModel.toggle(it) },
                    onSelectAll = { viewModel.selectAll() },
                    onClearAll = { viewModel.clearAll() }
                )
            }
        }
    }
}

// ===================================================================
//  تم دارک (رنگ‌های هماهنگ با بقیه برنامه)
// ===================================================================
private val KochakColors = darkColorScheme(
    primary = Color(0xFF4C8DFF),
    onPrimary = Color.White,
    background = Color(0xFF0F0F14),
    onBackground = Color.White,
    surface = Color(0xFF1A1A24),
    onSurface = Color.White
)

@Composable
private fun KochakTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = KochakColors, content = content)
}

// ===================================================================
//  صفحه — معادل PerAppProxyScreen در v2rayNG
// ===================================================================
@Composable
private fun TunnelAppsScreen(
    apps: List<AppInfo>,
    selected: Set<String>,
    loading: Boolean,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit
) {
    val listState = rememberLazyListState()
    val total = apps.size
    val selectedCount = apps.count { it.packageName in selected }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F14))
    ) {
        // ===== نوار بالا =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "←",
                color = Color.White,
                fontSize = 22.sp,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
            Text(
                text = "برنامه‌های تونل شده",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
            )
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(22.dp)
                        .padding(end = 12.dp),
                    color = Color(0xFF4C8DFF),
                    strokeWidth = 2.dp
                )
            }
        }

        // ===== شمارنده =====
        Text(
            text = when {
                loading && total == 0 -> "در حال دریافت لیست برنامه‌ها..."
                total == 0 -> "برنامه‌ای یافت نشد"
                else -> "$selectedCount از $total برنامه انتخاب شده"
            },
            color = Color(0xFF888888),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // ===== دکمه‌های انتخاب همه / لغو همه =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            ActionChip(text = "انتخاب همه", onClick = onSelectAll)
            Spacer(modifier = Modifier.width(12.dp))
            ActionChip(text = "لغو همه", onClick = onClearAll)
        }

        // ===== لیست برنامه‌ها =====
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items = apps, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    checked = app.packageName in selected,
                    onToggle = { onToggle(app.packageName) }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun ActionChip(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF1E1E2E),
        border = BorderStroke(1.dp, Color(0xFF2A2A3E))
    ) {
        Text(
            text = text,
            color = Color(0xFF4C8DFF),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun AppRow(
    app: AppInfo,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF262636),
        border = BorderStroke(1.dp, Color(0xFF3F3F5A)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // حرف اول اسم برنامه به جای آیکون
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4C8DFF)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (app.appName.isNotEmpty()) app.appName.take(1).uppercase() else "؟",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = app.appName,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                if (app.isSystemApp) {
                    Text(
                        text = "سیستمی",
                        color = Color(0xFF8A8A9A),
                        fontSize = 10.sp
                    )
                }
            }

            Checkbox(
                checked = checked,
                onCheckedChange = null, // فقط نمایشی؛ کل ردیف با clickable عوض می‌شود
                colors = CheckboxDefaults.colors(
                    checkedColor = Color(0xFF4C8DFF),
                    uncheckedColor = Color(0xFF8A8A9A)
                )
            )
        }
    }
}
