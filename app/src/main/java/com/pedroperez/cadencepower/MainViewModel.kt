package com.pedroperez.cadencepower

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pedroperez.cadencepower.ble.CadenceScanner
import com.pedroperez.cadencepower.ble.PowerAdvertiser
import com.pedroperez.cadencepower.model.PowerEstimator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val cadenceScanner = CadenceScanner(app)
    val powerAdvertiser = PowerAdvertiser(app)
    val estimator = PowerEstimator(metersPerCrankRev = 6.0)

    private val _power = MutableStateFlow(0)
    val power: StateFlow<Int> = _power

    private val _speedKmh = MutableStateFlow(0.0)
    val speedKmh: StateFlow<Double> = _speedKmh

    private val _subscribers = MutableStateFlow(0)
    val subscribers: StateFlow<Int> = _subscribers

    private val _gearFactor = MutableStateFlow(6.0) // meters per crank rev
    val gearFactor: StateFlow<Double> = _gearFactor

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private var publishJob: Job? = null

    init {
        powerAdvertiser.onClientCountChanged = { count -> _subscribers.value = count }
    }

    fun setGear(meters: Double) {
        _gearFactor.value = meters
        estimator.metersPerCrankRev = meters
    }

    fun start() {
        if (_running.value) return
        _running.value = true
        cadenceScanner.start()
        powerAdvertiser.start()

        publishJob = viewModelScope.launch {
            while (_running.value) {
                // Watchdog: if we haven't seen a crank revolution in >2s, force 0.
                val now = System.currentTimeMillis()
                val lastActivity = cadenceScanner.lastCrankActivityMillis
                if (lastActivity == 0L || now - lastActivity > 2000) {
                    cadenceScanner.forceZeroCadence()
                }

                val rpm = cadenceScanner.cadenceRpm.value
                val watts = estimator.powerFromCadence(rpm)
                _power.value = watts
                _speedKmh.value = estimator.speedKmhFromCadence(rpm)
                powerAdvertiser.publishPower(watts)
                delay(1000)
            }
        }
    }

    fun stop() {
        _running.value = false
        publishJob?.cancel()
        cadenceScanner.stop()
        powerAdvertiser.stop()
        _power.value = 0
        _speedKmh.value = 0.0
    }

    override fun onCleared() {
        stop()
    }
}
