package com.twonotaries.state

import com.twonotaries.contract.WalletContract
import net.corda.core.contracts.*
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import com.twonotaries.schema.WalletSchemaV1
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import java.time.Instant

@BelongsToContract(WalletContract::class)
data class WalletState(
        val owner: Party,
        val timeCreation: Instant,
        val timeUpdate: Instant,
        val amount: Double,
        val lastMovement: String,
        override val linearId: UniqueIdentifier = UniqueIdentifier()): LinearState, QueryableState {

    /** The public keys of the involved parties. */
    override val participants: List<AbstractParty> get() = listOf(owner)

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when (schema) {
            is WalletSchemaV1 -> WalletSchemaV1.PersistentWallet(
                    this.owner.name.toString(),
                    this.timeCreation,
                    this.timeUpdate,
                    this.amount,
                    this.linearId.id
            )
            else -> throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }

    override fun supportedSchemas(): Iterable<MappedSchema> = listOf(WalletSchemaV1)

}


