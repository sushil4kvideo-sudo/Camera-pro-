package com.hinnka.mycamera.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.hinnka.mycamera.utils.PLog
import java.io.File
import java.nio.ByteBuffer

/**
 * App-private companion footage for Spatial Video.
 *
 * Only the primary 1x recording is published to MediaStore. The ultra-wide track lives in
 * app-private storage and is therefore invisible to third-party gallery apps.
 */
object SpatialVideoStore {
    private const val TAG = "SpatialVideoStore"
    private const val SPECIAL_TIMELINE_TAG = "SpecialVideoTimeline"
    private const val DIRECTORY_NAME = "spatial_video"
    private const val SUFFIX = "_0_5x.mp4"
    private const val TELEPHOTO_SUFFIX = "_4x.mp4"
    private const val FALLBACK_BUFFER_SIZE = 4 * 1024 * 1024

    fun ultraWideFile(context: Context, primaryVideoUri: Uri): File {
        val rawKey = primaryVideoUri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: "video_${primaryVideoUri.toString().hashCode()}"
        val safeKey = rawKey
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120)
            .ifBlank { "video" }
        val directory = File(context.filesDir, DIRECTORY_NAME)
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IllegalStateException("Unable to create Special Video companion directory: ${directory.absolutePath}")
        }
        return File(directory, safeKey + SUFFIX)
    }

    fun telephotoFile(context: Context, primaryVideoUri: Uri): File {
        val rawKey = primaryVideoUri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: "video_${primaryVideoUri.toString().hashCode()}"
        val safeKey = rawKey
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120)
            .ifBlank { "video" }
        val directory = File(context.filesDir, DIRECTORY_NAME)
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IllegalStateException("Unable to create Special Video companion directory: ${directory.absolutePath}")
        }
        return File(directory, safeKey + TELEPHOTO_SUFFIX)
    }

    /**
     * Copies the primary recording's AAC audio samples into the ultra-wide MP4 without
     * re-encoding either stream. Both recorders use the same elapsed-realtime timeline origin.
     * The source video remains playable even when the primary file has no audio track.
     */
    fun attachPrimaryAudioTrack(
        context: Context,
        primaryVideoUri: Uri,
        ultraWideFile: File,
        lensLabel: String = "0.5x",
    ): Boolean {
        if (!ultraWideFile.isFile || ultraWideFile.length() <= 0L) return false

        val tempFile = File(ultraWideFile.parentFile, ultraWideFile.nameWithoutExtension + ".muxing.mp4")
        if (tempFile.exists()) tempFile.delete()

        val wideExtractor = MediaExtractor()
        val primaryExtractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        var muxer: MediaMuxer? = null
        var primaryVideoPfd: ParcelFileDescriptor? = null
        var muxerStarted = false

        try {
            wideExtractor.setDataSource(ultraWideFile.absolutePath)
            primaryVideoPfd = context.contentResolver.openFileDescriptor(primaryVideoUri, "r")
                ?: throw IllegalStateException("Unable to open primary video URI: $primaryVideoUri")
            primaryExtractor.setDataSource(primaryVideoPfd!!.fileDescriptor)

            val wideVideoTrack = (0 until wideExtractor.trackCount).firstOrNull { index ->
                wideExtractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IllegalStateException("Ultra-wide MP4 has no video track")
            val primaryAudioTrack = (0 until primaryExtractor.trackCount).firstOrNull { index ->
                primaryExtractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return true

            val videoFormat = wideExtractor.getTrackFormat(wideVideoTrack)
            val audioFormat = primaryExtractor.getTrackFormat(primaryAudioTrack)
            muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            runCatching {
                retriever.setDataSource(ultraWideFile.absolutePath)
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull()
                if (rotation != null && rotation in setOf(0, 90, 180, 270)) {
                    muxer.setOrientationHint(rotation)
                }
            }

            val outputVideoTrack = muxer.addTrack(videoFormat)
            val outputAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()
            muxerStarted = true
            wideExtractor.selectTrack(wideVideoTrack)
            primaryExtractor.selectTrack(primaryAudioTrack)

            val videoBuffer = ByteBuffer.allocateDirect(maxBufferSize(videoFormat))
            val audioBuffer = ByteBuffer.allocateDirect(maxBufferSize(audioFormat))
            val bufferInfo = MediaCodec.BufferInfo()
            var videoTimeUs = wideExtractor.sampleTime
            var audioTimeUs = primaryExtractor.sampleTime

            while (videoTimeUs >= 0L || audioTimeUs >= 0L) {
                if (videoTimeUs >= 0L && (audioTimeUs < 0L || videoTimeUs <= audioTimeUs)) {
                    writeNextSample(
                        extractor = wideExtractor,
                        outputTrack = outputVideoTrack,
                        buffer = videoBuffer,
                        bufferInfo = bufferInfo,
                        muxer = muxer
                    )
                    videoTimeUs = wideExtractor.sampleTime
                } else {
                    writeNextSample(
                        extractor = primaryExtractor,
                        outputTrack = outputAudioTrack,
                        buffer = audioBuffer,
                        bufferInfo = bufferInfo,
                        muxer = muxer
                    )
                    audioTimeUs = primaryExtractor.sampleTime
                }
            }

            muxer.stop()
            muxerStarted = false
            muxer.release()
            muxer = null

            if (!tempFile.isFile || tempFile.length() <= 0L) {
                throw IllegalStateException("Spatial MP4 audio mux produced an empty file")
            }
            tempFile.copyTo(ultraWideFile, overwrite = true)
            tempFile.delete()
            PLog.i(TAG, "Copied primary AAC audio into $lensLabel Special Video companion: ${ultraWideFile.name}")
            return true
        } catch (error: Exception) {
            PLog.w(TAG, "Could not attach primary audio to ultra-wide companion: ${error.message}", error)
            return false
        } finally {
            if (muxer != null) {
                runCatching {
                    if (muxerStarted) muxer.stop()
                }
                runCatching { muxer.release() }
            }
            runCatching { wideExtractor.release() }
            runCatching { primaryExtractor.release() }
            runCatching { primaryVideoPfd?.close() }
            runCatching { retriever.release() }
            if (tempFile.exists()) tempFile.delete()
            logFinalDurations(context, primaryVideoUri, ultraWideFile, lensLabel)
        }
    }

    private fun logFinalDurations(
        context: Context,
        primaryVideoUri: Uri,
        ultraWideFile: File,
        lensLabel: String,
    ) {
        val primaryDurationMs = runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(context, primaryVideoUri)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } finally {
                    release()
                }
            }
        }.getOrNull()
        val ultraWideDurationMs = runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(ultraWideFile.absolutePath)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } finally {
                    release()
                }
            }
        }.getOrNull()

        val primaryVideoEndUs = lastTrackSampleTimeUs(
            context = context,
            uri = primaryVideoUri,
            file = null,
            trackMimePrefix = "video/",
        )
        val ultraWideVideoEndUs = lastTrackSampleTimeUs(
            context = context,
            uri = null,
            file = ultraWideFile,
            trackMimePrefix = "video/",
        )
        val videoEndDeltaUs = if (primaryVideoEndUs != null && ultraWideVideoEndUs != null) {
            kotlin.math.abs(primaryVideoEndUs - ultraWideVideoEndUs)
        } else {
            null
        }
        PLog.i(
            SPECIAL_TIMELINE_TAG,
            "Final muxed Special Video video-track ends: 1x=$primaryVideoEndUs us, " +
                "$lensLabel=$ultraWideVideoEndUs us, deltaUs=$videoEndDeltaUs"
        )
        if (videoEndDeltaUs != null && videoEndDeltaUs > 40_000L) {
            PLog.e(
                SPECIAL_TIMELINE_TAG,
                "Final Special Video video tracks differ by more than 40ms: " +
                    "1xEndUs=$primaryVideoEndUs, $lensLabel EndUs=$ultraWideVideoEndUs, " +
                    "deltaUs=$videoEndDeltaUs"
            )
        }

        if (primaryDurationMs != null && ultraWideDurationMs != null) {
            val deltaMs = kotlin.math.abs(primaryDurationMs - ultraWideDurationMs)
            PLog.i(
                TAG,
                "Final Special Video container durations: main=${primaryDurationMs}ms, " +
                    "$lensLabel=${ultraWideDurationMs}ms, delta=${deltaMs}ms"
            )
            if (deltaMs > 40L) {
                PLog.w(
                    SPECIAL_TIMELINE_TAG,
                    "Special Video container duration mismatch exceeds 40ms: " +
                        "main=${primaryDurationMs}ms, $lensLabel=${ultraWideDurationMs}ms, delta=${deltaMs}ms"
                )
            }
        } else {
            PLog.w(
                TAG,
                "Unable to read final Special Video durations: main=$primaryDurationMs, " +
                    "ultraWide=$ultraWideDurationMs"
            )
        }
    }

    /**
     * Reads the last encoded sample PTS for one track. This deliberately inspects the actual
     * video track rather than relying on container duration, which may be extended by AAC audio.
     */
    private fun lastTrackSampleTimeUs(
        context: Context,
        uri: Uri?,
        file: File?,
        trackMimePrefix: String,
    ): Long? {
        val extractor = MediaExtractor()
        var descriptor: ParcelFileDescriptor? = null
        return try {
            if (file != null) {
                extractor.setDataSource(file.absolutePath)
            } else {
                val sourceUri = uri ?: return null
                descriptor = context.contentResolver.openFileDescriptor(sourceUri, "r")
                    ?: return null
                extractor.setDataSource(descriptor!!.fileDescriptor)
            }
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith(trackMimePrefix) == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            var lastSampleTimeUs: Long? = null
            while (true) {
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs < 0L) break
                lastSampleTimeUs = lastSampleTimeUs?.let { maxOf(it, sampleTimeUs) } ?: sampleTimeUs
                if (!extractor.advance()) break
            }
            lastSampleTimeUs
        } catch (error: Exception) {
            PLog.w(
                SPECIAL_TIMELINE_TAG,
                "Unable to inspect final $trackMimePrefix track PTS: ${error.message}",
                error,
            )
            null
        } finally {
            runCatching { extractor.release() }
            runCatching { descriptor?.close() }
        }
    }

    private fun maxBufferSize(format: MediaFormat): Int {
        val advertised = runCatching {
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                FALLBACK_BUFFER_SIZE
            }
        }.getOrDefault(FALLBACK_BUFFER_SIZE)
        return advertised.coerceAtLeast(FALLBACK_BUFFER_SIZE)
    }

    private fun writeNextSample(
        extractor: MediaExtractor,
        outputTrack: Int,
        buffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer
    ) {
        buffer.clear()
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) return
        bufferInfo.set(
            0,
            size,
            extractor.sampleTime.coerceAtLeast(0L),
            extractor.sampleFlags
        )
        muxer.writeSampleData(outputTrack, buffer, bufferInfo)
        extractor.advance()
    }
}
