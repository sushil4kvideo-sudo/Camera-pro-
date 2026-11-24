package com.hinnka.mycamera.video

import android.os.SystemClock
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.CompletableDeferred

/**
 * Shared BOOTTIME timeline and EOS coordinator for paired recordings.
 *
 * Special Video waits for both inputs to start, keeps one clock conversion for non-REALTIME
 * camera timestamps, and resolves a common end only after both video encoders have reached EOS.
 */
internal class SpecialVideoRecordingTimeline {
    enum class StreamId(val logName: String) {
        MAIN("1x-main"),
        ULTRA_WIDE("0.5x-ultraWide"),
        TELEPHOTO("4x-virtual-telephoto"),
        SECONDARY("secondary"),
    }

    @Volatile
    private var originUs: Long = UNSET

    @Volatile
    private var stopTimeUs: Long = UNSET

    private var firstMainFramePtsUs: Long = UNSET
    private var firstSecondaryFramePtsUs: Long = UNSET
    private var firstTelephotoFramePtsUs: Long = UNSET
    private var includeTelephoto: Boolean = false
    private var sharedCameraTimestampOffsetNs: Long = UNSET

    private var mainEndReported = false
    private var ultraWideEndReported = false
    private var mainVideoEndPtsUs: Long? = null
    private var ultraWideVideoEndPtsUs: Long? = null
    private var telephotoEndReported = false
    private var telephotoVideoEndPtsUs: Long? = null
    private var mainTailOverflowed = false
    private var ultraWideTailOverflowed = false
    private var telephotoTailOverflowed = false
    private var commonVideoEnd = CompletableDeferred<Long?>()
    private var cameraDrainCompleted = CompletableDeferred<Unit>()

    @Synchronized
    fun reportFirstFrame(streamId: StreamId, ptsUs: Long) {
        when (streamId) {
            StreamId.MAIN -> {
                if (firstMainFramePtsUs != UNSET) return
                firstMainFramePtsUs = ptsUs
            }
            StreamId.ULTRA_WIDE, StreamId.SECONDARY -> {
                if (firstSecondaryFramePtsUs != UNSET) return
                firstSecondaryFramePtsUs = ptsUs
            }
            StreamId.TELEPHOTO -> {
                if (firstTelephotoFramePtsUs != UNSET) return
                firstTelephotoFramePtsUs = ptsUs
            }
        }

        PLog.i(TAG, "First accepted video frame: stream=${streamId.logName}, ptsUs=$ptsUs")

        if (originUs == UNSET &&
            firstMainFramePtsUs != UNSET &&
            firstSecondaryFramePtsUs != UNSET &&
            (!includeTelephoto || firstTelephotoFramePtsUs != UNSET)
        ) {
            originUs = if (includeTelephoto) {
                maxOf(firstMainFramePtsUs, firstSecondaryFramePtsUs, firstTelephotoFramePtsUs)
            } else {
                maxOf(firstMainFramePtsUs, firstSecondaryFramePtsUs)
            }
            PLog.i(
                TAG,
                "Shared video origin selected: mainFirstFramePtsUs=$firstMainFramePtsUs, " +
                    "secondaryFirstFramePtsUs=$firstSecondaryFramePtsUs, originUs=$originUs"
            )
        }
    }

    @Synchronized
    fun currentOriginUs(): Long? = originUs.takeIf { it != UNSET }

    /**
     * Return the first recorder's BOOTTIME conversion to every paired recorder. The resolver is
     * intentionally used only for SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN streams.
     */
    @Synchronized
    fun resolveCameraTimestampOffsetNs(candidateOffsetNs: Long): Long {
        if (sharedCameraTimestampOffsetNs == UNSET) {
            sharedCameraTimestampOffsetNs = candidateOffsetNs
            PLog.i(TAG, "Shared camera-to-BOOTTIME offset selected: offsetNs=$candidateOffsetNs")
        }
        return sharedCameraTimestampOffsetNs
    }

    /**
     * Capture one BOOTTIME cutoff before both recorders begin asynchronous shutdown.
     * Each recorder snapshots it before the controller resets this shared timeline.
     */
    @Synchronized
    fun requestStop(): Long {
        if (stopTimeUs == UNSET) {
            stopTimeUs = SystemClock.elapsedRealtimeNanos() / 1_000L
            PLog.i(TAG, "Shared recording stop cutoff selected: stopTimeUs=$stopTimeUs")
        }
        return stopTimeUs
    }

    @Synchronized
    fun currentStopTimeUs(): Long? = stopTimeUs.takeIf { it != UNSET }

    @Synchronized
    fun reportVideoEnd(
        streamId: StreamId,
        videoEndPtsUs: Long?,
        tailOverflowed: Boolean,
    ) {
        when (streamId) {
            StreamId.MAIN -> {
                if (mainEndReported) return
                mainEndReported = true
                mainVideoEndPtsUs = videoEndPtsUs
                mainTailOverflowed = tailOverflowed
            }
            StreamId.ULTRA_WIDE -> {
                if (ultraWideEndReported) return
                ultraWideEndReported = true
                ultraWideVideoEndPtsUs = videoEndPtsUs
                ultraWideTailOverflowed = tailOverflowed
            }
            StreamId.TELEPHOTO -> {
                if (telephotoEndReported) return
                telephotoEndReported = true
                telephotoVideoEndPtsUs = videoEndPtsUs
                telephotoTailOverflowed = tailOverflowed
            }
            StreamId.SECONDARY -> return
        }

        PLog.i(
            TAG,
            "Recorder EOS reported: stream=${streamId.logName}, videoEndPtsUs=$videoEndPtsUs, " +
                "tailOverflowed=$tailOverflowed"
        )

        if (!mainEndReported || !ultraWideEndReported || (includeTelephoto && !telephotoEndReported)) return

        val mainEnd = mainVideoEndPtsUs
        val ultraWideEnd = ultraWideVideoEndPtsUs
        val telephotoEnd = telephotoVideoEndPtsUs
        if (mainEnd == null || ultraWideEnd == null || (includeTelephoto && telephotoEnd == null)) {
            PLog.e(
                TAG,
                "Cannot reconcile Special Video: missing video end PTS " +
                    "(1x=$mainEnd, 0.5x=$ultraWideEnd, 4x=$telephotoEnd); keeping untrimmed outputs"
            )
            commonVideoEnd.complete(null)
            return
        }

        val endPoints = if (includeTelephoto) listOf(mainEnd, ultraWideEnd, requireNotNull(telephotoEnd))
            else listOf(mainEnd, ultraWideEnd)
        val differenceUs = endPoints.maxOrNull()!! - endPoints.minOrNull()!!
        PLog.i(
            TAG,
            "Final Special Video encoded video-track ends: 1x=$mainEnd us, " +
                "0.5x=$ultraWideEnd us, 4x=$telephotoEnd us, delta=${differenceUs}us"
        )

        if (mainTailOverflowed || ultraWideTailOverflowed || (includeTelephoto && telephotoTailOverflowed)) {
            PLog.e(
                TAG,
                "Special Video tail buffer limit reached; refusing automatic trim " +
                    "(1xOverflow=$mainTailOverflowed, 0.5xOverflow=$ultraWideTailOverflowed, " +
                    "4xOverflow=$telephotoTailOverflowed)"
            )
            commonVideoEnd.complete(null)
            return
        }

        if (differenceUs > MAX_SAFE_TRIM_DIFFERENCE_US) {
            PLog.e(
                TAG,
                "Special Video video-track mismatch exceeds safety limit; refusing trim. " +
                    "1xEndPtsUs=$mainEnd, 0.5xEndPtsUs=$ultraWideEnd, 4xEndPtsUs=$telephotoEnd, " +
                    "deltaUs=$differenceUs, limitUs=$MAX_SAFE_TRIM_DIFFERENCE_US"
            )
            commonVideoEnd.complete(null)
            return
        }

        val endUs = endPoints.minOrNull()!!
        PLog.i(
            TAG,
            "Common Special Video end selected: commonEndUs=$endUs, " +
                "mainEndUs=$mainEnd, ultraWideEndUs=$ultraWideEnd, telephotoEndUs=$telephotoEnd"
        )
        commonVideoEnd.complete(endUs)
    }

    suspend fun awaitCommonVideoEndUs(): Long? = commonVideoEnd.await()

    fun completeCameraDrain() {
        cameraDrainCompleted.complete(Unit)
    }

    suspend fun awaitCameraDrain() {
        cameraDrainCompleted.await()
    }

    @Synchronized
    fun reset(includeTelephoto: Boolean = false) {
        originUs = UNSET
        stopTimeUs = UNSET
        firstMainFramePtsUs = UNSET
        firstSecondaryFramePtsUs = UNSET
        firstTelephotoFramePtsUs = UNSET
        this.includeTelephoto = includeTelephoto
        sharedCameraTimestampOffsetNs = UNSET
        mainEndReported = false
        ultraWideEndReported = false
        telephotoEndReported = false
        mainVideoEndPtsUs = null
        ultraWideVideoEndPtsUs = null
        telephotoVideoEndPtsUs = null
        mainTailOverflowed = false
        ultraWideTailOverflowed = false
        telephotoTailOverflowed = false
        commonVideoEnd = CompletableDeferred()
        cameraDrainCompleted = CompletableDeferred()
    }

    private companion object {
        const val TAG = "SpecialVideoTimeline"
        const val UNSET = Long.MIN_VALUE
        const val MAX_SAFE_TRIM_DIFFERENCE_US = 2_000_000L
    }
}
