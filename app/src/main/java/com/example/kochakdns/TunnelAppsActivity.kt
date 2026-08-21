package com.example.kochakdns

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
    val isSystemApp: Boolean,
    val icon: ImageBitmap? = null
)

// ===================================================================
//  ViewModel — معادل PerAppProxyViewModel در v2rayNG
// ===================================================================
class TunnelAppsViewModel(application: Application) : AndroidViewModel(application) {

    private val _selectedPackages = MutableStateFlow<Set<String>>(emptySet())
    val selectedPackages: StateFlow<Set<String>> = _selectedPackages.asStateFlow()

    private val _displayedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val displayedApps: StateFlow<List<AppInfo>> = _displayedApps.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

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
                // ۱) اول لیست سریع (بدون آیکون) بالا می‌آید
                val list = withContext(Dispatchers.IO) {
                    val raw = loadNetworkAppList(context)

                    // null یعنی هنوز چیزی ذخیره نشده → پیش‌فرض: همه برنامه‌ها انتخاب باشند
                    val stored = loadStoredSelection()
                    val selection = stored ?: raw.map { it.packageName }.toSet()
                    _selectedPackages.value = selection

                    sortApps(raw, selection)
                }
                appsAll = list
                _displayedApps.value = applyFilter(_query.value)

                // ۲) بعد از نمایش لیست، آیکون‌ها در پس‌زمینه لود و به‌تدریج نمایش داده می‌شوند
                loadIcons(context)
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

    fun filterApps(query: String) {
        _query.value = query
        _displayedApps.value = applyFilter(query)
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

    /** ذخیره فوری (مثل replaceBlacklist در v2rayNG) */
    private fun persistSelection(newSelection: Set<String>) {
        val context = getApplication<Application>()
        val all = appsAll
        if (all != null && newSelection.containsAll(all.map { it.packageName })) {
            TunnelAppsStore.clearSelection(context)
        } else {
            TunnelAppsStore.saveSelectedPackages(context, newSelection)
        }
    }

    private fun loadStoredSelection(): Set<String>? {
        return try {
            TunnelAppsStore.getSelectedPackages(getApplication<Application>())
        } catch (e: Exception) {
            null
        }
    }

    /** بارگذاری تدریجی آیکون‌ها در پس‌زمینه (اختیاری؛ خطا نادیده گرفته می‌شود) */
    private fun loadIcons(context: Context) {
        val snapshot = appsAll ?: return
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    snapshot.map { app ->
                        if (app.icon != null) {
                            app
                        } else {
                            val icon = loadIcon(context, app.packageName)
                            if (icon != null) app.copy(icon = icon) else app
                        }
                    }
                }
                appsAll = updated
                _displayedApps.value = applyFilter(_query.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // آیکون‌ها اختیاری‌اند؛ خطا نادیده گرفته می‌شود
            }
        }
    }

    private fun loadIcon(context: Context, packageName: String): ImageBitmap? {
        return try {
            val sizePx = (48 * context.resources.displayMetrics.density).toInt()
            val drawable = context.packageManager.getApplicationIcon(packageName)
            drawableToBitmap(drawable, sizePx).asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    private fun applyFilter(query: String): List<AppInfo> {
        val apps = appsAll ?: return emptyList()
        if (query.isEmpty()) return apps
        return apps.filter {
            it.appName.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
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

/** تبدیل Drawable به Bitmap (برای نمایش آیکون برنامه‌ها در Compose) */
private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
    val current = (drawable as? BitmapDrawable)?.bitmap
    if (current != null && current.width > 0 && current.height > 0) {
        return Bitmap.createScaledBitmap(current, sizePx, sizePx, true)
    }
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, sizePx, sizePx)
    drawable.draw(canvas)
    return bitmap
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
                val query by viewModel.query.collectAsStateWithLifecycle()

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
                    query = query,
                    onQueryChange = { viewModel.filterApps(it) },
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
//  تم دارک (مشکی، خاکستری، سفید + تاکید آبی برای موارد تعاملی)
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
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit
) {
    val listState = rememberLazyListState()
    var searchActive by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(searchActive) {
        if (searchActive) {
            focusRequester.requestFocus()
        }
    }

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
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // دکمه بازگشت (آیکون)
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "بازگشت",
                    tint = Color.White
                )
            }

            if (searchActive) {
                // فیلد جستجو
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    singleLine = true,
                    placeholder = {
                        Text("جستجو در برنامه‌ها...", color = Color(0xFF888888), fontSize = 14.sp)
                    },
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null, tint = Color(0xFF888888))
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = "پاک کردن", tint = Color(0xFF888888))
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = TextFieldDefaults.textFieldColors(
                        containerColor = Color(0xFF1E1E2E),
                        cursorColor = Color(0xFF4C8DFF),
                        textColor = Color.White,
                        placeholderColor = Color(0xFF888888),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
                // بستن جستجو
                IconButton(onClick = {
                    onQueryChange("")
                    searchActive = false
                }) {
                    Icon(Icons.Rounded.Close, contentDescription = "بستن جستجو", tint = Color.White)
                }
            } else {
                // عنوان صفحه
                Text(
                    text = "برنامه‌های تونل شده",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp)
                )
                // لودینگ
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color(0xFF4C8DFF),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                // دکمه جستجو (آیکون)
                IconButton(onClick = { searchActive = true }) {
                    Icon(Icons.Rounded.Search, contentDescription = "جستجو", tint = Color.White)
                }
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
            val icon = app.icon
            if (icon != null) {
                // آیکون واقعی برنامه (با گوشه‌های گرد)
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                // جای‌نگهدار: حرف اول اسم برنامه (تا وقتی آیکون لود شود)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
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
