package com.example.tapp

internal object SampleProviderActions {
    const val isAdjustEventAvailable: Boolean = true

    fun submitAdjustEvent(tapp: Tapp): Boolean {
        tapp.handleEvent("epoccj")
        return true
    }
}
