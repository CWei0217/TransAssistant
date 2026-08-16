package com.example.myapplication

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * 使用 Google ML Kit 实现的本地 OCR 引擎
 * 支持中文、日文、英文识别
 */
class LocalOcrEngine {
    
    // 初始化三个识别器（捆绑版）
    private val chineseRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val japaneseRecognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap, language: String = "自动检测"): String {
        return try {
            if (bitmap.isRecycled) return "错误: Bitmap 已回收"
            
            val image = InputImage.fromBitmap(bitmap, 0)
            
            // 根据源语言偏好选择识别器，如果是自动检测，默认使用中文识别器（中文识别器通常也支持拉丁字母）
            val recognizer = when (language) {
                "日本語" -> japaneseRecognizer
                "English" -> latinRecognizer
                else -> chineseRecognizer // 中文识别器是目前最通用的，支持中英混排
            }
            
            val result = recognizer.process(image).await()
            
            if (result.text.isBlank()) {
                "识别成功，但未发现文字"
            } else {
                result.text
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: "未知错误"
            Log.e("LocalOcrEngine", "本地识别失败: $errorMsg")
            "本地识别失败: $errorMsg"
        }
    }
}
