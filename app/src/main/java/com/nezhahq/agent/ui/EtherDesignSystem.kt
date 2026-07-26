package com.nezhahq.agent.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.nezhahq.agent.ui.UiEvent
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nezhahq.agent.grpc.GrpcConnectionState
import com.nezhahq.agent.core.model.SimulatedDeviceConfig
import com.nezhahq.agent.core.config.StorageStatus
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import rikka.shizuku.Shizuku
import com.nezhahq.agent.MainViewModel

// Liquid Glass 设计系统：配色、形状、阴影与基础控件。

// ══════════════════════════════════════════════════════════════════════════════
// Liquid Glass 调色板（源自 Tailwind 配置）
// ══════════════════════════════════════════════════════════════════════════════

/** 背景色 — 极浅青灰 */
internal val LgBackground = Color(0xFFF3F7F8)

/** 主文字色 — 深灰 */
internal val LgOnSurface = Color(0xFF2B2F31)

/** 主色调 — 深青 */
internal val LgPrimary = Color(0xFF006575)

/** 次要文字 — 中灰 */
internal val LgOnSurfaceVariant = Color(0xFF575C5D)

/** 轮廓线 */
internal val LgOutline = Color(0xFF737879)

/** 青色重点 — 用于按钮/指示器 */
internal val LgCyan600 = Color(0xFF0891B2) // tailwind cyan-600 近似
internal val LgCyan400 = Color(0xFF22D3EE) // tailwind cyan-400

/** 面板容器色 */
internal val LgSurfaceContainerLow = Color(0xFFEDF2F3)
internal val LgSurfaceContainerHigh = Color(0xFFDDE4E5)

/** 玻璃半透明白 */
internal val LgGlassWhite70 = Color(0xB3FFFFFF) // 70 % 白
internal val LgGlassWhite40 = Color(0x66FFFFFF) // 40 % 白

/** 成功 / 警告 / 错误 */
internal val LgSuccess = Color(0xFF4CAF50)
internal val LgWarning = Color(0xFFFF9800)
internal val LgError = Color(0xFFF44336)

/** Nekogram 底栏默认配色 */
internal val NekoGlassBarFillTop = Color(0xD9FFFFFF)
internal val NekoGlassBarFillBottom = Color(0xD9FFFFFF)
internal val NekoGlassBarStroke = Color(0x20000000)
internal val NekoGlassTabSelected = Color(0xFF1A91E6)
internal val NekoGlassTabSelectedText = Color(0xFF0D7FCF)
internal val NekoGlassTabUnselected = Color(0xFF1A1D21)

internal val LgControlRadius = 26.dp
internal val LgPanelPadding = 20.dp
internal val LgConsolePadding = 16.dp

internal val LgControlShape = SmoothCornerShape(LgControlRadius)
internal val LgPanelShape = outerSmoothShape(innerRadius = LgControlRadius, inset = LgPanelPadding)
internal val LgConsoleShape = outerSmoothShape(innerRadius = LgControlRadius, inset = LgConsolePadding)

internal fun outerSmoothShape(innerRadius: Dp, inset: Dp): SmoothCornerShape {
    return SmoothCornerShape(innerRadius + inset)
}

internal data class SmoothCornerShape(
    private val radius: Dp,
    private val exponent: Double = 2.35
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radiusPx = with(density) { radius.toPx() }
            .coerceAtMost(size.width / 2f)
            .coerceAtMost(size.height / 2f)

        val path = Path().apply {
            moveTo(radiusPx, 0f)
            lineTo(size.width - radiusPx, 0f)
            smoothCorner(size.width - radiusPx, radiusPx, radiusPx, -90.0, 0.0, exponent)
            lineTo(size.width, size.height - radiusPx)
            smoothCorner(size.width - radiusPx, size.height - radiusPx, radiusPx, 0.0, 90.0, exponent)
            lineTo(radiusPx, size.height)
            smoothCorner(radiusPx, size.height - radiusPx, radiusPx, 90.0, 180.0, exponent)
            lineTo(0f, radiusPx)
            smoothCorner(radiusPx, radiusPx, radiusPx, 180.0, 270.0, exponent)
            close()
        }

        return Outline.Generic(path)
    }
}

internal fun Path.smoothCorner(
    centerX: Float,
    centerY: Float,
    radius: Float,
    startDegrees: Double,
    endDegrees: Double,
    exponent: Double
) {
    val steps = 32
    val power = 2.0 / exponent

    for (i in 1..steps) {
        val angle = (startDegrees + (endDegrees - startDegrees) * i / steps) * PI / 180.0
        val cosine = cos(angle)
        val sine = sin(angle)
        val x = centerX + radius * cosine.signedPow(power)
        val y = centerY + radius * sine.signedPow(power)
        lineTo(x.toFloat(), y.toFloat())
    }
}

internal fun Double.signedPow(power: Double): Double {
    return if (this < 0.0) -abs(this).pow(power) else abs(this).pow(power)
}

// ══════════════════════════════════════════════════════════════════════════════
// 自定义 Modifier — 外部柔光阴影 (Ether Button 效果)
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 模拟 CSS `box-shadow: 6px 6px 12px rgba(0,0,0,0.08), -6px -6px 12px rgba(255,255,255,0.9)`。
 * 在 Compose 中使用 `shadow` + 微弱白色底边框来近似呈现。
 */
internal fun Modifier.etherShadow(
    elevation: Dp = 6.dp,
    shape: Shape = LgControlShape
): Modifier = this
    .shadow(elevation = elevation, shape = shape, ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.06f))
    .border(width = 1.dp, color = Color.White.copy(alpha = 0.5f), shape = shape)

// ══════════════════════════════════════════════════════════════════════════════
// 玻璃风格面板（EtherCard）
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 半透明毛玻璃卡片容器。
 * 外层圆角等距包裹内层控件：外半径 = 内层控件半径 + 面板内边距。
 */
@Composable
internal fun EtherCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .etherShadow(shape = LgPanelShape)
            .clip(LgPanelShape)
            .background(LgGlassWhite70)
            .padding(LgPanelPadding),
        content = content
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// 玻璃风格按钮
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Primary 玻璃按钮 — 半透明青色背景 + 白色光晕边框。
 * 取代原先 Material3 `Button`。
 */
@Composable
internal fun GlassButtonPrimary(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val bgAlpha = if (isPressed) 0.55f else 0.4f

    Button(
        onClick = onClick,
        modifier = modifier
            .height(52.dp)
            .etherShadow(shape = LgControlShape),
        enabled = enabled,
        shape = LgControlShape,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = LgCyan400.copy(alpha = bgAlpha),
            contentColor = LgPrimary,
            disabledContainerColor = LgCyan400.copy(alpha = 0.2f),
            disabledContentColor = LgOnSurfaceVariant
        ),
        elevation = ButtonDefaults.buttonElevation(0.dp),
        contentPadding = PaddingValues(horizontal = 24.dp),
        content = content
    )
}

/**
 * Secondary 玻璃按钮 — 半透明白色背景。
 * 取代原先 Material3 `OutlinedButton`。
 */
@Composable
internal fun GlassButtonSecondary(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .height(52.dp)
            .etherShadow(shape = LgControlShape),
        enabled = enabled,
        shape = LgControlShape,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = LgGlassWhite40,
            contentColor = LgOnSurfaceVariant
        ),
        border = null,
        contentPadding = PaddingValues(horizontal = 24.dp),
        content = content
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// 玻璃风格输入框
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Ether 风格输入框 — 浅色圆角容器 + 柔光内阴影效果。
 * 使用原生 `OutlinedTextField` 去掉边框，改用自定义容器包裹。
 */
@Composable
internal fun EtherTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    enabled: Boolean = true
) {
    Column(modifier = modifier) {
        // 输入容器（保留 label 语义以支持 TalkBack 无障碍朗读）
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, fontSize = 12.sp, color = LgOnSurfaceVariant) },
            modifier = Modifier
                .fillMaxWidth()
                .etherShadow(elevation = 2.dp, shape = LgControlShape),
            shape = LgControlShape,
            enabled = enabled,
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
            textStyle = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = LgOnSurface
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = LgSurfaceContainerLow,
                unfocusedContainerColor = LgSurfaceContainerLow,
                focusedBorderColor = LgCyan400.copy(alpha = 0.5f),
                unfocusedBorderColor = Color.Transparent,
                cursorColor = LgPrimary,
                focusedLabelColor = LgPrimary,
                unfocusedLabelColor = LgOnSurfaceVariant
            )
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 玻璃风格 Switch (Toggle)
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 使用 Material3 Switch，但着色为 Liquid Glass 调色板。
 */
@Composable
internal fun EtherSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = LgCyan400,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = LgSurfaceContainerHigh,
            uncheckedBorderColor = Color.Transparent
        )
    )
}

// ══════════════════════════════════════════════════════════════════════════════
