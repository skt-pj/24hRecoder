package com.sktpj.recorder24h.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.UUID

/**
 * Runs one-hour Gemma analysis without feeding an entire dense hour into the 4096-token context.
 *
 * The transcript is split near natural conversation boundaries (largest timestamp gap near the
 * target chunk size), with overlap on both sides of a boundary. Each chunk produces evidence,
 * not a final summary. Overlap evidence is deduplicated before a final hour-level synthesis.
 */
object GemmaHourlyConversationAnalyzer {
    private const val DIRECT_TRANSCRIPT_CHAR_LIMIT = 1100
    private const val CHUNK_TRANSCRIPT_CHAR_BUDGET = 1600
    private const val EVIDENCE_BATCH_CHAR_BUDGET = 1450
    private const val FINAL_EVIDENCE_CHAR_BUDGET = 2200
    private const val MAX_ENTRY_TEXT_CHARS = 850
    private const val BOUNDARY_OVERLAP_ITEMS = 2
    private const val MAX_REDUCTION_ROUNDS = 4

    private val lock = Any()
    private val promptTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.JAPAN)
    private val hourlyKeys = listOf(
        "summary", "topics", "keyEvents", "ideas", "decisions", "todos",
        "people", "places", "notableQuotes", "unresolved"
    )
    private val evidenceKeys = listOf("facts", "ongoing")

    @JvmStatic
    fun analyze(
        context: Context,
        source: AiAnalysisRepository.SourceWindow
    ): OpenAiLunaClient.Response = synchronized(lock) {
        check(Gemma4ModelManager.isReady(context)) { "Gemma 4 model is not ready" }
        val items = normalizeEntries(source.entries)
        check(items.isNotEmpty()) { "Gemma hourly source is empty" }

        withEngine(context) { engine ->
            val fullTranscript = renderItems(items)
            val analysis = if (fullTranscript.length <= DIRECT_TRANSCRIPT_CHAR_LIMIT) {
                try {
                    runJsonPrompt(engine, buildDirectHourlyPrompt(fullTranscript), hourlyKeys)
                } catch (error: Exception) {
                    if (!isInputTooLong(error)) throw error
                    analyzeHierarchically(engine, items)
                }
            } else {
                analyzeHierarchically(engine, items)
            }
            OpenAiLunaClient.Response(
                "local-${UUID.randomUUID()}",
                analysis,
                null
            )
        }
    }

    private fun analyzeHierarchically(engine: Engine, items: List<TranscriptItem>): JSONObject {
        val chunks = splitConversation(items)
        val extracted = chunks.mapIndexed { index, chunk ->
            runJsonPrompt(
                engine,
                buildEvidenceExtractionPrompt(index + 1, chunks.size, renderItems(chunk)),
                evidenceKeys
            )
        }

        var evidence = mergeEvidence(extracted)
        var rounds = 0
        while (evidence.toString().length > FINAL_EVIDENCE_CHAR_BUDGET && rounds < MAX_REDUCTION_ROUNDS) {
            val batches = batchEvidence(evidence)
            val reduced = batches.mapIndexed { index, batch ->
                runJsonPrompt(
                    engine,
                    buildEvidenceReductionPrompt(index + 1, batches.size, batch.toString()),
                    evidenceKeys
                )
            }
            val next = mergeEvidence(reduced)
            if (next.toString().length >= evidence.toString().length && rounds >= 1) {
                evidence = capEvidenceForFinal(next)
                break
            }
            evidence = next
            rounds++
        }
        if (evidence.toString().length > FINAL_EVIDENCE_CHAR_BUDGET) {
            evidence = capEvidenceForFinal(evidence)
        }

        return runJsonPrompt(engine, buildFinalHourlyPrompt(evidence.toString()), hourlyKeys)
    }

    private fun withEngine(context: Context, block: (Engine) -> OpenAiLunaClient.Response): OpenAiLunaClient.Response {
        val config = EngineConfig(
            modelPath = Gemma4ModelManager.modelFile(context).absolutePath,
            backend = Backend.GPU(),
            cacheDir = Gemma4ModelManager.cacheDir(context).absolutePath
        )
        Engine(config).use { engine ->
            engine.initialize()
            return block(engine)
        }
    }

    private fun runJsonPrompt(engine: Engine, prompt: String, requiredKeys: List<String>): JSONObject {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            val actualPrompt = if (attempt == 0) {
                prompt
            } else {
                "The previous response failed strict JSON validation. Return exactly one valid JSON object, with every required key, and no Markdown or commentary.\n\n$prompt"
            }
            try {
                val conversationConfig = ConversationConfig(
                    systemInstruction = Contents.of(
                        "You analyze a private 24-hour recorder notebook. " +
                            "Use only supplied evidence. Never invent missing events, people, places, numbers, decisions, or intentions. " +
                            "Return exactly one valid JSON object requested by the user prompt, with no Markdown fences or commentary."
                    ),
                    samplerConfig = SamplerConfig(
                        topK = 20,
                        topP = 0.9,
                        temperature = 0.1
                    )
                )
                engine.createConversation(conversationConfig).use { conversation ->
                    val raw = conversation.sendMessage(actualPrompt).toString()
                    val json = parseJsonObject(raw)
                    validateRequiredKeys(json, requiredKeys)
                    return json
                }
            } catch (error: Exception) {
                if (isInputTooLong(error)) throw error
                lastError = error
            }
        }
        throw IllegalStateException("Gemma 4 could not produce valid JSON after retry", lastError)
    }

    private fun buildDirectHourlyPrompt(transcript: String): String =
        hourlySchemaPrefix() +
            "Summarize this one-hour conversation log. Keep concrete details when the transcript contains them. " +
            "Preserve useful timestamps, numbers, materials, names, decisions, TODOs, ideas, and exact quotes. " +
            "Do not collapse concrete discussion into vague topic labels.\n\nTRANSCRIPT:\n$transcript"

    private fun buildEvidenceExtractionPrompt(
        index: Int,
        total: Int,
        transcript: String
    ): String =
        "Extract evidence from transcript fragment $index/$total. This fragment can overlap adjacent fragments. " +
            "Do NOT write the final hour summary. Preserve concrete information needed to reconstruct the conversation. " +
            "If a thought is cut off at a boundary, put a short description in ongoing instead of guessing the missing context. " +
            "Deduplicate repeated wording inside this fragment only.\n" +
            "Return exactly this JSON shape:\n" +
            "{\"facts\":[{\"time\":string,\"type\":string,\"detail\":string,\"speaker\":string}],\"ongoing\":[string]}\n" +
            "type should be one of topic,event,decision,todo,idea,person,place,quote. " +
            "Keep facts concise but specific. Preserve numbers, materials, names, steps, and timestamps when present. " +
            "For quote, copy only wording actually present.\n\nTRANSCRIPT FRAGMENT:\n$transcript"

    private fun buildEvidenceReductionPrompt(index: Int, total: Int, evidence: String): String =
        "Consolidate evidence batch $index/$total from overlapping transcript fragments. " +
            "This is still an intermediate evidence step, not the final summary. " +
            "Merge duplicates caused by overlap, but keep distinct concrete details. " +
            "Prioritize decisions, TODOs, ideas, specific procedures, numbers, materials, names, and notable quotes. " +
            "Return at most 10 facts and at most 3 ongoing items. Do not invent connections.\n" +
            "Return exactly this JSON shape:\n" +
            "{\"facts\":[{\"time\":string,\"type\":string,\"detail\":string,\"speaker\":string}],\"ongoing\":[string]}\n\n" +
            "EVIDENCE:\n$evidence"

    private fun buildFinalHourlyPrompt(evidence: String): String =
        hourlySchemaPrefix() +
            "Create the final one-hour conversation-log summary from the extracted evidence below. " +
            "Evidence can contain duplicates from overlap; merge duplicates by timestamp and meaning. " +
            "Keep concrete discussion concrete: retain useful numbers, materials, procedures, decisions, TODOs, ideas, people, places, and quotes. " +
            "Do not invent context that was not preserved in the evidence.\n\nEXTRACTED EVIDENCE:\n$evidence"

    private fun hourlySchemaPrefix(): String =
        "The output MUST be JSON and MUST follow this JSON format exactly:\n" +
            "{\"summary\":string,\"topics\":[string],\"keyEvents\":[{\"time\":string,\"event\":string}]," +
            "\"ideas\":[string],\"decisions\":[string],\"todos\":[{\"task\":string,\"evidence\":string}]," +
            "\"people\":[string],\"places\":[string],\"notableQuotes\":[{\"time\":string,\"text\":string}]," +
            "\"unresolved\":[string]}\n"

    private fun normalizeEntries(entries: List<AiAnalysisRepository.SourceEntry>): List<TranscriptItem> {
        val result = mutableListOf<TranscriptItem>()
        entries.forEach { entry ->
            val text = entry.text.trim()
            if (text.isEmpty()) return@forEach
            if (text.length <= MAX_ENTRY_TEXT_CHARS) {
                result += TranscriptItem(entry.startMs, entry.endMs, entry.speaker, text)
            } else {
                splitLongText(text).forEach { piece ->
                    result += TranscriptItem(entry.startMs, entry.endMs, entry.speaker, piece)
                }
            }
        }
        return result
    }

    private fun splitLongText(text: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val hardEnd = minOf(text.length, start + MAX_ENTRY_TEXT_CHARS)
            if (hardEnd == text.length) {
                parts += text.substring(start).trim()
                break
            }
            val preferredStart = start + (MAX_ENTRY_TEXT_CHARS * 2 / 3)
            var cut = -1
            for (i in hardEnd - 1 downTo preferredStart) {
                if (text[i] in charArrayOf('。', '！', '？', '.', '!', '?', '\n')) {
                    cut = i + 1
                    break
                }
            }
            if (cut <= start) cut = hardEnd
            parts += text.substring(start, cut).trim()
            start = cut
        }
        return parts.filter { it.isNotEmpty() }
    }

    private fun splitConversation(items: List<TranscriptItem>): List<List<TranscriptItem>> {
        if (items.isEmpty()) return emptyList()
        val chunks = mutableListOf<List<TranscriptItem>>()
        var start = 0
        while (start < items.size) {
            var endExclusive = start
            var chars = 0
            while (endExclusive < items.size) {
                val nextChars = renderItem(items[endExclusive]).length
                if (endExclusive > start && chars + nextChars > CHUNK_TRANSCRIPT_CHAR_BUDGET) break
                chars += nextChars
                endExclusive++
            }
            if (endExclusive >= items.size) {
                chunks += items.subList(start, items.size).toList()
                break
            }

            val count = endExclusive - start
            val candidateStart = start + maxOf(1, count * 2 / 3)
            var splitExclusive = endExclusive
            var bestGap = Long.MIN_VALUE
            for (candidate in candidateStart until endExclusive) {
                if (candidate <= start || candidate >= items.size) continue
                val gap = items[candidate].startMs - items[candidate - 1].endMs
                if (gap > bestGap) {
                    bestGap = gap
                    splitExclusive = candidate
                }
            }
            if (splitExclusive <= start) splitExclusive = endExclusive
            chunks += items.subList(start, splitExclusive).toList()
            start = maxOf(start + 1, splitExclusive - BOUNDARY_OVERLAP_ITEMS)
        }
        return chunks
    }

    private fun renderItems(items: List<TranscriptItem>): String =
        items.joinToString(separator = "\n", postfix = "\n") { renderItem(it) }

    private fun renderItem(item: TranscriptItem): String {
        val zone = ZoneId.systemDefault()
        val start = promptTime.format(Instant.ofEpochMilli(item.startMs).atZone(zone))
        val end = promptTime.format(Instant.ofEpochMilli(item.endMs).atZone(zone))
        return "[$start - $end] [${item.speaker}] ${item.text}"
    }

    private fun mergeEvidence(rows: List<JSONObject>): JSONObject {
        val facts = LinkedHashMap<String, JSONObject>()
        val ongoing = LinkedHashSet<String>()
        rows.forEach { row ->
            val factArray = row.optJSONArray("facts") ?: JSONArray()
            for (i in 0 until factArray.length()) {
                val fact = factArray.optJSONObject(i) ?: continue
                val time = fact.optString("time", "").trim()
                val type = fact.optString("type", "").trim()
                val detail = fact.optString("detail", "").trim()
                val speaker = fact.optString("speaker", "").trim()
                if (detail.isEmpty()) continue
                val key = "$time\u0000$type\u0000$detail"
                if (!facts.containsKey(key)) {
                    facts[key] = JSONObject()
                        .put("time", time)
                        .put("type", type)
                        .put("detail", detail)
                        .put("speaker", speaker)
                }
            }
            val ongoingArray = row.optJSONArray("ongoing") ?: JSONArray()
            for (i in 0 until ongoingArray.length()) {
                val value = ongoingArray.optString(i, "").trim()
                if (value.isNotEmpty()) ongoing += value
            }
        }
        return JSONObject()
            .put("facts", JSONArray(facts.values.toList()))
            .put("ongoing", JSONArray(ongoing.toList()))
    }

    private fun batchEvidence(evidence: JSONObject): List<JSONObject> {
        val rows = mutableListOf<JSONObject>()
        val facts = evidence.optJSONArray("facts") ?: JSONArray()
        val ongoing = evidence.optJSONArray("ongoing") ?: JSONArray()
        var currentFacts = mutableListOf<JSONObject>()
        var currentChars = 0

        fun flush() {
            if (currentFacts.isEmpty()) return
            rows += JSONObject()
                .put("facts", JSONArray(currentFacts))
                .put("ongoing", JSONArray())
            currentFacts = mutableListOf()
            currentChars = 0
        }

        for (i in 0 until facts.length()) {
            val fact = facts.optJSONObject(i) ?: continue
            val size = fact.toString().length + 1
            if (currentFacts.isNotEmpty() && currentChars + size > EVIDENCE_BATCH_CHAR_BUDGET) flush()
            currentFacts += JSONObject(fact.toString())
            currentChars += size
        }
        flush()
        if (rows.isEmpty()) {
            rows += JSONObject().put("facts", JSONArray()).put("ongoing", JSONArray())
        }

        for (i in 0 until ongoing.length()) {
            val value = ongoing.optString(i, "").trim()
            if (value.isEmpty()) continue
            val target = rows[i % rows.size]
            target.optJSONArray("ongoing")?.put(value)
        }
        return rows
    }

    private fun capEvidenceForFinal(evidence: JSONObject): JSONObject {
        val facts = evidence.optJSONArray("facts") ?: JSONArray()
        if (facts.length() <= 16) return evidence

        val priority = mutableListOf<JSONObject>()
        val normal = mutableListOf<JSONObject>()
        for (i in 0 until facts.length()) {
            val fact = facts.optJSONObject(i) ?: continue
            when (fact.optString("type", "")) {
                "decision", "todo", "idea", "quote" -> priority += fact
                else -> normal += fact
            }
        }
        val selected = mutableListOf<JSONObject>()
        priority.take(10).forEach { selected += JSONObject(it.toString()) }
        if (selected.size < 16 && normal.isNotEmpty()) {
            val remaining = 16 - selected.size
            if (normal.size <= remaining) {
                normal.forEach { selected += JSONObject(it.toString()) }
            } else {
                for (slot in 0 until remaining) {
                    val index = if (remaining == 1) 0 else slot * (normal.size - 1) / (remaining - 1)
                    selected += JSONObject(normal[index].toString())
                }
            }
        }
        return JSONObject()
            .put("facts", JSONArray(selected))
            .put("ongoing", evidence.optJSONArray("ongoing") ?: JSONArray())
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

    private fun isInputTooLong(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty()
            if (message.contains("Input token ids are too long", ignoreCase = true)) return true
            current = current.cause
        }
        return false
    }

    private data class TranscriptItem(
        val startMs: Long,
        val endMs: Long,
        val speaker: String,
        val text: String
    )
}
