package com.hinnka.mycamera.video

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.hinnka.mycamera.utils.PLog
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Multi-lens Special Video container and regenerable playback-cache support.
 *
 * The published 1x MP4 owns the 0.5x and optional telephoto video tracks. Photon Gallery
 * extracts temporary cache clips from the in-file track map after app data is cleared.
 */
object SpatialVideoStore {
    private const val TAG = "SpatialVideoStore"
    private const val SPECIAL_TIMELINE_TAG = "SpecialVideoTimeline"
    private const val DIRECTORY_NAME = "spatial_video"
    private const val CACHE_DIRECTORY_NAME = "spatial_video_cache"
    private const val SUFFIX = "_0_5x.mp4"
    private const val TELEPHOTO_SUFFIX = "_4x.mp4"
    private const val FALLBACK_BUFFER_SIZE = 4 * 1024 * 1024
    private const val LENS_MAP_MIME = "application/x-photon-lens-map"
    private const val LENS_MAP_MAGIC = "PHOTON_SPECIAL_VIDEO_V1"

    data class CompanionFiles(
        val ultraWide: File?,
        val telephoto: File?,
    )

    private data class LensTrackMap(
        val main: Int,
        val ultraWide: Int,
        val telephoto: Int,
    )

    private data class TrackCopySource(
        val extractor: MediaExtractor,
        val sourceTrack: Int,
        val outputTrack: Int,
        val buffer: ByteBuffer,
    )

    private fun safeVideoKey(primaryVideoUri: Uri): String {
        val rawKey = primaryVideoUri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: "video_" + primaryVideoUri.toString().hashCode()
        return rawKey
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120)
            .ifBlank { "video" }
    }

    private fun cacheCompanionFile(context: Context, primaryVideoUri: Uri, suffix: String): File {
        val directory = File(context.cacheDir, CACHE_DIRECTORY_NAME)
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IllegalStateException("Unable to create Special Video cache directory: " + directory.absolutePath)
        }
        return File(directory, safeVideoKey(primaryVideoUri) + suffix)
    }

    private fun legacyCompanionFile(context: Context, primaryVideoUri: Uri, suffix: String): File =
        File(File(context.filesDir, DIRECTORY_NAME), safeVideoKey(primaryVideoUri) + suffix)

    /** Temporary, regenerable extraction used by Photon Gallery's existing multi-preview UI. */
    fun ultraWideFile(context: Context, primaryVideoUri: Uri): File =
        cacheCompanionFile(context, primaryVideoUri, SUFFIX)

    /** Temporary, regenerable extraction used by Photon Gallery's existing multi-preview UI. */
    fun telephotoFile(context: Context, primaryVideoUri: Uri): File =
        cacheCompanionFile(context, primaryVideoUri, TELEPHOTO_SUFFIX)

    /**
     * Rebuilds the gallery's temporary lens previews from the multi-video-track MP4.
     *
     * The canonical 0.5x/4x footage is stored inside the public primary MP4. These extracted
     * files are only playback caches, so clearing app data never destroys the alternate footage.
     */
    fun restoreCompanionFiles(context: Context, primaryVideoUri: Uri): CompanionFiles {
        val wideDestination = ultraWideFile(context, primaryVideoUri)
        val telephotoDestination = telephotoFile(context, primaryVideoUri)

        if (!isUsableFile(wideDestination)) {
            val legacy = legacyCompanionFile(context, primaryVideoUri, SUFFIX)
            if (isUsableFile(legacy)) {
                runCatching { legacy.copyTo(wideDestination, overwrite = true) }
            }
        }
        if (!isUsableFile(telephotoDestination)) {
            val legacy = legacyCompanionFile(context, primaryVideoUri, TELEPHOTO_SUFFIX)
            if (isUsableFile(legacy)) {
                runCatching { legacy.copyTo(telephotoDestination, overwrite = true) }
            }
        }

        var wideReady = isUsableFile(wideDestination)
        var telephotoReady = isUsableFile(telephotoDestination)
        if (wideReady && telephotoReady) {
            return CompanionFiles(wideDestination, telephotoDestination)
        }

        val source = MediaExtractor()
        try {
            setExtractorDataSource(context, source, primaryVideoUri)
            val trackMap = readLensTrackMap(source)
            if (trackMap == null) {
                PLog.w(TAG, "No Photon multi-lens track map in " + primaryVideoUri)
                return CompanionFiles(wideDestination.takeIf { wideReady }, telephotoDestination.takeIf { telephotoReady })
            }
            val audioTrack = (0 until source.trackCount).firstOrNull { index ->
                source.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            if (!wideReady && isVideoTrack(source, trackMap.ultraWide)) {
                wideReady = extractVideoTrackWithAudio(
                    context = context,
                    primaryVideoUri = primaryVideoUri,
                    videoTrackIndex = trackMap.ultraWide,
                    audioTrackIndex = audioTrack,
                    destination = wideDestination,
                )
            }
            if (!telephotoReady && isVideoTrack(source, trackMap.telephoto)) {
                telephotoReady = extractVideoTrackWithAudio(
                    context = context,
                    primaryVideoUri = primaryVideoUri,
                    videoTrackIndex = trackMap.telephoto,
                    audioTrackIndex = audioTrack,
                    destination = telephotoDestination,
                )
            }
        } catch (error: Exception) {
            PLog.w(TAG, "Unable to restore Special Video companion tracks: " + error.message, error)
        } finally {
            runCatching { source.release() }
        }

        return CompanionFiles(
            wideDestination.takeIf { wideReady && isUsableFile(wideDestination) },
            telephotoDestination.takeIf { telephotoReady && isUsableFile(telephotoDestination) },
        )
    }

    /**
     * Repackages 1x + 0.5x + optional 4x streams into the published primary MP4.
     * Video track order is deliberate: main 1x is first/default for ordinary gallery players.
     * A small application-defined MP4 metadata track records the exact track indices for Photon.
     */
    fun muxSpecialVideoIntoPrimary(
        context: Context,
        primaryVideoUri: Uri,
        ultraWideFile: File,
        telephotoFile: File?,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            PLog.e(TAG, "Multi-video-track MP4 requires Android O or newer")
            return false
        }
        if (!isUsableFile(ultraWideFile) || (telephotoFile != null && !isUsableFile(telephotoFile))) {
            PLog.e(TAG, "Special Video mux rejected an empty companion source")
            return false
        }

        val temporaryOutput = File(
            context.cacheDir,
            "special_video_" + safeVideoKey(primaryVideoUri) + ".muxing.mp4",
        )
        if (temporaryOutput.exists()) temporaryOutput.delete()

        val primaryExtractor = MediaExtractor()
        val wideExtractor = MediaExtractor()
        val telephotoExtractor = if (telephotoFile != null) MediaExtractor() else null
        val retriever = MediaMetadataRetriever()
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            setExtractorDataSource(context, primaryExtractor, primaryVideoUri)
            wideExtractor.setDataSource(ultraWideFile.absolutePath)
            telephotoFile?.let { telephotoExtractor?.setDataSource(it.absolutePath) }

            val mainVideoSource = firstTrack(primaryExtractor, "video/")
                ?: throw IllegalStateException("Primary Special Video has no 1x video track")
            val wideVideoSource = firstTrack(wideExtractor, "video/")
                ?: throw IllegalStateException("0.5x Special Video companion has no video track")
            val telephotoVideoSource = telephotoExtractor?.let { firstTrack(it, "video/") }
            if (telephotoFile != null && telephotoVideoSource == null) {
                throw IllegalStateException("4x Special Video companion has no video track")
            }
            val primaryAudioSource = firstTrack(primaryExtractor, "audio/")

            val muxerInstance = MediaMuxer(
                temporaryOutput.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            muxer = muxerInstance
            runCatching {
                retriever.setDataSource(context, primaryVideoUri)
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull()
                if (rotation != null && rotation in setOf(0, 90, 180, 270)) {
                    muxerInstance.setOrientationHint(rotation)
                }
            }

            // Add the 1x stream first so normal Android gallery playback opens the main view.
            val mainOutputTrack = muxerInstance.addTrack(primaryExtractor.getTrackFormat(mainVideoSource))
            val wideOutputTrack = muxerInstance.addTrack(wideExtractor.getTrackFormat(wideVideoSource))
            val telephotoOutputTrack = if (telephotoVideoSource != null && telephotoExtractor != null) {
                muxerInstance.addTrack(telephotoExtractor.getTrackFormat(telephotoVideoSource))
            } else {
                -1
            }
            val audioOutputTrack = if (primaryAudioSource != null) {
                muxerInstance.addTrack(primaryExtractor.getTrackFormat(primaryAudioSource))
            } else {
                -1
            }
            val metadataFormat = MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, LENS_MAP_MIME)
            }
            val metadataOutputTrack = muxerInstance.addTrack(metadataFormat)

            muxerInstance.start()
            muxerStarted = true

            val sources = mutableListOf(
                TrackCopySource(
                    primaryExtractor,
                    mainVideoSource,
                    mainOutputTrack,
                    ByteBuffer.allocateDirect(maxBufferSize(primaryExtractor.getTrackFormat(mainVideoSource))),
                ),
                TrackCopySource(
                    wideExtractor,
                    wideVideoSource,
                    wideOutputTrack,
                    ByteBuffer.allocateDirect(maxBufferSize(wideExtractor.getTrackFormat(wideVideoSource))),
                ),
            )
            if (telephotoVideoSource != null && telephotoExtractor != null) {
                sources += TrackCopySource(
                    telephotoExtractor,
                    telephotoVideoSource,
                    telephotoOutputTrack,
                    ByteBuffer.allocateDirect(maxBufferSize(telephotoExtractor.getTrackFormat(telephotoVideoSource))),
                )
            }
            if (primaryAudioSource != null && audioOutputTrack >= 0) {
                val audioFormat = primaryExtractor.getTrackFormat(primaryAudioSource)
                sources += TrackCopySource(
                    primaryExtractor,
                    primaryAudioSource,
                    audioOutputTrack,
                    ByteBuffer.allocateDirect(maxBufferSize(audioFormat)),
                )
            }
            copyTracksInTimestampOrder(muxerInstance, sources)

            val mapText = buildString {
                append("magic=").append(LENS_MAP_MAGIC).append('\n')
                append("main=").append(mainOutputTrack).append('\n')
                append("ultraWide=").append(wideOutputTrack).append('\n')
                append("telephoto=").append(telephotoOutputTrack).append('\n')
            }
            val mapBytes = mapText.toByteArray(Charsets.UTF_8)
            val metadataBuffer = ByteBuffer.allocateDirect(mapBytes.size)
            metadataBuffer.put(mapBytes)
            metadataBuffer.position(0)
            val metadataInfo = MediaCodec.BufferInfo().apply {
                set(0, mapBytes.size, 0L, 0)
            }
            muxerInstance.writeSampleData(metadataOutputTrack, metadataBuffer, metadataInfo)

            muxerInstance.stop()
            muxerStarted = false
            muxerInstance.release()
            muxer = null

            val expectedVideoCount = if (telephotoFile != null) 3 else 2
            if (!validateMuxedOutput(temporaryOutput, expectedVideoCount)) {
                throw IllegalStateException("Muxed Special Video failed its multi-track validation")
            }

            // Keep the original 1x file untouched until all companion tracks have been muxed
            // and validated successfully.
            val descriptor = context.contentResolver.openFileDescriptor(primaryVideoUri, "rwt")
            if (descriptor != null) {
                descriptor.use { pfd ->
                    val output = FileOutputStream(pfd.fileDescriptor)
                    temporaryOutput.inputStream().use { input -> input.copyTo(output) }
                    output.flush()
                    runCatching { output.fd.sync() }
                }
            } else {
                context.contentResolver.openOutputStream(primaryVideoUri, "wt")?.use { output ->
                    temporaryOutput.inputStream().use { input -> input.copyTo(output) }
                    output.flush()
                } ?: throw IllegalStateException("Unable to replace primary Special Video MP4")
            }

            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.SIZE, temporaryOutput.length())
                    put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
                }
                context.contentResolver.update(primaryVideoUri, values, null, null)
            }
            PLog.i(
                TAG,
                "Special Video converted to one multi-track MP4: uri=" + primaryVideoUri +
                    ", videoTracks=" + expectedVideoCount +
                    ", audioTrack=" + (audioOutputTrack >= 0),
            )
            return true
        } catch (error: Exception) {
            PLog.e(TAG, "Special Video multi-track mux failed; keeping the original 1x recording: " + error.message, error)
            return false
        } finally {
            if (muxer != null) {
                runCatching {
                    if (muxerStarted) muxer.stop()
                }
                runCatching { muxer.release() }
            }
            runCatching { primaryExtractor.release() }
            runCatching { wideExtractor.release() }
            runCatching { telephotoExtractor?.release() }
            runCatching { retriever.release() }
            if (temporaryOutput.exists()) temporaryOutput.delete()
        }
    }

    private fun extractVideoTrackWithAudio(
        context: Context,
        primaryVideoUri: Uri,
        videoTrackIndex: Int,
        audioTrackIndex: Int?,
        destination: File,
    ): Boolean {
        val temporaryOutput = File(destination.parentFile, destination.nameWithoutExtension + ".tmp.mp4")
        if (temporaryOutput.exists()) temporaryOutput.delete()
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            setExtractorDataSource(context, extractor, primaryVideoUri)
            if (!isVideoTrack(extractor, videoTrackIndex)) return false
            val videoFormat = extractor.getTrackFormat(videoTrackIndex)
            val usableAudioTrack = audioTrackIndex?.takeIf { index ->
                index in 0 until extractor.trackCount &&
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            val audioFormat = usableAudioTrack?.let(extractor::getTrackFormat)

            val muxerInstance = MediaMuxer(
                temporaryOutput.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            muxer = muxerInstance
            runCatching {
                retriever.setDataSource(context, primaryVideoUri)
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull()
                if (rotation != null && rotation in setOf(0, 90, 180, 270)) {
                    muxerInstance.setOrientationHint(rotation)
                }
            }
            val outputVideoTrack = muxerInstance.addTrack(videoFormat)
            val outputAudioTrack = audioFormat?.let(muxerInstance::addTrack) ?: -1
            muxerInstance.start()
            muxerStarted = true
            extractor.selectTrack(videoTrackIndex)
            if (usableAudioTrack != null) extractor.selectTrack(usableAudioTrack)

            val videoBuffer = ByteBuffer.allocateDirect(maxBufferSize(videoFormat))
            val audioBuffer = audioFormat?.let { ByteBuffer.allocateDirect(maxBufferSize(it)) }
            val bufferInfo = MediaCodec.BufferInfo()
            while (extractor.sampleTime >= 0L) {
                val sourceTrack = extractor.sampleTrackIndex
                val buffer = when {
                    sourceTrack == videoTrackIndex -> videoBuffer
                    sourceTrack == usableAudioTrack && audioBuffer != null -> audioBuffer
                    else -> null
                }
                val outputTrack = when {
                    sourceTrack == videoTrackIndex -> outputVideoTrack
                    sourceTrack == usableAudioTrack && outputAudioTrack >= 0 -> outputAudioTrack
                    else -> -1
                }
                if (buffer != null && outputTrack >= 0) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size > 0) {
                        bufferInfo.set(0, size, extractor.sampleTime.coerceAtLeast(0L), extractor.sampleFlags)
                        muxerInstance.writeSampleData(outputTrack, buffer, bufferInfo)
                    }
                }
                if (!extractor.advance()) break
            }
            muxerInstance.stop()
            muxerStarted = false
            muxerInstance.release()
            muxer = null
            if (!isUsableFile(temporaryOutput)) {
                throw IllegalStateException("Extracted Special Video lens track is empty")
            }
            temporaryOutput.copyTo(destination, overwrite = true)
            return isUsableFile(destination)
        } catch (error: Exception) {
            PLog.w(TAG, "Unable to extract Special Video lens track " + videoTrackIndex + ": " + error.message, error)
            return false
        } finally {
            if (muxer != null) {
                runCatching {
                    if (muxerStarted) muxer.stop()
                }
                runCatching { muxer.release() }
            }
            runCatching { extractor.release() }
            runCatching { retriever.release() }
            if (temporaryOutput.exists()) temporaryOutput.delete()
        }
    }

    private fun validateMuxedOutput(file: File, expectedVideoCount: Int): Boolean {
        if (!isUsableFile(file)) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val videoTracks = (0 until extractor.trackCount).filter { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            val map = readLensTrackMap(extractor)
            videoTracks.size == expectedVideoCount &&
                map != null &&
                videoTracks.contains(map.main) &&
                videoTracks.contains(map.ultraWide) &&
                (map.telephoto < 0 || videoTracks.contains(map.telephoto))
        } catch (error: Exception) {
            PLog.w(TAG, "Unable to validate multi-track Special Video output", error)
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun readLensTrackMap(extractor: MediaExtractor): LensTrackMap? {
        val metadataTrack = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) == LENS_MAP_MIME
        } ?: return null
        return try {
            extractor.selectTrack(metadataTrack)
            val sampleSize = extractor.sampleSize
            if (sampleSize <= 0L || sampleSize > 64L * 1024L) return null
            val buffer = ByteBuffer.allocateDirect(sampleSize.toInt())
            val read = extractor.readSampleData(buffer, 0)
            if (read <= 0) return null
            val bytes = ByteArray(read)
            buffer.position(0)
            buffer.get(bytes)
            val values = String(bytes, Charsets.UTF_8).lineSequence().mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null
                else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
            }.toMap()
            if (values["magic"] != LENS_MAP_MAGIC) return null
            LensTrackMap(
                main = values["main"]?.toIntOrNull() ?: return null,
                ultraWide = values["ultraWide"]?.toIntOrNull() ?: return null,
                telephoto = values["telephoto"]?.toIntOrNull() ?: -1,
            )
        } catch (error: Exception) {
            PLog.w(TAG, "Unable to read Photon lens track metadata", error)
            null
        } finally {
            runCatching { extractor.unselectTrack(metadataTrack) }
        }
    }

    private fun copyTracksInTimestampOrder(
        muxer: MediaMuxer,
        sources: List<TrackCopySource>,
    ) {
        sources.forEach { it.extractor.selectTrack(it.sourceTrack) }
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val next = sources
                .filter { it.extractor.sampleTime >= 0L && it.extractor.sampleTrackIndex == it.sourceTrack }
                .minByOrNull { it.extractor.sampleTime }
                ?: break
            val ptsUs = next.extractor.sampleTime
            next.buffer.clear()
            val size = next.extractor.readSampleData(next.buffer, 0)
            if (size > 0) {
                bufferInfo.set(0, size, ptsUs.coerceAtLeast(0L), next.extractor.sampleFlags)
                muxer.writeSampleData(next.outputTrack, next.buffer, bufferInfo)
            }
            if (!next.extractor.advance()) {
                // The extractor has reached EOS; its sampleTime will be -1 on the next pass.
            }
        }
    }

    private fun setExtractorDataSource(context: Context, extractor: MediaExtractor, uri: Uri) {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            extractor.setDataSource(uri.path ?: throw IllegalArgumentException("File URI has no path"))
        } else {
            extractor.setDataSource(context, uri, null)
        }
    }

    private fun firstTrack(extractor: MediaExtractor, mimePrefix: String): Int? =
        (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith(mimePrefix) == true
        }

    private fun isVideoTrack(extractor: MediaExtractor, index: Int): Boolean =
        index >= 0 && index < extractor.trackCount &&
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true

    private fun isUsableFile(file: File): Boolean = file.isFile && file.length() > 0L

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
