package com.suhas.globaledgeai.domain.engine

import com.suhas.globaledgeai.notifications.MultifyTrustPolicy
import org.junit.Assert.*
import org.junit.Test

class MultifyTrustPolicyTest {
    @Test fun researchCandidateRequiresMultifyNamespace(){
        assertTrue(MultifyTrustPolicy.isResearchCandidatePackage("com.example.multify.app"))
        assertFalse(MultifyTrustPolicy.isResearchCandidatePackage("com.example.other"))
    }

    @Test fun liveRequiresExactExplicitPackageMatch(){
        assertFalse(MultifyTrustPolicy.canUseForLive("","com.example.multify.app"))
        assertFalse(MultifyTrustPolicy.canUseForLive("com.example.multify.app","com.attacker.multify.app"))
        assertTrue(MultifyTrustPolicy.canUseForLive("com.example.multify.app","com.example.multify.app"))
    }
}
