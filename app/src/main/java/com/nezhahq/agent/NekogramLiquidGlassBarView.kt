package com.nezhahq.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class NekogramLiquidGlassBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    init {
    }

    private val clipPath = Path()
    private val strokePath = Path()
    private val glassRect = RectF()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val topStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val bottomStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val topStrokeClip = Rect()
    private val bottomStrokeClip = Rect()

    private val blurNode: RenderNode
    private val fillNode: RenderNode
    private val liquidGlassEffect: LiquidGlassRuntimeEffect

    init {
        blurNode = RenderNode("AgentLiquidGlassBarBlur")

        fillNode = RenderNode("AgentLiquidGlassBarFill")

        liquidGlassEffect = LiquidGlassRuntimeEffect(context, fillNode)
    }


    private var cornerRadius = dp(28f)
    private var foregroundColor = 0xB9FFFFFF.toInt()
    private var strokeTopColor = 0x11000000
    private var strokeBottomColor = 0x20000000
    private val strokeTopWidth = dp(0.4f)
    private val strokeBottomWidth = dp(0.4f)

    private var sourceView: View? = null

    private val sourcePreDrawListener = ViewTreeObserver.OnPreDrawListener {
        postInvalidateOnAnimation()
        true
    }

    init {
        try {
            setWillNotDraw(false)
        } catch (e: Exception) {
            android.util.Log.e("LiquidGlass", "✗ setWillNotDraw 失败", e)
            throw e
        }

        try {
            topStrokePaint.strokeWidth = strokeTopWidth
            bottomStrokePaint.strokeWidth = strokeBottomWidth
            topStrokePaint.color = strokeTopColor
            bottomStrokePaint.color = strokeBottomColor
        } catch (e: Exception) {
            android.util.Log.e("LiquidGlass", "✗ Paint 初始化失败", e)
            throw e
        }
    }

    fun setSourceView(view: View?) {
        if (sourceView === view) {
            return
        }
        detachSourceListener()
        sourceView = view
        attachSourceListener()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachSourceListener()
    }

    override fun onDetachedFromWindow() {
        detachSourceListener()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        try {
            blurNode.setPosition(0, 0, w, h)
        } catch (e: Exception) {
            android.util.Log.e("LiquidGlass", "✗ blurNode.setPosition 失败", e)
        }

        try {
            blurNode.setRenderEffect(RenderEffect.createBlurEffect(dp(1.66f), dp(1.66f), Shader.TileMode.CLAMP))
        } catch (e: Exception) {
            android.util.Log.e("LiquidGlass", "✗ BlurEffect 创建失败", e)
        }

        try {
            fillNode.setPosition(0, 0, w, h)
        } catch (e: Exception) {
            android.util.Log.e("LiquidGlass", "✗ fillNode.setPosition 失败", e)
        }

        try {
            rebuildPath(w, h)
            topStrokeClip.set(0, 0, w, h / 2)
            bottomStrokeClip.set(0, h / 3, w, h)
        } catch (e: Exception) {
            android.util.Log.e("LiquidGlass", "✗ Path 重建失败", e)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) {
            return
        }

        if (canvas.isHardwareAccelerated) {
            val source = sourceView
            if (source != null && source.width > 0 && source.height > 0) {
                val sourceLocation = IntArray(2)
                val selfLocation = IntArray(2)
                source.getLocationInWindow(sourceLocation)
                getLocationInWindow(selfLocation)

                val blurCanvas = blurNode.beginRecording()
                blurCanvas.save()
                blurCanvas.translate(
                    (sourceLocation[0] - selfLocation[0]).toFloat(),
                    (sourceLocation[1] - selfLocation[1]).toFloat()
                )
                // 【架构修复】舍弃原来的软件 Canvas 快照截帧模式（软件画布会使 Compose RenderNode 处理 
                // StretchOverscrollEffect 的弹性拉伸时发生底层管线冲突导致永远卡死在拉伸状态）。
                // 这里直接利用 RenderNode 引擎分配的真·硬件加速画布(Hardware Canvas)进行绘制，
                // 不仅能让列表天然地保持带阻尼的过度拉伸-回弹物理特性，更能降低约 2MB-3MB 频繁擦写的常驻 Bitmap 内存。
                source.draw(blurCanvas)
                blurCanvas.restore()
                blurNode.endRecording()
            } else {
                val blurCanvas = blurNode.beginRecording()
                blurCanvas.drawColor(Color.TRANSPARENT)
                blurNode.endRecording()
            }

            val recordingCanvas = fillNode.beginRecording()
            recordingCanvas.drawRenderNode(blurNode)
            fillNode.endRecording()

            liquidGlassEffect.update(
                left = 0f,
                top = 0f,
                right = width.toFloat(),
                bottom = height.toFloat(),
                radiusLeftTop = cornerRadius,
                radiusRightTop = cornerRadius,
                radiusRightBottom = cornerRadius,
                radiusLeftBottom = cornerRadius,
                thickness = dp(11f),
                intensity = 0.75f,
                index = 1.5f,
                foregroundColor = foregroundColor
            )

            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawRenderNode(fillNode)
            canvas.restore()
        } else {
            // Android 13+ 常规环境 100% 为 HardwareAccelerated
            // 兜底方案直接绘制不透明/半透明遮罩
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawColor(foregroundColor)
            canvas.restore()
        }

        canvas.save()
        canvas.clipRect(topStrokeClip)
        canvas.drawPath(strokePath, topStrokePaint)
        canvas.restore()

        canvas.save()
        canvas.clipRect(bottomStrokeClip)
        canvas.drawPath(strokePath, bottomStrokePaint)
        canvas.restore()
    }


    private fun rebuildPath(width: Int, height: Int) {
        glassRect.set(0f, 0f, width.toFloat(), height.toFloat())
        clipPath.rewind()
        clipPath.addSmoothRect(glassRect, cornerRadius)
        strokePath.rewind()
        strokePath.addSmoothRect(glassRect, cornerRadius)
    }

    private fun attachSourceListener() {
        val source = sourceView ?: return
        if (isAttachedToWindow && source.viewTreeObserver.isAlive) {
            source.viewTreeObserver.removeOnPreDrawListener(sourcePreDrawListener)
            source.viewTreeObserver.addOnPreDrawListener(sourcePreDrawListener)
        }
    }

    private fun detachSourceListener() {
        val source = sourceView ?: return
        if (source.viewTreeObserver.isAlive) {
            source.viewTreeObserver.removeOnPreDrawListener(sourcePreDrawListener)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

private fun Path.addSmoothRect(rect: RectF, radius: Float) {
    val smoothRadius = radius
        .coerceAtMost(rect.width() / 2f)
        .coerceAtMost(rect.height() / 2f)

    moveTo(rect.left + smoothRadius, rect.top)
    lineTo(rect.right - smoothRadius, rect.top)
    smoothCorner(rect.right - smoothRadius, rect.top + smoothRadius, smoothRadius, -90.0, 0.0)
    lineTo(rect.right, rect.bottom - smoothRadius)
    smoothCorner(rect.right - smoothRadius, rect.bottom - smoothRadius, smoothRadius, 0.0, 90.0)
    lineTo(rect.left + smoothRadius, rect.bottom)
    smoothCorner(rect.left + smoothRadius, rect.bottom - smoothRadius, smoothRadius, 90.0, 180.0)
    lineTo(rect.left, rect.top + smoothRadius)
    smoothCorner(rect.left + smoothRadius, rect.top + smoothRadius, smoothRadius, 180.0, 270.0)
    close()
}

private fun Path.smoothCorner(
    centerX: Float,
    centerY: Float,
    radius: Float,
    startDegrees: Double,
    endDegrees: Double
) {
    val steps = 32
    val power = 2.0 / 2.35

    for (i in 1..steps) {
        val angle = (startDegrees + (endDegrees - startDegrees) * i / steps) * PI / 180.0
        val cosine = cos(angle)
        val sine = sin(angle)
        val x = centerX + radius * cosine.signedPow(power)
        val y = centerY + radius * sine.signedPow(power)
        lineTo(x.toFloat(), y.toFloat())
    }
}

private fun Double.signedPow(power: Double): Double {
    return if (this < 0.0) -abs(this).pow(power) else abs(this).pow(power)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class LiquidGlassRuntimeEffect(
    context: Context,
    private val node: RenderNode
) {
    private val shader = try {
        val shaderCode = context.resources.openRawResource(R.raw.liquid_glass_shader)
            .bufferedReader()
            .use { it.readText() }

        val result = RuntimeShader(shaderCode)
        result
    } catch (e: Exception) {
        android.util.Log.e("LiquidGlass", "✗ RuntimeShader 创建失败", e)
        throw e
    }

    private var effect: RenderEffect = try {
        val result = RenderEffect.createRuntimeShaderEffect(shader, "img")
        result
    } catch (e: Exception) {
        android.util.Log.e("LiquidGlass", "✗ RenderEffect.createRuntimeShaderEffect 失败", e)
        throw e
    }

    init {
        try {
            node.setRenderEffect(effect)
        } catch (e: Exception) {
            android.util.Log.e("LiquidGlass", "✗ node.setRenderEffect 失败", e)
            throw e
        }
    }

    fun update(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radiusLeftTop: Float,
        radiusRightTop: Float,
        radiusRightBottom: Float,
        radiusLeftBottom: Float,
        thickness: Float,
        intensity: Float,
        index: Float,
        foregroundColor: Int
    ) {
        val alpha = Color.alpha(foregroundColor) / 255f
        val red = Color.red(foregroundColor) / 255f * alpha
        val green = Color.green(foregroundColor) / 255f * alpha
        val blue = Color.blue(foregroundColor) / 255f * alpha

        shader.setFloatUniform("resolution", node.width.toFloat(), node.height.toFloat())
        shader.setFloatUniform("center", (left + right) / 2f, (top + bottom) / 2f)
        shader.setFloatUniform("size", (right - left) / 2f, (bottom - top) / 2f)
        shader.setFloatUniform(
            "radius",
            radiusRightBottom,
            radiusRightTop,
            radiusLeftBottom,
            radiusLeftTop
        )
        shader.setFloatUniform("thickness", thickness)
        shader.setFloatUniform("refract_intensity", intensity)
        shader.setFloatUniform("refract_index", index)
        shader.setFloatUniform("foreground_color_premultiplied", red, green, blue, alpha)

        effect = RenderEffect.createRuntimeShaderEffect(shader, "img")
        node.setRenderEffect(effect)
    }
}
