package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaliKbaseProfilesTest {
    @Test
    fun g615ReleaseTag_isQualifiedAndDownloadable() {
        val p = MaliKbaseProfiles.fromReleaseTag("g615-v11-csf-v0.1.0-beta.3")
        assertNotNull(p)
        assertEquals("g615-v11-csf", p!!.id)
        assertTrue(p.glibcReleaseIntegrated)
    }

    @Test
    fun unknownOrResearchTag_isNotAutoQualified() {
        assertNull(MaliKbaseProfiles.fromReleaseTag("g720-v12-csf-v0.1.0-beta.2"))
        assertNull(MaliKbaseProfiles.fromReleaseTag("anything-else"))
    }

    @Test
    fun g720AndG52_areRecognizedButNeverAutoQualifiedAsReleases() {
        val g720 = MaliKbaseProbe.Result(
            usable = true, frontend = MaliKbaseProbe.Frontend.CSF,
            uapiMajor = 1, uapiMinor = 30, productId = 0xc870L,
            versionStatus = 0, majorRevision = 0, minorRevision = 0,
            shaderPresent = 0L, errno = 0, gpuId = 0xc8700010L,
        )
        val g52 = MaliKbaseProbe.Result(
            usable = true, frontend = MaliKbaseProbe.Frontend.JM,
            uapiMajor = 11, uapiMinor = 38, productId = 0x7402L,
            versionStatus = 0, majorRevision = 0, minorRevision = 0,
            shaderPresent = 0L, errno = 0, gpuId = 0x74021000L,
        )

        assertEquals("g720-v12-csf", MaliKbaseProfiles.forDevice(g720)?.id)
        assertEquals("g52-v7-jm", MaliKbaseProfiles.forDevice(g52)?.id)
        assertFalse(MaliKbaseProfiles.forDevice(g720)!!.glibcReleaseIntegrated)
        assertFalse(MaliKbaseProfiles.forDevice(g52)!!.glibcReleaseIntegrated)
        assertNull(MaliKbaseProfiles.fromReleaseTag("g720-v12-csf-v0.1.0-beta.2"))
        assertNull(MaliKbaseProfiles.fromReleaseTag("g52-v7-jm-v0.1.0-alpha.1"))
    }

    @Test
    fun profileMatchingRejectsWrongFrontendOrUapi() {
        val wrongFrontend = MaliKbaseProbe.Result(
            usable = true, frontend = MaliKbaseProbe.Frontend.JM,
            uapiMajor = 1, uapiMinor = 30, productId = 0xc870L,
            versionStatus = 0, majorRevision = 0, minorRevision = 0,
            shaderPresent = 0L, errno = 0,
        )
        val wrongUapi = MaliKbaseProbe.Result(
            usable = true, frontend = MaliKbaseProbe.Frontend.CSF,
            uapiMajor = 1, uapiMinor = 29, productId = 0xc870L,
            versionStatus = 0, majorRevision = 0, minorRevision = 0,
            shaderPresent = 0L, errno = 0,
        )
        assertNull(MaliKbaseProfiles.forDevice(wrongFrontend))
        assertNull(MaliKbaseProfiles.forDevice(wrongUapi))
    }

    @Test
    fun bionicAssets_areNeverOfferedAsGuestDrivers() {
        assertNull(MaliKbaseProfiles.releaseAssetLabel("driver-ADPKG.zip", "g615-v11-csf-v0.1.0-beta.3"))
        assertNotNull(MaliKbaseProfiles.releaseAssetLabel("PanVK-EMULATOR.zip", "g615-v11-csf-v0.1.0-beta.3"))
    }
}
