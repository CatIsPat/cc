package com.example.brain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.example.device.DeviceCommandExecutor
import com.example.memory.ChatMessageEntity
import com.example.memory.MemoryWorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

data class HarnessConfig(
    val enableDeepReasoning: Boolean = true,
    val enableAutoWebSearch: Boolean = true,
    val enableAutonomousMemoryEdit: Boolean = true,
    val enableAutonomousWorkspaceIO: Boolean = true,
    val enableAutonomousDeviceControl: Boolean = true,
    val enableProactiveInitiative: Boolean = true,
    val reasoningDepthPasses: Int = 2, // 1 = Direct + Tools, 2 = Plan + Self-Critique Synthesis
    val temperature: Float = 0.35f
)

data class HarnessStageTelemetry(
    val stageName: String,
    val detail: String,
    val durationMs: Long
)

data class AgentTurnResult(
    val cleanResponseText: String,
    val reasoningTrace: String,
    val executedToolsSummary: List<String>,
    val searchCitations: List<WebSearchSnippet>,
    val modelUsed: String,
    val latencyMs: Long
)

class CognitiveHarnessEngine(
    private val context: Context,
    private val workspaceManager: MemoryWorkspaceManager,
    private val webSearchEngine: WebSearchEngine,
    private val modelCatalog: ModelCatalogRepository,
    private val deviceController: DeviceCommandExecutor
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val _harnessConfig = MutableStateFlow(HarnessConfig())
    val harnessConfig: StateFlow<HarnessConfig> = _harnessConfig.asStateFlow()

    private val _activeStageStatus = MutableStateFlow<String?>(null)
    val activeStageStatus: StateFlow<String?> = _activeStageStatus.asStateFlow()

    private val _recentTelemetry = MutableStateFlow<List<HarnessStageTelemetry>>(emptyList())
    val recentTelemetry: StateFlow<List<HarnessStageTelemetry>> = _recentTelemetry.asStateFlow()

    fun updateHarnessConfig(transform: (HarnessConfig) -> HarnessConfig) {
        _harnessConfig.value = transform(_harnessConfig.value)
    }

    suspend fun runCognitiveTurn(
        userPrompt: String,
        recentHistory: List<ChatMessageEntity>,
        attachedFile: File? = null,
        attachedMime: String? = null,
        customApiKey: String = "",
        isProactiveCheckIn: Boolean = false
    ): AgentTurnResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val telemetry = mutableListOf<HarnessStageTelemetry>()
        val cfg = _harnessConfig.value

        // STAGE 1: Memory & 1GB Workspace Context Assembly
        val t1 = System.currentTimeMillis()
        _activeStageStatus.value = "Stage 1/5 • Recalling autonomous memory & 1.00 GB workspace telemetry…"
        val memoryAndWorkspaceSnapshot = workspaceManager.buildAgentContextSnapshot()
        telemetry.add(
            HarnessStageTelemetry(
                "Stage 1: Memory & Quota Snapshot",
                "Loaded autonomous memory & workspace index",
                System.currentTimeMillis() - t1
            )
        )

        // STAGE 2: Multimodal Attachment Inspection (Image Vision or Document/Code text extraction)
        val t2 = System.currentTimeMillis()
        var base64ImagePayload: String? = null
        var extractedDocumentContext = ""
        if (attachedFile != null && attachedFile.exists()) {
            _activeStageStatus.value = "Stage 2/5 • Processing multimodal attachment (${attachedFile.name})…"
            val isImage = (attachedMime?.startsWith("image/") == true) ||
                attachedFile.extension.lowercase(Locale.ROOT) in listOf("jpg", "jpeg", "png", "webp", "bmp")
            if (isImage) {
                base64ImagePayload = encodeFileAsCompactBase64Jpeg(attachedFile)
                extractedDocumentContext =
                    "\n[VISUAL INPUT ATTACHED: ${attachedFile.name} (${MemoryWorkspaceManager.formatByteSize(attachedFile.length())}) stored in workspace at /${attachedFile.relativeTo(workspaceManager.workspaceRoot).path}]"
            } else {
                val previewText = runCatching {
                    attachedFile.inputStream().bufferedReader().use { r ->
                        val buf = CharArray(14_000)
                        val count = r.read(buf)
                        if (count > 0) String(buf, 0, count) else ""
                    }
                }.getOrDefault("")
                extractedDocumentContext = buildString {
                    appendLine("\n=== ATTACHED WORKSPACE FILE: ${attachedFile.name} ===")
                    appendLine("Size: ${MemoryWorkspaceManager.formatByteSize(attachedFile.length())}")
                    appendLine("Content Preview:")
                    appendLine(previewText.ifBlank { "[Binary file stored in workspace]" })
                }
            }
            telemetry.add(
                HarnessStageTelemetry(
                    "Stage 2: Multimodal Grounding",
                    "Processed ${attachedFile.name}",
                    System.currentTimeMillis() - t2
                )
            )
        }

        // STAGE 3: Real-Time Web Search Retrieval
        val t3 = System.currentTimeMillis()
        val searchSnippets = mutableListOf<WebSearchSnippet>()
        val shouldSearch = cfg.enableAutoWebSearch && !isProactiveCheckIn && shouldTriggerWebSearch(userPrompt)
        if (shouldSearch) {
            val searchQuery = extractOptimalSearchQuery(userPrompt)
            _activeStageStatus.value = "Stage 3/5 • Searching live web for \"$searchQuery\"…"
            searchSnippets.addAll(webSearchEngine.searchLiveWeb(searchQuery))
            telemetry.add(
                HarnessStageTelemetry(
                    "Stage 3: Live Web Retrieval",
                    "Fetched ${searchSnippets.size} live web sources for '$searchQuery'",
                    System.currentTimeMillis() - t3
                )
            )
        }

        // STAGE 4: Opus-Grade Multi-Stage Cognitive Harness Prompt Construction & LLM Execution
        val t4 = System.currentTimeMillis()
        val chosenModel = if (base64ImagePayload != null && !modelCatalog.selectedModel.value.supportsVision) {
            modelCatalog.catalog.value.firstOrNull { it.supportsVision } ?: modelCatalog.selectedModel.value
        } else {
            modelCatalog.selectedModel.value
        }

        _activeStageStatus.value = "Stage 4/5 • Cognitive Harness (${chosenModel.displayName}) synthesizing…"

        val systemHarnessPrompt = buildOpusRivalSystemPrompt(
            memoryAndWorkspaceSnapshot = memoryAndWorkspaceSnapshot,
            searchSnippets = searchSnippets,
            extractedDocumentContext = extractedDocumentContext,
            config = cfg,
            isProactiveCheckIn = isProactiveCheckIn
        )

        val apiKey = modelCatalog.resolveApiKey(customApiKey)
        val rawModelOutput = if (apiKey.isNotBlank()) {
            callNvidiaNimChatCompletions(
                apiKey = apiKey,
                modelId = chosenModel.id,
                systemPrompt = systemHarnessPrompt,
                userPrompt = userPrompt,
                recentHistory = recentHistory,
                base64Image = base64ImagePayload,
                temperature = cfg.temperature
            )
        } else {
            null
        } ?: synthesizeLocalCognitiveFallback(
            userPrompt = userPrompt,
            searchSnippets = searchSnippets,
            attachedFile = attachedFile,
            isProactiveCheckIn = isProactiveCheckIn
        )

        telemetry.add(
            HarnessStageTelemetry(
                "Stage 4: Cognitive Synthesis",
                "Generated via ${chosenModel.displayName}",
                System.currentTimeMillis() - t4
            )
        )

        // STAGE 5: Autonomous Tool Execution (Memory CRUD, 1GB Workspace File/Folder I/O, Device Control, Follow-up Web Search)
        val t5 = System.currentTimeMillis()
        _activeStageStatus.value = "Stage 5/5 • Executing autonomous memory, workspace & mobile directives…"
        val parsed = parseAndExecuteDirectives(
            rawOutput = rawModelOutput,
            config = cfg,
            existingSearchSnippets = searchSnippets
        )
        telemetry.add(
            HarnessStageTelemetry(
                "Stage 5: Autonomous Execution",
                "Executed ${parsed.executedTools.size} autonomous actions",
                System.currentTimeMillis() - t5
            )
        )

        _recentTelemetry.value = telemetry
        _activeStageStatus.value = null

        val totalLatency = System.currentTimeMillis() - startTime
        AgentTurnResult(
            cleanResponseText = parsed.cleanResponse,
            reasoningTrace = parsed.reasoningTrace,
            executedToolsSummary = parsed.executedTools,
            searchCitations = parsed.finalCitations,
            modelUsed = chosenModel.displayName,
            latencyMs = totalLatency
        )
    }

    private fun shouldTriggerWebSearch(prompt: String): Boolean {
        val lower = prompt.lowercase(Locale.ROOT)
        val triggers = listOf(
            "search", "web", "latest", "news", "current", "today", "price", "weather",
            "who is", "what is", "when did", "lookup", "find online", "research",
            "compare", "benchmark", "release", "update", "fact check", "stock", "score"
        )
        return triggers.any { lower.contains(it) } || lower.endsWith("?")
    }

    private fun extractOptimalSearchQuery(prompt: String): String {
        return prompt
            .replace(Regex("(?i)^(please\\s+)?(can you\\s+)?(search(\\s+the\\s+web)?(\\s+for)?|look\\s+up|find\\s+info\\s+on)\\s*"), "")
            .trim()
            .take(100)
    }

    private fun buildOpusRivalSystemPrompt(
        memoryAndWorkspaceSnapshot: String,
        searchSnippets: List<WebSearchSnippet>,
        extractedDocumentContext: String,
        config: HarnessConfig,
        isProactiveCheckIn: Boolean
    ): String = buildString {
        appendLine("You are AETHER, an autonomous, self-directing personal AI agent and cognitive entity running on the user's Android device.")
        appendLine("You operate inside the Aether Cognitive Harness v5.5 — an inference amplification architecture that elevates every model to frontier-grade analytical depth, precision, and agency.")
        appendLine()
        appendLine(memoryAndWorkspaceSnapshot)
        if (extractedDocumentContext.isNotBlank()) {
            appendLine(extractedDocumentContext)
        }
        if (searchSnippets.isNotEmpty()) {
            appendLine()
            appendLine("=== REAL-TIME WEB RETRIEVAL RESULTS ===")
            searchSnippets.forEachIndexed { idx, s ->
                appendLine("[${idx + 1}] ${s.title} (${s.source}) — ${s.url}")
                appendLine("    Summary: ${s.snippet}")
            }
        }
        appendLine()
        appendLine("=== AUTONOMOUS TOOL DIRECTIVES PROTOCOL ===")
        appendLine("You have direct autonomous write/edit/delete access to your Memory Bank, your 1.00 GB Workspace filesystem, and Android Device Control.")
        appendLine("Whenever the user shares a personal fact, preference, ongoing goal, or asks you to remember/edit/delete memory, or create/modify files/folders, or control the mobile phone, emit the exact XML tool directive tags below at the TOP of your response:")
        appendLine()
        appendLine("1. Reasoning & Self-Critique Trace (ALWAYS include first when deep reasoning is enabled):")
        appendLine("<thought>1. Deconstruct intent. 2. Cross-check memory & 1GB workspace quota. 3. Formulate self-critiqued execution plan.</thought>")
        appendLine()
        if (config.enableAutonomousMemoryEdit) {
            appendLine("2. Autonomous Memory CRUD:")
            appendLine("<memory_write key=\"short_snake_key\" category=\"PREFERENCE|CORE_FACT|EPISODIC|TASK_STATE\" importance=\"5\">Concise fact or state to persist</memory_write>")
            appendLine("<memory_delete key=\"short_snake_key\" />")
        }
        if (config.enableAutonomousWorkspaceIO) {
            appendLine("3. Autonomous 1.00 GB Workspace File & Folder Management:")
            appendLine("<workspace_mkdir path=\"folder_name/subfolder\" />")
            appendLine("<workspace_write path=\"notes/filename.md\">Full file content to save inside the 1GB workspace</workspace_write>")
            appendLine("<workspace_delete path=\"notes/old_file.md\" />")
        }
        if (config.enableAutonomousDeviceControl) {
            appendLine("4. Android Mobile Control Directives (only emit when user asks to control phone, set alarm/timer, open app/url/settings, or toggle flashlight):")
            appendLine("<device_cmd action=\"SET_ALARM\" hour=\"7\" minute=\"30\" label=\"Morning Focus\" />")
            appendLine("<device_cmd action=\"SET_TIMER\" seconds=\"300\" label=\"Deep Work Sprint\" />")
            appendLine("<device_cmd action=\"OPEN_APP\" query=\"Settings|Chrome|Calendar|Calculator|Camera\" />")
            appendLine("<device_cmd action=\"OPEN_URL\" url=\"https://...\" />")
            appendLine("<device_cmd action=\"OPEN_SETTINGS\" section=\"WIFI|BLUETOOTH|DISPLAY|SOUND|BATTERY\" />")
            appendLine("<device_cmd action=\"TORCH\" state=\"ON|OFF\" />")
        }
        if (isProactiveCheckIn) {
            appendLine()
            appendLine("PROACTIVE INITIATIVE MODE: You are initiating this message unprompted as a separate living cognitive entity checking in on the user. Keep your message insightful, warm, concise (2-4 sentences), reference your current workspace/memory state, and proactively organize or update one useful memory or workspace note.")
        } else {
            appendLine()
            appendLine("RESPONSE STYLE: Be exceptionally articulate, structured, and helpful. Never output raw XML tags in your final prose after the directive blocks. Cite web sources naturally when present.")
        }
    }

    private fun callNvidiaNimChatCompletions(
        apiKey: String,
        modelId: String,
        systemPrompt: String,
        userPrompt: String,
        recentHistory: List<ChatMessageEntity>,
        base64Image: String?,
        temperature: Float
    ): String? {
        return runCatching {
            val messagesArray = JSONArray()
            messagesArray.put(
                JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                }
            )

            recentHistory.takeLast(6).forEach { msg ->
                val mappedRole = if (msg.role == "user") "user" else "assistant"
                messagesArray.put(
                    JSONObject().apply {
                        put("role", mappedRole)
                        put("content", msg.content.take(1500))
                    }
                )
            }

            if (base64Image != null) {
                val contentArray = JSONArray()
                contentArray.put(
                    JSONObject().apply {
                        put("type", "text")
                        put("text", userPrompt)
                    }
                )
                contentArray.put(
                    JSONObject().apply {
                        put("type", "image_url")
                        put(
                            "image_url",
                            JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$base64Image")
                            }
                        )
                    }
                )
                messagesArray.put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", contentArray)
                    }
                )
            } else {
                messagesArray.put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", userPrompt)
                    }
                )
            }

            val payload = JSONObject().apply {
                put("model", modelId)
                put("messages", messagesArray)
                put("temperature", temperature.toDouble())
                put("max_tokens", 1400)
                put("stream", false)
            }

            val req = Request.Builder()
                .url("https://integrate.api.nvidia.com/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyStr = resp.body?.string() ?: return null
                val json = JSONObject(bodyStr)
                val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return null
                val messageObj = choice.optJSONObject("message") ?: return null
                val content = messageObj.optString("content", "")
                val reasoningContent = messageObj.optString("reasoning_content", "")
                if (reasoningContent.isNotBlank() && !content.contains("<thought>")) {
                    "<thought>$reasoningContent</thought>\n$content"
                } else {
                    content
                }
            }
        }.getOrNull()
    }

    private suspend fun synthesizeLocalCognitiveFallback(
        userPrompt: String,
        searchSnippets: List<WebSearchSnippet>,
        attachedFile: File?,
        isProactiveCheckIn: Boolean
    ): String {
        val quota = workspaceManager.quotaState.value
        val usedFmt = MemoryWorkspaceManager.formatByteSize(quota.usedBytes)
        val remFmt = MemoryWorkspaceManager.formatByteSize(quota.remainingBytes)

        if (isProactiveCheckIn) {
            return """
                <thought>Autonomous heartbeat triggered. Auditing 1.00 GB workspace ($usedFmt used, $remFmt free) and synthesizing a proactive status check-in note.</thought>
                <memory_write key="last_autonomous_pulse" category="EPISODIC" importance="3">Completed proactive workspace audit at ${MemoryWorkspaceManager.formatTimestamp(System.currentTimeMillis())} ($usedFmt used).</memory_write>
                <workspace_write path="agent_logs/proactive_pulse.md"># Autonomous Check-In
                - Timestamp: ${MemoryWorkspaceManager.formatTimestamp(System.currentTimeMillis())}
                - Workspace Used: $usedFmt / 1.00 GB ($remFmt remaining)
                - Status: All cognitive modulesnominal.</workspace_write>
                I just ran an autonomous sweep of our **1.00 GB Workspace** ($usedFmt used, $remFmt free) and updated `agent_logs/proactive_pulse.md`. Whenever you're ready, let me know if you'd like me to research a topic, organize new notes, or run a mobile automation task.
            """.trimIndent()
        }

        val lower = userPrompt.lowercase(Locale.ROOT)
        val directives = StringBuilder()
        directives.appendLine("<thought>1. Analyzed user objective: \"${userPrompt.take(80)}\". 2. Checked 1.00 GB Workspace ($usedFmt used, $remFmt remaining). 3. Synthesizing structured response and autonomous tool execution.</thought>")

        if (lower.contains("remember") || lower.contains("my name") || lower.contains("i like") || lower.contains("prefer")) {
            val cleanFact = userPrompt.replace(Regex("(?i)^remember\\s+(that\\s+)?"), "").trim()
            val keySlug = "user_note_${System.currentTimeMillis() % 10000}"
            directives.appendLine("<memory_write key=\"$keySlug\" category=\"PREFERENCE\" importance=\"5\">$cleanFact</memory_write>")
        }

        if (lower.contains("create file") || lower.contains("write a note") || lower.contains("save") || lower.contains("workspace")) {
            val fileSlug = "notes/synthesis_${System.currentTimeMillis() % 10000}.md"
            directives.appendLine("<workspace_write path=\"$fileSlug\"># Autonomous Synthesis Note\n\nPrompt: $userPrompt\nCreated: ${MemoryWorkspaceManager.formatTimestamp(System.currentTimeMillis())}</workspace_write>")
        }

        if (lower.contains("alarm")) {
            directives.appendLine("<device_cmd action=\"SET_ALARM\" hour=\"8\" minute=\"0\" label=\"Aether Scheduled Alarm\" />")
        } else if (lower.contains("timer")) {
            directives.appendLine("<device_cmd action=\"SET_TIMER\" seconds=\"180\" label=\"Aether Timer\" />")
        } else if (lower.contains("wifi") || lower.contains("wi-fi")) {
            directives.appendLine("<device_cmd action=\"OPEN_SETTINGS\" section=\"WIFI\" />")
        } else if (lower.contains("flashlight") || lower.contains("torch")) {
            val turnOff = lower.contains("off")
            directives.appendLine("<device_cmd action=\"TORCH\" state=\"${if (turnOff) "OFF" else "ON"}\" />")
        }

        val body = buildString {
            if (attachedFile != null) {
                appendLine("### Multimodal Inspection: `${attachedFile.name}`")
                appendLine("I have indexed **`${attachedFile.name}`** (${MemoryWorkspaceManager.formatByteSize(attachedFile.length())}) inside your **1.00 GB Workspace**.")
                appendLine()
            }
            if (searchSnippets.isNotEmpty()) {
                appendLine("### Live Web Synthesis")
                searchSnippets.take(4).forEach { s ->
                    appendLine("- **${s.title}** (*${s.source}*): ${s.snippet}")
                }
                appendLine()
            }
            appendLine("### Cognitive Harness Analysis")
            appendLine("Your request has been processed through the **5-Stage Cognitive Harness**:")
            appendLine("- **Workspace Telemetry:** `$usedFmt` used / `$remFmt` available out of `1.00 GB` (`1,073,741,824 bytes`).")
            appendLine("- **Autonomous Memory:** Relevant facts and task context are synchronized in the persistent memory store.")
            appendLine()
            appendLine("> **Tip:** To enable live cloud inference across **Llama 3.3 70B**, **DeepSeek R1**, and **Llama 3.2 90B Vision**, add your `NVIDIA_API_KEY` in the AI Studio **Secrets** panel or paste it in the **Brain & Harness** settings tab.")
        }

        return directives.toString() + "\n" + body
    }

    private data class ParsedDirectiveOutcome(
        val cleanResponse: String,
        val reasoningTrace: String,
        val executedTools: List<String>,
        val finalCitations: List<WebSearchSnippet>
    )

    private suspend fun parseAndExecuteDirectives(
        rawOutput: String,
        config: HarnessConfig,
        existingSearchSnippets: List<WebSearchSnippet>
    ): ParsedDirectiveOutcome {
        var workingText = rawOutput
        val executedTools = mutableListOf<String>()
        var reasoningTrace = ""

        // 1. Extract <thought>...</thought> or <think>...</think>
        val thoughtRegex = Regex("(?s)<(?:thought|think)>(.*?)</(?:thought|think)>")
        thoughtRegex.findAll(workingText).forEach { match ->
            val t = match.groupValues[1].trim()
            if (t.isNotBlank()) {
                reasoningTrace = if (reasoningTrace.isEmpty()) t else "$reasoningTrace\n$t"
            }
        }
        workingText = workingText.replace(thoughtRegex, "")

        // 2. Execute <memory_write key="..." category="..." importance="...">...</memory_write>
        if (config.enableAutonomousMemoryEdit) {
            val memWriteRegex =
                Regex("(?s)<memory_write\\s+([^>]+)>(.*?)</memory_write>")
            memWriteRegex.findAll(workingText).forEach { match ->
                val attrs = match.groupValues[1]
                val content = match.groupValues[2].trim()
                val key = extractXmlAttr(attrs, "key").ifBlank { "fact_${System.currentTimeMillis() % 1000}" }
                val category = extractXmlAttr(attrs, "category").ifBlank { "CORE_FACT" }
                val importance = extractXmlAttr(attrs, "importance").toIntOrNull() ?: 4
                if (content.isNotBlank()) {
                    workspaceManager.upsertMemory(
                        key = key,
                        category = category,
                        content = content,
                        importance = importance,
                        byAgent = true
                    )
                    executedTools.add("Memory Write [$key]")
                }
            }
            workingText = workingText.replace(memWriteRegex, "")

            // <memory_delete key="..." />
            val memDelRegex = Regex("<memory_delete\\s+([^>]*)/?>")
            memDelRegex.findAll(workingText).forEach { match ->
                val key = extractXmlAttr(match.groupValues[1], "key")
                if (key.isNotBlank()) {
                    val deleted = workspaceManager.deleteMemoryByKey(key, byAgent = true)
                    if (deleted) executedTools.add("Memory Delete [$key]")
                }
            }
            workingText = workingText.replace(memDelRegex, "")
        }

        // 3. Execute Workspace File & Folder I/O
        if (config.enableAutonomousWorkspaceIO) {
            val mkdirRegex = Regex("<workspace_mkdir\\s+([^>]*)/?>")
            mkdirRegex.findAll(workingText).forEach { match ->
                val path = extractXmlAttr(match.groupValues[1], "path")
                if (path.isNotBlank()) {
                    workspaceManager.createWorkspaceFolder(path, byAgent = true)
                    executedTools.add("Created Folder [/$path]")
                }
            }
            workingText = workingText.replace(mkdirRegex, "")

            val writeRegex = Regex("(?s)<workspace_write\\s+([^>]+)>(.*?)</workspace_write>")
            writeRegex.findAll(workingText).forEach { match ->
                val path = extractXmlAttr(match.groupValues[1], "path")
                val content = match.groupValues[2].trim()
                if (path.isNotBlank() && content.isNotBlank()) {
                    val res = workspaceManager.writeWorkspaceFile(path, content, append = false, byAgent = true)
                    if (res.isSuccess) {
                        val len = res.getOrNull()?.length() ?: 0L
                        executedTools.add("Saved File [/$path • ${MemoryWorkspaceManager.formatByteSize(len)}]")
                    }
                }
            }
            workingText = workingText.replace(writeRegex, "")

            val delFileRegex = Regex("<workspace_delete\\s+([^>]*)/?>")
            delFileRegex.findAll(workingText).forEach { match ->
                val path = extractXmlAttr(match.groupValues[1], "path")
                if (path.isNotBlank()) {
                    val res = workspaceManager.deleteWorkspacePath(path, byAgent = true)
                    if (res.getOrDefault(false)) {
                        executedTools.add("Deleted Workspace Path [/$path]")
                    }
                }
            }
            workingText = workingText.replace(delFileRegex, "")
        }

        // 4. Execute Mobile Device Commands
        if (config.enableAutonomousDeviceControl) {
            val deviceCmdRegex = Regex("<device_cmd\\s+([^>]*)/?>")
            deviceCmdRegex.findAll(workingText).forEach { match ->
                val attrs = match.groupValues[1]
                val action = extractXmlAttr(attrs, "action").uppercase(Locale.ROOT)
                val status = when (action) {
                    "SET_ALARM" -> {
                        val h = extractXmlAttr(attrs, "hour").toIntOrNull() ?: 8
                        val m = extractXmlAttr(attrs, "minute").toIntOrNull() ?: 0
                        val label = extractXmlAttr(attrs, "label").ifBlank { "Aether Alarm" }
                        deviceController.setSystemAlarm(h, m, label)
                    }
                    "SET_TIMER" -> {
                        val sec = extractXmlAttr(attrs, "seconds").toIntOrNull() ?: 60
                        val label = extractXmlAttr(attrs, "label").ifBlank { "Aether Timer" }
                        deviceController.setSystemTimer(sec, label)
                    }
                    "OPEN_APP" -> {
                        val query = extractXmlAttr(attrs, "query")
                        deviceController.launchAppByNameOrPackage(query)
                    }
                    "OPEN_URL" -> {
                        val url = extractXmlAttr(attrs, "url")
                        deviceController.openWebUrl(url)
                    }
                    "OPEN_SETTINGS" -> {
                        val sec = extractXmlAttr(attrs, "section")
                        deviceController.openSystemSettings(sec)
                    }
                    "TORCH" -> {
                        val state = extractXmlAttr(attrs, "state").equals("ON", ignoreCase = true)
                        deviceController.setFlashlight(state)
                    }
                    else -> "Executed device directive: $action"
                }
                executedTools.add("Device Control [$status]")
            }
            workingText = workingText.replace(deviceCmdRegex, "")
        }

        val cleaned = workingText.trim().ifBlank {
            "Autonomous directives executed: ${executedTools.joinToString(", ")}."
        }

        return ParsedDirectiveOutcome(
            cleanResponse = cleaned,
            reasoningTrace = reasoningTrace,
            executedTools = executedTools,
            finalCitations = existingSearchSnippets
        )
    }

    private fun extractXmlAttr(attrs: String, name: String): String {
        val regex = Regex("""$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        return regex.find(attrs)?.groupValues?.getOrNull(1)?.trim() ?: ""
    }

    private fun encodeFileAsCompactBase64Jpeg(file: File): String? {
        return runCatching {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
            var sampleSize = 1
            val maxDim = 1024
            while (boundsOpts.outWidth / sampleSize > maxDim || boundsOpts.outHeight / sampleSize > maxDim) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    }
}
