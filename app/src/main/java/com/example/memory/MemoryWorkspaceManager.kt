package com.example.memory

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WorkspaceNode(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val childCount: Int = 0,
    val lastModified: Long,
    val category: String // "FOLDER", "CODE", "DOC", "IMAGE", "DATA", "LOG"
)

data class CategoryBreakdown(
    val category: String,
    val bytes: Long,
    val count: Int,
    val percentageOfUsed: Float
)

data class WorkspaceQuotaState(
    val maxQuotaBytes: Long = 1_073_741_824L, // 1.00 GB exact
    val usedBytes: Long = 0L,
    val remainingBytes: Long = 1_073_741_824L,
    val currentDirectory: String = "",
    val nodesInCurrentDir: List<WorkspaceNode> = emptyList(),
    val allNodesFlat: List<WorkspaceNode> = emptyList(),
    val breakdown: List<CategoryBreakdown> = emptyList()
) {
    val usageFraction: Float
        get() = (usedBytes.toDouble() / maxQuotaBytes.toDouble()).toFloat().coerceIn(0f, 1f)
}

class MemoryWorkspaceManager(
    private val context: Context,
    private val dao: AgentDao
) {
    companion object {
        const val MAX_QUOTA_BYTES = 1_073_741_824L // 1 GB (1024 * 1024 * 1024)

        fun formatByteSize(bytes: Long): String {
            if (bytes < 1024L) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024.0) return String.format(Locale.US, "%.2f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024.0) return String.format(Locale.US, "%.2f MB", mb)
            val gb = mb / 1024.0
            return String.format(Locale.US, "%.3f GB", gb)
        }

        fun formatTimestamp(ms: Long): String {
            return SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(ms))
        }
    }

    val workspaceRoot: File = File(context.filesDir, "aether_workspace").apply {
        if (!exists()) mkdirs()
    }

    private val _quotaState = MutableStateFlow(WorkspaceQuotaState())
    val quotaState: StateFlow<WorkspaceQuotaState> = _quotaState.asStateFlow()

    val memoriesFlow: Flow<List<MemoryEntry>> = dao.observeMemories()
    val messagesFlow: Flow<List<ChatMessageEntity>> = dao.observeMessages()
    val auditLogsFlow: Flow<List<MemoryAuditLog>> = dao.observeAuditLogs()

    suspend fun initializeDefaultWorkspaceAndMemories() = withContext(Dispatchers.IO) {
        // Ensure default folders exist in the 1GB workspace
        val defaultFolders = listOf(
            "notes",
            "research",
            "scripts",
            "uploads",
            "vision_captures",
            "agent_logs"
        )
        defaultFolders.forEach { folder ->
            File(workspaceRoot, folder).apply { if (!exists()) mkdirs() }
        }

        // Seed starter workspace files if empty
        val readmeFile = File(workspaceRoot, "README_WORKSPACE.md")
        if (!readmeFile.exists()) {
            readmeFile.writeText(
                """
                # Aether 1.00 GB Autonomous Workspace
                
                This workspace is managed autonomously by **Aether Agent** and accessible to you at any time.
                
                ## Directory Architecture
                - `/notes` — Structured synthesis notes, daily briefings, and user plans.
                - `/research` — Saved web search dossiers and real-time citations.
                - `/scripts` — Automation blueprints, shell snippets, and math models.
                - `/uploads` — User-uploaded documents, PDFs, datasets, and media.
                - `/vision_captures` — Visual frames shown to Aether for multimodal inspection.
                - `/agent_logs` — Cognitive harness self-critique logs and telemetry.
                
                ## Autonomous Commands
                Aether can autonomously create folders, write files, append logs, and prune obsolete artifacts while monitoring exact byte usage against the 1,073,741,824 byte (1.00 GB) allocation.
                """.trimIndent()
            )
        }

        val archNote = File(workspaceRoot, "notes/cognitive_harness_spec.md")
        if (!archNote.exists()) {
            archNote.writeText(
                """
                # Cognitive Harness v5.5 Specification
                - Stage 1: Episodic & Semantic Memory Recall
                - Stage 2: Workspace Telemetry & Quota Inspection
                - Stage 3: Live Web Retrieval & Multimodal Grounding
                - Stage 4: Deep Chain-of-Thought Decomposition & Self-Critique
                - Stage 5: Autonomous Tool Execution (Memory CRUD / Workspace File I/O / Device Control)
                """.trimIndent()
            )
        }

        // Seed initial self-managed memories if none exist
        val currentMemories = dao.getAllMemoriesNow()
        if (currentMemories.isEmpty()) {
            dao.insertMemory(
                MemoryEntry(
                    memoryKey = "agent_identity",
                    category = "SYSTEM_RULE",
                    content = "I am Aether, a proactive personal AI entity with full autonomy over my episodic/semantic memory bank and my 1.00 GB virtual workspace.",
                    importance = 5,
                    updatedByAgent = true
                )
            )
            dao.insertMemory(
                MemoryEntry(
                    memoryKey = "workspace_policy",
                    category = "SYSTEM_RULE",
                    content = "Maintain organized directories (/notes, /research, /scripts, /uploads). Track byte consumption against the 1.00 GB workspace cap and log key findings automatically.",
                    importance = 5,
                    updatedByAgent = true
                )
            )
            dao.insertMemory(
                MemoryEntry(
                    memoryKey = "device_environment",
                    category = "CORE_FACT",
                    content = "Running on a dedicated personal Android device with direct intent-based mobile control (alarms, timers, app launcher, system settings, web navigation, and clipboard).",
                    importance = 4,
                    updatedByAgent = true
                )
            )
            dao.insertMemory(
                MemoryEntry(
                    memoryKey = " aesthetic_preference",
                    category = "PREFERENCE",
                    content = "User prefers a refined Warm Architectural Editorial UI/UX (no neon themes), ultra-responsive transitions, and modular architecture.",
                    importance = 4,
                    updatedByAgent = true
                )
            )
            dao.insertAuditLog(
                MemoryAuditLog(
                    operation = "WRITE",
                    targetKey = "bootstrap_core_memory",
                    details = "Initialized 4 foundational memory nodes and 1.00 GB workspace hierarchy."
                )
            )
        }
        refreshWorkspaceState(_quotaState.value.currentDirectory)
    }

    suspend fun refreshWorkspaceState(targetDirRelative: String = _quotaState.value.currentDirectory) =
        withContext(Dispatchers.IO) {
            val cleanRel = targetDirRelative.trim('/').replace("..", "")
            val currentDirFile = if (cleanRel.isEmpty()) workspaceRoot else File(workspaceRoot, cleanRel)
            if (!currentDirFile.exists()) currentDirFile.mkdirs()

            val allFlat = mutableListOf<WorkspaceNode>()
            var totalUsed = 0L

            fun walkDirectory(dir: File) {
                val children = dir.listFiles() ?: return
                for (child in children) {
                    val rel = child.relativeTo(workspaceRoot).path.replace("\\", "/")
                    if (child.isDirectory) {
                        val subSize = computeDirSize(child)
                        val count = child.listFiles()?.size ?: 0
                        allFlat.add(
                            WorkspaceNode(
                                name = child.name,
                                relativePath = rel,
                                isDirectory = true,
                                sizeBytes = subSize,
                                childCount = count,
                                lastModified = child.lastModified(),
                                category = "FOLDER"
                            )
                        )
                        walkDirectory(child)
                    } else {
                        val len = child.length()
                        totalUsed += len
                        allFlat.add(
                            WorkspaceNode(
                                name = child.name,
                                relativePath = rel,
                                isDirectory = false,
                                sizeBytes = len,
                                childCount = 0,
                                lastModified = child.lastModified(),
                                category = classifyFileCategory(child.name)
                            )
                        )
                    }
                }
            }

            walkDirectory(workspaceRoot)

            val directChildren = (currentDirFile.listFiles() ?: emptyArray())
                .map { child ->
                    val rel = child.relativeTo(workspaceRoot).path.replace("\\", "/")
                    if (child.isDirectory) {
                        WorkspaceNode(
                            name = child.name,
                            relativePath = rel,
                            isDirectory = true,
                            sizeBytes = computeDirSize(child),
                            childCount = child.listFiles()?.size ?: 0,
                            lastModified = child.lastModified(),
                            category = "FOLDER"
                        )
                    } else {
                        WorkspaceNode(
                            name = child.name,
                            relativePath = rel,
                            isDirectory = false,
                            sizeBytes = child.length(),
                            childCount = 0,
                            lastModified = child.lastModified(),
                            category = classifyFileCategory(child.name)
                        )
                    }
                }
                .sortedWith(compareByDescending<WorkspaceNode> { it.isDirectory }.thenBy { it.name.lowercase() })

            val filesOnly = allFlat.filter { !it.isDirectory }
            val grouped = filesOnly.groupBy { it.category }
            val breakdownList = listOf("DOC", "CODE", "IMAGE", "DATA", "LOG").mapNotNull { cat ->
                val list = grouped[cat] ?: emptyList()
                val sum = list.sumOf { it.sizeBytes }
                if (list.isEmpty()) null
                else CategoryBreakdown(
                    category = cat,
                    bytes = sum,
                    count = list.size,
                    percentageOfUsed = if (totalUsed > 0L) (sum.toFloat() / totalUsed.toFloat()) else 0f
                )
            }.sortedByDescending { it.bytes }

            val remaining = (MAX_QUOTA_BYTES - totalUsed).coerceAtLeast(0L)
            _quotaState.value = WorkspaceQuotaState(
                maxQuotaBytes = MAX_QUOTA_BYTES,
                usedBytes = totalUsed,
                remainingBytes = remaining,
                currentDirectory = cleanRel,
                nodesInCurrentDir = directChildren,
                allNodesFlat = allFlat.sortedByDescending { it.sizeBytes },
                breakdown = breakdownList
            )
        }

    private fun computeDirSize(dir: File): Long {
        var total = 0L
        val files = dir.listFiles() ?: return 0L
        for (f in files) {
            total += if (f.isDirectory) computeDirSize(f) else f.length()
        }
        return total
    }

    private fun classifyFileCategory(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "kt", "java", "py", "js", "ts", "sh", "html", "css", "sql", "c", "cpp" -> "CODE"
            "png", "jpg", "jpeg", "webp", "gif", "bmp" -> "IMAGE"
            "json", "csv", "xml", "yaml", "yml", "toml", "db" -> "DATA"
            "log", "trace" -> "LOG"
            else -> "DOC"
        }
    }

    // Memory Operations (Agent & User)
    suspend fun upsertMemory(
        key: String,
        category: String,
        content: String,
        importance: Int = 4,
        byAgent: Boolean = true
    ): MemoryEntry = withContext(Dispatchers.IO) {
        val cleanKey = key.trim().lowercase(Locale.ROOT).replace(" ", "_")
        val existing = dao.getMemoryByKey(cleanKey)
        val now = System.currentTimeMillis()
        val result = if (existing != null) {
            val updated = existing.copy(
                category = category.uppercase(Locale.ROOT),
                content = content.trim(),
                importance = importance.coerceIn(1, 5),
                updatedByAgent = byAgent,
                updatedAt = now
            )
            dao.updateMemory(updated)
            dao.insertAuditLog(
                MemoryAuditLog(
                    operation = "EDIT",
                    targetKey = cleanKey,
                    details = "${if (byAgent) "[Agent]" else "[User]"} Updated ($category): ${content.take(90)}"
                )
            )
            updated
        } else {
            val entry = MemoryEntry(
                memoryKey = cleanKey,
                category = category.uppercase(Locale.ROOT),
                content = content.trim(),
                importance = importance.coerceIn(1, 5),
                updatedByAgent = byAgent,
                createdAt = now,
                updatedAt = now
            )
            val id = dao.insertMemory(entry)
            dao.insertAuditLog(
                MemoryAuditLog(
                    operation = "WRITE",
                    targetKey = cleanKey,
                    details = "${if (byAgent) "[Agent]" else "[User]"} Created ($category): ${content.take(90)}"
                )
            )
            entry.copy(id = id)
        }
        result
    }

    suspend fun deleteMemoryByKey(key: String, byAgent: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val cleanKey = key.trim().lowercase(Locale.ROOT)
        val deletedCount = dao.deleteMemoryByKey(cleanKey)
        if (deletedCount > 0) {
            dao.insertAuditLog(
                MemoryAuditLog(
                    operation = "DELETE",
                    targetKey = cleanKey,
                    details = "${if (byAgent) "[Agent]" else "[User]"} Deleted memory key '$cleanKey'"
                )
            )
            true
        } else {
            false
        }
    }

    suspend fun deleteMemoryById(entry: MemoryEntry, byAgent: Boolean = false) = withContext(Dispatchers.IO) {
        dao.deleteMemoryById(entry.id)
        dao.insertAuditLog(
            MemoryAuditLog(
                operation = "DELETE",
                targetKey = entry.memoryKey,
                details = "${if (byAgent) "[Agent]" else "[User]"} Removed memory '${entry.memoryKey}'"
            )
        )
    }

    // Workspace Operations (Agent & User) with 1GB quota enforcement
    suspend fun createWorkspaceFolder(relativePath: String, byAgent: Boolean = true): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val safeRel = sanitizeRelativePath(relativePath)
                val folder = File(workspaceRoot, safeRel)
                if (!folder.exists()) {
                    folder.mkdirs()
                }
                dao.insertAuditLog(
                    MemoryAuditLog(
                        operation = "WORKSPACE_OP",
                        targetKey = safeRel,
                        details = "${if (byAgent) "[Agent]" else "[User]"} Created directory /$safeRel"
                    )
                )
                refreshWorkspaceState()
                folder
            }
        }

    suspend fun writeWorkspaceFile(
        relativePath: String,
        content: String,
        append: Boolean = false,
        byAgent: Boolean = true
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val safeRel = sanitizeRelativePath(relativePath)
            val bytesToWrite = content.toByteArray(Charsets.UTF_8)
            val currentUsed = _quotaState.value.usedBytes
            if (currentUsed + bytesToWrite.size > MAX_QUOTA_BYTES) {
                throw IllegalStateException("Workspace 1.00 GB quota exceeded! Cannot write ${bytesToWrite.size} bytes.")
            }
            val targetFile = File(workspaceRoot, safeRel)
            targetFile.parentFile?.mkdirs()
            if (append && targetFile.exists()) {
                targetFile.appendText("\n" + content)
            } else {
                targetFile.writeText(content)
            }
            dao.insertAuditLog(
                MemoryAuditLog(
                    operation = "WORKSPACE_OP",
                    targetKey = safeRel,
                    details = "${if (byAgent) "[Agent]" else "[User]"} Wrote ${formatByteSize(targetFile.length())} to /$safeRel"
                )
            )
            refreshWorkspaceState()
            targetFile
        }
    }

    suspend fun readWorkspaceFile(relativePath: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val safeRel = sanitizeRelativePath(relativePath)
            val file = File(workspaceRoot, safeRel)
            if (!file.exists() || file.isDirectory) {
                throw IllegalArgumentException("File not found: /$safeRel")
            }
            if (file.length() > 250_000L) {
                file.inputStream().bufferedReader().use { reader ->
                    val buf = CharArray(32_000)
                    val read = reader.read(buf)
                    String(buf, 0, read.coerceAtLeast(0)) + "\n\n... [Truncated Preview — Total Size: ${formatByteSize(file.length())}]"
                }
            } else {
                file.readText()
            }
        }
    }

    suspend fun deleteWorkspacePath(relativePath: String, byAgent: Boolean = true): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val safeRel = sanitizeRelativePath(relativePath)
                if (safeRel.isEmpty()) throw IllegalArgumentException("Cannot delete root workspace")
                val target = File(workspaceRoot, safeRel)
                val freed = if (target.isDirectory) computeDirSize(target) else target.length()
                val ok = target.deleteRecursively()
                if (ok) {
                    dao.insertAuditLog(
                        MemoryAuditLog(
                            operation = "WORKSPACE_OP",
                            targetKey = safeRel,
                            details = "${if (byAgent) "[Agent]" else "[User]"} Deleted /$safeRel (freed ${formatByteSize(freed)})"
                        )
                    )
                }
                refreshWorkspaceState()
                ok
            }
        }

    suspend fun importExternalUriToWorkspace(
        uri: Uri,
        subFolder: String = "uploads"
    ): Result<WorkspaceNode> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            var displayName = "upload_${System.currentTimeMillis()}"
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    val raw = cursor.getString(nameIndex)
                    if (!raw.isNullOrBlank()) displayName = raw.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                }
            }

            val folder = File(workspaceRoot, subFolder).apply { if (!exists()) mkdirs() }
            val destFile = File(folder, displayName)
            resolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Unable to open stream for URI")

            val rel = destFile.relativeTo(workspaceRoot).path.replace("\\", "/")
            dao.insertAuditLog(
                MemoryAuditLog(
                    operation = "WORKSPACE_OP",
                    targetKey = rel,
                    details = "[User Upload] Imported $displayName (${formatByteSize(destFile.length())}) into /$subFolder"
                )
            )
            refreshWorkspaceState()
            WorkspaceNode(
                name = destFile.name,
                relativePath = rel,
                isDirectory = false,
                sizeBytes = destFile.length(),
                lastModified = destFile.lastModified(),
                category = classifyFileCategory(destFile.name)
            )
        }
    }

    suspend fun buildAgentContextSnapshot(): String = withContext(Dispatchers.IO) {
        val memories = dao.getAllMemoriesNow()
        val q = _quotaState.value
        val sb = StringBuilder()
        sb.appendLine("=== AUTONOMOUS MEMORY BANK (${memories.size} entries) ===")
        if (memories.isEmpty()) {
            sb.appendLine("(Memory bank is currently empty)")
        } else {
            memories.forEach { m ->
                sb.appendLine("- [${m.category}] key=\"${m.memoryKey}\" (importance=${m.importance}/5): ${m.content}")
            }
        }
        sb.appendLine()
        sb.appendLine("=== 1.00 GB WORKSPACE TELEMETRY ===")
        sb.appendLine("- Total Quota: ${formatByteSize(q.maxQuotaBytes)} (${q.maxQuotaBytes} bytes)")
        sb.appendLine("- Used Space: ${formatByteSize(q.usedBytes)} (${q.usedBytes} bytes)")
        sb.appendLine("- Remaining Free Space: ${formatByteSize(q.remainingBytes)} (${q.remainingBytes} bytes)")
        sb.appendLine("- Workspace Files & Folders (${q.allNodesFlat.size} items):")
        q.allNodesFlat.take(30).forEach { node ->
            val typeTag = if (node.isDirectory) "[DIR]" else "[FILE:${node.category}]"
            sb.appendLine("  * $typeTag /${node.relativePath} — ${formatByteSize(node.sizeBytes)}")
        }
        sb.toString()
    }

    private fun sanitizeRelativePath(raw: String): String {
        return raw.trim()
            .removePrefix("/")
            .replace("\\", "/")
            .split("/")
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/")
    }
}
