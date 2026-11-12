package com.example.ui.icon

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppIconCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8 // 1/8th of available memory

    private val lruCache = object : LruCache<String, ImageBitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: ImageBitmap): Int {
            return (bitmap.width * bitmap.height * 4) / 1024
        }
    }

    fun get(packageName: String): ImageBitmap? = lruCache.get(packageName)

    fun put(packageName: String, bitmap: ImageBitmap) {
        lruCache.put(packageName, bitmap)
    }

    suspend fun loadIcon(context: Context, packageName: String): ImageBitmap? {
        get(packageName)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val drawable = pm.getApplicationIcon(packageName)
                val bitmap = drawableToBitmap(drawable)
                val imageBitmap = bitmap.asImageBitmap()
                put(packageName, imageBitmap)
                imageBitmap
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }

        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}

@Composable
fun AppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    contentDescription: String? = null
) {
    val context = LocalContext.current
    val cached = remember(packageName) { AppIconCache.get(packageName) }
    val iconBitmap = remember(packageName) { mutableStateOf(cached) }

    LaunchedEffect(packageName) {
        if (iconBitmap.value == null) {
            val loaded = AppIconCache.loadIcon(context, packageName)
            iconBitmap.value = loaded
        }
    }

    val currentBitmap = iconBitmap.value
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap,
            contentDescription = contentDescription,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Android,
                contentDescription = contentDescription,
                modifier = Modifier.size(size * 0.65f),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
