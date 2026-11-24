package com.hinnka.mycamera.processor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds a compact focus-energy depth map from a set of focus-bracket JPEGs.
 * The full-resolution planes remain untouched for the gallery refocus viewer.
 */
object OppoRefocusDepthEngine {
    private const val TARGET_LONG_EDGE = 480

    fun createDepthMap(frameFiles: List<File>, destination: File): Boolean {
        if (frameFiles.size < 3 || frameFiles.any { !it.isFile }) return false
        val optionsBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(frameFiles.first().absolutePath, optionsBounds)
        val largestEdge = max(optionsBounds.outWidth, optionsBounds.outHeight).coerceAtLeast(1)
        var sampleSize = 1
        while (largestEdge / (sampleSize * 2) >= TARGET_LONG_EDGE) sampleSize *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val energyPlanes = ArrayList<FloatArray>(frameFiles.size)
        var width = 0
        var height = 0
        try {
            for (file in frameFiles) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return false
                try {
                    if (width == 0) {
                        width = bitmap.width
                        height = bitmap.height
                    }
                    if (bitmap.width != width || bitmap.height != height) return false
                    val pixels = IntArray(width * height)
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                    // Match Oppo's focus-energy scale (0..255) so the confidence threshold
                    // remains independent of Bitmap color normalization.
                    val luma = FloatArray(pixels.size) { index ->
                        val c = pixels[index]
                        0.299f * ((c shr 16) and 0xFF) +
                            0.587f * ((c shr 8) and 0xFF) +
                            0.114f * (c and 0xFF)
                    }
                    val energy = FloatArray(pixels.size)
                    for (y in 1 until height - 1) {
                        val row = y * width
                        for (x in 1 until width - 1) {
                            val index = row + x
                            val laplacian = abs(
                                4f * luma[index] - luma[index - 1] - luma[index + 1] -
                                    luma[index - width] - luma[index + width]
                            )
                            energy[index] = laplacian
                        }
                    }
                    energyPlanes.add(energy)
                } finally {
                    bitmap.recycle()
                }
            }

            val depth = FloatArray(width * height) { 0.5f }
            val planeCount = energyPlanes.size
            for (index in depth.indices) {
                var bestPlane = planeCount / 2
                var bestEnergy = 0f
                for (plane in 0 until planeCount) {
                    val energy = energyPlanes[plane][index]
                    if (energy > bestEnergy) {
                        bestEnergy = energy
                        bestPlane = plane
                    }
                }
                depth[index] = if (bestEnergy > 1.5f) {
                    bestPlane.toFloat() / (planeCount - 1).coerceAtLeast(1)
                } else {
                    0.5f
                }
            }

            // Spatial smoothing removes isolated focus-energy winners without ever blending
            // the captured planes themselves. This keeps the saved reference image untouched.
            val smoothedDepth = smoothDepthMap(depth, width, height)
            val depthPixels = IntArray(smoothedDepth.size)

            val depthBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                for (i in smoothedDepth.indices) {
                    val value = (smoothedDepth[i] * 255f).toInt().coerceIn(0, 255)
                    depthPixels[i] = Color.rgb(value, value, value)
                }
                depthBitmap.setPixels(depthPixels, 0, width, 0, 0, width, height)
                destination.parentFile?.mkdirs()
                FileOutputStream(destination).use {
                    if (!depthBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) return false
                }
                return destination.isFile && destination.length() > 0L
            } finally {
                depthBitmap.recycle()
            }
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * Picks the focus plane with the strongest local focus-energy vote at the selected subject
     * point. If no explicit point was selected, the image centre is used.
     */
    fun selectFocusPlane(
        depthMapFile: File,
        frameCount: Int,
        focusPointX: Float? = null,
        focusPointY: Float? = null,
    ): Int {
        val fallback = (frameCount / 2).coerceIn(0, (frameCount - 1).coerceAtLeast(0))
        if (frameCount < 2 || !depthMapFile.isFile) return fallback
        val bitmap = BitmapFactory.decodeFile(depthMapFile.absolutePath) ?: return fallback
        try {
            if (bitmap.width <= 0 || bitmap.height <= 0) return fallback
            val cx = ((focusPointX ?: 0.5f).coerceIn(0f, 1f) * (bitmap.width - 1)).roundToInt()
            val cy = ((focusPointY ?: 0.5f).coerceIn(0f, 1f) * (bitmap.height - 1)).roundToInt()
            val votes = IntArray(frameCount)
            val left = (cx - 2).coerceAtLeast(0)
            val right = (cx + 2).coerceAtMost(bitmap.width - 1)
            val top = (cy - 2).coerceAtLeast(0)
            val bottom = (cy + 2).coerceAtMost(bitmap.height - 1)
            for (y in top..bottom) {
                for (x in left..right) {
                    val plane = (((bitmap.getPixel(x, y) and 0xFF) / 255f) * (frameCount - 1))
                        .roundToInt().coerceIn(0, frameCount - 1)
                    votes[plane]++
                }
            }
            var selected = fallback
            for (index in votes.indices) {
                if (votes[index] > votes[selected] ||
                    (votes[index] == votes[selected] && abs(index - fallback) < abs(selected - fallback))
                ) selected = index
            }
            return selected
        } finally {
            bitmap.recycle()
        }
    }

    private fun smoothDepthMap(depth: FloatArray, width: Int, height: Int): FloatArray {
        val smoothed = FloatArray(depth.size)
        for (y in 0 until height) {
            val y0 = (y - 1).coerceAtLeast(0)
            val y1 = (y + 1).coerceAtMost(height - 1)
            for (x in 0 until width) {
                val x0 = (x - 1).coerceAtLeast(0)
                val x1 = (x + 1).coerceAtMost(width - 1)
                var sum = 0f
                var count = 0
                for (ny in y0..y1) {
                    val row = ny * width
                    for (nx in x0..x1) {
                        sum += depth[row + nx]
                        count++
                    }
                }
                smoothed[y * width + x] = sum / count.coerceAtLeast(1)
            }
        }
        return smoothed
    }
}
