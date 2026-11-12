package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.VersionHistoryEntity
import com.example.ui.icon.AppIcon
import com.example.ui.theme.ChangeDowngraded
import com.example.ui.theme.ChangeInstalled
import com.example.ui.theme.ChangeUninstalled
import com.example.ui.theme.ChangeUpdated
import com.example.ui.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppDetailScreen(
    packageName: String,
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val appFlow = remember(packageName) { viewModel.getAppDetailFlow(packageName) }
    val historyFlow = remember(packageName) { viewModel.getAppHistoryFlow(packageName) }

    val app by appFlow.collectAsStateWithLifecycle(initialValue = null)
    val historyList by historyFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val latestHistory = historyList.firstOrNull()
    val displayAppName = app?.appName ?: latestHistory?.appName ?: packageName
    val displayVersionName = app?.versionName ?: latestHistory?.let {
        if (it.changeType == "UNINSTALLED") it.oldVersionName else it.newVersionName
    } ?: "Unknown"
    val displayVersionCode = app?.versionCode ?: latestHistory?.let {
        if (it.changeType == "UNINSTALLED") it.oldVersionCode else it.newVersionCode
    } ?: 0L
    val isInstalled = app?.isInstalled ?: (latestHistory?.changeType != "UNINSTALLED")

    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
    val dateOnlyFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = displayAppName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("app_detail_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("app_detail_screen"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(
                                packageName = packageName,
                                size = 68.dp,
                                contentDescription = "$displayAppName icon"
                            )

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = displayAppName,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(modifier = Modifier.height(2.dp))

                                Text(
                                    text = packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (app?.isSystemApp == true) {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.primaryContainer
                                        }
                                    ) {
                                        Text(
                                            text = if (app?.isSystemApp == true) "System App" else "User App",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (app?.isSystemApp == true) {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            } else {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            },
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }

                                    if (!isInstalled) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = ChangeUninstalled.copy(alpha = 0.16f)
                                        ) {
                                            Text(
                                                text = "Uninstalled",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = ChangeUninstalled,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Current version & dates grid
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = if (isInstalled) "Current Version" else "Last Known Version",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "v$displayVersionName ($displayVersionCode)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Installed On",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    val firstInstall = app?.firstInstallTime ?: historyList.lastOrNull()?.systemUpdateTime ?: 0L
                                    Text(
                                        text = if (firstInstall > 0L) dateOnlyFormat.format(Date(firstInstall)) else "Unknown",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Last System Update",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    val lastUpdate = app?.lastUpdateTime ?: latestHistory?.systemUpdateTime ?: 0L
                                    Text(
                                        text = if (lastUpdate > 0L) dateOnlyFormat.format(Date(lastUpdate)) else "Unknown",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Action Buttons: Open in Settings and Launch App
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    try {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.parse("package:$packageName")
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        try {
                                            val intent = Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            val intent = Intent(Settings.ACTION_SETTINGS)
                                            context.startActivity(intent)
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("open_settings_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Open in Settings")
                            }

                            val launchIntent = remember(packageName, isInstalled, displayVersionCode) {
                                if (isInstalled) {
                                    runCatching { context.packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()
                                } else {
                                    null
                                }
                            }
                            if (launchIntent != null && isInstalled) {
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            context.startActivity(launchIntent)
                                        } catch (_: Exception) {
                                            viewModel.refreshScan()
                                        }
                                    },
                                    modifier = Modifier.testTag("launch_app_button"),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Open")
                                }
                            }
                        }
                    }
                }
            }

            // Timeline Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Version Timeline (${historyList.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Tracked permanently",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Timeline Items
            if (historyList.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(modifier = Modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = "No version history recorded yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = historyList,
                    key = { _, item -> item.id }
                ) { index, item ->
                    TimelineItemRow(
                        item = item,
                        isFirst = index == 0,
                        isLast = index == historyList.size - 1,
                        dateFormat = dateFormat
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimelineItemRow(
    item: VersionHistoryEntity,
    isFirst: Boolean,
    isLast: Boolean,
    dateFormat: SimpleDateFormat
) {
    val (typeColor, typeText) = when (item.changeType) {
        "UPDATED" -> Pair(ChangeUpdated, "UPDATED")
        "DOWNGRADED" -> Pair(ChangeDowngraded, "DOWNGRADED")
        "UNINSTALLED" -> Pair(ChangeUninstalled, "UNINSTALLED")
        else -> Pair(ChangeInstalled, "INSTALLED")
    }
    val typeBg = typeColor.copy(alpha = 0.16f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = 16.dp)
            .testTag("timeline_item_${item.id}")
    ) {
        // Vertical indicator line with node that adapts to dynamic card height
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(32.dp)
                .fillMaxHeight()
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(14.dp)
                    .background(if (isFirst) Color.Transparent else MaterialTheme.colorScheme.outlineVariant)
            )

            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(typeColor, CircleShape)
            )

            Box(
                modifier = Modifier
                    .width(2.dp)
                    .weight(1f)
                    .background(if (isLast) Color.Transparent else MaterialTheme.colorScheme.outlineVariant)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Content Card
        Card(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 8.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(1.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = typeBg
                    ) {
                        Text(
                            text = typeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = typeColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = dateFormat.format(Date(item.detectedTime)),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Old -> New version transition
                if (item.changeType == "UNINSTALLED") {
                    FlowRow(
                        verticalArrangement = Arrangement.Center,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "v${item.oldVersionName ?: "Unknown"} (${item.oldVersionCode ?: "?"})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "transitioned to",
                            modifier = Modifier
                                .size(14.dp)
                                .align(Alignment.CenterVertically),
                            tint = ChangeUninstalled
                        )
                        Text(
                            text = "Uninstalled",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = ChangeUninstalled
                        )
                    }
                } else if (item.oldVersionName != null) {
                    FlowRow(
                        verticalArrangement = Arrangement.Center,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "v${item.oldVersionName} (${item.oldVersionCode ?: "?"})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "transitioned to",
                            modifier = Modifier
                                .size(14.dp)
                                .align(Alignment.CenterVertically),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "v${item.newVersionName} (${item.newVersionCode})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                } else {
                    Text(
                        text = "Initial baseline: v${item.newVersionName} (${item.newVersionCode})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (item.systemUpdateTime > 0 && item.systemUpdateTime != item.detectedTime) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "OS update time: ${dateFormat.format(Date(item.systemUpdateTime))}",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
