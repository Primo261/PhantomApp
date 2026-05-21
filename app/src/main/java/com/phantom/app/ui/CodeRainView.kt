package com.phantom.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

/**
 * Ambient "code-in-the-background" decoration.
 *
 * Draws bands of small violet rectangles (= stylised glyphs) that slowly
 * scroll upward. The pattern is pre-computed once per size change so on-draw
 * is just a flat loop over `Glyph` items; no allocations per frame, no blur
 * filters (those are GPU-expensive). Low-alpha colors deliver the "flou
 * radial" feel without actually blurring.
 *
 * Cycle: ~22s for a full pattern translation = very calm motion, virtually
 * unnoticeable as motion but reads as alive.
 */
class CodeRainView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private data class Glyph(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val paintIndex: Int,
    )

    // Pre-built paints with low alpha so the lines feel like distant code.
    // Three tiers: bulk subtle, occasional brighter, rare "keyword" pop.
    private val paints = arrayOf(
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1E8B5CF6.toInt() }, // 12% violet
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x18A78BFA.toInt() }, //  9% bright violet
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x28C4B5FD.toInt() }, // 16% lavender (rare)
    )

    private var glyphs: List<Glyph> = emptyList()
    private var patternHeight = 0f
    private var scrollY = 0f
    private val rng = Random(System.currentTimeMillis())

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 22_000L // one full pattern cycle per 22s
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        addUpdateListener {
            scrollY = (it.animatedValue as Float) * patternHeight
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        regenerate(w, h)
    }

    private fun regenerate(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        // Make the pattern taller than the view so the scroll has somewhere
        // to "come from" without a visible loop seam.
        patternHeight = (h * 2).toFloat()
        val d = resources.displayMetrics.density
        val lineHeight = 18f * d
        val list = ArrayList<Glyph>(400)
        var y = 0f
        while (y < patternHeight) {
            // Each line gets a varied left-indent (suggests code structure).
            var x = rng.nextFloat() * 60f * d
            val glyphsOnLine = rng.nextInt(4, 11)
            repeat(glyphsOnLine) {
                if (x > w) return@repeat
                val gw = (10f + rng.nextFloat() * 70f) * d
                val gh = (3f + rng.nextFloat() * 2f) * d
                val pi = when {
                    rng.nextInt(28) == 0 -> 2 // rare lavender keyword
                    rng.nextInt(3) == 0 -> 0 // primary violet
                    else -> 1                 // bulk bright violet
                }
                list.add(Glyph(x, y + (lineHeight - gh) * 0.5f, gw, gh, pi))
                x += gw + (5f + rng.nextFloat() * 18f) * d
            }
            y += lineHeight + rng.nextFloat() * 6f * d
        }
        glyphs = list
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Don't burn CPU drawing under a hidden activity.
        if (visibility == VISIBLE) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (glyphs.isEmpty() || patternHeight <= 0f) return
        val viewH = height.toFloat()
        val baseOffset = -scrollY
        // Draw the pattern twice (current + wrap copy below) so the seam at
        // patternHeight is hidden.
        for (pass in 0..1) {
            val yOff = baseOffset + pass * patternHeight
            // Quick out-of-bounds reject per pass.
            if (yOff > viewH || yOff + patternHeight < 0f) continue
            for (i in glyphs.indices) {
                val g = glyphs[i]
                val top = g.y + yOff
                if (top + g.height < 0f || top > viewH) continue
                canvas.drawRect(g.x, top, g.x + g.width, top + g.height, paints[g.paintIndex])
            }
        }
    }
}
