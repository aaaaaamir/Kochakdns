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
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppsAdapter
    private lateinit var headerSpinner: ProgressBar
    private lateinit var countText: TextView

    private var isProcessStarted = false

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

        // لودینگ دایره‌ای گوگل
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
        actionsRow.addView(actionButton("انتخاب همه") { setAll(true) })
        actionsRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(12), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        actionsRow.addView(actionButton("لغو همه") { setAll(false) })
        column.addView(actionsRow)

        adapter = AppsAdapter { updateCount() }
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
        adapter.selectAll(checked)
        updateCount()
    }

    private fun updateCount() {
        val total = adapter.itemCount
        val selected = adapter.getSelectedCount()
        countText.text = "$selected از $total برنامه انتخاب شده"
    }

    private fun loadAppsProgressively() {
        headerSpinner.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pm = packageManager
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

                val selectedSet = try {
                    TunnelAppsStore.getSelectedPackages(this@TunnelAppsActivity)
                } catch (_: Exception) {
                    null
                }

                // ساخت کل لیست یک‌باره روی رشته پس‌زمینه
                val models = apps
                    .map { appInfo ->
                        val icon = try { pm.getApplicationIcon(appInfo) } catch (_: Exception) { null }
                        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        AppModel(appInfo.packageName, safeLabel(pm, appInfo), icon, isSystem)
                    }
                    .sortedBy { it.label.lowercase() }
                    .map { it to (selectedSet?.contains(it.packageName) ?: true) }

                withContext(Dispatchers.Main) {
                    adapter.submitAll(models)
                    headerSpinner.visibility = View.GONE
                    updateCount()
                    if (models.isEmpty()) {
                        countText.text = "برنامه‌ای یافت نشد"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    headerSpinner.visibility = View.GONE
                    countText.text = "خطا در دریافت لیست برنامه‌ها"
                    Toast.makeText(this@TunnelAppsActivity, "خطا: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun safeLabel(pm: PackageManager, appInfo: ApplicationInfo): String {
        return try { pm.getApplicationLabel(appInfo).toString() } catch (_: Exception) { appInfo.packageName }
    }

    private fun saveAndFinish() {
        val totalCount = adapter.itemCount
        val selectedCount = adapter.getSelectedCount()

        if (totalCount == selectedCount) {
            TunnelAppsStore.clearSelection(this)
        } else {
            val selected = adapter.getSelectedPackages()
            TunnelAppsStore.saveSelectedPackages(this, selected)
        }
        Toast.makeText(this, "ذخیره شد", Toast.LENGTH_SHORT).show()
        finish()
    }

    private inner class AppsAdapter(
        private val onItemCheckChanged: () -> Unit
    ) : RecyclerView.Adapter<AppViewHolder>() {

        private val items = mutableListOf<AppModel>()
        private val selectedPackages = mutableSetOf<String>()

        fun submitAll(newItems: List<Pair<AppModel, Boolean>>) {
            items.clear()
            selectedPackages.clear()
            newItems.forEach { (app, checked) ->
                items.add(app)
                if (checked) selectedPackages.add(app.packageName)
            }
            notifyDataSetChanged()
        }

        fun addApp(app: AppModel, isChecked: Boolean) {
            items.add(app)
            if (isChecked) {
                selectedPackages.add(app.packageName)
            }
            notifyItemInserted(items.size - 1)
        }

        fun clear() {
            items.clear()
            selectedPackages.clear()
            notifyDataSetChanged()
        }

        fun selectAll(checked: Boolean) {
            selectedPackages.clear()
            if (checked) {
                selectedPackages.addAll(items.map { it.packageName })
            }
            notifyDataSetChanged()
        }

        fun getSelectedCount(): Int = selectedPackages.size

        fun getSelectedPackages(): Set<String> = selectedPackages.toSet()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val rowView = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(8)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1A1A24"))
                        cornerRadius = dpToPx(8).toFloat()
                    }
                }
                isClickable = true
                isFocusable = true
            }

            val iconView = ImageView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(44))
            }

            val labelColumn = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dpToPx(12)
                    marginEnd = dpToPx(8)
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
                setPadding(0, dpToPx(2), 0, 0)
            }

            labelColumn.addView(titleText)
            labelColumn.addView(systemText)

            val checkBox = CheckBox(parent.context)

            rowView.addView(iconView)
            rowView.addView(labelColumn)
            rowView.addView(checkBox)

            return AppViewHolder(rowView, iconView, titleText, systemText, checkBox)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            val app = items[position]

            holder.iconView.setImageDrawable(app.icon)
            holder.titleText.text = app.label
            holder.systemText.visibility = if (app.isSystem) View.VISIBLE else View.GONE

            holder.checkBox.setOnCheckedChangeListener(null)
            val isChecked = selectedPackages.contains(app.packageName)
            holder.checkBox.isChecked = isChecked

            holder.itemView.setOnClickListener {
                val newState = !holder.checkBox.isChecked
                holder.checkBox.isChecked = newState
                if (newState) {
                    selectedPackages.add(app.packageName)
                } else {
                    selectedPackages.remove(app.packageName)
                }
                onItemCheckChanged()
            }

            holder.checkBox.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    selectedPackages.add(app.packageName)
                } else {
                    selectedPackages.remove(app.packageName)
                }
                onItemCheckChanged()
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private class AppViewHolder(
        itemView: View,
        val iconView: ImageView,
        val titleText: TextView,
        val systemText: TextView,
        val checkBox: CheckBox
    ) : RecyclerView.ViewHolder(itemView)
}
