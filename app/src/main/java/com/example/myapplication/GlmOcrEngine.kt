package com.example.myapplication

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 智谱 GLM-OCR 版面解析（与官方示例一致：POST layout_parsing，Bearer + JSON）。
 * 本地截图通过 data URI（image/jpeg;base64,…）传入 [file] 字段。
 *
 * @see <a href="https://open.bigmodel.cn/api/paas/v4/layout_parsing">layout_parsing</a>
 */
class GlmOcrEngine(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun recognize(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext "失败: 请填写 GLM API Key"
        }
        if (bitmap.isRecycled) {
            return@withContext "失败: 图片已回收"
        }
        try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            val bytes = stream.toByteArray()
            if (bytes.size > MAX_IMAGE_BYTES) {
                return@withContext "失败: 图片超过 10MB 限制"
            }
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val fileUri = "data:image/jpeg;base64,$b64"

            val payload = JSONObject()
            payload.put("model", MODEL)
            payload.put("file", fileUri)

            val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val msg = parseErrorMessage(responseBody, response.message)
                    Log.e(TAG, "HTTP ${response.code}: $responseBody")
                    return@withContext "失败: HTTP ${response.code} $msg"
                }
                val root = JSONObject(responseBody)
                if (root.has("error")) {
                    val err = root.optJSONObject("error")
                    val em = err?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: root.optString("message", "未知错误")
                    return@withContext "失败: $em"
                }
                if (root.has("code") && root.has("message") && !root.has("md_results")) {
                    return@withContext "失败: ${root.optString("message")}"
                }
                val md = root.optString("md_results", "").trim()
                if (md.isNotEmpty()) md else "识别成功，但未发现文字"
            }
        } catch (e: Exception) {
            Log.e(TAG, "GLM-OCR layout_parsing 失败", e)
            "失败: ${e.message ?: "未知错误"}"
        }
    }

    private fun parseErrorMessage(responseBody: String, fallback: String): String {
        return try {
            val o = JSONObject(responseBody)
            o.optJSONObject("error")?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: o.optString("message").takeIf { it.isNotBlank() }
                ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    companion object {
        private const val TAG = "GlmOcrEngine"
        private const val MODEL = "glm-ocr"
        private const val API_URL = "https://open.bigmodel.cn/api/paas/v4/layout_parsing"
        private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
