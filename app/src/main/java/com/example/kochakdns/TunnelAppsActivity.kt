package com.example.kochakdns

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
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

    private data class AppModel(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        val isSystem: Boolean
    )

    private lateinit var listContainer: LinearLayout
    private lateinit var headerSpinner: ProgressBar
    private lateinit var countText: TextView
    private val checkBoxes = mutableMapOf<String, CheckBox>()
    
    private var isProcessStarted = false

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

        // لودینگ مینی‌مال دایره‌ای گوگل در بالا سمت راست
        headerSpinner = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(22), dpToPx(22)).apply {
                marginEnd = dpToPx(12)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#4C8DFF"))
            }
            visibility = android.view.View.GONE
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
        actionsRow.addView(actionButton("انتخاب همه") { setAll(true) })
        actionsRow.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(12), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        actionsRow.addView(actionButton("لغو همه") { setAll(false) })
        column.addView(actionsRow)

        // اسکرول‌ویو برای لیست برنامه‌ها
        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), 0, dpToPx(16), dpToPx(24))
        }
        scroll.addView(listContainer)
        column.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(column)
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        if (!isProcessStarted) {
            isProcessStarted = true
            loadAppsProgressively()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        saveAndFinish()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

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

    private fun setAll(checked: Boolean) {
        checkBoxes.values.forEach { it.isChecked = checked }
        updateCount()
    }

    private fun updateCount() {
        val total = checkBoxes.size
        val selected = checkBoxes.values.count { it.isChecked }
        countText.text = "$selected از $total برنامه انتخاب شده"
    }

    private fun loadAppsProgressively() {
        headerSpinner.visibility = android.view.View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager

            val apps = try {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            } catch (e: Exception) {
                emptyList()
            }

            val selected = try {
                TunnelAppsStore.getSelectedPackages(this@TunnelAppsActivity)
            } catch (_: Exception) {
                null
            }

            val sortedApps = apps.map { appInfo ->
                Pair(appInfo, safeLabel(pm, appInfo))
            }.sortedBy { it.second.lowercase() }

            withContext(Dispatchers.Main) {
                listContainer.removeAllViews()
                checkBoxes.clear()
            }

            sortedApps.forEach { (appInfo, label) ->
                val icon = try { pm.getApplicationIcon(appInfo) } catch (_: Exception) { null }
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val appModel = AppModel(appInfo.packageName, label, icon, isSystem)

                withContext(Dispatchers.Main) {
                    val isChecked = selected?.contains(appModel.packageName) ?: true
                    listContainer.addView(buildAppRow(appModel, isChecked))
                    updateCount()
                }
            }

            withContext(Dispatchers.Main) {
                headerSpinner.visibility = android.view.View.GONE

                if (sortedApps.isEmpty()) {
                    countText.text = "برنامه‌ای یافت نشد"
                    listContainer.addView(TextView(this@TunnelAppsActivity).apply {
                        text = "هیچ برنامه‌ای پیدا نشد.\nمجوز QUERY_ALL_PACKAGES را در AndroidManifest.xml بررسی کنید."
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

    private fun buildAppRow(app: AppModel, initialChecked: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(8) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A1A24"))
                    cornerRadius = dpToPx(12).toFloat()
                }
            }
            isClickable = true
            isFocusable = true

            val iconView = ImageView(this@TunnelAppsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(44))
                app.icon?.let { setImageDrawable(it) }
            }

            val labelColumn = LinearLayout(this@TunnelAppsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dpToPx(12)
                    marginEnd = dpToPx(8)
                }
            }

            labelColumn.addView(TextView(this@TunnelAppsActivity).apply {
                text = app.label
                setTextColor(Color.WHITE)
                textSize = 14f
            })

            if (app.isSystem) {
                labelColumn.addView(TextView(this@TunnelAppsActivity).apply {
                    text = "سیستمی"
                    setTextColor(Color.parseColor("#666666"))
                    textSize = 10f
                    setPadding(0, dpToPx(2), 0, 0)
                })
            }

            val checkBox = CheckBox(this@TunnelAppsActivity).apply {
                isChecked = initialChecked
            }
            checkBoxes[app.packageName] = checkBox

            setOnClickListener {
                checkBox.isChecked = !checkBox.isChecked
                updateCount()
            }
            checkBox.setOnCheckedChangeListener { _, _ -> updateCount() }

            addView(iconView)
            addView(labelColumn)
            addView(checkBox)
        }
    }

    private fun saveAndFinish() {
        val allSelected = checkBoxes.values.all { it.isChecked }
        if (allSelected) {
            TunnelAppsStore.clearSelection(this)
        } else {
            val selected = checkBoxes.filterValues { it.isChecked }.keys
            TunnelAppsStore.saveSelectedPackages(this, selected)
        }
        Toast.makeText(this, "ذخیره شد", Toast.LENGTH_SHORT).show()
        finish()
    }
}
