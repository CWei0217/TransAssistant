package com.example.myapplication

import android.graphics.Bitmap
import android.util.Base64
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 与百度官方示例一致：
 * - Token：POST oauth/2.0/token，表单 grant_type / client_id / client_secret
 * - 识别：POST rest/2.0/ocr/v1/accurate_basic，表单 image 及与示例相同的布尔参数
 */
interface BaiduOcrService {
    @FormUrlEncoded
    @POST("rest/2.0/ocr/v1/accurate_basic")
    suspend fun recognizeText(
        @Query("access_token") accessToken: String,
        @Field("image") base64Image: String,
        @Field("detect_direction") detectDirection: String = "false",
        @Field("paragraph") paragraph: String = "false",
        @Field("probability") probability: String = "false",
        @Field("multidirectional_recognize") multidirectionalRecognize: String = "false"
    ): OcrResponse

    @FormUrlEncoded
    @POST("oauth/2.0/token")
    suspend fun getAccessToken(
        @Field("grant_type") grantType: String = "client_credentials",
        @Field("client_id") apiKey: String,
        @Field("client_secret") secretKey: String
    ): TokenResponse
}

data class TokenResponse(
    @SerializedName("access_token") val accessToken: String
)

data class OcrResponse(
    @SerializedName("words_result") val wordsResult: List<WordsResult>?
)

data class WordsResult(
    val words: String
)

class BaiduOcrEngine(private val apiKey: String, private val secretKey: String) {
    private var accessToken: String? = null

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(300, TimeUnit.SECONDS)
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val service: BaiduOcrService by lazy {
        Retrofit.Builder()
            .baseUrl("https://aip.baidubce.com/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BaiduOcrService::class.java)
    }

    suspend fun recognize(bitmap: Bitmap): String {
        try {
            if (accessToken == null) {
                accessToken = service.getAccessToken(apiKey = apiKey, secretKey = secretKey).accessToken
            }

            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            val base64Image = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

            val response = service.recognizeText(accessToken!!, base64Image)
            return response.wordsResult?.joinToString("\n") { it.words } ?: ""
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }
}
