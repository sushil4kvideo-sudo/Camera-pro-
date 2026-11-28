package com.hinnka.mycamera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopOffPanPresetTest {

    @Test
    fun aiSubjectTrackingIsAvailableAsABuiltInPanPreset() {
        val preset = TopOffPanPreset.builtInPresets().firstOrNull {
            it.id == TopOffPanPreset.BUILTIN_AI_SUBJECT_TRACKING_ID
        }

        assertNotNull(preset)
        assertEquals("AI Subject Tracking", preset?.name)
        assertTrue(preset?.aiSubjectTracking == true)
        assertEquals(2, preset?.points?.size)
    }

    @Test
    fun antiShakeIsRemovedFromBuiltInPresets() {
        assertFalse(
            TopOffPanPreset.builtInPresets().any { it.id == "builtin_gyro_anti_shake" }
        )
    }

    @Test
    fun legacyAntiShakePresetsAreDroppedWhenPreferencesAreLoaded() {
        val saved = """[
            {"id":"builtin_gyro_anti_shake","name":"Gyro Anti-Shake","gyroMode":"ANTI_SHAKE","points":[{"panX":0,"panY":0,"zoomRatio":2},{"panX":0,"panY":0,"zoomRatio":2}]},
            {"id":"old-anti-shake-copy","name":"Old Anti-Shake","gyroMode":"ANTI_SHAKE","points":[{"panX":0,"panY":0,"zoomRatio":2},{"panX":0,"panY":0,"zoomRatio":2}]},
            {"id":"custom-pan","name":"My pan","gyroMode":null,"points":[{"panX":0,"panY":0,"zoomRatio":2},{"panX":0,"panY":0,"zoomRatio":2}]}
        ]"""

        val restored = TopOffPanPresetCodec.decode(saved)

        assertEquals(listOf("custom-pan"), restored.map { it.id })
    }
}
