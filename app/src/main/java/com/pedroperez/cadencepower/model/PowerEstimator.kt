package com.pedroperez.cadencepower.model

import kotlin.math.pow

/**
 * Estimates cycling power from cadence using a virtual gear and a generic
 * trainer power curve (Kurt Kinetic Road Machine), the same approach Zwift
 * uses for its "zPower" mode when no real power meter is available.
 *
 * Pipeline:  cadence (rpm) --gearFactor--> wheel speed (m/s) --curve--> power (W)
 *
 * P(W) = 5.244820 * v + 0.019168 * v^3   with v in m/s
 */
class PowerEstimator(
    /** Meters travelled per crank revolution. Larger = harder gear. */
    @Volatile var metersPerCrankRev: Double = 6.0
) {
    fun powerFromCadence(cadenceRpm: Double): Int {
        if (cadenceRpm <= 0.0) return 0
        val metersPerSecond = (cadenceRpm / 60.0) * metersPerCrankRev
        val watts = 5.244820 * metersPerSecond + 0.019168 * metersPerSecond.pow(3.0)
        return watts.toInt().coerceIn(0, 2000)
    }

    fun speedKmhFromCadence(cadenceRpm: Double): Double {
        val mps = (cadenceRpm / 60.0) * metersPerCrankRev
        return mps * 3.6
    }
}
