package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Test

class MaliKbaseProbeTest {
    @Test
    fun canonicalProductId_acceptsKbaseProductCode() {
        assertEquals(0xb8a3L, MaliKbaseProbe.canonicalProductId(0xb8a3L))
    }

    @Test
    fun canonicalProductId_stripsRevisionSuffixFromFullGpuId() {
        assertEquals(0xb8a3L, MaliKbaseProbe.canonicalProductId(0xb8a31030L))
        assertEquals(0xc870L, MaliKbaseProbe.canonicalProductId(0xc8700010L))
        assertEquals(0x7402L, MaliKbaseProbe.canonicalProductId(0x74021000L))
    }

    @Test
    fun canonicalProductId_preservesUnknownZero() {
        assertEquals(0L, MaliKbaseProbe.canonicalProductId(0L))
    }
}
