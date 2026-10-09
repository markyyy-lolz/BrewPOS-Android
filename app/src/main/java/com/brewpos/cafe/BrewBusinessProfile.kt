package com.brewpos.cafe

/**
 * UI-only workspace labels. Does not alter Supabase tenancy, licenses,
 * inventory ledgers or checkout math. Each shop selects its own category
 * names from the actual local product catalog.
 */
object BrewBusinessProfile {
    const val DEFAULT = "Café"
    val supported = listOf("Café", "Restaurant", "Milk Tea", "Silogan")

    fun normalize(value: String?): String =
        supported.firstOrNull { it.equals(value?.trim(), ignoreCase = true) } ?: DEFAULT

    fun suggestedCategories(value: String): List<String> = when (normalize(value)) {
        "Restaurant" -> listOf("Main Course", "Appetizers", "Sides", "Drinks", "Desserts")
        "Milk Tea" -> listOf("Milk Tea", "Fruit Tea", "Coffee", "Snacks", "Add-ons")
        "Silogan" -> listOf("Silog Meals", "Rice Meals", "Add-ons", "Drinks", "Sides")
        else -> listOf("Coffee", "Non-Coffee", "Tea", "Pastries", "Food")
    }
}
