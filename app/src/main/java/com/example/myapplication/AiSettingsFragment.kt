package com.example.myapplication

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import com.google.android.material.slider.Slider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class AiSettingsFragment : Fragment() {

    private val prefs by lazy {
        requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private val defaultTriggerKeyCode = KeyEvent.KEYCODE_BUTTON_A
    private var isListeningForMappingKey = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val mappingTimeoutMs = 6000L
    private val hintAutoHideMs = 2000L
    private var mappingTimeoutRunnable: Runnable? = null
    private var hintAutoHideRunnable: Runnable? = null

    private val providers = listOf(
        "ChatGPT (OpenAI)",
        "DeepSeek",
        "Moonshot (Kimi)",
        "智谱清言 (GLM)",
        "通义千问 (Qwen)",
        "文心一言 (Yiyan)",
        "Gemini (Google)",
        "自定义代理 (Custom Proxy)"
    )

    private val languages = listOf(
        "自动检测", "中文", "English", "日本語", "Español", "Français", "Deutsch", "Русский", 
        "Português", "Italiano", "韩国어", "العربية", "हिन्दी", "Türkçe", "Tiếng Việt", 
        "Bahasa Indonesia", "ไทย", "فارسی", "Hungarian"
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_ai_settings, container, false)
        setupAiSettings(view)
        return view
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupAiSettings(view: View) {
        view.isFocusableInTouchMode = true
        view.requestFocus()

        val spinnerProvider = view.findViewById<AutoCompleteTextView>(R.id.spinner_ai_provider)
        val etAiApiKey = view.findViewById<EditText>(R.id.et_ai_api_key)
        val etAiBaseUrl = view.findViewById<EditText>(R.id.et_ai_base_url)
        val etAiModel = view.findViewById<AutoCompleteTextView>(R.id.et_ai_model)
        val etSourceLang = view.findViewById<AutoCompleteTextView>(R.id.et_source_lang)
        val etTargetLang = view.findViewById<AutoCompleteTextView>(R.id.et_target_lang)
        val etSystemPrompt = view.findViewById<EditText>(R.id.et_system_prompt)
        val etUserPrompt = view.findViewById<EditText>(R.id.et_user_prompt)
        val etAk = view.findViewById<EditText>(R.id.et_ai_ak)
        val etSk = view.findViewById<EditText>(R.id.et_ai_sk)
        
        val layoutStandardKey = view.findViewById<View>(R.id.layout_ai_api_key)
        val layoutYiyanCreds = view.findViewById<View>(R.id.layout_yiyan_credentials)
        
        val sliderTemp = view.findViewById<Slider>(R.id.slider_temp)
        val tvTempScore = view.findViewById<TextView>(R.id.tv_temp_value)
        val btnTest = view.findViewById<Button>(R.id.btn_test_connection)
        val btnFetchModels = view.findViewById<Button>(R.id.btn_fetch_models)

        // Result UI Customization views
        val sliderAlpha = view.findViewById<Slider>(R.id.slider_result_alpha)
        val tvAlphaValue = view.findViewById<TextView>(R.id.tv_result_alpha_value)
        val sliderFontSize = view.findViewById<Slider>(R.id.slider_result_font_size)
        val tvFontSizeValue = view.findViewById<TextView>(R.id.tv_result_font_size_value)
        val sliderDuration = view.findViewById<Slider>(R.id.slider_result_duration)
        val tvDurationValue = view.findViewById<TextView>(R.id.tv_result_duration_value)
        val rgResultWindowMode = view.findViewById<RadioGroup>(R.id.rg_result_window_mode)
        val tvGamepadMapping = view.findViewById<TextView>(R.id.tv_gamepad_mapping_value)
        val btnEditGamepadMapping = view.findViewById<Button>(R.id.btn_edit_gamepad_mapping)
        val btnResetGamepadMapping = view.findViewById<Button>(R.id.btn_reset_gamepad_mapping)
        val tvMappingHint = view.findViewById<TextView>(R.id.tv_mapping_hint)
        tvMappingHint.visibility = View.GONE

        // Adapters
        val providerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, providers)
        spinnerProvider.setAdapter(providerAdapter)
        
        val langAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, languages)
        etSourceLang.setAdapter(langAdapter)
        etTargetLang.setAdapter(langAdapter)

        // 彻底解决过滤问题的关键：在触摸时重置过滤并强行展示所有项
        val touchListener = View.OnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (v is AutoCompleteTextView) {
                    v.showDropDown()
                }
            }
            false
        }
        
        spinnerProvider.setOnTouchListener(touchListener)
        etAiModel.setOnTouchListener(touchListener)
        etSourceLang.setOnTouchListener(touchListener)
        etTargetLang.setOnTouchListener(touchListener)

        // Default Prompt Values
        val defSystem = "You are a professional, authentic translation engine, only returns translations."
        val defUser = "Please translate the following text from {{from}} into {{to}}, please do not explain the original text:\n\n{{text}}"

        // Initial Load（兼容旧版已存 display 名）
        val rawProvider = prefs.getString("ai_provider_name", providers[0])!!
        val currentProvider = migrateProviderDisplayName(rawProvider)
        if (currentProvider != rawProvider) {
            prefs.edit().putString("ai_provider_name", currentProvider).apply()
        }
        spinnerProvider.setText(currentProvider, false)
        
        etAiApiKey.setText(prefs.getString("ai_api_key", ""))
        etAk.setText(prefs.getString("ai_ak", ""))
        etSk.setText(prefs.getString("ai_sk", ""))
        etAiBaseUrl.setText(prefs.getString("ai_base_url", "https://api.openai.com/v1"))
        etAiModel.setText(prefs.getString("ai_model", "gpt-3.5-turbo"), false)
        etSourceLang.setText(prefs.getString("source_lang", "自动检测"), false)
        etTargetLang.setText(prefs.getString("target_lang", "中文"), false)
        etSystemPrompt.setText(prefs.getString("ai_system_prompt", defSystem))
        etUserPrompt.setText(prefs.getString("ai_user_prompt", defUser))
        
        val savedTemp = prefs.getFloat("ai_temp", 0.3f)
        sliderTemp.value = savedTemp
        tvTempScore.text = String.format(Locale.US, "%.1f", savedTemp)

        // Load Result UI Customization values
        val savedAlpha = prefs.getFloat("result_alpha", 1.0f)
        sliderAlpha.value = savedAlpha
        tvAlphaValue.text = String.format(Locale.US, "%.2f", savedAlpha)

        val savedFontSize = prefs.getFloat("result_font_size", 15.0f)
        sliderFontSize.value = savedFontSize
        tvFontSizeValue.text = savedFontSize.toInt().toString()

        val savedDuration = prefs.getFloat("result_duration", 0.0f)
        sliderDuration.value = savedDuration
        tvDurationValue.text = savedDuration.toInt().toString()
        val resultWindowMode = prefs.getString("result_window_mode", "follow") ?: "follow"
        val modeButtonId = when (resultWindowMode) {
            "fixed" -> R.id.rb_result_fixed
            "subtitle" -> R.id.rb_result_subtitle
            else -> R.id.rb_result_follow
        }
        rgResultWindowMode.check(modeButtonId)
        tvGamepadMapping.text = keyCodeToFriendlyName(getMappedTriggerKeyCode())

        updateUiForProvider(currentProvider!!, layoutStandardKey, layoutYiyanCreds, etAiBaseUrl, etAiModel, isInitial = true)

        // Listeners
        spinnerProvider.setOnItemClickListener { _, _, position, _ ->
            // 注意：获取项时应无视过滤状态
            val selected = providerAdapter.getItem(position) ?: providers[position]
            prefs.edit().putString("ai_provider_name", selected).apply()
            updateUiForProvider(selected, layoutStandardKey, layoutYiyanCreds, etAiBaseUrl, etAiModel, isInitial = false)
            // 确保文字更新时不会产生二次过滤
            spinnerProvider.setText(selected, false)
        }

        etAiApiKey.addTextChangedListener { prefs.edit().putString("ai_api_key", it.toString()).apply() }
        etAk.addTextChangedListener { prefs.edit().putString("ai_ak", it.toString()).apply() }
        etSk.addTextChangedListener { prefs.edit().putString("ai_sk", it.toString()).apply() }
        etAiBaseUrl.addTextChangedListener { prefs.edit().putString("ai_base_url", it.toString()).apply() }
        etAiModel.addTextChangedListener { prefs.edit().putString("ai_model", it.toString()).apply() }
        etSourceLang.addTextChangedListener { prefs.edit().putString("source_lang", it.toString()).apply() }
        etTargetLang.addTextChangedListener { prefs.edit().putString("target_lang", it.toString()).apply() }
        etSystemPrompt.addTextChangedListener { prefs.edit().putString("ai_system_prompt", it.toString()).apply() }
        etUserPrompt.addTextChangedListener { prefs.edit().putString("ai_user_prompt", it.toString()).apply() }

        sliderTemp.addOnChangeListener { _, value, _ ->
            tvTempScore.text = String.format(Locale.US, "%.1f", value)
            prefs.edit().putFloat("ai_temp", value).apply()
        }

        sliderAlpha.addOnChangeListener { _, value, _ ->
            tvAlphaValue.text = String.format(Locale.US, "%.2f", value)
            prefs.edit().putFloat("result_alpha", value).apply()
        }

        sliderFontSize.addOnChangeListener { _, value, _ ->
            tvFontSizeValue.text = value.toInt().toString()
            prefs.edit().putFloat("result_font_size", value).apply()
        }

        sliderDuration.addOnChangeListener { _, value, _ ->
            tvDurationValue.text = value.toInt().toString()
            prefs.edit().putFloat("result_duration", value).apply()
        }

        rgResultWindowMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rb_result_fixed -> "fixed"
                R.id.rb_result_subtitle -> "subtitle"
                else -> "follow"
            }
            prefs.edit().putString("result_window_mode", mode).apply()
        }

        btnTest.setOnClickListener {
            val provider = spinnerProvider.text.toString()
            val key = if (provider == "文心一言 (Yiyan)") {
                etAk.text.toString()
            } else {
                etAiApiKey.text.toString()
            }
            testConnection(key, etAiBaseUrl.text.toString(), etAiModel.text.toString(), etSourceLang.text.toString(), etTargetLang.text.toString())
        }

        btnFetchModels.setOnClickListener {
            val provider = spinnerProvider.text.toString()
            val key = if (provider == "文心一言 (Yiyan)") {
                etAk.text.toString()
            } else {
                etAiApiKey.text.toString()
            }
            fetchSupportedModels(key, etAiBaseUrl.text.toString(), etAiModel)
        }

        btnEditGamepadMapping.setOnClickListener {
            isListeningForMappingKey = true
            view.requestFocus()
            showMappingHint(tvMappingHint, "请按一次你选择的按键", autoHide = false)
            startMappingTimeout(tvMappingHint)
        }

        btnResetGamepadMapping.setOnClickListener {
            isListeningForMappingKey = false
            clearMappingTimeout()
            saveTriggerKeyCode(defaultTriggerKeyCode)
            tvGamepadMapping.text = keyCodeToFriendlyName(defaultTriggerKeyCode)
            hideMappingHint(tvMappingHint)
            Toast.makeText(context, "已恢复默认映射", Toast.LENGTH_SHORT).show()
        }

        view.setOnKeyListener { _, keyCode, event ->
            if (!isListeningForMappingKey) return@setOnKeyListener false
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return@setOnKeyListener true
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                isListeningForMappingKey = false
                clearMappingTimeout()
                showMappingHint(tvMappingHint, "映射未成功", autoHide = true)
                return@setOnKeyListener true
            }
            saveTriggerKeyCode(keyCode)
            tvGamepadMapping.text = keyCodeToFriendlyName(keyCode)
            isListeningForMappingKey = false
            clearMappingTimeout()
            showMappingHint(tvMappingHint, "映射成功！", autoHide = true)
            Toast.makeText(context, "映射已设置为: ${keyCodeToFriendlyName(keyCode)}", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun migrateProviderDisplayName(stored: String): String = when (stored) {
        "智谱清言 (Zhipu)" -> "智谱清言 (GLM)"
        "通义千问 (Tongyi)" -> "通义千问 (Qwen)"
        else -> stored
    }

    private fun getMappedTriggerKeyCode(): Int {
        val single = prefs.getInt("trigger_key_code", -1)
        if (single != -1) return single
        val legacySingle = prefs.getInt("gamepad_start_key_code", -1)
        if (legacySingle != -1) return legacySingle
        val legacyMulti = prefs.getString("gamepad_start_key_codes", null)
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.firstOrNull()
        return legacyMulti ?: defaultTriggerKeyCode
    }

    private fun saveTriggerKeyCode(keyCode: Int) {
        prefs.edit()
            .putInt("trigger_key_code", keyCode)
            .putString("gamepad_start_key_codes", keyCode.toString())
            .remove("gamepad_start_key_code")
            .apply()
    }

    override fun onPause() {
        super.onPause()
        isListeningForMappingKey = false
        clearMappingTimeout()
        hintAutoHideRunnable?.let { uiHandler.removeCallbacks(it) }
        hintAutoHideRunnable = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clearMappingTimeout()
        hintAutoHideRunnable?.let { uiHandler.removeCallbacks(it) }
        hintAutoHideRunnable = null
    }

    private fun startMappingTimeout(hintView: TextView) {
        clearMappingTimeout()
        mappingTimeoutRunnable = Runnable {
            if (!isListeningForMappingKey) return@Runnable
            isListeningForMappingKey = false
            showMappingHint(hintView, "长时间无操作，映射未成功", autoHide = true)
        }
        uiHandler.postDelayed(mappingTimeoutRunnable!!, mappingTimeoutMs)
    }

    private fun clearMappingTimeout() {
        mappingTimeoutRunnable?.let { uiHandler.removeCallbacks(it) }
        mappingTimeoutRunnable = null
    }

    private fun showMappingHint(hintView: TextView, message: String, autoHide: Boolean) {
        hintAutoHideRunnable?.let { uiHandler.removeCallbacks(it) }
        hintAutoHideRunnable = null
        hintView.text = message
        hintView.visibility = View.VISIBLE
        if (autoHide) {
            hintAutoHideRunnable = Runnable { hintView.visibility = View.GONE }
            uiHandler.postDelayed(hintAutoHideRunnable!!, hintAutoHideMs)
        }
    }

    private fun hideMappingHint(hintView: TextView) {
        hintAutoHideRunnable?.let { uiHandler.removeCallbacks(it) }
        hintAutoHideRunnable = null
        hintView.visibility = View.GONE
    }

    private fun keyCodeToFriendlyName(keyCode: Int): String {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> "A"
            KeyEvent.KEYCODE_BUTTON_B -> "B"
            KeyEvent.KEYCODE_BUTTON_X -> "X"
            KeyEvent.KEYCODE_BUTTON_Y -> "Y"
            KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
            KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
            KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
            KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
            KeyEvent.KEYCODE_BUTTON_START -> "START"
            KeyEvent.KEYCODE_BUTTON_SELECT -> "SELECT"
            KeyEvent.KEYCODE_DPAD_CENTER -> "DPAD_CENTER"
            else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        }
    }

    private fun updateUiForProvider(
        provider: String, 
        standardLayout: View, 
        yiyanLayout: View, 
        baseUrlEdit: EditText, 
        modelEdit: AutoCompleteTextView,
        isInitial: Boolean
    ) {
        if (provider == "文心一言 (Yiyan)") {
            standardLayout.visibility = View.GONE
            yiyanLayout.visibility = View.VISIBLE
        } else {
            standardLayout.visibility = View.VISIBLE
            yiyanLayout.visibility = View.GONE
        }

        if (!isInitial) {
            when (provider) {
                "ChatGPT (OpenAI)" -> {
                    baseUrlEdit.setText("https://api.openai.com/v1")
                    modelEdit.setText("gpt-3.5-turbo", false)
                }
                "DeepSeek" -> {
                    baseUrlEdit.setText("https://api.deepseek.com")
                    modelEdit.setText("deepseek-chat", false)
                }
                "Moonshot (Kimi)" -> {
                    baseUrlEdit.setText("https://api.moonshot.cn/v1")
                    modelEdit.setText("moonshot-v1-8k", false)
                }
                "智谱清言 (GLM)" -> {
                    baseUrlEdit.setText("https://open.bigmodel.cn/api/paas/v4")
                    modelEdit.setText("glm-4", false)
                }
                "通义千问 (Qwen)" -> {
                    baseUrlEdit.setText("https://dashscope.aliyuncs.com/compatible-mode/v1")
                    modelEdit.setText("qwen-turbo", false)
                }
                "文心一言 (Yiyan)" -> {
                    baseUrlEdit.setText("https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop")
                    modelEdit.setText("ernie-bot-turbo", false)
                }
                "Gemini (Google)" -> {
                    baseUrlEdit.setText("https://generativelanguage.googleapis.com/v1beta/openai")
                    modelEdit.setText("gemini-pro", false)
                }
            }
        }
    }

    private fun fetchSupportedModels(apiKey: String, baseUrl: String, modelView: AutoCompleteTextView) {
        if (apiKey.isBlank()) {
            Toast.makeText(context, "请先输入 API Key", Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch {
            Toast.makeText(context, "正在获取模型列表...", Toast.LENGTH_SHORT).show()
            val engine = AiTranslationEngine(apiKey, baseUrl, "", "", "")
            val models = withContext(Dispatchers.IO) { engine.fetchModels() }

            if (models.isNotEmpty()) {
                val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, models)
                modelView.setAdapter(adapter)
                modelView.showDropDown()
                Toast.makeText(context, "成功获取 ${models.size} 个模型", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "获取模型失败，请检查 Key 或 URL", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun testConnection(apiKey: String, baseUrl: String, model: String, sourceLang: String, targetLang: String) {
        if (apiKey.isBlank()) {
            Toast.makeText(context, "请先输入凭据", Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch {
            Toast.makeText(context, "正在测试连接...", Toast.LENGTH_SHORT).show()
            val engine = AiTranslationEngine(
                apiKey, baseUrl, model, 
                prefs.getString("ai_system_prompt", "") ?: "",
                prefs.getString("ai_user_prompt", "") ?: ""
            )
            
            val result = withContext(Dispatchers.IO) {
                engine.translate("Hello", sourceLang, targetLang)
            }

            if (result.contains("Error") || result.contains("Failed")) {
                Toast.makeText(context, "测试失败: $result", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "测试成功！AI 回复: $result", Toast.LENGTH_LONG).show()
            }
        }
    }
}
