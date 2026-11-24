package com.hinnka.mycamera.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.min

/**
 * Small, consistent branded marks for Photon capture modes.
 *
 * The photo helper stamps only the visible refocus composite. Its hidden focus planes
 * remain untouched so Photon Gallery can continue to refocus the original capture.
 */
object MediaWatermark {
    const val SPECIAL_VIDEO = "SPECIAL VIDEO"
    const val MULTIFOCUS = "MULTIFOCUS"

    const val BADGE_WIDTH = 480
    const val BADGE_HEIGHT = 112

    fun createOverlayBitmap(label: String): Bitmap {
        val bitmap = Bitmap.createBitmap(BADGE_WIDTH, BADGE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bounds = RectF(2f, 2f, BADGE_WIDTH - 2f, BADGE_HEIGHT - 2f)

        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(178, 9, 12, 17)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(bounds, 30f, 30f, backgroundPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(92, 242, 246, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(bounds, 30f, 30f, borderPaint)

        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 193, 7)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(39f, BADGE_HEIGHT / 2f, 11f, accentPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(246, 255, 255, 255)
            textSize = 39f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        val metrics = textPaint.fontMetrics
        val baseline = BADGE_HEIGHT / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label.uppercase(Locale.ROOT), 72f, baseline, textPaint)
        return bitmap
    }

    /**
     * Returns a new bitmap with a small bottom-right badge, or the source unchanged if
     * watermark allocation fails. The caller owns and should recycle a distinct result.
     */
    fun stampBitmap(source: Bitmap, label: String): Bitmap {
        val badge = runCatching { createOverlayBitmap(label) }.getOrNull() ?: return source
        val output = runCatching {
            Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: run {
            badge.recycle()
            return source
        }

        return try {
            val canvas = Canvas(output)
            canvas.drawBitmap(
                source,
                0f,
                0f,
                Paint(Paint.FILTER_BITMAP_FLAG),
            )

            val margin = (min(source.width, source.height) * 0.028f).coerceAtLeast(8f)
            val badgeWidth = (source.width * 0.22f)
                .coerceAtMost((source.width - margin * 2f).coerceAtLeast(1f))
            val badgeHeight = badgeWidth * BADGE_HEIGHT.toFloat() / BADGE_WIDTH.toFloat()
            val destination = RectF(
                source.width - margin - badgeWidth,
                source.height - margin - badgeHeight,
                source.width - margin,
                source.height - margin,
            )
            canvas.drawBitmap(
                badge,
                null,
                destination,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
            output
        } catch (_: Exception) {
            output.recycle()
            source
        } finally {
            if (!badge.isRecycled) badge.recycle()
        }
    }
}
