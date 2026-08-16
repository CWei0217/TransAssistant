package com.example.myapplication

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*

class UsageFragment : Fragment() {

    private var themePackPopup: PopupWindow? = null
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val prefs by lazy {
        requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    }

    private val projectionManager by lazy {
        requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(requireContext(), ScreenshotService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
            }
            ContextCompat.startForegroundService(requireContext(), serviceIntent)
            requireActivity().moveTaskToBack(true)
        } else {
            Toast.makeText(requireContext(), "未获得屏幕截图权限", Toast.LENGTH_SHORT).show()
        }
    }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateStatusIndicators(requireView())
        if (Settings.canDrawOverlays(requireContext())) {
            startCapture()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_usage, container, false)
        
        view.findViewById<Button>(R.id.btn_start_capture).setOnClickListener {
            checkOverlayPermission()
        }
        view.findViewById<MaterialButton>(R.id.btn_open_logs).setOnClickListener {
            findNavController().navigate(R.id.nav_logs)
        }

        view.findViewById<Button>(R.id.btn_clear_history).setOnClickListener {
            clearHistory()
        }

        view.findViewById<MaterialButton>(R.id.btn_theme_toggle).setOnClickListener {
            ThemeAppearance.toggleLightDark(requireContext())
            requireActivity().recreate()
        }

        view.findViewById<MaterialButton>(R.id.btn_theme_accent_menu).setOnClickListener { v ->
            showThemePackMenu(v)
        }

        refreshThemeToggleLabel(view)
        updateStatusIndicators(view)
        loadHistory(view)
        return view
    }

    override fun onDestroyView() {
        themePackPopup?.dismiss()
        themePackPopup = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        view?.let {
            refreshThemeToggleLabel(it)
            updateStatusIndicators(it)
            loadHistory(it)
        }
    }

    override fun onStart() {
        super.onStart()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in setOf("ocr_provider", "ai_provider_name", "ai_model")) {
                view?.let { updateStatusIndicators(it) }
            }
        }
        prefsListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onStop() {
        prefsListener?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
        prefsListener = null
        super.onStop()
    }

    private fun showThemePackMenu(anchor: View) {
        themePackPopup?.dismiss()
        val ctx = requireContext()
        val content = layoutInflater.inflate(R.layout.popup_theme_pack, null)
        val classic = content.findViewById<MaterialCardView>(R.id.card_pack_classic)
        val inkwell = content.findViewById<MaterialCardView>(R.id.card_pack_inkwell)
        val meadow = content.findViewById<MaterialCardView>(R.id.card_pack_meadow)
        val pop = content.findViewById<MaterialCardView>(R.id.card_pack_pop)
        val velvet = content.findViewById<MaterialCardView>(R.id.card_pack_velvet)
        val flax = content.findViewById<MaterialCardView>(R.id.card_pack_flax)
        val harbor = content.findViewById<MaterialCardView>(R.id.card_pack_harbor)
        val strokePx = (3 * resources.displayMetrics.density).toInt()
        val ring = ctx.themeColor(R.attr.colorUiSurface)

        fun applySelection(selected: Int) {
            classic.strokeWidth = if (selected == ThemeColorPack.PACK_CLASSIC) strokePx else 0
            classic.strokeColor = ring
            inkwell.strokeWidth = if (selected == ThemeColorPack.PACK_INKWELL) strokePx else 0
            inkwell.strokeColor = ring
            meadow.strokeWidth = if (selected == ThemeColorPack.PACK_MEADOW) strokePx else 0
            meadow.strokeColor = ring
            pop.strokeWidth = if (selected == ThemeColorPack.PACK_POP) strokePx else 0
            pop.strokeColor = ring
            velvet.strokeWidth = if (selected == ThemeColorPack.PACK_VELVET) strokePx else 0
            velvet.strokeColor = ring
            flax.strokeWidth = if (selected == ThemeColorPack.PACK_FLAX) strokePx else 0
            flax.strokeColor = ring
            harbor.strokeWidth = if (selected == ThemeColorPack.PACK_HARBOR) strokePx else 0
            harbor.strokeColor = ring
        }
        applySelection(ThemeColorPack.currentPackId(ctx))

        classic.setOnClickListener {
            if (ThemeColorPack.currentPackId(ctx) != ThemeColorPack.PACK_CLASSIC) {
                ThemeColorPack.persistPack(ctx, ThemeColorPack.PACK_CLASSIC)
                themePackPopup?.dismiss()
                requireActivity().recreate()
            } else {
                themePackPopup?.dismiss()
            }
        }
        inkwell.setOnClickListener {
            if (ThemeColorPack.currentPackId(ctx) != ThemeColorPack.PACK_INKWELL) {
                ThemeColorPack.persistPack(ctx, ThemeColorPack.PACK_INKWELL)
                themePackPopup?.dismiss()
                requireActivity().recreate()
            } else {
                themePackPopup?.dismiss()
            }
        }
        meadow.setOnClickListener {
            if (ThemeColorPack.currentPackId(ctx) != ThemeColorPack.PACK_MEADOW) {
                ThemeColorPack.persistPack(ctx, ThemeColorPack.PACK_MEADOW)
                themePackPopup?.dismiss()
                requireActivity().recreate()
            } else {
                themePackPopup?.dismiss()
            }
        }
        pop.setOnClickListener {
            if (ThemeColorPack.currentPackId(ctx) != ThemeColorPack.PACK_POP) {
                ThemeColorPack.persistPack(ctx, ThemeColorPack.PACK_POP)
                themePackPopup?.dismiss()
                requireActivity().recreate()
            } else {
                themePackPopup?.dismiss()
            }
        }
        velvet.setOnClickListener {
            if (ThemeColorPack.currentPackId(ctx) != ThemeColorPack.PACK_VELVET) {
                ThemeColorPack.persistPack(ctx, ThemeColorPack.PACK_VELVET)
                themePackPopup?.dismiss()
                requireActivity().recreate()
            } else {
                themePackPopup?.dismiss()
            }
        }
        flax.setOnClickListener {
            if (ThemeColorPack.currentPackId(ctx) != ThemeColorPack.PACK_FLAX) {
                ThemeColorPack.persistPack(ctx, ThemeColorPack.PACK_FLAX)
                themePackPopup?.dismiss()
                requireActivity().recreate()
            } else {
                themePackPopup?.dismiss()
            }
        }
        harbor.setOnClickListener {
            if (ThemeColorPack.currentPackId(ctx) != ThemeColorPack.PACK_HARBOR) {
                ThemeColorPack.persistPack(ctx, ThemeColorPack.PACK_HARBOR)
                themePackPopup?.dismiss()
                requireActivity().recreate()
            } else {
                themePackPopup?.dismiss()
            }
        }

        val w = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        w.elevation = 12f
        themePackPopup = w
        val yOff = (6 * resources.displayMetrics.density).toInt()
        w.showAsDropDown(anchor, 0, yOff)
    }

    private fun refreshThemeToggleLabel(view: View) {
        val btn = view.findViewById<MaterialButton>(R.id.btn_theme_toggle) ?: return
        val mask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDark = mask == Configuration.UI_MODE_NIGHT_YES
        // 与 design-preview index 一致：◐ 表示当前浅色侧 / 点按可去深色；◑ 表示当前深色侧
        btn.text = if (isDark) "\u25D1" else "\u25D0"
        btn.contentDescription = if (isDark) getString(R.string.theme_cd_to_light)
        else getString(R.string.theme_cd_to_dark)
    }

    private fun displaySourceLang(raw: String?): String {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return getString(R.string.auto_detect)
        return when (v.uppercase(Locale.US)) {
            "AUTO" -> getString(R.string.auto_detect)
            else -> v
        }
    }

    private fun displayTargetLang(raw: String?): String {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return getString(R.string.chinese)
        return when (v.uppercase(Locale.US)) {
            "CHINESE" -> getString(R.string.chinese)
            else -> v
        }
    }

    private fun updateStatusIndicators(view: View) {
        val tvConfig = view.findViewById<TextView>(R.id.tv_current_config)
        val tvSource = view.findViewById<TextView>(R.id.tv_main_source_lang)
        val tvTarget = view.findViewById<TextView>(R.id.tv_main_target_lang)

        // Status check
        val isReady = Settings.canDrawOverlays(requireContext())
        val statusPrefix = if (isReady) "[ACTIVE]" else "[PENDING]"

        // 语言栏展示中文：与 AI 设置中保存的文案一致，并兼容历史英文枚举值
        tvSource.text = displaySourceLang(prefs.getString("source_lang", null))
        tvTarget.text = displayTargetLang(prefs.getString("target_lang", null))

        // Configuration Summary - OCR 与 AI 分开读取，避免显示混淆
        val ocr = displayOcrProvider(prefs.getString("ocr_provider", "baidu"))
        val aiProvider = (prefs.getString("ai_provider_name", "OpenAI") ?: "OpenAI").trim().ifBlank { "OpenAI" }
        val model = (prefs.getString("ai_model", "gpt-3.5-turbo") ?: "gpt-3.5-turbo").trim().ifBlank { "gpt-3.5-turbo" }
        tvConfig.text = "$statusPrefix CORE: OCR $ocr | AI $aiProvider/$model"
    }

    private fun displayOcrProvider(raw: String?): String {
        return when (raw?.trim()?.lowercase(Locale.US)) {
            "baidu" -> "Baidu"
            "alibaba" -> "Alibaba"
            "paddle" -> "Paddle"
            "glm_ocr" -> "GLM-OCR"
            "local" -> "Local"
            else -> "Baidu"
        }
    }

    private fun loadHistory(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.layout_history_container) ?: return
        val tvEmpty = view.findViewById<TextView>(R.id.tv_empty_history) ?: return

        try {
            val historyJson = prefs.getString("translation_history", "[]") ?: "[]"
            val type = object : TypeToken<List<HistoryItem>>() {}.type
            val history: List<HistoryItem> = Gson().fromJson(historyJson, type)

            // 仅移除历史条目，保留 tv_empty_history，否则清空后占位视图被摘掉，界面不会刷新
            for (i in container.childCount - 1 downTo 0) {
                val child = container.getChildAt(i)
                if (child.id != R.id.tv_empty_history) {
                    container.removeViewAt(i)
                }
            }
            if (history.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
            } else {
                tvEmpty.visibility = View.GONE
                history.sortedByDescending { it.timestamp }.take(5).forEach { item ->
                    val itemView = LayoutInflater.from(requireContext()).inflate(R.layout.item_history, container, false)
                    itemView.findViewById<TextView>(R.id.tv_history_source).text = item.source
                    itemView.findViewById<TextView>(R.id.tv_history_translated).text = item.translated
                    itemView.findViewById<TextView>(R.id.tv_history_timestamp).text =
                        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(item.timestamp))
                    
                    itemView.setOnClickListener {
                        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("translation", item.translated)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(requireContext(), R.string.toast_translation_copied, Toast.LENGTH_SHORT).show()
                    }
                    container.addView(itemView)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            prefs.edit().putString("translation_history", "[]").apply()
            for (i in container.childCount - 1 downTo 0) {
                val child = container.getChildAt(i)
                if (child.id != R.id.tv_empty_history) {
                    container.removeViewAt(i)
                }
            }
            tvEmpty.visibility = View.VISIBLE
        }
    }

    private fun clearHistory() {
        prefs.edit().putString("translation_history", "[]").apply()
        // 使用 fragment 根视图，避免在部分生命周期下 fragment.view 尚未就绪
        (view ?: requireView()).let { loadHistory(it) }
    }

    private fun checkOverlayPermission() {
        if (Settings.canDrawOverlays(requireContext())) {
            startCapture()
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${requireContext().packageName}")
            )
            overlayLauncher.launch(intent)
        }
    }

    private fun startCapture() {
        captureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}
