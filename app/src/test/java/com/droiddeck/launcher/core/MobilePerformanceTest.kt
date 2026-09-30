package com.droiddeck.launcher.core

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobilePerformanceTest {
    @Test fun fourGigPhoneKeeps720pAndUsesSaver() {
        val p = MobilePerformance.automaticForSignals(4L * 1024 * 1024 * 1024, false, PowerManager.THERMAL_STATUS_NONE)
        assertEquals(MobilePerformance.SAVER, p.id)
        assertEquals(720, p.maxHeight)
        assertEquals(30, p.targetFps)
        assertTrue(p.lowMemory)
    }

    @Test fun sixGigClassPhoneUsesBalanced45() {
        val p = MobilePerformance.automaticForSignals(6L * 1024 * 1024 * 1024, false, PowerManager.THERMAL_STATUS_NONE)
        assertEquals(MobilePerformance.BALANCED, p.id)
        assertEquals(720, p.maxHeight)
        assertEquals(45, p.targetFps)
        assertFalse(p.lowMemory)
    }

    @Test fun roomyCoolPhoneUses720p60ButNot900pAutomatically() {
        val p = MobilePerformance.automaticForSignals(8L * 1024 * 1024 * 1024, false, PowerManager.THERMAL_STATUS_NONE)
        assertEquals(MobilePerformance.PERFORMANCE, p.id)
        assertEquals(720, p.maxHeight)
        assertEquals(60, p.targetFps)
        assertFalse(p.lowMemory)
    }

    @Test fun moderateHeatBacksRoomyPhoneDownTo45() {
        val p = MobilePerformance.automaticForSignals(12L * 1024 * 1024 * 1024, false, PowerManager.THERMAL_STATUS_MODERATE)
        assertEquals(MobilePerformance.BALANCED, p.id)
        assertEquals(45, p.targetFps)
    }

    @Test fun severeHeatDropsToSaverWithoutDroppingBelow720p() {
        val p = MobilePerformance.automaticForSignals(12L * 1024 * 1024 * 1024, false, PowerManager.THERMAL_STATUS_SEVERE)
        assertEquals(MobilePerformance.SAVER, p.id)
        assertEquals(720, p.maxHeight)
    }

    @Test fun threeGigDeviceIsOutsideSupportedMinimum() {
        assertNotNull(MobilePerformance.minimumRamIssue(3_000_000_000L))
    }

    @Test fun nominalFourGigVisibleRamPassesMinimum() {
        assertNull(MobilePerformance.minimumRamIssue(3_600_000_000L))
    }
}
