package com.nova.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING, WAKE_ACTIVE, PAUSED, ERROR }

/**
 * Nova's reactive AI core. Deliberately built with 2D Canvas + gradients +
 * animators, NOT a real 3D/OpenGL mesh — a true 3D renderer risks lag/battery
 * drain on an average phone, which the spec explicitly asked to avoid. This is
 * an honest "2.5D" effect: layered radial glow, an orbiting particle ring, and
 * (during LISTENING) genuine microphone-level reactivity — not a fake pulse,
 * it's driven by the real RMS dB value SpeechRecognizer reports (see
 * setAudioLevel, called from MainActivity.onRmsChanged).
 */
class NovaOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var state: OrbState = OrbState.IDLE
    private var pulsePhase = 0f
    private var ringRotation = 0f
    private var audioLevel = 0f      // 0..1, smoothed
    private var targetAudioLevel = 0f

    private data class Particle(var angle: Float, var radiusFactor: Float, var speed: Float, var size: Float)
    private val particles = List(14) {
        Particle(
            angle = Random.nextFloat() * 360f,
            radiusFactor = 1.5f + Random.nextFloat() * 0.9f,
            speed = 0.15f + Random.nextFloat() * 0.35f,
            size = 2.5f + Random.nextFloat() * 3.5f
        )
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulsePhase = it.animatedValue as Float
            // Smooth audio-level toward its target so real mic input doesn't look jittery.
            audioLevel += (targetAudioLevel - audioLevel) * 0.25f
            invalidate()
        }
    }

    private val rotateAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 6000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            ringRotation = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pulseAnimator.start()
        rotateAnimator.start() // particle ring always orbits gently, even at idle
    }

    override fun onDetachedFromWindow() {
        pulseAnimator.cancel()
        rotateAnimator.cancel()
        super.onDetachedFromWindow()
    }

    fun setState(newState: OrbState) {
        if (state == newState) return
        state = newState
        if (state != OrbState.LISTENING) targetAudioLevel = 0f
        invalidate()
    }

    /** rmsdB from SpeechRecognizer.onRmsChanged — real mic input level, roughly
     *  -2 (silence) to ~10+ (loud speech) in practice, device-dependent. */
    fun setAudioLevel(rmsdB: Float) {
        targetAudioLevel = ((rmsdB + 2f) / 10f).coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = (minOf(width, height) / 2f) * 0.42f
        if (baseRadius <= 0f) return

        val (coreColor, glowColor) = colorsFor(state)

        val micBoost = if (state == OrbState.LISTENING) audioLevel * 0.35f else 0f
        val pulseScale = when (state) {
            OrbState.LISTENING -> 1f + 0.12f * pulsePhase + micBoost
            OrbState.SPEAKING -> 1f + 0.16f * pulsePhase
            OrbState.WAKE_ACTIVE -> 1f + 0.05f * pulsePhase
            OrbState.THINKING -> 1f + 0.08f * pulsePhase
            OrbState.PAUSED -> 0.82f
            else -> 1f + 0.035f * pulsePhase
        }
        val radius = baseRadius * pulseScale

        // Outer ambient wash — soft, wide, sets the "cinematic" backdrop behind the core.
        outerGlowPaint.shader = RadialGradient(
            cx, cy, radius * 3.2f,
            intArrayOf(
                Color.argb(50, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 3.2f, outerGlowPaint)

        // Orbiting particle ring — the "depth/3D-inspired" layer, honestly just
        // small circles on an ellipse-ish orbit, but reads as ambient AI energy.
        if (state != OrbState.PAUSED) {
            val orbitRadius = radius * 1.9f
            particlePaint.color = glowColor
            for (p in particles) {
                val angleRad = Math.toRadians((p.angle + ringRotation * p.speed).toDouble())
                val px = cx + orbitRadius * cos(angleRad).toFloat()
                val py = cy + orbitRadius * sin(angleRad).toFloat() * 0.55f // flattened for a "disc" feel
                val depthAlpha = (0.35f + 0.65f * ((sin(angleRad).toFloat() + 1f) / 2f))
                particlePaint.alpha = (depthAlpha * 200).toInt().coerceIn(0, 255)
                canvas.drawCircle(px, py, p.size, particlePaint)
            }
        }

        // Mid glow, tighter than the ambient wash.
        glowPaint.shader = RadialGradient(
            cx, cy, radius * 2.0f,
            intArrayOf(
                Color.argb(110, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 2.0f, glowPaint)

        // Core sphere with a light-source highlight for a "glassy" 3D-ish read.
        corePaint.shader = RadialGradient(
            cx - radius * 0.32f, cy - radius * 0.32f, radius * 1.7f,
            intArrayOf(lighten(coreColor), coreColor, darken(coreColor)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, corePaint)

        if (state == OrbState.THINKING || state == OrbState.WAKE_ACTIVE) {
            ringPaint.color = glowColor
            ringPaint.alpha = 210
            val ringRadius = radius * 1.3f
            val sweep = if (state == OrbState.THINKING) 110f else 40f
            canvas.drawArc(
                cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius,
                ringRotation * (if (state == OrbState.THINKING) 2.2f else 1f), sweep, false, ringPaint
            )
        }

        if (state == OrbState.ERROR) {
            ringPaint.color = Color.parseColor("#FF5C5C")
            ringPaint.alpha = (150 + 100 * pulsePhase).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, radius * 1.15f, ringPaint)
        }
    }

    private fun colorsFor(s: OrbState): Pair<Int, Int> {
        val cyan = Color.parseColor("#4CE0D2")
        val violet = Color.parseColor("#8B5CF6")
        val amber = Color.parseColor("#FFB86B")
        val dim = Color.parseColor("#5B6478")
        val red = Color.parseColor("#FF5C5C")
        return when (s) {
            OrbState.IDLE -> cyan to cyan
            OrbState.LISTENING -> cyan to Color.parseColor("#7CF5E8")
            OrbState.THINKING -> violet to violet
            OrbState.SPEAKING -> amber to amber
            OrbState.WAKE_ACTIVE -> violet to cyan
            OrbState.PAUSED -> dim to dim
            OrbState.ERROR -> red to red
        }
    }

    private fun lighten(color: Int): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * 0.45f).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * 0.45f).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * 0.45f).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun darken(color: Int): Int {
        val r = (Color.red(color) * 0.5f).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * 0.5f).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * 0.5f).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}
