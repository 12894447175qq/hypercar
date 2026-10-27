package io.github.hypercar.core

import org.junit.Assert.*
import org.junit.Test

class ProjectLinksTest {
    @Test fun migratesDefaultButRetainsCustomMirror() {
        assertEquals(ProjectLinks.PROFILES, ProjectLinks.profilesUrl(""))
        assertEquals(ProjectLinks.PROFILES, ProjectLinks.profilesUrl("https://raw.githubusercontent.com/fw867/hypercar/main/profiles/headunits.json"))
        assertEquals("https://example.test/cars.json", ProjectLinks.profilesUrl("https://example.test/cars.json"))
    }
}
