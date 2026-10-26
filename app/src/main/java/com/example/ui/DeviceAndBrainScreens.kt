package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.brain.AiModelInfo
import com.example.brain.HarnessConfig
import com.example.brain.HarnessStageTelemetry
import com.example.device.DeviceTelemetrySnapshot
import com.example.device.InstalledAppSummary

@Composable
fun DeviceControlScreen(
    telemetry: DeviceTelemetrySnapshot,
    installedApps: List<InstalledAppSummary>,
    lastActionStatus: String,
    onRefreshTelemetry: () -> Unit,
    onSetAlarm: (Int, Int, String) -> Unit,
    onSetTimer: (Int, String) -> Unit,
    onToggleTorch: (Boolean) -> Unit,
    onOpenSettingsSection: (String) -> Unit,
    onLaunchApp: (String) -> Unit,
    onRunComplexAutomationMacro: (String) -> Unit
) {
    var searchAppQuery by rememberSaveable { mutableStateOf("") }
    val filteredApps = installedApps.filter {
        searchAppQuery.isBlank() ||
            it.label.contains(searchAppQuery, ignoreCase = true) ||
            it.packageName.contains(searchAppQuery, ignoreCase = true)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Hardware Telemetry Card
        item {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.PhoneAndroid,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Mobile Control Bridge",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = "${telemetry.deviceModel} • ${telemetry.androidVersion}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(
                            onClick = onRefreshTelemetry,
                            modifier = Modifier.testTag("refresh_device_telemetry_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh device telemetry"
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TelemetryStatPill("BATTERY", "${telemetry.batteryPercent}% ${if (telemetry.isCharging) "⚡" else ""}")
                        TelemetryStatPill("RAM FREE", "${telemetry.availRamGb} / ${telemetry.totalRamGb}")
                        TelemetryStatPill("NETWORK", telemetry.networkStatus)
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Status: $lastActionStatus",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        // Direct Hardware & System Actions
        item {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Instant Hardware & System Controls",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { onSetAlarm(7, 30, "Aether Morning Focus") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("device_alarm_button")
                        ) {
                            Icon(Icons.Default.AccessAlarm, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("07:30 Alarm")
                        }
                        FilledTonalButton(
                            onClick = { onSetTimer(300, "5m Cognitive Sprint") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("device_timer_button")
                        ) {
                            Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("5m Timer")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { onToggleTorch(!telemetry.torchActive) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("device_torch_button")
                        ) {
                            Icon(Icons.Default.FlashlightOn, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (telemetry.torchActive) "Torch OFF" else "Torch ON")
                        }
                        FilledTonalButton(
                            onClick = { onOpenSettingsSection("WIFI") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("device_wifi_button")
                        ) {
                            Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Wi-Fi Panel")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { onOpenSettingsSection("BLUETOOTH") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Bluetooth")
                        }
                        FilledTonalButton(
                            onClick = { onOpenSettingsSection("SETTINGS") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("System Settings")
                        }
                    }
                }
            }
        }

        // Complex Multi-Step Agent Macros
        item {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Autonomous Multi-Step Device Macros",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Dispatches a complex task across Memory, 1.00 GB Workspace, Web Search, and Mobile Control.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            onRunComplexAutomationMacro(
                                "Execute Deep Work Protocol: 1) Set a 25-minute timer labeled Deep Work. 2) Create /notes/deep_work_session.md in the 1GB workspace with a focus checklist. 3) Log my active deep work state into autonomous memory."
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("macro_deep_work_button")
                    ) {
                        Text("Run Deep Work Protocol (Timer + Workspace Note + Memory)")
                    }
                }
            }
        }

        // Installed App Launcher Bridge
        item {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Installed App Launcher (${installedApps.size} Apps)",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = searchAppQuery,
                        onValueChange = { searchAppQuery = it },
                        placeholder = { Text("Filter installed apps…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        items(filteredApps.take(20), key = { it.packageName }) { app ->
            OutlinedCard(
                onClick = { onLaunchApp(app.packageName) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = app.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = app.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Launch,
                        contentDescription = "Launch ${app.label}",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryStatPill(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun BrainHarnessScreen(
    harnessConfig: HarnessConfig,
    selectedModel: AiModelInfo,
    catalogCount: Int,
    catalogSyncStatus: String,
    recentTelemetry: List<HarnessStageTelemetry>,
    customApiKey: String,
    onUpdateConfig: ((HarnessConfig) -> HarnessConfig) -> Unit,
    onUpdateCustomApiKey: (String) -> Unit,
    onOpenModelSelector: () -> Unit,
    onSyncCatalogNow: () -> Unit
) {
    var keyDraft by rememberSaveable(customApiKey) { mutableStateOf(customApiKey) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Architecture Overview Card
        item {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Hub,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Opus-Rival Cognitive Harness v5.5",
                                style = MaterialTheme.typography.headlineMedium
                            )
                            Text(
                                text = "5-Stage Inference Amplification & Modular Architecture",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "The app is cleanly partitioned into 4 decoupled subsystems so future upgrades remain effortless:\n" +
                            "• Part 1 (`com.example.memory`): Autonomous Room Memory + 1.00 GB Workspace Manager\n" +
                            "• Part 2 (`com.example.ui`): Warm Architectural Editorial Jetpack Compose UI/UX\n" +
                            "• Part 3 (`com.example.brain`): 5-Stage Cognitive Harness, Web Search & Live Model Catalog\n" +
                            "• Part 4 (`com.example.device`): Android Hardware Telemetry, TTS & Mobile Command Executor",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Active Model & Live Catalog Card
        item {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Active Model: ${selectedModel.displayName}",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "$catalogSyncStatus ($catalogCount models)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(
                            onClick = onOpenModelSelector,
                            modifier = Modifier.testTag("brain_change_model_button")
                        ) {
                            Text("Switch Model")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "NVIDIA NIM API Key Configuration",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Reads NVIDIA_API_KEY from AI Studio Secrets (BuildConfig.NVIDIA_API_KEY), or you can supply a session override below:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = keyDraft,
                            onValueChange = { keyDraft = it },
                            placeholder = { Text("nvapi-...") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("api_key_override_input")
                        )
                        Button(
                            onClick = { onUpdateCustomApiKey(keyDraft) },
                            modifier = Modifier.testTag("save_api_key_button")
                        ) {
                            Text("Apply & Sync")
                        }
                    }
                }
            }
        }

        // Cognitive Harness Toggles
        item {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Harness Amplification Controls",
                        style = MaterialTheme.typography.titleLarge
                    )

                    HarnessSwitchRow(
                        title = "Autonomous Memory CRUD",
                        subtitle = "Allow Aether to write, edit, and delete memory nodes on its own",
                        checked = harnessConfig.enableAutonomousMemoryEdit,
                        onCheckedChange = { v -> onUpdateConfig { it.copy(enableAutonomousMemoryEdit = v) } }
                    )

                    HarnessSwitchRow(
                        title = "Autonomous 1.00 GB Workspace File I/O",
                        subtitle = "Allow Aether to create folders, write notes, and prune files autonomously",
                        checked = harnessConfig.enableAutonomousWorkspaceIO,
                        onCheckedChange = { v -> onUpdateConfig { it.copy(enableAutonomousWorkspaceIO = v) } }
                    )

                    HarnessSwitchRow(
                        title = "Real-Time Web Search Grounding",
                        subtitle = "Automatically query Wikipedia, DuckDuckGo & HackerNews live APIs",
                        checked = harnessConfig.enableAutoWebSearch,
                        onCheckedChange = { v -> onUpdateConfig { it.copy(enableAutoWebSearch = v) } }
                    )

                    HarnessSwitchRow(
                        title = "Unprompted Proactive Entity Loop",
                        subtitle = "Aether initiates helpful check-ins even if you haven't texted first",
                        checked = harnessConfig.enableProactiveInitiative,
                        onCheckedChange = { v -> onUpdateConfig { it.copy(enableProactiveInitiative = v) } }
                    )

                    HarnessSwitchRow(
                        title = "Mobile Device Command Execution",
                        subtitle = "Allow Aether to set alarms, timers, launch apps & open system panels",
                        checked = harnessConfig.enableAutonomousDeviceControl,
                        onCheckedChange = { v -> onUpdateConfig { it.copy(enableAutonomousDeviceControl = v) } }
                    )

                    Text(
                        text = "Sampling Temperature: ${String.format("%.2f", harnessConfig.temperature)}",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Slider(
                        value = harnessConfig.temperature,
                        onValueChange = { v -> onUpdateConfig { it.copy(temperature = v) } },
                        valueRange = 0.05f..0.95f
                    )
                }
            }
        }

        // Recent Stage Telemetry
        if (recentTelemetry.isNotEmpty()) {
            item {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Last Turn Stage Telemetry",
                            style = MaterialTheme.typography.titleMedium
                        )
                        recentTelemetry.forEach { stage ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${stage.stageName}: ${stage.detail}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${stage.durationMs} ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HarnessSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
