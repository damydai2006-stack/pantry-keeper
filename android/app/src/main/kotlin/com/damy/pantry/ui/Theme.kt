package com.damy.pantry.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.damy.pantry.data.ListBucket

val Bg = Color(0xFFF4F1EB)
val Surface = Color(0xFFFFFFFF)
val Surface2 = Color(0xFFEFEBE3)
val Line = Color(0xFFD4CFC5)
val Text = Color(0xFF1A1D1A)
val TextSecondary = Color(0xFF3E4640)
val TextTertiary = Color(0xFF5A615B)
val Brand = Color(0xFF1E4A62)
val BrandSoft = Color(0xFFE6EEF2)
val Danger = Color(0xFF9B1C1C)
val Expired = Color(0xFF9B1C1C)
val ExpiredSoft = Color(0xFFFCEBEA)
val Soon = Color(0xFFB54708)
val SoonSoft = Color(0xFFFEF0E4)
val Month = Color(0xFF8A5A00)
val MonthSoft = Color(0xFFFBF3D0)
val Later = Color(0xFF166534)
val LaterSoft = Color(0xFFE5F3EA)
private val ExpiredText = Color(0xFF8B1A14)
private val SoonText = Color(0xFF8A3D00)
private val MonthText = Color(0xFF6B4E00)
private val LaterText = Color(0xFF145A32)

fun bucketBadgeText(bucket: ListBucket): Color = when (bucket) {
    ListBucket.EXPIRED -> ExpiredText
    ListBucket.SOON -> SoonText
    ListBucket.MONTH -> MonthText
    ListBucket.LATER -> LaterText
}

fun bucketColor(bucket: ListBucket): Color = when (bucket) {
    ListBucket.EXPIRED -> Expired
    ListBucket.SOON -> Soon
    ListBucket.MONTH -> Month
    ListBucket.LATER -> Later
}

fun bucketSoft(bucket: ListBucket): Color = when (bucket) {
    ListBucket.EXPIRED -> ExpiredSoft
    ListBucket.SOON -> SoonSoft
    ListBucket.MONTH -> MonthSoft
    ListBucket.LATER -> LaterSoft
}

private val scheme = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    background = Bg,
    surface = Surface,
    onBackground = Text,
    onSurface = Text,
    error = Danger,
    outline = Line,
)

@Composable
fun PantryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
