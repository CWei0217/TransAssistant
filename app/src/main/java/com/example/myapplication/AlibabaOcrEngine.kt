package com.example.myapplication

import android.graphics.Bitmap
import android.util.Log
import com.aliyun.ocr_api20210707.Client
import com.aliyun.ocr_api20210707.models.RecognizeAdvancedRequest
import com.aliyun.teaopenapi.models.Config
import com.aliyun.teautil.models.RuntimeOptions
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream

class AlibabaOcrEngine(private val accessKeyId: String, private val accessKeySecret: String) {

    private fun createClient(): Client {
        val config = Config()
            .setAccessKeyId(accessKeyId)
            .setAccessKeySecret(accessKeySecret)
        // 接入地址
        config.endpoint = "ocr-api.cn-hangzhou.aliyuncs.com"
        return Client(config)
    }

    suspend fun recognize(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        try {
            val client = createClient()
            
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val imageBytes = stream.toByteArray()
            
            // RecognizeAdvanced：全文识别高精版（与官方示例一致，图片通过 body 上传）
            val recognizeAdvancedRequest = RecognizeAdvancedRequest()
                .setBody(ByteArrayInputStream(imageBytes))

            val runtime = RuntimeOptions()
            val resp = client.recognizeAdvancedWithOptions(recognizeAdvancedRequest, runtime)
            
            val body = resp.body ?: return@withContext ""
            // RecognizeAdvanced 的 data 为 JSON 字符串，需解析后取 content（全文识别结果）
            val dataStr = body.data ?: return@withContext ""
            try {
                val root = JsonParser.parseString(dataStr).asJsonObject
                root.get("content")?.asString ?: ""
            } catch (e: Exception) {
                Log.e("AlibabaOcrEngine", "解析 RecognizeAdvanced 返回 data 失败: ${e.message}")
                ""
            }
        } catch (e: Exception) {
            Log.e("AlibabaOcrEngine", "阿里云 OCR 调用失败: ${e.message}")
            "Error: ${e.message}"
        }
    }
}
