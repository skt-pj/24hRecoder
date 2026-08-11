package com.sktpj.recorder24h.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import org.json.JSONObject
import java.util.UUID

object Gemma4LocalClient {
    const val MODEL_ID = "gemma-4-e2b-it-litertlm"
    private val inferenceLock = Any()

    @JvmStatic
    fun analyzeHourly(
        context: Context,
        source: AiAnalysisRepository.SourceWindow
    ): OpenAiLunaClient.Response = runAnalysis(
        context,
        buildHourlyPrompt(source),
        listOf(
            "summary", "topics", "keyEvents", "ideas", "decisions", "todos",
            "people", "places", "notableQuotes", "unresolved"
        )
    )

    @JvmStatic
    fun analyzeDaily(
        context: Context,
        source: AiAnalysisRepository.SourceWindow
    ): OpenAiLunaClient.Response = runAnalysis(
        context,
        buildDailyPrompt(source),
        listOf(
            "summary", "mindMap", "timeline", "topics", "keyEvents", "decisions",
            "todos", "ideas", "unresolved", "people", "places", "timeAllocation",
            "recurringTopics", "searchIndex"
        )
    )

    @JvmStatic
    fun analyzeRollup(
        context: Context,
        kind: String,
        source: AiRollupRepository.RollupSource
    ): OpenAiLunaClient.Response {
        require(
            kind == AiAnalysisScheduler.KIND_WEEKLY ||
                kind == AiAnalysisScheduler.KIND_MONTHLY ||
                kind == AiAnalysisScheduler.KIND_YEARLY
        ) { "Unsupported rollup kind: $kind" }
        return runAnalysis(
            context,
            buildRollupPrompt(kind, source),
            listOf(
                "summary", "highlights", "topics", "decisions", "todos", "ideas",
                "unresolved", "people", "places", "timeAllocation", "trends",
                "mindMap", "searchIndex"
            )
        )
    }

    private fun runAnalysis(
        context: Context,
        prompt: String,
        requiredKeys: List<String>
    ): OpenAiLunaClient.Response = synchronized(inferenceLock) {
        check(Gemma4ModelManager.isReady(context)) { "Gemma 4 model is not ready" }
        val modelFile = Gemma4ModelManager.modelFile(context)
        val cacheDir = Gemma4ModelManager.cacheDir(context)
        val engineConfig = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.GPU(),
            cacheDir = cacheDir.absolutePath
        )
        Engine(engineConfig).use { engine ->
            engine.initialize()
            val conversationConfig = ConversationConfig(
                systemInstruction = Contents.of(
                    "You analyze a private 24-hour recorder notebook. " +
                        "Use only the supplied source data as evidence. " +
                        "Do not invent missing events, people, places, durations, intentions, counts, or conclusions. " +
                        "If evidence is absent, return an empty array or an empty string as appropriate. " +
                        "Return only one valid JSON object matching the JSON format requested by the user prompt. " +
                        "Do not use Markdown code fences or commentary outside the JSON object."
                ),
                samplerConfig = SamplerConfig(
                    topK = 20,
                    topP = 0.9,
                    temperature = 0.1
                )
            )
            engine.createConversation(conversationConfig).use { conversation ->
                val message = conversation.sendMessage(prompt)
                val analysis = parseJsonObject(message.text)
                validateRequiredKeys(analysis, requiredKeys)
                OpenAiLunaClient.Response(
                    "local-${UUID.randomUUID()}",
                    analysis,
                    null
                )
            }
        }
    }

    private fun parseJsonObject(raw: String): JSONObject {
        val text = raw.trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        check(start >= 0 && end > start) { "Gemma 4 response did not contain a JSON object" }
        return try {
            JSONObject(text.substring(start, end + 1))
        } catch (error: Exception) {
            throw IllegalStateException("Gemma 4 response was not valid JSON", error)
        }
    }

    private fun validateRequiredKeys(json: JSONObject, requiredKeys: List<String>) {
        val missing = requiredKeys.filterNot(json::has)
        check(missing.isEmpty()) {
            "Gemma 4 JSON response is missing required keys: ${missing.joinToString(",")}" 
        }
    }

    private fun buildHourlyPrompt(source: AiAnalysisRepository.SourceWindow): String =
        "Analyze the following one-hour transcript window.\n" +
            "The output MUST be JSON and MUST follow this JSON format exactly:\n" +
            "{\"summary\":string,\"topics\":[string],\"keyEvents\":[{\"time\":string,\"event\":string}]," +
            "\"ideas\":[string],\"decisions\":[string],\"todos\":[{\"task\":string,\"evidence\":string}]," +
            "\"people\":[string],\"places\":[string],\"notableQuotes\":[{\"time\":string,\"text\":string}]," +
            "\"unresolved\":[string]}\n" +
            "Keep the summary concise. Preserve timestamps when they are useful. " +
            "For quotes, only copy wording that is actually present in the transcript.\n\n" +
            "TRANSCRIPT:\n" + AiAnalysisRepository.promptTranscript(source)

    private fun buildDailyPrompt(source: AiAnalysisRepository.SourceWindow): String =
        "Analyze the following full-day transcript from the original transcript data. " +
            "Do not treat hourly summaries as source evidence.\n" +
            "The output MUST be JSON and MUST follow this JSON format exactly:\n" +
            "{\"summary\":string,\"mindMap\":[{\"id\":string,\"label\":string,\"parentId\":string}]," +
            "\"timeline\":[{\"time\":string,\"event\":string}],\"topics\":[{\"name\":string,\"summary\":string}]," +
            "\"keyEvents\":[string],\"decisions\":[string],\"todos\":[{\"task\":string,\"status\":string,\"evidence\":string}]," +
            "\"ideas\":[string],\"unresolved\":[string],\"people\":[{\"name\":string,\"summary\":string}]," +
            "\"places\":[{\"name\":string,\"summary\":string}]," +
            "\"timeAllocation\":[{\"category\":string,\"minutes\":integer,\"evidence\":string}]," +
            "\"recurringTopics\":[{\"topic\":string,\"count\":integer,\"summary\":string}],\"searchIndex\":[string]}\n" +
            "Mind map rules: create one root node with parentId as an empty string, use unique stable ids inside this response, " +
            "and connect all other nodes through parentId. " +
            "Time allocation rules: count only intervals supported by transcript timestamps; do not force the categories to total 24 hours. " +
            "Recurring topic counts must represent evidence occurrences in this supplied transcript only.\n\n" +
            "TRANSCRIPT:\n" + AiAnalysisRepository.promptTranscript(source)

    private fun buildRollupPrompt(
        kind: String,
        source: AiRollupRepository.RollupSource
    ): String {
        val label = when (kind) {
            AiAnalysisScheduler.KIND_WEEKLY -> "weekly"
            AiAnalysisScheduler.KIND_MONTHLY -> "monthly"
            else -> "yearly"
        }
        return "Create a $label notebook by aggregating the supplied lower-level AI notes. " +
            "These notes are the only available source because raw audio/transcripts may already be deleted. " +
            "Do not claim evidence that is not present in the supplied notes.\n" +
            "The output MUST be JSON and MUST follow this JSON format exactly:\n" +
            "{\"summary\":string,\"highlights\":[string]," +
            "\"topics\":[{\"name\":string,\"summary\":string,\"count\":integer}]," +
            "\"decisions\":[string],\"todos\":[{\"task\":string,\"status\":string,\"evidence\":string}]," +
            "\"ideas\":[string],\"unresolved\":[string]," +
            "\"people\":[{\"name\":string,\"summary\":string}]," +
            "\"places\":[{\"name\":string,\"summary\":string}]," +
            "\"timeAllocation\":[{\"category\":string,\"minutes\":integer,\"evidence\":string}]," +
            "\"trends\":[{\"label\":string,\"summary\":string}]," +
            "\"mindMap\":[{\"id\":string,\"label\":string,\"parentId\":string}]," +
            "\"searchIndex\":[string]}\n" +
            "Rules: highlight only cross-note or period-level information supported by the source notes. " +
            "Topic counts must count supporting source notes, not invented occurrences. " +
            "Time allocation may only aggregate evidence-backed minutes already present in source notes; " +
            "do not force it to cover the full period. " +
            "For trends, describe only changes or repeated patterns that can be supported by multiple source notes. " +
            "For the mind map, create one root with empty parentId and connect all other nodes to it or its descendants.\n\n" +
            "SOURCE NOTES:\n" + AiRollupRepository.promptSource(source)
    }
}
