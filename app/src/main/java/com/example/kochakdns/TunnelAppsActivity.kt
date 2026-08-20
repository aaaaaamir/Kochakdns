package com.example.kochakdns

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
//  آیتم نمایشی هر ردیف (معادل AppListItem + checked در نسخه Compose)
// ===================================================================
private data class AppItem(
    val app: AppInfo,
    val checked: Boolean
)

// ===================================================================
//  ViewModel — معادل PerAppProxyViewModel در v2rayNG
// ===================================================================
class TunnelAppsViewModel(application: Application) : AndroidViewModel(application) {

    // وضعیت بارگذاری
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // پیام خطا (یک‌بار مصرف؛ Activity بعد از نمایش clearError می‌کند)
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // کل لیست برنامه‌ها (بدون فیلتر)
    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    // برنامه‌های انتخاب‌شده (معادل blacklist در v2rayNG)
    private val _selectedPackages = MutableStateFlow<Set<String>>(emptySet())
    val selectedPackages: StateFlow<Set<String>> = _selectedPackages.asStateFlow()

    // عبارت جستجو
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    // لیست نمایشی (فیلترشده)
    private val _displayedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val displayedApps: StateFlow<List<AppInfo>> = _displayedApps.asStateFlow()

    // کش لیست کامل برای فیلتر (مثل appsAll در v2rayNG)
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
                val list = withContext(Dispatchers.IO) { loadNetworkAppList(context) }

                // null یعنی هنوز چیزی ذخیره نشده → پیش‌فرض: همه برنامه‌ها انتخاب باشند
                // (همان منطق قدیمی: selectedSet?.contains(...) ?: true)
                val stored = loadStoredSelection()
                _selectedPackages.value = stored ?: list.map { it.packageName }.toSet()

                val sorted = withContext(Dispatchers.Default) { sortApps(list) }
                appsAll = sorted
                _apps.value = sorted
                _displayedApps.value = applyFilter(_query.value)
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
        val newSet = if (packageName in current) current - packageName else current + packageName
        replaceSelected(newSet)
    }

    fun selectAll() {
        val displayed = _displayedApps.value.map { it.packageName }.toSet()
        replaceSelected(_selectedPackages.value + displayed)
    }

    fun clearAll() {
        val displayed = _displayedApps.value.map { it.packageName }.toSet()
        replaceSelected(_selectedPackages.value - displayed)
    }

    private fun replaceSelected(newSet: Set<String>) {
        if (newSet == _selectedPackages.value) return
        _selectedPackages.value = newSet
        persistSelection(newSet)
    }

    /** ذخیره فوری (مثل replaceBlacklist در v2rayNG که بلافاصله در MMKV می‌نویسد) */
    private fun persistSelection(newSet: Set<String>) {
        val context = getApplication<Application>()
        val all = appsAll
        if (all != null && newSet.containsAll(all.map { it.packageName })) {
            // همه انتخاب شده = حالت پیش‌فرض (همان منطق saveAndFinish قدیمی)
            TunnelAppsStore.clearSelection(context)
        } else {
            TunnelAppsStore.saveSelectedPackages(context, newSet)
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

    private fun applyFilter(query: String): List<AppInfo> {
        val apps = appsAll ?: return emptyList()
        if (query.isEmpty()) return apps
        return apps.filter {
            it.appName.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
        }
    }

    /** مرتب‌سازی مثل v2rayNG: انتخاب‌شده‌ها اول، غیرسیستمی قبل از سیستمی، سپس حروف الفبا */
    private fun sortApps(apps: List<AppInfo>): List<AppInfo> {
        val collator = Collator.getInstance()
        val selected = _selectedPackages.value
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
     * فقط برنامه‌های دارای دسترسی اینترنت (دقیقاً مثل AppManagerUtil.loadNetworkAppList در v2rayNG).
     * اگر می‌خواهی همه برنامه‌ها نمایش داده شوند، کافیست شرط checkPermission را حذف کنی.
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

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppsAdapter
    private lateinit var headerSpinner: ProgressBar
    private lateinit var countText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0F0F14"))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // هدر
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), dpToPx(24), dpToPx(12), dpToPx(8))
        }

        // دکمه بازگشت
        header.addView(TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { saveAndFinish() }
        })

        // عنوان صفحه
        header.addView(TextView(this).apply {
            text = "برنامه‌های تونل شده"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dpToPx(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        // لودینگ دایره‌ای
        headerSpinner = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(22), dpToPx(22)).apply {
                marginEnd = dpToPx(12)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#4C8DFF"))
            }
            visibility = View.GONE
        }
        header.addView(headerSpinner)

        column.addView(header)

        // شمارنده برنامه‌ها
        countText = TextView(this).apply {
            text = "در حال دریافت لیست برنامه‌ها..."
            setTextColor(Color.parseColor("#888888"))
            textSize = 12f
            setPadding(dpToPx(16), 0, dpToPx(16), dpToPx(12))
        }
        column.addView(countText)

        // دکمه‌های انتخاب همه / لغو همه
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(16), 0, dpToPx(16), dpToPx(12))
        }
        actionsRow.addView(actionButton("انتخاب همه") { viewModel.selectAll() })
        actionsRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(12), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        actionsRow.addView(actionButton("لغو همه") { viewModel.clearAll() })
        column.addView(actionsRow)

        adapter = AppsAdapter { packageName -> viewModel.toggle(packageName) }
        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TunnelAppsActivity)
            adapter = this@TunnelAppsActivity.adapter
            setPadding(dpToPx(16), 0, dpToPx(16), dpToPx(24))
            clipToPadding = false
        }
        column.addView(recyclerView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        root.addView(column)
        setContentView(root)

        viewModel.loadApps()
        observeViewModel()
    }

    /** معادل collectAsStateWithLifecycle در Compose */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.displayedApps,
                    viewModel.selectedPackages,
                    viewModel.isLoading,
                    viewModel.errorMessage
                ) { apps, selected, loading, error ->
                    UiState(apps.map { AppItem(it, it.packageName in selected) }, loading, error)
                }.collect { state -> render(state) }
            }
        }
    }

    private data class UiState(
        val items: List<AppItem>,
        val loading: Boolean,
        val error: String?
    )

    private fun render(state: UiState) {
        headerSpinner.visibility = if (state.loading) View.VISIBLE else View.GONE

        state.error?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }

        adapter.submitList(state.items)

        val total = state.items.size
        val selected = state.items.count { it.checked }
        countText.text = when {
            state.loading && total == 0 -> "در حال دریافت لیست برنامه‌ها..."
            total == 0 -> "برنامه‌ای یافت نشد"
            else -> "$selected از $total برنامه انتخاب شده"
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        saveAndFinish()
    }

    private fun saveAndFinish() {
        // چون هر تغییر بلافاصله در ViewModel ذخیره می‌شود، فقط Toast و خروج
        Toast.makeText(this, "ذخیره شد", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun actionButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#4C8DFF"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8))
            isClickable = true
            isFocusable = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E1E2E"))
                    cornerRadius = dpToPx(8).toFloat()
                    setStroke(dpToPx(1), Color.parseColor("#2A2A3E"))
                }
            }
            setOnClickListener { onClick() }
        }
    }
}

// ===================================================================
//  Adapter — معادل ListAdapter + DiffUtil = نسخه XML از items(key = ...) در Compose
// ===================================================================
private class AppsAdapter(
    private val onToggle: (String) -> Unit
) : ListAdapter<AppItem, AppsAdapter.AppViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppItem>() {
            override fun areItemsTheSame(oldItem: AppItem, newItem: AppItem): Boolean =
                oldItem.app.packageName == newItem.app.packageName

            override fun areContentsTheSame(oldItem: AppItem, newItem: AppItem): Boolean =
                oldItem == newItem
        }

        private fun dp(context: Context, dp: Int): Int =
            (dp * context.resources.displayMetrics.density).toInt()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val rowView = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(parent.context, 12), dp(parent.context, 10), dp(parent.context, 12), dp(parent.context, 10))
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(parent.context, 8)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A1A24"))
                    cornerRadius = dp(parent.context, 8).toFloat()
                }
            }
            isClickable = true
            isFocusable = true
        }

        // به جای آیکون، حرف اول اسم برنامه (v2rayNG هم آیکون را null پاس می‌دهد)
        val monogram = TextView(parent.context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(dp(parent.context, 44), dp(parent.context, 44))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#4C8DFF"))
                    cornerRadius = dp(parent.context, 22).toFloat()
                }
            }
        }

        val labelColumn = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(parent.context, 12)
                marginEnd = dp(parent.context, 8)
            }
        }

        val titleText = TextView(parent.context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
        }

        val systemText = TextView(parent.context).apply {
            text = "سیستمی"
            setTextColor(Color.parseColor("#666666"))
            textSize = 10f
            setPadding(0, dp(parent.context, 2), 0, 0)
        }

        labelColumn.addView(titleText)
        labelColumn.addView(systemText)

        val checkBox = CheckBox(parent.context)

        rowView.addView(monogram)
        rowView.addView(labelColumn)
        rowView.addView(checkBox)

        return AppViewHolder(rowView, monogram, titleText, systemText, checkBox)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val item = getItem(position)
        val app = item.app

        holder.monogram.text = if (app.appName.isNotEmpty()) app.appName.take(1).uppercase() else "؟"
        holder.titleText.text = app.appName
        holder.systemText.visibility = if (app.isSystemApp) View.VISIBLE else View.GONE

        holder.checkBox.setOnCheckedChangeListener(null)
        holder.checkBox.isChecked = item.checked

        holder.itemView.setOnClickListener { onToggle(app.packageName) }
        holder.checkBox.setOnCheckedChangeListener { _, _ -> onToggle(app.packageName) }
    }

    class AppViewHolder(
        itemView: View,
        val monogram: TextView,
        val titleText: TextView,
        val systemText: TextView,
        val checkBox: CheckBox
    ) : RecyclerView.ViewHolder(itemView)
}
