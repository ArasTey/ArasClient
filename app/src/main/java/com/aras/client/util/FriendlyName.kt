package com.aras.client.util

/**
 * A name the app picks for a profile the user did not name: two friendly words,
 * so a fresh profile never has to carry an address for a name. The pools are wide
 * (over two thousand pairings) so a page of profiles rarely shows the same one twice.
 */
object FriendlyName {
    private val first = listOf(
        "Amber", "Arctic", "Ashen", "Autumn", "Azure", "Breezy", "Bright", "Calm",
        "Clear", "Copper", "Coral", "Crimson", "Crystal", "Dawn", "Dusk", "Echo",
        "Ember", "Fabled", "Frost", "Gentle", "Gleaming", "Golden", "Indigo",
        "Ivory", "Jade", "Lively", "Lucky", "Lunar", "Mellow", "Midnight", "Misty",
        "Noble", "Northern", "Ocean", "Pale", "Pearl", "Quiet", "Rapid", "Royal",
        "Rustic", "Serene", "Shadow", "Silver", "Silent", "Solar", "Starry",
        "Storm", "Sunny", "Tender", "Twilight", "Umber", "Velvet", "Vesper",
        "Vivid", "Warm", "Wild", "Willow", "Winter", "Zephyr",
    )
    private val second = listOf(
        "Aspen", "Atlas", "Aurora", "Beacon", "Breeze", "Canyon", "Cedar", "Comet",
        "Drift", "Falcon", "Fjord", "Grove", "Harbor", "Haven", "Iris", "Jetty",
        "Lagoon", "Lark", "Lotus", "Lynx", "Marlin", "Meadow", "Meteor", "Nebula",
        "Onyx", "Orbit", "Otter", "Prairie", "Quill", "Raven", "Reef", "Robin",
        "Sequoia", "Sierra", "Solstice", "Sparrow", "Summit", "Thistle", "Tide",
        "Tundra", "Vista", "Wren", "Zenith",
    )

    fun pick(): String = "${first.random()} ${second.random()}"
}
