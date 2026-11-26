package com.hinnka.mycamera.video

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Range
import android.util.Size
import com.hinnka.mycamera.camera.readMetadataOrNull
import com.hinnka.mycamera.utils.PLog
import kotlin.math.abs

object VideoCapabilitiesResolver {

    private const val TAG = "VideoCapabilitiesResolver"
    private const val VIDEO_PREVIEW_SHORT_EDGE = 1080
    private const val VIDEO_PREVIEW_ASPECT_TOLERANCE = 0.01f

    fun resolve(
        characteristics: CameraCharacteristics,
        requestedConfig: VideoConfig,
        algorithmicStabilizationSupported: Boolean = false,
    ): VideoCapabilitySnapshot {
        val availableCodecs = resolveAvailableCodecs()
        val resolvedCodec = requestedConfig.codec.takeIf { it in availableCodecs }
            ?: availableCodecs.firstOrNull()
            ?: VideoCodec.H264

        // Resolve from one metadata snapshot. Session caches are cleared while reopening,
        // which must not turn a supported user selection into an unsupported capability.
        val availableTonemapModes = characteristics.get(
            CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES
        ) ?: intArrayOf()
        val availableVideoStabilizationModes = characteristics.get(
            CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
        ) ?: intArrayOf()
        val availableOpticalStabilizationModes = characteristics.get(
            CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
        ) ?: intArrayOf()
        val isFlashSupported = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val previewOutputSizes = streamConfigMap?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        val yuvOutputSizes = streamConfigMap?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        // Dedicated recording uses a SurfaceTexture input, including full-sensor sizes
        // that may not be advertised for MediaRecorder.
        val advertisedRecordingSizes = resolveRecordingOutputSizes(characteristics)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val openGateAspect = resolveOpenGatePortraitAspectRatio(activeArray, sensorOrientation)
        val targetPortraitAspect = requestedConfig.aspectRatio.getPortraitAspectRatio(openGateAspect)
        // Capture input is resolved once at a fixed 4K-capable size. It must not
        // follow the output preset selected in the video settings panel.
        val fixedCameraInputSize = findBestOutputSize(
            previewOutputSizes,
            VideoResolutionPreset.UHD_2160P,
            requestedConfig.aspectRatio,
            openGateAspect,
        ) ?: previewOutputSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
        // EIS+ uses a separate YUV source, but it follows the same fixed-input rule.
        // If a 4K-capable YUV source is unavailable, EIS+ is not advertised.
        val fixedEnhancedStabilizationInputSize = findBestOutputSize(
            yuvOutputSizes,
            VideoResolutionPreset.UHD_2160P,
            requestedConfig.aspectRatio,
            openGateAspect,
        )?.takeIf { source ->
            canProduceOutputWithoutUpscaling(
                source,
                VideoResolutionPreset.UHD_2160P.resolveOutputSize(targetPortraitAspect),
            )
        }
        val threeKOutputSize = if (requestedConfig.aspectRatio == VideoAspectRatio.OPEN_GATE) {
            null
        } else {
            VideoResolutionPreset.THREE_K.resolveOutputSize(targetPortraitAspect)
        }
        // 3K is offered only when the camera's real SurfaceTexture stream can be
        // center-cropped to 3072x1728 (or the equivalent selected aspect ratio)
        // without enlarging either dimension. This excludes "3K" made from 1080p.
        val nativeThreeKSources = threeKOutputSize?.let { targetSize ->
            previewOutputSizes.filter { canProduceOutputWithoutUpscaling(it, targetSize) }
        }.orEmpty()
        val supportsThreeKEncoder = threeKOutputSize?.let { targetSize ->
            supportsEncoderOutputSize(resolvedCodec.mimeType, targetSize)
        } ?: false
        val qhdOutputSize = if (requestedConfig.aspectRatio == VideoAspectRatio.OPEN_GATE) {
            null
        } else {
            VideoResolutionPreset.QHD_1440P.resolveOutputSize(targetPortraitAspect)
        }
        val nativeQhdSources = qhdOutputSize?.let { targetSize ->
            previewOutputSizes.filter { canProduceOutputWithoutUpscaling(it, targetSize) }
        }.orEmpty()
        val supportsQhdEncoder = qhdOutputSize?.let { targetSize ->
            supportsEncoderOutputSize(resolvedCodec.mimeType, targetSize)
        } ?: false

        // Some front/ultra-wide sensors expose an ~8 MP 4:3 SurfaceTexture stream
        // but do not advertise a 3840x2160 MediaRecorder stream. The app records
        // through its SurfaceTexture + GL renderer, so use that sensor stream as
        // the source and let the renderer crop/scale to the selected output.
        val hasNativeUhdRecorderSize = advertisedRecordingSizes.any {
            maxOf(it.width, it.height) >= VideoResolutionPreset.UHD_2160P.longEdge
        }
        val hasEightMpFourThreeSource = previewOutputSizes.any(::isEightMpFourThreeSource)
        // Open Gate keeps the largest full-sensor SurfaceTexture as its camera input.
        // Check both supported video codecs for a matching encoder canvas: the currently
        // selected codec may expose a smaller maximum size even when the other hardware
        // encoder can encode the full sensor stream. VideoRecorder selects the matching
        // codec explicitly and falls back only if configuration fails on this device.
        // An explicitly selected APV encoder must never be replaced by H.264/HEVC.
        // For the legacy codecs retain the existing compatible-codec fallback behavior.
        val openGateEncoderMimeTypes = if (resolvedCodec == VideoCodec.APV) {
            listOf(resolvedCodec.mimeType)
        } else {
            availableCodecs.filterNot { it == VideoCodec.APV }.map { it.mimeType }
                .ifEmpty { listOf(resolvedCodec.mimeType) }
        }
        val openGateEncoderOutputSizes = if (requestedConfig.aspectRatio == VideoAspectRatio.OPEN_GATE) {
            previewOutputSizes
                .map { Size(minOf(it.width, it.height), maxOf(it.width, it.height)) }
                .distinct()
                .filter { size ->
                    openGateEncoderMimeTypes.any { mime -> supportsEncoderOutputSize(mime, size) }
                }
        } else {
            emptyList()
        }

        val recordingOutputSizes = when {
            requestedConfig.aspectRatio == VideoAspectRatio.OPEN_GATE -> openGateEncoderOutputSizes
            !hasNativeUhdRecorderSize && hasEightMpFourThreeSource -> previewOutputSizes
            nativeThreeKSources.isNotEmpty() || nativeQhdSources.isNotEmpty() ->
                (advertisedRecordingSizes + nativeThreeKSources + nativeQhdSources).distinct()
            else -> advertisedRecordingSizes
        }

        val availableResolutions = VideoResolutionPreset.entries
            .filter { it != VideoResolutionPreset.QHD_1440P }
            .filter { preset ->
                (preset != VideoResolutionPreset.THREE_K || supportsThreeKEncoder) &&
                    (preset != VideoResolutionPreset.QHD_1440P || supportsQhdEncoder) &&
                    findBestOutputSize(recordingOutputSizes, preset, requestedConfig.aspectRatio, openGateAspect) != null &&
                    fixedCameraInputSize?.let { inputSize ->
                        when (preset) {
                            VideoResolutionPreset.THREE_K, VideoResolutionPreset.QHD_1440P ->
                                canProduceOutputWithoutUpscaling(
                                    inputSize,
                                    preset.resolveOutputSize(targetPortraitAspect),
                                )
                            else -> maxOf(inputSize.width, inputSize.height) >= preset.longEdge
                        }
                    } == true
            }

        val recordingSizesByResolution = availableResolutions.mapNotNull { preset ->
            findBestOutputSize(recordingOutputSizes, preset, requestedConfig.aspectRatio, openGateAspect)
                ?.let { preset to it }
        }.toMap()
        // Every output preset receives the same source stream; only the encoder
        // canvas changes when the user selects 720p / 1080p / 3K / 4K.
        val cameraInputSizesByResolution = availableResolutions.mapNotNull { preset ->
            fixedCameraInputSize?.let { preset to it }
        }.toMap()
        val enhancedStabilizationInputSizesByResolution = availableResolutions.mapNotNull { preset ->
            fixedEnhancedStabilizationInputSize?.let { preset to it }
        }.toMap()

        val resolvedResolution = requestedConfig.resolution.takeIf { availableResolutions.contains(it) }
            ?: VideoResolutionPreset.FHD_1080P.takeIf { it in availableResolutions }
            ?: availableResolutions.firstOrNull()
            ?: VideoResolutionPreset.FHD_1080P

        val recordingSize = recordingSizesByResolution[resolvedResolution]
            ?: resolvedResolution.resolveOutputSize(
                requestedConfig.aspectRatio.getPortraitAspectRatio(openGateAspect)
            )

        val fallbackPreviewSize = findFixedPreviewOutputSize(
            outputSizes = previewOutputSizes,
            aspectRatio = requestedConfig.aspectRatio,
            openGatePortraitAspectRatio = openGateAspect
        ) ?: VideoResolutionPreset.FHD_1080P.resolveOutputSize(
            requestedConfig.aspectRatio.getPortraitAspectRatio(openGateAspect)
        )
        val cameraInputSize = fixedCameraInputSize ?: fallbackPreviewSize
        if (fixedCameraInputSize != null) {
            val fixed4kTarget = VideoResolutionPreset.UHD_2160P.resolveOutputSize(targetPortraitAspect)
            val inputIs4kCapable = maxOf(fixedCameraInputSize.width, fixedCameraInputSize.height) >=
                VideoResolutionPreset.UHD_2160P.longEdge &&
                minOf(fixedCameraInputSize.width, fixedCameraInputSize.height) + 16 >=
                minOf(fixed4kTarget.width, fixed4kTarget.height)
            PLog.i(
                TAG,
                "Fixed camera video input=${fixedCameraInputSize.width}x${fixedCameraInputSize.height}, " +
                    "4KCapable=$inputIs4kCapable; output resolution=${requestedConfig.resolution.displayName}",
            )
            if (!inputIs4kCapable) {
                PLog.w(TAG, "Camera does not expose a 4K SurfaceTexture source; using its highest available source")
            }
        }
        if (requestedConfig.aspectRatio == VideoAspectRatio.OPEN_GATE) {
            val sourceCandidates = previewOutputSizes
                .sortedByDescending { it.width.toLong() * it.height.toLong() }
                .take(8)
            val encoderCandidates = openGateEncoderOutputSizes
                .sortedByDescending { it.width.toLong() * it.height.toLong() }
                .take(8)
            PLog.i(
                TAG,
                "Open Gate stream selection: activeArray=$activeArray, sensorOrientation=$sensorOrientation, " +
                    "aspect=$openGateAspect, cameraInput=${cameraInputSize.width}x${cameraInputSize.height}, " +
                    "encoderCanvas=${recordingSize.width}x${recordingSize.height}, " +
                    "sourceCandidates=$sourceCandidates, encoderCandidates=$encoderCandidates"
            )
        }
        // Keep the live video viewfinder on the same selected-resolution camera stream.
        // The 1080p-sized preview is only a fallback when no matching input stream is available.
        val previewSize = cameraInputSize

        val availableFpsByResolution = cameraInputSizesByResolution.mapValues { (_, inputSize) ->
            resolveAvailableFps(
                characteristics = characteristics,
                cameraInputSize = inputSize,
                previewSize = inputSize
            )
        }
        val availableFps = availableFpsByResolution[resolvedResolution]
            .orEmpty()
            .ifEmpty {
                resolveAvailableFps(
                    characteristics = characteristics,
                    cameraInputSize = cameraInputSize,
                    previewSize = previewSize
                )
            }
        val resolvedFps = requestedConfig.fps.takeIf { availableFps.contains(it) }
            ?: availableFps.firstOrNull()
            ?: VideoFpsPreset.FPS_30

        val enhancedCameraInputSize =
            enhancedStabilizationInputSizesByResolution[resolvedResolution]
        val enhancedStabilizationAvailable = algorithmicStabilizationSupported &&
            enhancedCameraInputSize != null

        /*PLog.d(
            TAG,
            "Resolved video capabilities: resolution=${resolvedResolution.displayName}, " +
                "recording=${recordingSize.width}x${recordingSize.height}, " +
                "cameraInput=${cameraInputSize.width}x${cameraInputSize.height}, " +
                "preview=${previewSize.width}x${previewSize.height}, " +
                "fps=${availableFps.map { it.fps }}"
        )*/

        val linearTonemapSupported = availableTonemapModes.contains(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE) ||
            availableTonemapModes.contains(CaptureRequest.TONEMAP_MODE_GAMMA_VALUE)
        // HLG is generated by the recording renderer and does not require the
        // Camera2 custom tone-map modes used by the vendor Log curves.
        // OFF is the normal camera path; the other choices expose the supported profiles.
        // Natural and HLG10 use OppoCam's ISP curves; Apple Log keeps Photon's log renderer.
        val availableLogProfiles = VideoLogProfile.SELECTABLE_PROFILES
        val resolvedLogProfile = requestedConfig.logProfile.takeIf { availableLogProfiles.contains(it) }
            ?: VideoLogProfile.OFF

        val availableStabilizationModes = mutableListOf(VideoStabilizationMode.OFF)
        if (resolvedFps != VideoFpsPreset.FPS_120) {
            if (supportsElectronicStabilization(availableVideoStabilizationModes)) {
                availableStabilizationModes.add(VideoStabilizationMode.EIS)
            }
            if (availableOpticalStabilizationModes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
                availableStabilizationModes.add(VideoStabilizationMode.OIS)
            }
            if (enhancedStabilizationAvailable) {
                availableStabilizationModes.add(VideoStabilizationMode.ENHANCED)
            }
        }
        
        val resolvedStabilizationMode = if (availableStabilizationModes.contains(requestedConfig.stabilizationMode)) {
            requestedConfig.stabilizationMode
        } else {
            if (availableStabilizationModes.contains(VideoStabilizationMode.OIS)) VideoStabilizationMode.OIS
            else if (availableStabilizationModes.contains(VideoStabilizationMode.EIS)) VideoStabilizationMode.EIS
            else VideoStabilizationMode.OFF
        }

        return VideoCapabilitySnapshot(
            config = requestedConfig.copy(
                codec = resolvedCodec,
                resolution = resolvedResolution,
                fps = resolvedFps,
                logProfile = resolvedLogProfile,
                bitrate = requestedConfig.bitrate,
                stabilizationMode = resolvedStabilizationMode,
                torchEnabled = requestedConfig.torchEnabled && isFlashSupported
            ),
            capabilities = VideoCapabilities(
                availableResolutions = if (requestedConfig.aspectRatio == VideoAspectRatio.OPEN_GATE) {
                    emptyList()
                } else {
                    availableResolutions
                },
                availableFps = availableFps,
                availableAspectRatios = VideoAspectRatio.entries.toList(),
                availableLogProfiles = availableLogProfiles,
                availableBitrates = VideoBitratePreset.entries.toList(),
                cameraInputSizesByResolution = cameraInputSizesByResolution,
                enhancedStabilizationInputSizesByResolution =
                    enhancedStabilizationInputSizesByResolution,
                recordingSizesByResolution = recordingSizesByResolution,
                openGatePortraitAspectRatio = openGateAspect,
                availableStabilizationModes = availableStabilizationModes,
                supportsTorch = isFlashSupported,
                linearTonemapSupported = linearTonemapSupported,
                availableFpsByResolution = availableFpsByResolution,
                availableCodecs = availableCodecs
            ),
            previewSize = previewSize,
            recordingSize = recordingSize
        )
    }

    private fun supportsElectronicStabilization(availableVideoStabilizationModes: IntArray): Boolean {
        return availableVideoStabilizationModes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) ||
            supportsPreviewStabilization(availableVideoStabilizationModes)
    }

    private fun supportsPreviewStabilization(availableVideoStabilizationModes: IntArray): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            availableVideoStabilizationModes.contains(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
            )
    }

    private fun findBestOutputSize(
        outputSizes: List<Size>,
        preset: VideoResolutionPreset,
        aspectRatio: VideoAspectRatio,
        openGatePortraitAspectRatio: Float
    ): Size? {
        if (outputSizes.isEmpty()) return null

        val targetAspect = aspectRatio.getPortraitAspectRatio(openGatePortraitAspectRatio)
        if (aspectRatio == VideoAspectRatio.OPEN_GATE) {
            // Ignore the saved resolution preset. Prefer the largest full-sensor
            // stream, allowing the same aspect tolerance as the preview selection.
            val aspectMatchedSizes = outputSizes.filter {
                abs(getPortraitAspectRatio(it) - targetAspect) <= VIDEO_PREVIEW_ASPECT_TOLERANCE
            }
            return if (aspectMatchedSizes.isNotEmpty()) {
                aspectMatchedSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
            } else {
                outputSizes.minWithOrNull(
                    compareBy<Size> { abs(getPortraitAspectRatio(it) - targetAspect) }
                        .thenByDescending { it.width.toLong() * it.height.toLong() }
                )
            }
        }
        val nativeSize = outputSizes
            .filter {
                maxOf(it.width, it.height) >= preset.longEdge &&
                    (preset != VideoResolutionPreset.THREE_K && preset != VideoResolutionPreset.QHD_1440P ||
                        canProduceOutputWithoutUpscaling(it, preset.resolveOutputSize(targetAspect)))
            }
            .sortedWith(
                compareBy<Size> { abs(getPortraitAspectRatio(it) - targetAspect) }
                    .thenBy { abs(maxOf(it.width, it.height) - preset.longEdge) }
                    .thenByDescending { it.width.toLong() * it.height.toLong() }
            )
            .firstOrNull()
        if (nativeSize != null) return nativeSize

        // 3K may use a different native sensor aspect ratio (for example 4:3),
        // but only when center-cropping that source still leaves enough pixels for
        // the exact 3K encoder canvas. No lower-resolution source is upscaled.
        if (preset == VideoResolutionPreset.THREE_K || preset == VideoResolutionPreset.QHD_1440P) {
            val targetSize = preset.resolveOutputSize(targetAspect)
            return outputSizes
                .filter { canProduceOutputWithoutUpscaling(it, targetSize) }
                .minByOrNull { it.width.toLong() * it.height.toLong() }
        }

        // UHD is an encoded output preset, not a requirement that the sensor itself
        // produce 8.3 MP. If the camera exposes a genuine ~8 MP 4:3 source, allow it
        // and crop/scale in RealtimeVideoRenderer to the 4K 16:9 (or portrait 9:16)
        // encoder surface. Do not offer this fallback for smaller sensors.
        if (preset == VideoResolutionPreset.UHD_2160P) {
            return outputSizes
                .filter(::isEightMpFourThreeSource)
                .maxByOrNull { it.width.toLong() * it.height.toLong() }
        }
        return null
    }

    private fun canProduceOutputWithoutUpscaling(sourceSize: Size, outputSize: Size): Boolean {
        if (sourceSize.width <= 0 || sourceSize.height <= 0 ||
            outputSize.width <= 0 || outputSize.height <= 0
        ) return false

        val sourceShort = minOf(sourceSize.width, sourceSize.height).toFloat()
        val sourceLong = maxOf(sourceSize.width, sourceSize.height).toFloat()
        val outputShort = minOf(outputSize.width, outputSize.height)
        val outputLong = maxOf(outputSize.width, outputSize.height)
        val targetAspect = outputShort.toFloat() / outputLong
        val sourceAspect = sourceShort / sourceLong

        // Match the renderer's center-crop geometry, then verify both resulting
        // source dimensions are at least as large as the requested encoded frame.
        val croppedShort = if (sourceAspect >= targetAspect) {
            sourceLong * targetAspect
        } else {
            sourceShort
        }
        val croppedLong = if (sourceAspect >= targetAspect) {
            sourceLong
        } else {
            sourceShort / targetAspect
        }
        return croppedShort + 0.5f >= outputShort &&
            croppedLong + 0.5f >= outputLong
    }

    private fun resolveAvailableCodecs(): List<VideoCodec> {
        val codecInfos = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        }.getOrDefault(emptyArray())

        return VideoCodec.entries.filter { codec ->
            codecInfos.any { codecInfo ->
                if (!codecInfo.isEncoder || codecInfo.isAlias) return@any false
                val encoderType = codecInfo.supportedTypes.firstOrNull {
                    it.equals(codec.mimeType, ignoreCase = true)
                } ?: return@any false
                runCatching {
                    codecInfo.getCapabilitiesForType(encoderType).colorFormats.contains(
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                }.getOrDefault(false)
            }
        }
    }

    private fun supportsEncoderOutputSize(codecMime: String, size: Size): Boolean {
        val codecInfos = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        }.getOrDefault(emptyArray())

        return codecInfos.any { codecInfo ->
            if (!codecInfo.isEncoder || codecInfo.isAlias) return@any false
            val encoderType = codecInfo.supportedTypes.firstOrNull {
                it.equals(codecMime, ignoreCase = true)
            } ?: return@any false
            runCatching {
                val capabilities = codecInfo.getCapabilitiesForType(encoderType)
                capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) &&
                    capabilities.videoCapabilities.isSizeSupported(size.width, size.height)
            }.getOrDefault(false)
        }
    }

    private fun isEightMpFourThreeSource(size: Size): Boolean {
        if (size.width <= 0 || size.height <= 0) return false
        val pixels = size.width.toLong() * size.height.toLong()
        val aspect = getPortraitAspectRatio(size)
        return pixels >= 7_500_000L &&
            pixels < 12_000_000L &&
            abs(aspect - (3f / 4f)) <= 0.04f
    }

    private fun findFixedPreviewOutputSize(
        outputSizes: List<Size>,
        aspectRatio: VideoAspectRatio,
        openGatePortraitAspectRatio: Float
    ): Size? {
        val validSizes = outputSizes
            .filter { it.width > 0 && it.height > 0 }
            .distinctBy { it.width to it.height }
        if (validSizes.isEmpty()) return null

        val targetAspect = aspectRatio.getPortraitAspectRatio(openGatePortraitAspectRatio)
        val aspectMatchedSizes = validSizes.filter {
            abs(getPortraitAspectRatio(it) - targetAspect) <= VIDEO_PREVIEW_ASPECT_TOLERANCE
        }
        val candidates = aspectMatchedSizes.ifEmpty { validSizes }
        val sizesWithinLimit = candidates.filter {
            minOf(it.width, it.height) <= VIDEO_PREVIEW_SHORT_EDGE
        }

        return if (sizesWithinLimit.isNotEmpty()) {
            sizesWithinLimit.sortedWith(
                compareByDescending<Size> { minOf(it.width, it.height) }
                    .thenBy { abs(getPortraitAspectRatio(it) - targetAspect) }
                    .thenByDescending { it.width.toLong() * it.height.toLong() }
            ).first()
        } else {
            candidates.sortedWith(
                compareBy<Size> { minOf(it.width, it.height) }
                    .thenBy { abs(getPortraitAspectRatio(it) - targetAspect) }
                    .thenBy { it.width.toLong() * it.height.toLong() }
            ).first()
        }
    }

    private fun resolveAvailableFps(
        characteristics: CameraCharacteristics,
        cameraInputSize: Size,
        previewSize: Size
    ): List<VideoFpsPreset> {
        val outputRanges = characteristics.readMetadataOrNull(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            .orEmpty()
        val minFrameDurationNs = resolveMinFrameDurationNs(characteristics, cameraInputSize, previewSize)
        val maxFpsByDuration = if (minFrameDurationNs > 0) {
            (1_000_000_000.0 / minFrameDurationNs.toDouble()).toInt()
        } else {
            Int.MAX_VALUE
        }

        // 120 fps is exposed only through Camera2 constrained-high-speed sessions.
        // The regular AE range and regular stream-duration checks are not sufficient.
        val highSpeed120Supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            cameraInputSize == previewSize &&
            maxOf(cameraInputSize.width, cameraInputSize.height) == VideoResolutionPreset.FHD_1080P.longEdge &&
            minOf(cameraInputSize.width, cameraInputSize.height) == VideoResolutionPreset.FHD_1080P.shortEdge &&
            runCatching {
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: return@runCatching false
                val highSpeedSize = map.highSpeedVideoSizes?.any { it == cameraInputSize } == true
                highSpeedSize && map.getHighSpeedVideoFpsRangesFor(cameraInputSize).any { range ->
                    range.lower <= 120 && range.upper >= 120
                }
            }.getOrDefault(false)

        val standardPresets = VideoFpsPreset.entries.filter { it != VideoFpsPreset.FPS_120 }
        val strictAvailableFps = standardPresets.filter { preset ->
            preset.fps <= maxFpsByDuration && outputRanges.any { range ->
                supportsFps(range, preset.fps)
            }
        }

        val standardAvailableFps = if (strictAvailableFps.isNotEmpty()) {
            val advertisedMaxFps = strictAvailableFps.maxOf { it.fps }
            if (advertisedMaxFps >= 50) {
                strictAvailableFps
            } else {
                val optimisticFps = standardPresets.filter { it.fps > advertisedMaxFps }
                (strictAvailableFps + optimisticFps).distinctBy { it.fps }
            }
        } else {
            listOf(VideoFpsPreset.FPS_30)
        }

        return if (highSpeed120Supported) {
            (standardAvailableFps + VideoFpsPreset.FPS_120).distinctBy { it.fps }.sortedBy { it.fps }
        } else {
            standardAvailableFps
        }
    }

    private fun supportsFps(range: Range<Int>, fps: Int): Boolean {
        return range.upper >= fps && range.lower <= fps
    }

    private fun resolveRecordingOutputSizes(characteristics: CameraCharacteristics): List<Size> {
        val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        return try {
            streamConfigMap?.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty()
        } catch (e: Exception) {
            PLog.w(TAG, "Failed to query MediaRecorder output sizes: ${e.message}")
            emptyList()
        }.ifEmpty {
            streamConfigMap?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        }
    }

    private fun resolveMinFrameDurationNs(
        characteristics: CameraCharacteristics,
        cameraInputSize: Size,
        previewSize: Size
    ): Long {
        val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (streamConfigMap == null) return 0L

        val cameraInputDurationNs = queryMinFrameDurationNs(
            streamConfigMap = streamConfigMap,
            outputClass = SurfaceTexture::class.java,
            outputSize = cameraInputSize,
            label = "recording SurfaceTexture"
        )
        val previewDurationNs = queryMinFrameDurationNs(
            streamConfigMap = streamConfigMap,
            outputClass = SurfaceTexture::class.java,
            outputSize = previewSize,
            label = "preview SurfaceTexture"
        )
        return maxOf(cameraInputDurationNs, previewDurationNs)
    }

    private fun <T> queryMinFrameDurationNs(
        streamConfigMap: android.hardware.camera2.params.StreamConfigurationMap,
        outputClass: Class<T>,
        outputSize: Size,
        label: String
    ): Long {
        return try {
            streamConfigMap.getOutputMinFrameDuration(outputClass, outputSize)
        } catch (e: Exception) {
            PLog.w(TAG, "Failed to query $label min frame duration for $outputSize: ${e.message}")
            0L
        }
    }

    private fun getPortraitAspectRatio(size: Size): Float {
        val width = minOf(size.width, size.height).coerceAtLeast(1)
        val height = maxOf(size.width, size.height).coerceAtLeast(1)
        return width.toFloat() / height.toFloat()
    }
}
