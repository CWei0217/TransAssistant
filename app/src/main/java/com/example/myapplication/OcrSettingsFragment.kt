package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OcrSettingsFragment : Fragment() {

    private val prefs by lazy {
        requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    }

    private val ocrPrefs by lazy {
        requireContext().getSharedPreferences("ocr_cache_prefs", Context.MODE_PRIVATE)
    }

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_ocr_settings, container, false)
        setupOcrSettings(view)
        return view
    }

    private fun setupOcrSettings(view: View) {
        val rgProvider = view.findViewById<RadioGroup>(R.id.rg_ocr_provider)
        
        // Baidu views
        val etBaiduApiKey = view.findViewById<EditText>(R.id.et_baidu_api_key)
        val etBaiduSecretKey = view.findViewById<EditText>(R.id.et_baidu_secret_key)
        val layoutBaidu = view.findViewById<View>(R.id.layout_baidu_settings)
        val btnTestBaidu = view.findViewById<Button>(R.id.btn_test_baidu)
        val tvStatusBaidu = view.findViewById<TextView>(R.id.tv_status_baidu)

        // Alibaba views
        val etAlibabaAkId = view.findViewById<EditText>(R.id.et_alibaba_access_key_id)
        val etAlibabaAkSecret = view.findViewById<EditText>(R.id.et_alibaba_access_key_secret)
        val layoutAlibaba = view.findViewById<View>(R.id.layout_alibaba_settings)
        val btnTestAlibaba = view.findViewById<Button>(R.id.btn_test_alibaba)
        val tvStatusAlibaba = view.findViewById<TextView>(R.id.tv_status_alibaba)

        // Paddle views
        val etPaddleToken = view.findViewById<EditText>(R.id.et_paddle_ocr_token)
        val layoutPaddle = view.findViewById<View>(R.id.layout_paddle_settings)
        val btnTestPaddle = view.findViewById<Button>(R.id.btn_test_paddle)
        val tvStatusPaddle = view.findViewById<TextView>(R.id.tv_status_paddle)

        val etGlmApiKey = view.findViewById<EditText>(R.id.et_glm_ocr_api_key)
        val layoutGlmOcr = view.findViewById<View>(R.id.layout_glm_ocr_settings)
        val btnTestGlmOcr = view.findViewById<Button>(R.id.btn_test_glm_ocr)
        val tvStatusGlmOcr = view.findViewById<TextView>(R.id.tv_status_glm_ocr)

        // Local views
        val layoutLocal = view.findViewById<View>(R.id.layout_local_settings)
        val btnTestLocal = view.findViewById<Button>(R.id.btn_test_local)
        val tvStatusLocal = view.findViewById<TextView>(R.id.tv_status_local)

        val switchCache = view.findViewById<SwitchMaterial>(R.id.switch_enable_cache)
        val btnClearCache = view.findViewById<Button>(R.id.btn_clear_cache)

        // Load saved values
        val currentProvider = prefs.getString("ocr_provider", "baidu")
        when (currentProvider) {
            "baidu" -> rgProvider.check(R.id.rb_baidu)
            "alibaba" -> rgProvider.check(R.id.rb_alibaba)
            "local" -> rgProvider.check(R.id.rb_local)
            "paddle" -> rgProvider.check(R.id.rb_paddle)
            "glm_ocr" -> rgProvider.check(R.id.rb_glm_ocr)
        }
        updateProviderLayouts(currentProvider, layoutBaidu, layoutAlibaba, layoutLocal, layoutPaddle, layoutGlmOcr)

        // Load Credentials from Prefs
        etBaiduApiKey.setText(prefs.getString("baidu_api_key", ""))
        etBaiduSecretKey.setText(prefs.getString("baidu_secret_key", ""))
        etAlibabaAkId.setText(prefs.getString("alibaba_access_key_id", ""))
        etAlibabaAkSecret.setText(prefs.getString("alibaba_access_key_secret", ""))
        etPaddleToken.setText(prefs.getString("paddle_ocr_token", ""))
        etGlmApiKey.setText(prefs.getString("glm_ocr_api_key", ""))

        switchCache.isChecked = ocrPrefs.getBoolean("enable_cache", true)

        // Listeners for saving
        rgProvider.setOnCheckedChangeListener { _, checkedId ->
            val provider = when (checkedId) {
                R.id.rb_baidu -> "baidu"
                R.id.rb_alibaba -> "alibaba"
                R.id.rb_local -> "local"
                R.id.rb_paddle -> "paddle"
                R.id.rb_glm_ocr -> "glm_ocr"
                else -> "baidu"
            }
            prefs.edit().putString("ocr_provider", provider).apply()
            updateProviderLayouts(provider, layoutBaidu, layoutAlibaba, layoutLocal, layoutPaddle, layoutGlmOcr)
        }

        // Setup individual text watchers
        setupSaveListeners(
            etBaiduApiKey,
            etBaiduSecretKey,
            etAlibabaAkId,
            etAlibabaAkSecret,
            etPaddleToken,
            etGlmApiKey
        )

        switchCache.setOnCheckedChangeListener { _, isChecked -> 
            ocrPrefs.edit().putBoolean("enable_cache", isChecked).apply() 
        }

        btnClearCache.setOnClickListener {
            OcrCacheManager(requireContext()).clearCache()
            Toast.makeText(requireContext(), "缓存已清空", Toast.LENGTH_SHORT).show()
        }

        // Test Buttons Logic
        btnTestBaidu.setOnClickListener { 
            testOcrConnection("baidu", etBaiduApiKey.text.toString(), etBaiduSecretKey.text.toString(), tvStatusBaidu) 
        }
        btnTestAlibaba.setOnClickListener { 
            testOcrConnection("alibaba", etAlibabaAkId.text.toString(), etAlibabaAkSecret.text.toString(), tvStatusAlibaba) 
        }
        btnTestLocal.setOnClickListener {
            testOcrConnection("local", "", "", tvStatusLocal)
        }
        btnTestPaddle.setOnClickListener {
            testOcrConnection("paddle", etPaddleToken.text.toString(), "", tvStatusPaddle)
        }
        btnTestGlmOcr.setOnClickListener {
            testOcrConnection("glm_ocr", etGlmApiKey.text.toString(), "", tvStatusGlmOcr)
        }

    }

    private fun setupSaveListeners(vararg views: EditText) {
        views.forEach { view ->
            val key = resources.getResourceEntryName(view.id).replace("et_", "")
            view.addTextChangedListener { prefs.edit().putString(key, it.toString()).apply() }
        }
    }

    private fun updateProviderLayouts(
        provider: String?,
        baidu: View,
        alibaba: View,
        local: View,
        paddle: View,
        glmOcr: View
    ) {
        baidu.visibility = if (provider == "baidu") View.VISIBLE else View.GONE
        alibaba.visibility = if (provider == "alibaba") View.VISIBLE else View.GONE
        local.visibility = if (provider == "local") View.VISIBLE else View.GONE
        paddle.visibility = if (provider == "paddle") View.VISIBLE else View.GONE
        glmOcr.visibility = if (provider == "glm_ocr") View.VISIBLE else View.GONE
    }

    private fun testOcrConnection(provider: String, key1: String, key2: String, statusView: TextView) {
        when {
            provider == "local" -> { /* 无需凭据 */ }
            provider == "paddle" || provider == "glm_ocr" -> {
                if (key1.isBlank()) {
                    statusView.text = if (provider == "glm_ocr") "请先输入 API Key" else "请先输入 Token"
                    statusView.setTextColor(requireContext().themeColor(R.attr.colorUiStatusError))
                    return
                }
            }
            key1.isBlank() || key2.isBlank() -> {
                statusView.text = "请先输入凭据"
                statusView.setTextColor(requireContext().themeColor(R.attr.colorUiStatusError))
                return
            }
        }

        scope.launch {
            statusView.text = "正在验证..."
            statusView.setTextColor(requireContext().themeColor(R.attr.colorUiStatusPending))
            
            val result = withContext(Dispatchers.IO) {
                try {
                    val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    
                    when (provider) {
                        "baidu" -> BaiduOcrEngine(key1, key2).recognize(bitmap)
                        "alibaba" -> AlibabaOcrEngine(key1, key2).recognize(bitmap)
                        "local" -> LocalOcrEngine().recognize(bitmap)
                        "paddle" -> PaddleLayoutParsingOcrEngine(key1).recognize(bitmap)
                        "glm_ocr" -> GlmOcrEngine(key1).recognize(bitmap)
                        else -> "Unknown"
                    }
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
            }

            if (result.startsWith("Error") || result.isBlank() || result.contains("失败")) {
                statusView.text = "● 失败: $result"
                statusView.setTextColor(requireContext().themeColor(R.attr.colorUiStatusError))
            } else {
                statusView.text = "● 成功 (识别测试: ${result.take(10)}...)"
                statusView.setTextColor(requireContext().themeColor(R.attr.colorUiStatusSuccess))
            }
        }
    }
}
