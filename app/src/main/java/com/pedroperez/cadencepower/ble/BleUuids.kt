package com.pedroperez.cadencepower.ble

import java.util.UUID

object BleUuids {
    // Standard 16-bit UUID base
    private fun uuid16(short: String): UUID =
        UUID.fromString("0000$short-0000-1000-8000-00805F9B34FB")

    // Cycling Speed and Cadence (CSC) — sensor we read FROM (CAD 70)
    val CSC_SERVICE: UUID = uuid16("1816")
    val CSC_MEASUREMENT: UUID = uuid16("2A5B")
    val CSC_FEATURE: UUID = uuid16("2A5C")

    // Heart Rate — optional, for Garmin broadcast
    val HR_SERVICE: UUID = uuid16("180D")
    val HR_MEASUREMENT: UUID = uuid16("2A37")

    // Cycling Power — what we EMIT to Zwift
    val CYCLING_POWER_SERVICE: UUID = uuid16("1818")
    val CYCLING_POWER_MEASUREMENT: UUID = uuid16("2A63")
    val CYCLING_POWER_FEATURE: UUID = uuid16("2A65")
    val SENSOR_LOCATION: UUID = uuid16("2A5D")

    // Generic CCCD (for enabling notifications)
    val CCCD: UUID = uuid16("2902")
}
