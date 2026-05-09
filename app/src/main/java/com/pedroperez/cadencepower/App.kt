package com.pedroperez.cadencepower

import android.app.Application
import com.pedroperez.cadencepower.ble.CadenceScanner
import com.pedroperez.cadencepower.ble.PowerAdvertiser
import com.pedroperez.cadencepower.model.PowerEstimator
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Singleton holders living for the whole process lifetime. The foreground
 * BridgeService drives the BLE work; the Activity/ViewModel only observe.
 * This way, sending the app to background does NOT tear down the GATT server.
 */
class App : Application() {

    val cadenceScanner: CadenceScanner by lazy { CadenceScanner(this) }
    val powerAdvertiser: PowerAdvertiser by lazy { PowerAdvertiser(this) }
    val estimator: PowerEstimator = PowerEstimator(metersPerCrankRev = 6.0)

    /** Current published power (W). */
    val power = MutableStateFlow(0)
    /** Current speed derived from cadence (km/h). */
    val speedKmh = MutableStateFlow(0.0)
    /** Subscribed Zwift / MyWhoosh clients. */
    val subscribers = MutableStateFlow(0)
    /** Virtual gear: meters per crank revolution. */
    val gearFactor = MutableStateFlow(6.0)
    /** Service running flag. */
    val running = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        powerAdvertiser.onClientCountChanged = { count -> subscribers.value = count }
    }

    fun setGear(meters: Double) {
        gearFactor.value = meters
        estimator.metersPerCrankRev = meters
    }
}
