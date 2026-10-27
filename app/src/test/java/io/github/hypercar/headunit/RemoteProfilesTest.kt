package io.github.hypercar.headunit

import org.junit.Assert.*
import org.junit.Test

class RemoteProfilesTest {
    @Test fun oldAudiCacheInheritsNewMfiPreference() {
        val parsed = RemoteProfiles.parseRaw("""{"_schema":2,"profiles":[{"id":"audi_mib3","label":"Audi"}]}""")
        val merged = RemoteProfiles.merge(HeadUnitProfiles.builtin, parsed.profiles, parsed.schema)
        assertEquals(20, merged.single { it.id == "audi_mib3" }.mfiPreferredChallengeBytes)
        assertEquals(32, merged.single { it.id == "bmw_id85" }.mfiPreferredChallengeBytes)
    }
    @Test fun invalidPreferenceDefaultsAndValidOverrideRoundTrips() {
        assertEquals(20, RemoteProfiles.parseRaw("""[{"id":"audi_mib3","mfiPreferredChallengeBytes":7}]""").profiles.single().mfiPreferredChallengeBytes)
        val profile = HeadUnitProfiles.AUDI_MIB3.copy(mfiPreferredChallengeBytes = 32)
        assertEquals(32, RemoteProfiles.parseRaw(RemoteProfiles.encode(listOf(profile))).profiles.single().mfiPreferredChallengeBytes)
    }
}
