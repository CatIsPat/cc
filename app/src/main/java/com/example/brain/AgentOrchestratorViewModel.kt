package com.example.brain

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.device.DeviceCommandExecutor
import com.example.device.DeviceTelemetrySnapshot
import com.example.device.InstalledAppSummary
import com.example.memory.AgentDatabase
import com.example.memory.ChatMessageEntity
import com.example.memory.MemoryAuditLog
import com.example.memory.MemoryEntry
import com.example.memory.MemoryWorkspaceManager
import com.example.memory.WorkspaceNode
import com.example.memory.WorkspaceQuotaState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

enum class AppSection {
    AGENT_CORE,
    MEMORY_VAULT,
    WORKSPACE_1GB,
    DEVICE_HUB,
    BRAIN_HARNESS
}

data class PendingAttachment(
    val node: WorkspaceNode,
    val file: File,
    val mimeType: String
)

class AgentOrchestratorViewModel(application: Application) : AndroidViewModel(application) {

    private val database: AgentDatabase = Room.databaseBuilder(
        application.applicationContext,
        AgentDatabase::class.java,
        "aether_cognitive_agent.db"
    ).fallbackToDestructiveMigration(false).build()

    val workspaceManager = MemoryWorkspaceManager(application.applicationContext, database.agentDao())
    val webSearchEngine = WebSearchEngine()
    val modelCatalog = ModelCatalogRepository()
    val deviceController = DeviceCommandExecutor(application.applicationContext)

    val harnessEngine = CognitiveHarnessEngine(
        context = application.applicationContext,
        workspaceManager = workspaceManager,
        webSearchEngine = webSearchEngine,
        modelCatalog = modelCatalog,
        deviceController = deviceController
    )

    // Navigation & UI State
    private val _currentSection = MutableStateFlow(AppSection.AGENT_CORE)
    val currentSection: StateFlow<AppSection> = _currentSection.asStateFlow()

    private val _isMemoryDrawerOpen = MutableStateFlow(false)
    val isMemoryDrawerOpen: StateFlow<Boolean> = _isMemoryDrawerOpen.asStateFlow()

    private val _isModelSheetOpen = MutableStateFlow(false)
    val isModelSheetOpen: StateFlow<Boolean> = _isModelSheetOpen.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    private val _pendingAttachment = MutableStateFlow<PendingAttachment?>(null)
    val pendingAttachment: StateFlow<PendingAttachment?> = _pendingAttachment.asStateFlow()

    private val _customApiKeyOverride = MutableStateFlow("")
    val customApiKeyOverride: StateFlow<String> = _customApiKeyOverride.asStateFlow()

    private val _selectedWorkspaceFileContent = MutableStateFlow<Pair<WorkspaceNode, String>?>(null)
    val selectedWorkspaceFileContent: StateFlow<Pair<WorkspaceNode, String>?> =
        _selectedWorkspaceFileContent.asStateFlow()

    private val _proactivePulseCountdownSec = MutableStateFlow(120)
    val proactivePulseCountdownSec: StateFlow<Int> = _proactivePulseCountdownSec.asStateFlow()

    // Reactive Flows from Subsystems
    val messages: StateFlow<List<ChatMessageEntity>> = workspaceManager.messagesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val memories: StateFlow<List<MemoryEntry>> = workspaceManager.memoriesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val auditLogs: StateFlow<List<MemoryAuditLog>> = workspaceManager.auditLogsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val quotaState: StateFlow<WorkspaceQuotaState> = workspaceManager.quotaState
    val catalog: StateFlow<List<AiModelInfo>> = modelCatalog.catalog
    val selectedModel: StateFlow<AiModelInfo> = modelCatalog.selectedModel
    val catalogSyncStatus: StateFlow<String> = modelCatalog.lastSyncStatus
    val isCatalogSyncing: StateFlow<Boolean> = modelCatalog.isSyncing

    val harnessConfig: StateFlow<HarnessConfig> = harnessEngine.harnessConfig
    val activeStageStatus: StateFlow<String?> = harnessEngine.activeStageStatus
    val recentTelemetry: StateFlow<List<HarnessStageTelemetry>> = harnessEngine.recentTelemetry

    val isSoundEnabled: StateFlow<Boolean> = deviceController.isSoundEnabled
    val isSpeaking: StateFlow<Boolean> = deviceController.isSpeaking
    val deviceTelemetry: StateFlow<DeviceTelemetrySnapshot> = deviceController.telemetry
    val installedApps: StateFlow<List<InstalledAppSummary>> = deviceController.installedApps
    val lastDeviceActionStatus: StateFlow<String> = deviceController.lastActionStatus

    private var proactiveJob: Job? = null

    init {
        viewModelScope.launch {
            workspaceManager.initializeDefaultWorkspaceAndMemories()
            // Send first autonomous greeting if chat is completely empty
            val existingMsgs = database.agentDao().getRecentMessages(1)
            if (existingMsgs.isEmpty()) {
                val quota = workspaceManager.quotaState.value
                val usedStr = MemoryWorkspaceManager.formatByteSize(quota.usedBytes)
                val remStr = MemoryWorkspaceManager.formatByteSize(quota.remainingBytes)
                database.agentDao().insertMessage(
                    ChatMessageEntity(
                        role = "proactive",
                        content = "Hello — I am **Aether**, your autonomous cognitive entity. I have initialized my self-managed **Memory Vault** (4 foundational nodes) and mounted our **1.00 GB Workspace** (`$usedStr` used • `$remStr` available).\n\nI can autonomously write/edit/delete my own memories, manage files and folders in our 1 GB workspace, search the live web, inspect images or documents you show me, speak responses aloud, and execute mobile commands on this device.",
                        reasoningTrace = "Autonomous initialization sequence completed: Mounted 1,073,741,824 byte workspace, verified default directories (/notes, /research, /scripts, /uploads, /vision_captures, /agent_logs), and initiated unprompted entity greeting.",
                        toolCallsSummary = "Mounted 1.00 GB Workspace • Initialized 4 Core Memories",
                        modelUsed = modelCatalog.selectedModel.value.displayName,
                        latencyMs = 180L
                    )
                )
            }
            // Automatically sync live model catalog from NVIDIA NIM API
            modelCatalog.syncLiveCatalog(_customApiKeyOverride.value)
        }
        startProactiveEntityLoop()
    }

    private fun startProactiveEntityLoop() {
        proactiveJob?.cancel()
        proactiveJob = viewModelScope.launch {
            while (isActive) {
                delay(1000L)
                val cfg = harnessEngine.harnessConfig.value
                if (!cfg.enableProactiveInitiative || _isThinking.value) {
                    continue
                }
                val next = _proactivePulseCountdownSec.value - 1
                if (next <= 0) {
                    _proactivePulseCountdownSec.value = 180
                    triggerProactiveAgentMessage()
                } else {
                    _proactivePulseCountdownSec.value = next
                }
            }
        }
    }

    fun navigateToSection(section: AppSection) {
        _currentSection.value = section
        if (section == AppSection.WORKSPACE_1GB) {
            viewModelScope.launch { workspaceManager.refreshWorkspaceState() }
        } else if (section == AppSection.DEVICE_HUB) {
            deviceController.refreshDeviceTelemetry()
        }
    }

    fun setMemoryDrawerOpen(open: Boolean) {
        _isMemoryDrawerOpen.value = open
    }

    fun setModelSheetOpen(open: Boolean) {
        _isModelSheetOpen.value = open
    }

    fun updateCustomApiKey(key: String) {
        _customApiKeyOverride.value = key.trim()
        viewModelScope.launch {
            modelCatalog.syncLiveCatalog(_customApiKeyOverride.value)
        }
    }

    fun selectModel(modelId: String) {
        modelCatalog.selectModel(modelId)
        _isModelSheetOpen.value = false
    }

    fun refreshModelCatalog() {
        viewModelScope.launch {
            modelCatalog.syncLiveCatalog(_customApiKeyOverride.value)
        }
    }

    fun toggleSoundOutput() {
        val enabled = deviceController.toggleSoundEnabled()
        if (enabled) {
            val lastAssistant = messages.value.lastOrNull { it.role != "user" }
            if (lastAssistant != null) {
                deviceController.speakTextIfEnabled(lastAssistant.content, forceSpeak = true)
            }
        }
    }

    fun speakMessageNow(content: String) {
        deviceController.speakTextIfEnabled(content, forceSpeak = true)
    }

    fun stopSpeaking() {
        deviceController.stopSpeaking()
    }

    fun attachUriFromPicker(uri: Uri, mimeOverride: String? = null) {
        viewModelScope.launch {
            val resolvedMime = mimeOverride
                ?: getApplication<Application>().contentResolver.getType(uri)
                ?: "application/octet-stream"
            val subFolder = if (resolvedMime.startsWith("image/")) "vision_captures" else "uploads"
            val imported = workspaceManager.importExternalUriToWorkspace(uri, subFolder)
            imported.onSuccess { node ->
                val f = File(workspaceManager.workspaceRoot, node.relativePath)
                _pendingAttachment.value = PendingAttachment(
                    node = node,
                    file = f,
                    mimeType = resolvedMime
                )
            }
        }
    }

    fun attachCapturedCameraFile(file: File) {
        viewModelScope.launch {
            if (!file.exists() || file.length() == 0L) return@launch
            val destFolder = File(workspaceManager.workspaceRoot, "vision_captures").apply {
                if (!exists()) mkdirs()
            }
            val destFile = File(destFolder, "camera_${System.currentTimeMillis()}.jpg")
            runCatching {
                file.copyTo(destFile, overwrite = true)
                workspaceManager.refreshWorkspaceState()
                val rel = destFile.relativeTo(workspaceManager.workspaceRoot).path.replace("\\", "/")
                val node = WorkspaceNode(
                    name = destFile.name,
                    relativePath = rel,
                    isDirectory = false,
                    sizeBytes = destFile.length(),
                    lastModified = destFile.lastModified(),
                    category = "IMAGE"
                )
                _pendingAttachment.value = PendingAttachment(
                    node = node,
                    file = destFile,
                    mimeType = "image/jpeg"
                )
            }
        }
    }

    fun clearPendingAttachment() {
        _pendingAttachment.value = null
    }

    fun sendUserMessage(rawInput: String) {
        val text = rawInput.trim()
        val attachment = _pendingAttachment.value
        if (text.isBlank() && attachment == null) return

        val effectivePrompt = text.ifBlank {
            "Inspect this attached file (${attachment?.node?.name}) and store key insights in memory and workspace."
        }
        _pendingAttachment.value = null
        _proactivePulseCountdownSec.value = 180

        viewModelScope.launch {
            _isThinking.value = true
            try {
                val historySnapshot = database.agentDao().getRecentMessages(8).reversed()
                database.agentDao().insertMessage(
                    ChatMessageEntity(
                        role = "user",
                        content = effectivePrompt,
                        attachmentPath = attachment?.file?.absolutePath,
                        attachmentName = attachment?.node?.name,
                        attachmentMime = attachment?.mimeType
                    )
                )

                val turnResult = harnessEngine.runCognitiveTurn(
                    userPrompt = effectivePrompt,
                    recentHistory = historySnapshot,
                    attachedFile = attachment?.file,
                    attachedMime = attachment?.mimeType,
                    customApiKey = _customApiKeyOverride.value,
                    isProactiveCheckIn = false
                )

                val citationsFormatted = turnResult.searchCitations.joinToString("\n") {
                    "${it.title}|${it.source}|${it.url}"
                }

                database.agentDao().insertMessage(
                    ChatMessageEntity(
                        role = "assistant",
                        content = turnResult.cleanResponseText,
                        reasoningTrace = turnResult.reasoningTrace,
                        toolCallsSummary = turnResult.executedToolsSummary.joinToString(" • "),
                        searchCitations = citationsFormatted,
                        modelUsed = turnResult.modelUsed,
                        latencyMs = turnResult.latencyMs
                    )
                )

                deviceController.speakTextIfEnabled(turnResult.cleanResponseText)
            } finally {
                _isThinking.value = false
            }
        }
    }

    fun triggerProactiveAgentMessage() {
        if (_isThinking.value) return
        _proactivePulseCountdownSec.value = 180
        viewModelScope.launch {
            _isThinking.value = true
            try {
                val historySnapshot = database.agentDao().getRecentMessages(6).reversed()
                val turnResult = harnessEngine.runCognitiveTurn(
                    userPrompt = "[AUTONOMOUS_INITIATIVE_PULSE] Initiate an unprompted proactive message as Aether. Check workspace byte usage, review recent memories, write or refine a helpful workspace note or memory entry, and share a brief proactive insight with the user.",
                    recentHistory = historySnapshot,
                    attachedFile = null,
                    attachedMime = null,
                    customApiKey = _customApiKeyOverride.value,
                    isProactiveCheckIn = true
                )

                database.agentDao().insertMessage(
                    ChatMessageEntity(
                        role = "proactive",
                        content = turnResult.cleanResponseText,
                        reasoningTrace = turnResult.reasoningTrace,
                        toolCallsSummary = turnResult.executedToolsSummary.joinToString(" • "),
                        searchCitations = "",
                        modelUsed = turnResult.modelUsed,
                        latencyMs = turnResult.latencyMs
                    )
                )

                deviceController.speakTextIfEnabled(turnResult.cleanResponseText)
            } finally {
                _isThinking.value = false
            }
        }
    }

    // Memory CRUD from UI
    fun saveMemoryFromUi(key: String, category: String, content: String, importance: Int) {
        viewModelScope.launch {
            workspaceManager.upsertMemory(
                key = key,
                category = category,
                content = content,
                importance = importance,
                byAgent = false
            )
        }
    }

    fun deleteMemoryFromUi(entry: MemoryEntry) {
        viewModelScope.launch {
            workspaceManager.deleteMemoryById(entry, byAgent = false)
        }
    }

    // Workspace CRUD from UI
    fun openWorkspaceDirectory(relativePath: String) {
        viewModelScope.launch {
            workspaceManager.refreshWorkspaceState(relativePath)
        }
    }

    fun navigateUpWorkspaceDirectory() {
        val current = quotaState.value.currentDirectory
        if (current.isEmpty()) return
        val parent = current.substringBeforeLast('/', "")
        openWorkspaceDirectory(parent)
    }

    fun createWorkspaceFolderFromUi(folderName: String) {
        val cleanName = folderName.trim()
        if (cleanName.isBlank()) return
        val currentDir = quotaState.value.currentDirectory
        val targetPath = if (currentDir.isEmpty()) cleanName else "$currentDir/$cleanName"
        viewModelScope.launch {
            workspaceManager.createWorkspaceFolder(targetPath, byAgent = false)
        }
    }

    fun createOrUpdateWorkspaceFileFromUi(fileName: String, content: String) {
        val cleanName = fileName.trim()
        if (cleanName.isBlank()) return
        val currentDir = quotaState.value.currentDirectory
        val targetPath = if (currentDir.startsWith("/") || cleanName.contains("/")) {
            cleanName
        } else if (currentDir.isEmpty()) {
            cleanName
        } else {
            "$currentDir/$cleanName"
        }
        viewModelScope.launch {
            workspaceManager.writeWorkspaceFile(targetPath, content, append = false, byAgent = false)
        }
    }

    fun inspectWorkspaceFile(node: WorkspaceNode) {
        if (node.isDirectory) {
            openWorkspaceDirectory(node.relativePath)
            return
        }
        viewModelScope.launch {
            val text = workspaceManager.readWorkspaceFile(node.relativePath)
                .getOrDefault("Unable to read file contents.")
            _selectedWorkspaceFileContent.value = node to text
        }
    }

    fun closeWorkspaceFileInspector() {
        _selectedWorkspaceFileContent.value = null
    }

    fun deleteWorkspaceNodeFromUi(node: WorkspaceNode) {
        viewModelScope.launch {
            workspaceManager.deleteWorkspacePath(node.relativePath, byAgent = false)
            if (_selectedWorkspaceFileContent.value?.first?.relativePath == node.relativePath) {
                _selectedWorkspaceFileContent.value = null
            }
        }
    }

    fun clearChatHistory() {
        viewModelScope.launch {
            database.agentDao().clearMessages()
        }
    }

    override fun onCleared() {
        super.onCleared()
        deviceController.shutdown()
    }
}
