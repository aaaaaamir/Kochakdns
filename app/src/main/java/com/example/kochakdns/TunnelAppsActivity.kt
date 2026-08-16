package com.example.kochakdns

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TunnelAppsActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var countText: TextView
    private val checkBoxes = mutableMapOf<String, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0F0F14"))
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // هدر
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 56, 24, 16)
        }
        header.addView(TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(24, 16, 24, 16)
            isClickable = true
            isFocusable = true
            setOnClickListener { saveAndFinish() }
        })
        header.addView(TextView(this).apply {
            text = "برنامه‌های تونل شده"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(16, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        column.addView(header)

        countText = TextView(this).apply {
            setTextColor(Color.parseColor("#888888"))
            textSize = 12f
            setPadding(24, 0, 24, 16)
        }
        column.addView(countText)

        // انتخاب همه / لغو همه
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 0, 24, 16)
        }
        actionsRow.addView(actionButton("انتخاب همه") { setAll(true) })
        actionsRow.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(16, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        actionsRow.addView(actionButton("لغو همه") { setAll(false) })
        column.addView(actionsRow)

        loadingSpinner = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                topMargin = 80
            }
        }
        val spinnerWrap = FrameLayout(this)
        spinnerWrap.addView(loadingSpinner)
        column.addView(spinnerWrap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(this)
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 24, 32)
        }
        scroll.addView(listContainer)
        column.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(column)
        setContentView(root)

        loadApps()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        saveAndFinish()
    }

    private fun actionButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#4C8DFF"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(28, 16, 28, 16)
            isClickable = true
            isFocusable = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E1E2E"))
                    cornerRadius = 16f
                    setStroke(2, Color.parseColor("#2A2A3E"))
                }
            }
            setOnClickListener { onClick() }
        }
    }

    private fun setAll(checked: Boolean) {
        checkBoxes.values.forEach { it.isChecked = checked }
        updateCount()
    }

    private fun updateCount() {
        val total = checkBoxes.size
        val selected = checkBoxes.values.count { it.isChecked }
        countText.text = "$selected از $total برنامه انتخاب شده"
    }

    private fun loadApps() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            // getInstalledApplications با فلگ صفر هم برنامه‌های عادی هم سیستمی
            // رو برمی‌گردونه؛ روی اندروید ۱۱ به بالا برای دیدن کامل لیست به
            // مجوز QUERY_ALL_PACKAGES توی مانیفست نیاز داره، وگرنه لیست
            // تقریباً خالی برمی‌گرده (بدون خطا، فقط ساکت فیلتر می‌شه).
            val apps = try {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            } catch (e: Exception) {
                emptyList()
            }
            val selected = TunnelAppsStore.getSelectedPackages(this@TunnelAppsActivity)
            // sortedBy رو گارد می‌کنیم: اگه گرفتن لیبل حتی یک برنامه throw کنه،
            // قبلاً کل مرتب‌سازی (و در نتیجه کل لیست) از کار می‌افتاد.
            val sorted = try {
                apps.sortedBy { safeLabel(pm, it).lowercase() }
            } catch (e: Exception) {
                apps
            }

            withContext(Dispatchers.Main) {
                loadingSpinner.visibility = android.view.View.GONE
                sorted.forEach { appInfo ->
                    try {
                        val isChecked = selected?.contains(appInfo.packageName) ?: true // null = پیش‌فرض همه انتخاب
                        listContainer.addView(buildAppRow(pm, appInfo, isChecked))
                    } catch (_: Exception) {
                        // یک برنامه مشکل داشت (مثلاً آیکون/لیبلش قابل خوندن نبود)؛
                        // به‌جای متوقف کردن کل لیست، فقط همینو رد می‌کنیم.
                    }
                }
                updateCount()
                if (sorted.isEmpty()) {
                    listContainer.addView(TextView(this@TunnelAppsActivity).apply {
                        text = "هیچ برنامه‌ای پیدا نشد. اگه روی اندروید ۱۱ به بالایی، مطمئن شو\nQUERY_ALL_PACKAGES توی AndroidManifest.xml اضافه شده."
                        setTextColor(Color.parseColor("#888888"))
                        textSize = 13f
                        setPadding(16, 48, 16, 16)
                        gravity = Gravity.CENTER
                    })
                }
            }
        }
    }

    private fun safeLabel(pm: PackageManager, appInfo: ApplicationInfo): String {
        return try { pm.getApplicationLabel(appInfo).toString() } catch (_: Exception) { appInfo.packageName }
    }

    private fun buildAppRow(pm: PackageManager, appInfo: ApplicationInfo, initialChecked: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 20, 16, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A1A24"))
                    cornerRadius = 18f
                }
            }
            isClickable = true
            isFocusable = true

            val icon = ImageView(this@TunnelAppsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(72, 72)
                try {
                    setImageDrawable(pm.getApplicationIcon(appInfo))
                } catch (_: Exception) {
                }
            }

            val labelColumn = LinearLayout(this@TunnelAppsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 20
                    marginEnd = 12
                }
            }
            labelColumn.addView(TextView(this@TunnelAppsActivity).apply {
                text = safeLabel(pm, appInfo)
                setTextColor(Color.WHITE)
                textSize = 14f
            })
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystemApp) {
                labelColumn.addView(TextView(this@TunnelAppsActivity).apply {
                    text = "سیستمی"
                    setTextColor(Color.parseColor("#666666"))
                    textSize = 10f
                    setPadding(0, 4, 0, 0)
                })
            }

            val checkBox = CheckBox(this@TunnelAppsActivity).apply {
                isChecked = initialChecked
            }
            checkBoxes[appInfo.packageName] = checkBox

            setOnClickListener {
                checkBox.isChecked = !checkBox.isChecked
                updateCount()
            }
            checkBox.setOnCheckedChangeListener { _, _ -> updateCount() }

            addView(icon)
            addView(labelColumn)
            addView(checkBox)
        }
    }

    private fun saveAndFinish() {
        val allSelected = checkBoxes.values.all { it.isChecked }
        if (allSelected) {
            // اگه همه انتخابن، همون حالت پیش‌فرض (بدون محدودیت) رو ذخیره کن
            TunnelAppsStore.clearSelection(this)
        } else {
            val selected = checkBoxes.filterValues { it.isChecked }.keys
            TunnelAppsStore.saveSelectedPackages(this, selected)
        }
        Toast.makeText(this, "ذخیره شد", Toast.LENGTH_SHORT).show()
        finish()
    }
}
