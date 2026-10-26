package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.brain.AgentOrchestratorViewModel
import com.example.brain.AppSection
import com.example.ui.AgentChatScreen
import com.example.ui.BrainHarnessScreen
import com.example.ui.DeviceControlScreen
import com.example.ui.MemoryQuickInspectorSheet
import com.example.ui.MemoryVaultScreen
import com.example.ui.WorkspaceExplorerScreen
import com.example.ui.theme.MyApplicationTheme
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                AetherAgentRootApp()
            }
        }
    }
}

@Composable
fun AetherAgentRootApp(
    viewModel: AgentOrchestratorViewModel = viewModel()
) {
    val context = LocalContext.current

    val currentSection by viewModel.currentSection.collectAsStateWithLifecycle()
    val isMemoryDrawerOpen by viewModel.isMemoryDrawerOpen.collectAsStateWithLifecycle()
    val isModelSheetOpen by viewModel.isModelSheetOpen.collectAsStateWithLifecycle()
    val isThinking by viewModel.isThinking.collectAsStateWithLifecycle()
    val pendingAttachment by viewModel.pendingAttachment.collectAsStateWithLifecycle()
    val customApiKey by viewModel.customApiKeyOverride.collectAsStateWithLifecycle()
    val selectedWorkspaceFile by viewModel.selectedWorkspaceFileContent.collectAsStateWithLifecycle()
    val proactiveCountdownSec by viewModel.proactivePulseCountdownSec.collectAsStateWithLifecycle()

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val auditLogs by viewModel.auditLogs.collectAsStateWithLifecycle()
    val quotaState by viewModel.quotaState.collectAsStateWithLifecycle()

    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()
    val catalogSyncStatus by viewModel.catalogSyncStatus.collectAsStateWithLifecycle()
    val isCatalogSyncing by viewModel.isCatalogSyncing.collectAsStateWithLifecycle()

    val harnessConfig by viewModel.harnessConfig.collectAsStateWithLifecycle()
    val activeStageStatus by viewModel.activeStageStatus.collectAsStateWithLifecycle()
    val recentTelemetry by viewModel.recentTelemetry.collectAsStateWithLifecycle()

    val isSoundEnabled by viewModel.isSoundEnabled.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()
    val deviceTelemetry by viewModel.deviceTelemetry.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val lastDeviceActionStatus by viewModel.lastDeviceActionStatus.collectAsStateWithLifecycle()

    // Universal Document / File / Image / Archive Picker ("upload everything")
    val universalFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.attachUriFromPicker(uri)
        }
    }

    // Camera Capture Launcher for showing live real-world scenes to Aether
    var tempCameraFile by remember { mutableStateOf<File?>(null) }
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) {
            tempCameraFile?.let { file ->
                viewModel.attachCapturedCameraFile(file)
            }
        }
    }

    val launchCameraCapture = {
        runCatching {
            val file = File(context.cacheDir, "vision_${System.currentTimeMillis()}.jpg")
            tempCameraFile = file
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            takePictureLauncher.launch(uri)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) {
            launchCameraCapture()
        }
    }

    // BackHandler for non-Home sections
    BackHandler(enabled = currentSection != AppSection.AGENT_CORE) {
        viewModel.navigateToSection(AppSection.AGENT_CORE)
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    selected = currentSection == AppSection.AGENT_CORE,
                    onClick = { viewModel.navigateToSection(AppSection.AGENT_CORE) },
                    icon = {
                        Icon(
                            imageVector = if (currentSection == AppSection.AGENT_CORE) {
                                Icons.Filled.Memory
                            } else {
                                Icons.Outlined.Memory
                            },
                            contentDescription = stringResource(R.string.nav_agent)
                        )
                    },
                    label = { Text(stringResource(R.string.nav_agent)) },
                    modifier = Modifier.testTag("nav_tab_agent")
                )

                NavigationBarItem(
                    selected = currentSection == AppSection.MEMORY_VAULT,
                    onClick = { viewModel.navigateToSection(AppSection.MEMORY_VAULT) },
                    icon = {
                        Icon(
                            imageVector = if (currentSection == AppSection.MEMORY_VAULT) {
                                Icons.Filled.Psychology
                            } else {
                                Icons.Outlined.Psychology
                            },
                            contentDescription = stringResource(R.string.nav_memory)
                        )
                    },
                    label = { Text("${stringResource(R.string.nav_memory)} (${memories.size})") },
                    modifier = Modifier.testTag("nav_tab_memory")
                )

                NavigationBarItem(
                    selected = currentSection == AppSection.WORKSPACE_1GB,
                    onClick = { viewModel.navigateToSection(AppSection.WORKSPACE_1GB) },
                    icon = {
                        Icon(
                            imageVector = if (currentSection == AppSection.WORKSPACE_1GB) {
                                Icons.Filled.FolderSpecial
                            } else {
                                Icons.Outlined.FolderSpecial
                            },
                            contentDescription = stringResource(R.string.nav_workspace)
                        )
                    },
                    label = { Text(stringResource(R.string.nav_workspace)) },
                    modifier = Modifier.testTag("nav_tab_workspace")
                )

                NavigationBarItem(
                    selected = currentSection == AppSection.DEVICE_HUB,
                    onClick = { viewModel.navigateToSection(AppSection.DEVICE_HUB) },
                    icon = {
                        Icon(
                            imageVector = if (currentSection == AppSection.DEVICE_HUB) {
                                Icons.Filled.PhoneAndroid
                            } else {
                                Icons.Outlined.PhoneAndroid
                            },
                            contentDescription = stringResource(R.string.nav_device)
                        )
                    },
                    label = { Text(stringResource(R.string.nav_device)) },
                    modifier = Modifier.testTag("nav_tab_device")
                )

                NavigationBarItem(
                    selected = currentSection == AppSection.BRAIN_HARNESS,
                    onClick = { viewModel.navigateToSection(AppSection.BRAIN_HARNESS) },
                    icon = {
                        Icon(
                            imageVector = if (currentSection == AppSection.BRAIN_HARNESS) {
                                Icons.Filled.Hub
                            } else {
                                Icons.Outlined.Hub
                            },
                            contentDescription = stringResource(R.string.nav_harness)
                        )
                    },
                    label = { Text(stringResource(R.string.nav_harness)) },
                    modifier = Modifier.testTag("nav_tab_brain")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Crossfade(
                targetState = currentSection,
                animationSpec = tween(durationMillis = 200),
                label = "section_crossfade"
            ) { section ->
                when (section) {
                    AppSection.AGENT_CORE -> {
                        AgentChatScreen(
                            messages = messages,
                            memoriesCount = memories.size,
                            quotaState = quotaState,
                            selectedModel = selectedModel,
                            catalog = catalog,
                            catalogSyncStatus = catalogSyncStatus,
                            isCatalogSyncing = isCatalogSyncing,
                            isSoundEnabled = isSoundEnabled,
                            isSpeaking = isSpeaking,
                            isThinking = isThinking,
                            activeStageStatus = activeStageStatus,
                            pendingAttachment = pendingAttachment,
                            proactiveCountdownSec = proactiveCountdownSec,
                            isModelSheetOpen = isModelSheetOpen,
                            onToggleSound = { viewModel.toggleSoundOutput() },
                            onStopSpeaking = { viewModel.stopSpeaking() },
                            onSpeakMessage = { text -> viewModel.speakMessageNow(text) },
                            onOpenMemoryModal = { viewModel.setMemoryDrawerOpen(true) },
                            onOpenWorkspaceTab = { viewModel.navigateToSection(AppSection.WORKSPACE_1GB) },
                            onOpenModelSelector = { viewModel.setModelSheetOpen(true) },
                            onDismissModelSelector = { viewModel.setModelSheetOpen(false) },
                            onSelectModel = { modelId -> viewModel.selectModel(modelId) },
                            onRefreshCatalog = { viewModel.refreshModelCatalog() },
                            onRequestUploadFile = {
                                universalFilePicker.launch(arrayOf("*/*"))
                            },
                            onRequestCameraCapture = {
                                val hasCamPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasCamPerm) {
                                    launchCameraCapture()
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            onClearAttachment = { viewModel.clearPendingAttachment() },
                            onSendMessage = { prompt -> viewModel.sendUserMessage(prompt) },
                            onTriggerProactiveNow = { viewModel.triggerProactiveAgentMessage() },
                            onOpenUrl = { url -> viewModel.deviceController.openWebUrl(url) }
                        )
                    }

                    AppSection.MEMORY_VAULT -> {
                        MemoryVaultScreen(
                            memories = memories,
                            auditLogs = auditLogs,
                            onSaveMemory = { k, c, content, imp ->
                                viewModel.saveMemoryFromUi(k, c, content, imp)
                            },
                            onDeleteMemory = { entry -> viewModel.deleteMemoryFromUi(entry) }
                        )
                    }

                    AppSection.WORKSPACE_1GB -> {
                        WorkspaceExplorerScreen(
                            quotaState = quotaState,
                            selectedFileContent = selectedWorkspaceFile,
                            onOpenDirectory = { rel -> viewModel.openWorkspaceDirectory(rel) },
                            onNavigateUp = { viewModel.navigateUpWorkspaceDirectory() },
                            onInspectNode = { node -> viewModel.inspectWorkspaceFile(node) },
                            onCloseFileInspector = { viewModel.closeWorkspaceFileInspector() },
                            onCreateFolder = { name -> viewModel.createWorkspaceFolderFromUi(name) },
                            onCreateOrUpdateFile = { name, content ->
                                viewModel.createOrUpdateWorkspaceFileFromUi(name, content)
                            },
                            onDeleteNode = { node -> viewModel.deleteWorkspaceNodeFromUi(node) },
                            onUploadExternalFile = {
                                universalFilePicker.launch(arrayOf("*/*"))
                            }
                        )
                    }

                    AppSection.DEVICE_HUB -> {
                        DeviceControlScreen(
                            telemetry = deviceTelemetry,
                            installedApps = installedApps,
                            lastActionStatus = lastDeviceActionStatus,
                            onRefreshTelemetry = { viewModel.deviceController.refreshDeviceTelemetry() },
                            onSetAlarm = { h, m, label ->
                                viewModel.deviceController.setSystemAlarm(h, m, label)
                            },
                            onSetTimer = { sec, label ->
                                viewModel.deviceController.setSystemTimer(sec, label)
                            },
                            onToggleTorch = { enable ->
                                viewModel.deviceController.setFlashlight(enable)
                            },
                            onOpenSettingsSection = { sec ->
                                viewModel.deviceController.openSystemSettings(sec)
                            },
                            onLaunchApp = { pkg ->
                                viewModel.deviceController.launchAppByNameOrPackage(pkg)
                            },
                            onRunComplexAutomationMacro = { macroPrompt ->
                                viewModel.navigateToSection(AppSection.AGENT_CORE)
                                viewModel.sendUserMessage(macroPrompt)
                            }
                        )
                    }

                    AppSection.BRAIN_HARNESS -> {
                        BrainHarnessScreen(
                            harnessConfig = harnessConfig,
                            selectedModel = selectedModel,
                            catalogCount = catalog.size,
                            catalogSyncStatus = catalogSyncStatus,
                            recentTelemetry = recentTelemetry,
                            customApiKey = customApiKey,
                            onUpdateConfig = { transform ->
                                viewModel.harnessEngine.updateHarnessConfig(transform)
                            },
                            onUpdateCustomApiKey = { key -> viewModel.updateCustomApiKey(key) },
                            onOpenModelSelector = {
                                viewModel.navigateToSection(AppSection.AGENT_CORE)
                                viewModel.setModelSheetOpen(true)
                            },
                            onSyncCatalogNow = { viewModel.refreshModelCatalog() }
                        )
                    }
                }
            }
        }
    }

    if (isMemoryDrawerOpen) {
        MemoryQuickInspectorSheet(
            memories = memories,
            quotaState = quotaState,
            onDismiss = { viewModel.setMemoryDrawerOpen(false) },
            onJumpToFullMemoryTab = {
                viewModel.setMemoryDrawerOpen(false)
                viewModel.navigateToSection(AppSection.MEMORY_VAULT)
            }
        )
    }
}
