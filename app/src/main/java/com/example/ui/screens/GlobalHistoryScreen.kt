package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.example.ui.viewmodel.HistoryFilter
import com.example.ui.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GlobalHistoryScreen(
    viewModel: MainViewModel,
    onAppClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val groupedHistory by viewModel.groupedHistory.collectAsStateWithLifecycle()
    val allHistory by viewModel.allHistory.collectAsStateWithLifecycle()
    val historyFilter by viewModel.historyFilter.collectAsStateWithLifecycle()
    val historySearchQuery by viewModel.historySearchQuery.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()

    val updatedCount = remember(allHistory) { allHistory.count { it.changeType == "UPDATED" } }
    val downgradedCount = remember(allHistory) { allHistory.count { it.changeType == "DOWNGRADED" } }
    val installedCount = remember(allHistory) { allHistory.count { it.changeType == "INSTALLED" } }
    val uninstalledCount = remember(allHistory) { allHistory.count { it.changeType == "UNINSTALLED" } }

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("global_history_screen")
    ) {
        // Search bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = historySearchQuery,
                onValueChange = { viewModel.onHistorySearchQueryChanged(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("history_search_text_field"),
                placeholder = { Text("Search version events...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search version events")
                },
                trailingIcon = {
                    if (historySearchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onHistorySearchQueryChanged("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear history search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )
        }

        // Filter chips: All, Updated, Downgraded, Installed, Uninstalled
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = historyFilter == HistoryFilter.ALL,
                onClick = { viewModel.onHistoryFilterSelected(HistoryFilter.ALL) },
                label = { Text("All (${allHistory.size})") },
                modifier = Modifier.testTag("history_filter_all")
            )
            FilterChip(
                selected = historyFilter == HistoryFilter.UPDATED,
                onClick = { viewModel.onHistoryFilterSelected(HistoryFilter.UPDATED) },
                label = { Text("Updated ($updatedCount)") },
                modifier = Modifier.testTag("history_filter_updated")
            )
            FilterChip(
                selected = historyFilter == HistoryFilter.DOWNGRADED,
                onClick = { viewModel.onHistoryFilterSelected(HistoryFilter.DOWNGRADED) },
                label = { Text("Downgraded ($downgradedCount)") },
                modifier = Modifier.testTag("history_filter_downgraded")
            )
            FilterChip(
                selected = historyFilter == HistoryFilter.INSTALLED,
                onClick = { viewModel.onHistoryFilterSelected(HistoryFilter.INSTALLED) },
                label = { Text("Installed ($installedCount)") },
                modifier = Modifier.testTag("history_filter_installed")
            )
            FilterChip(
                selected = historyFilter == HistoryFilter.UNINSTALLED,
                onClick = { viewModel.onHistoryFilterSelected(HistoryFilter.UNINSTALLED) },
                label = { Text("Uninstalled ($uninstalledCount)") },
                modifier = Modifier.testTag("history_filter_uninstalled")
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (groupedHistory.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                val hasActiveFilters = historySearchQuery.isNotBlank() || historyFilter != HistoryFilter.ALL
                if (isScanning && allHistory.isEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Loading version history...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (hasActiveFilters) "No matching history found" else "No version changes yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (hasActiveFilters) {
                                "No events match your current search or filter selection."
                            } else {
                                "Version changes are permanently logged whenever an app is updated or installed."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        if (hasActiveFilters) {
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedButton(
                                onClick = {
                                    viewModel.onHistorySearchQueryChanged("")
                                    viewModel.onHistoryFilterSelected(HistoryFilter.ALL)
                                }
                            ) {
                                Text("Reset Filters")
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("global_history_list"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                groupedHistory.forEach { (dateHeader, itemsInDate) ->
                    item(key = "header_$dateHeader") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = dateHeader,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "${itemsInDate.size} event${if (itemsInDate.size > 1) "s" else ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    items(
                        items = itemsInDate,
                        key = { it.id }
                    ) { historyItem ->
                        GlobalHistoryCard(
                            item = historyItem,
                            timeFormat = timeFormat,
                            onClick = { onAppClick(historyItem.packageName) }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
}

@Composable
fun GlobalHistoryCard(
    item: VersionHistoryEntity,
    timeFormat: SimpleDateFormat,
    onClick: () -> Unit
) {
    val (typeColor, typeText) = when (item.changeType) {
        "UPDATED" -> Pair(ChangeUpdated, "UPDATED")
        "DOWNGRADED" -> Pair(ChangeDowngraded, "DOWNGRADED")
        "UNINSTALLED" -> Pair(ChangeUninstalled, "UNINSTALLED")
        else -> Pair(ChangeInstalled, "INSTALLED")
    }
    val typeBg = typeColor.copy(alpha = 0.16f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick)
            .testTag("history_card_${item.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(
                packageName = item.packageName,
                size = 46.dp,
                contentDescription = "${item.appName} icon"
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.appName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = typeBg
                    ) {
                        Text(
                            text = typeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = typeColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = item.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Version transition
                    if (item.changeType == "UNINSTALLED") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Text(
                                text = "v${item.oldVersionName ?: "Unknown"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "transitioned to",
                                modifier = Modifier.size(12.dp),
                                tint = ChangeUninstalled
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Uninstalled",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = ChangeUninstalled
                            )
                        }
                    } else if (item.oldVersionName != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Text(
                                text = "v${item.oldVersionName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "transitioned to",
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "v${item.newVersionName}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        Text(
                            text = "v${item.newVersionName} (${item.newVersionCode})",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = timeFormat.format(Date(item.detectedTime)),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
