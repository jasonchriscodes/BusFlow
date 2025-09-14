package com.jason.publisher.main.model

/**
 * Data class representing attributes data, including latitude, longitude, bearing, speed, and direction.
 *
 * @property latitude The latitude coordinate.
 * @property longitude The longitude coordinate.
 * @property bearing The bearing angle.
 * @property bearingCustomer The customer-specific bearing angle.
 * @property speed The speed value.
 * @property direction The direction value.
 */
data class AttributesData(
    val latitude: Double,
    val longitude: Double,
    val bearing: Float?,
    val bearingCustomer: Float?,
    val speed: Float?,
    val direction: String?,
    val scheduleData: String? = null,
    val currentTripLabel: String? = null
)
