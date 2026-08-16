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
 * 飞桨 / AI Studio 版式解析（layout-parsing）接口，与官方 Python 示例一致。
 * @see <a href="https://aistudio.baidu.com">Paddle AI Studio</a>
 */
class PaddleLayoutParsingOcrEngine(private val token: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun recognize(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        if (token.isBlank()) {
            return@withContext "失败: 请填写 Paddle Token"
        }
        if (bitmap.isRecycled) {
            return@withContext "失败: 图片已回收"
        }
        try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            val fileData = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

            val payload = JSONObject()
            payload.put("file", fileData)
            payload.put("fileType", 1) // 图片为 1，PDF 为 0
            payload.put("useDocOrientationClassify", false)
            payload.put("useDocUnwarping", false)
            payload.put("useChartRecognition", false)

            val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(API_URL)
                .header("Authorization", "token $token")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val err = runCatching { JSONObject(responseBody).optString("message") }
                        .getOrDefault("")
                    val msg = if (err.isNotBlank()) err else response.message
                    Log.e(TAG, "HTTP ${response.code}: $responseBody")
                    return@withContext "失败: HTTP ${response.code} $msg"
                }
                val root = JSONObject(responseBody)
                val result = root.optJSONObject("result")
                    ?: return@withContext "失败: 响应缺少 result"
                val arr = result.optJSONArray("layoutParsingResults")
                    ?: return@withContext "识别成功，但未发现文字"
                if (arr.length() == 0) {
                    return@withContext "识别成功，但未发现文字"
                }
                val sb = StringBuilder()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val markdown = item.optJSONObject("markdown") ?: continue
                    val text = markdown.optString("text", "").trim()
                    if (text.isNotEmpty()) {
                        if (sb.isNotEmpty()) sb.append("\n\n")
                        sb.append(text)
                    }
                }
                val out = sb.toString().trim()
                if (out.isEmpty()) "识别成功，但未发现文字" else out
            }
        } catch (e: Exception) {
            Log.e(TAG, "Paddle layout-parsing 失败", e)
            "失败: ${e.message ?: "未知错误"}"
        }
    }

    companion object {
        private const val TAG = "PaddleLayoutOcr"
        private const val API_URL =
            "https://a2b5j0abk7m2x4z9.aistudio-app.com/layout-parsing"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
