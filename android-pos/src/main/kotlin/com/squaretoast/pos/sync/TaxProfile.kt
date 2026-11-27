package com.squaretoast.pos.sync

import kotlinx.serialization.Serializable

/**
 * Slice 14 (S14-3): the location's tax setup, from `GET /api/v1/locations/{id}/tax-profile`. Rules are those in
 * force at some point in [validFrom, validUntil) (local dates); all are in [stateCode].
 */
@Serializable
data class TaxProfile(
    val locationId: String,
    val stateCode: String,
    val currency: String,
    val timezone: String,
    val ca8080Applies: Boolean,
    val caSeparateAccountingElected: Boolean,
    val mandatoryServiceChargeTaxable: Boolean,
    val validFrom: String,
    val validUntil: String,
    val rules: List<TaxRuleDto>,
    val items: List<ItemTax>,
)

@Serializable
data class TaxRuleDto(
    val jurisdictionCode: String,
    val taxCategory: String,
    val channel: String?,
    val ratePpm: Int,
    val effectiveFrom: String,
    val effectiveTo: String?,
)

@Serializable
data class ItemTax(val menuItemId: String, val taxCategory: String, val temp: String)
