package com.brewpos.cafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrewBusinessProfileTest {
    @Test fun defaultsToCafeAndRejectsUnknownSettings() {
        assertEquals("Café", BrewBusinessProfile.normalize(null))
        assertEquals("Café", BrewBusinessProfile.normalize("unexpected"))
    }
    @Test fun suggestedCategoriesAreBusinessSpecific() {
        assertTrue(BrewBusinessProfile.suggestedCategories("Restaurant").contains("Main Course"))
        assertTrue(BrewBusinessProfile.suggestedCategories("Milk Tea").contains("Fruit Tea"))
        assertTrue(BrewBusinessProfile.suggestedCategories("Silogan").contains("Silog Meals"))
        assertTrue(BrewBusinessProfile.suggestedCategories("Café").contains("Coffee"))
    }

    @Test fun supportsRestaurantMilkTeaAndSilogan() {
        assertEquals("Restaurant", BrewBusinessProfile.normalize("restaurant"))
        assertEquals("Milk Tea", BrewBusinessProfile.normalize("  MILK TEA  "))
        assertEquals("Silogan", BrewBusinessProfile.normalize("silogan"))
        assertTrue(BrewBusinessProfile.supported.size == 4)
    }
}
