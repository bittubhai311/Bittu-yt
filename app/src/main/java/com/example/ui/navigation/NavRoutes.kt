package com.example.ui.navigation

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Home : Screen("home", "Apps", Icons.Default.Apps)
    object History : Screen("history", "History", Icons.Default.History)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    object AppDetail : Screen("app_detail/{packageName}", "App Detail", Icons.Default.Apps) {
        fun createRoute(packageName: String): String = "app_detail/${Uri.encode(packageName)}"
    }
}
