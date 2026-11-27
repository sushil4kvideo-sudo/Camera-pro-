package com.hinnka.mycamera.camera

import org.junit.Assert.assertEquals
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
}
