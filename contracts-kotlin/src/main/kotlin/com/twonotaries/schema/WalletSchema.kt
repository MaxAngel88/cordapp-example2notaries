package com.twonotaries.schema

import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import java.time.Instant
import java.util.*
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table

/**
 * The family of schemas for WalletState.
 */
object WalletSchema

/**
 * An WalletState schema.
 */
object WalletSchemaV1 : MappedSchema(
        schemaFamily = WalletSchema.javaClass,
        version = 1,
        mappedTypes = listOf(PersistentWallet::class.java)) {

    @Entity
    @Table(name = "wallet_states")
    class PersistentWallet(
            @Column(name = "owner")
            var owner: String,

            @Column(name = "timeCreation")
            var timeCreation: Instant,

            @Column(name = "timeUpdate")
            var timeUpdate: Instant,

            @Column(name = "amount")
            var amount: Double,

            @Column(name = "linear_id")
            var linearId: UUID

    ) : PersistentState() {
        // Default constructor required by hibernate.
        constructor(): this(owner = "", timeCreation = Instant.now(), timeUpdate = Instant.now(), amount = 0.0, linearId = UUID.randomUUID())
    }
}