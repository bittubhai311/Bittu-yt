package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.icon.AppIcon
import com.example.ui.theme.ChangeUninstalled
import com.example.ui.theme.ChangeUpdated
import com.example.ui.viewmodel.AppFilter
import com.example.ui.viewmodel.AppSort
import com.example.ui.viewmodel.AppWithHistoryCount
import com.example.ui.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onAppClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredApps by viewModel.filteredApps.collectAsStateWithLifecycle()
    val allApps by viewModel.allApps.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val selectedSort by viewModel.selectedSort.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val lastScanError by viewModel.lastScanError.collectAsStateWithLifecycle()
    val lastScanTime by viewModel.lastScanTime.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current
    var sortMenuExpanded by remember { mutableStateOf(false) }

    val userCount = remember(allApps) { allApps.count { !it.isSystemApp } }
    val systemCount = remember(allApps) { allApps.count { it.isSystemApp } }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    val sortLabel = when (selectedSort) {
        AppSort.NAME -> "Name"
        AppSort.RECENTLY_UPDATED -> "Recently updated"
        AppSort.INSTALL_DATE -> "Install date"
        AppSort.VERSION_COUNT -> "Version history count"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_screen")
    ) {
        // Top search bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_text_field"),
                placeholder = { Text("Search apps or packages...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search apps")
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.commitSearch("") },
                            modifier = Modifier.testTag("clear_search_button")
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search query")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        viewModel.commitSearch(searchQuery)
                        focusManager.clearFocus()
                    }
                ),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )
        }

        // Persistent search history chips
        AnimatedVisibility(visible = searchQuery.isEmpty() && recentSearches.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent Searches",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { viewModel.clearSearches() }) {
                        Text("Clear", style = MaterialTheme.typography.labelSmall)
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    recentSearches.take(6).forEach { recent ->
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .heightIn(min = 36.dp)
                                .clickable { viewModel.commitSearch(recent.query) }
                                .testTag("search_history_chip_${recent.query}")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.History,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = recent.query,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .clickable { viewModel.deleteSearch(recent.query) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove ${recent.query} from recent searches",
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Filter chips and Sort button row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == AppFilter.ALL,
                    onClick = { viewModel.onFilterSelected(AppFilter.ALL) },
                    label = { Text("All (${allApps.size})") },
                    colors = FilterChipDefaults.filterChipColors(),
                    modifier = Modifier.testTag("filter_all")
                )
                FilterChip(
                    selected = selectedFilter == AppFilter.USER,
                    onClick = { viewModel.onFilterSelected(AppFilter.USER) },
                    label = { Text("User ($userCount)") },
                    modifier = Modifier.testTag("filter_user")
                )
                FilterChip(
                    selected = selectedFilter == AppFilter.SYSTEM,
                    onClick = { viewModel.onFilterSelected(AppFilter.SYSTEM) },
                    label = { Text("System ($systemCount)") },
                    modifier = Modifier.testTag("filter_system")
                )
            }

            Box {
                IconButton(
                    onClick = { sortMenuExpanded = true },
                    modifier = Modifier.testTag("sort_menu_button")
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Sort,
                        contentDescription = "Sort apps, current: $sortLabel"
                    )
                }

                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Sort by Recently Updated") },
                        onClick = {
                            viewModel.onSortSelected(AppSort.RECENTLY_UPDATED)
                            sortMenuExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Sort by Install Date") },
                        onClick = {
                            viewModel.onSortSelected(AppSort.INSTALL_DATE)
                            sortMenuExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Sort by Name (A-Z)") },
                        onClick = {
                            viewModel.onSortSelected(AppSort.NAME)
                            sortMenuExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Sort by Version Count") },
                        onClick = {
                            viewModel.onSortSelected(AppSort.VERSION_COUNT)
                            sortMenuExpanded = false
                        }
                    )
                }
            }
        }

        // Active Sort & Last Scan indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val scanSuffix = if (lastScanTime > 0L) {
                " • Scanned ${timeFormat.format(Date(lastScanTime))}"
            } else {
                ""
            }
            Text(
                text = "${filteredApps.size} apps • Sorted by $sortLabel$scanSuffix",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (isScanning) {
                Spacer(modifier = Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Scanning...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Visible scan error banner
        AnimatedVisibility(visible = lastScanError != null && !isScanning) {
            lastScanError?.let { errorMsg ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = "Scan error",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = errorMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.refreshScan() }) {
                            Text("Retry", color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // App list with distinct loading, filtered-empty, and empty-database states
        if (filteredApps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                val hasActiveFilters = searchQuery.isNotBlank() || selectedFilter != AppFilter.ALL
                if (isScanning && allApps.isEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Scanning installed applications...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Building initial version baseline on device",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (hasActiveFilters) "No matching apps found" else "No apps recorded yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (hasActiveFilters) {
                                "Try searching with a different term or resetting filters"
                            } else {
                                "Tap Refresh to scan installed packages"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        if (hasActiveFilters) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.onSearchQueryChanged("")
                                    viewModel.onFilterSelected(AppFilter.ALL)
                                }
                            ) {
                                Text("Reset Filters")
                            }
                        } else {
                            IconButton(
                                onClick = { viewModel.refreshScan() },
                                modifier = Modifier.testTag("empty_refresh_button")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh package scan")
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("apps_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = filteredApps,
                    key = { it.app.packageName }
                ) { item ->
                    AppItemCard(
                        item = item,
                        onClick = {
                            if (searchQuery.isNotBlank()) {
                                viewModel.commitSearch(searchQuery)
                            }
                            onAppClick(item.app.packageName)
                        }
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppItemCard(
    item: AppWithHistoryCount,
    onClick: () -> Unit
) {
    val app = item.app
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val installedDateStr = remember(app.firstInstallTime) {
        if (app.firstInstallTime > 0) dateFormat.format(Date(app.firstInstallTime)) else "Unknown"
    }
    val updatedDateStr = remember(app.lastUpdateTime) {
        if (app.lastUpdateTime > 0) dateFormat.format(Date(app.lastUpdateTime)) else "Unknown"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick)
            .testTag("app_card_${app.packageName}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App icon with badge
            BadgedBox(
                badge = {
                    if (item.changeCount > 1) {
                        Badge(
                            containerColor = ChangeUpdated,
                            contentColor = Color.White
                        ) {
                            Text(
                                text = "${item.changeCount}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            ) {
                AppIcon(
                    packageName = app.packageName,
                    size = 52.dp,
                    contentDescription = "${app.appName} icon"
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!app.isInstalled) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = ChangeUninstalled.copy(alpha = 0.16f)
                            ) {
                                Text(
                                    text = "Uninstalled",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ChangeUninstalled,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (app.isSystemApp) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            }
                        ) {
                            Text(
                                text = if (app.isSystemApp) "System" else "User",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (app.isSystemApp) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                },
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Version display
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = "v${app.versionName} (${app.versionCode})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Dates: "Installed: dd MMM yyyy", "Updated: dd MMM yyyy" (wraps cleanly on small screens)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Installed: $installedDateStr",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Updated: $updatedDateStr",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
