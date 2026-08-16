package com.example.myapplication

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

interface OpenAiApiService {
    @POST
    suspend fun chatCompletion(
        @Url url: String,
        @Header("Authorization") auth: String,
        @Body request: ChatRequest
    ): ChatResponse

    @GET
    suspend fun getModels(
        @Url url: String,
        @Header("Authorization") auth: String
    ): ModelsResponse
}

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Float = 0.3f
)

data class ChatMessage(
    val role: String,
    val content: String
)

data class ChatResponse(
    val choices: List<Choice>
)

data class Choice(
    val message: ChatMessage
)

data class ModelsResponse(
    val data: List<ModelData>
)

data class ModelData(
    val id: String
)

class AiTranslationEngine(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
    private val systemPrompt: String,
    private val userPromptTemplate: String
) {
    private val service: OpenAiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(if (baseUrl.isNotBlank()) baseUrl else "https://api.openai.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenAiApiService::class.java)
    }

    suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
        if (apiKey.isBlank()) return "Error: AI API Key not configured"

        val finalSystemPrompt = if (systemPrompt.isBlank()) 
            "You are a professional, authentic translation engine, only returns translations." 
            else systemPrompt
            
        val finalUserPrompt = if (userPromptTemplate.isBlank())
            "Please translate the following text from $sourceLang into $targetLang: \n\n$text"
            else userPromptTemplate.replace("{{text}}", text).replace("{{from}}", sourceLang).replace("{{to}}", targetLang)

        val request = ChatRequest(
            model = if (model.isBlank()) "gpt-3.5-turbo" else model,
            messages = listOf(
                ChatMessage("system", finalSystemPrompt),
                ChatMessage("user", finalUserPrompt)
            )
        )

        return try {
            val url = if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions"
            val response = service.chatCompletion(url, "Bearer $apiKey", request)
            response.choices.firstOrNull()?.message?.content?.trim() ?: "No translation result"
        } catch (e: Exception) {
            "Translation Failed: ${e.message}"
        }
    }

    suspend fun fetchModels(): List<String> {
        if (apiKey.isBlank()) return emptyList()
        return try {
            val url = if (baseUrl.endsWith("/models")) baseUrl else "$baseUrl/models"
            val response = service.getModels(url, "Bearer $apiKey")
            response.data.map { it.id }.filterNot { it.contains("vision") || it.contains("image") }.sorted()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
