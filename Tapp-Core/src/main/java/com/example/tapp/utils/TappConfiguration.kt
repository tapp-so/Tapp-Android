package com.example.tapp.utils

import com.example.tapp.models.Affiliate
import com.example.tapp.models.Environment
import kotlinx.serialization.Serializable

@Serializable
data class TappConfiguration(
    val authToken: String,
    val env: Environment,
    val tappToken: String,
    val affiliate: Affiliate
)
