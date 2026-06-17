package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.api.*
import com.example.model.SCENARIOS
import com.example.model.Scenario
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val feedback: String? = null
)

class ScenarioViewModel : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    
    private val _currentScenario = MutableStateFlow<Scenario?>(null)
    val currentScenario: StateFlow<Scenario?> = _currentScenario

    fun setScenario(scenarioId: String) {
        val scenario = SCENARIOS.find { it.id == scenarioId } ?: return
        _currentScenario.value = scenario
        _messages.value = listOf(
            ChatMessage(
                text = scenario.welcomeMessage,
                isUser = false
            )
        )
    }

    fun sendMessage(userText: String, apiKey: String) {
        if (userText.isBlank()) return
        
        val scenario = _currentScenario.value ?: return

        _messages.update { it + ChatMessage(text = userText, isUser = true) }
        _isLoading.value = true

        viewModelScope.launch {
            try {
                val conversationHistory = _messages.value.map { msg ->
                    Content(
                        role = if (msg.isUser) "user" else "model",
                        parts = listOf(Part(text = msg.text))
                    )
                }

                // We ask Gemini to format the response as JSON containing ai_response and optional feedback.
                val schema = buildJsonObject {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("ai_response") {
                            put("type", "STRING")
                            put("description", "Your response as the character.")
                        }
                        putJsonObject("feedback") {
                            put("type", "STRING")
                            put("description", "A short 1-sentence feedback or suggestion to improve the user's last message, if they made a grammar mistake or sounded unnatural. Leave empty if no feedback is needed.")
                        }
                    }
                    putJsonArray("required") {
                        add("ai_response")
                        add("feedback")
                    }
                }

                val request = GenerateContentRequest(
                    systemInstruction = Content(
                        role = "system",
                        parts = listOf(Part(text = scenario.systemPrompt + "\nRespond using the requested JSON schema."))
                    ),
                    contents = conversationHistory,
                    generationConfig = GenerationConfig(
                        temperature = 0.7f,
                        responseModalities = listOf("TEXT"),
                    )
                )

                // Add responseFormat to generationConfig for JSON structured output
                // But Wait, ResponseFormat logic isn't fully typed in my basic data classes. 
                // Let's just instruct it to return JSON and we'll parse it.
                val requestWithJsonInstruction = request.copy(
                    systemInstruction = Content(role = "system", parts = listOf(Part(text = scenario.systemPrompt + "\nYou MUST return your answer as a JSON object with 'ai_response' (your character's response) and 'feedback' (a 1-sentence language tip for the user's latest input, or empty string). No markdown formatting, just raw JSON.")))
                )

                val response = RetrofitClient.service.generateContent(apiKey, requestWithJsonInstruction)
                
                val rawText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: ""
                
                // Parse the response
                val cleanRawText = rawText.removePrefix("```json").removeSuffix("```").trim()
                
                val jsonResponse = Json.parseToJsonElement(cleanRawText).jsonObject
                val aiResponse = jsonResponse["ai_response"]?.jsonPrimitive?.content ?: "Sorry, I didn't catch that."
                val feedback = jsonResponse["feedback"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

                _messages.update { 
                    it + ChatMessage(text = aiResponse, isUser = false, feedback = feedback) 
                }

            } catch (e: retrofit2.HttpException) {
                e.printStackTrace()
                val errorMsg = if (e.code() == 429) {
                    "Model capacity overloaded (HTTP 429). Please wait a few seconds and try again."
                } else {
                    "An error occurred: HTTP ${e.code()}. Please try again."
                }
                _messages.update {
                    it + ChatMessage(text = errorMsg, isUser = false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _messages.update { 
                    it + ChatMessage(text = "An error occurred: ${e.localizedMessage}", isUser = false)
                }
            } finally {
                _isLoading.value = false
            }
        }
    }
}
