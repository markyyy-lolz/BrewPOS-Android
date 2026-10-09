package com.brewpos.cafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrewBusinessProfileTest {
    @Test fun defaultsToCafeAndRejectsUnknownSettings() {
        assertEquals("Café", BrewBusinessProfile.normalize(null))
        assertEquals("Café", BrewBusinessProfile.normalize("unexpected"))
    }
    @Test fun supportsRestaurantMilkTeaAndSilogan() {
        assertEquals("Restaurant", BrewBusinessProfile.normalize("restaurant"))
        assertEquals("Milk Tea", BrewBusinessProfile.normalize("  MILK TEA  "))
        assertEquals("Silogan", BrewBusinessProfile.normalize("silogan"))
        assertTrue(BrewBusinessProfile.supported.size == 4)
    }
}
