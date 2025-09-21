package com.example.tapp.models

import kotlinx.serialization.Serializable

@Serializable
enum class Environment {
    PRODUCTION,
    SANDBOX;

    fun environmentName(): String {
        return name
    }
}
