package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test

class VideoPolicySelectionTest {
    @Test fun audiVivoUsesCompatibilityOnlyWithoutAnExplicitSelection() {
        assertEquals("level40_inline",VideoPolicySelection.resolve(null,"audi_mib3",CarLifeSource.VIVO))
        for(p in listOf("original","level40","inline_headers","level40_inline","hardware_transcode"))assertEquals(p,VideoPolicySelection.resolve(p,"audi_mib3",CarLifeSource.VIVO))
        assertEquals("original",VideoPolicySelection.resolve(null,"generic",CarLifeSource.VIVO))
        assertEquals("original",VideoPolicySelection.resolve(null,"audi_mib3",CarLifeSource.PUBLIC))
    }
}
