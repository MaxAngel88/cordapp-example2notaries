package pojo


import net.corda.core.serialization.CordaSerializable

@CordaSerializable
data class UpdateWalletPojo(
        val walletLinearId: String = "",
        val newAddAmount: Double = 0.0,
        val lastMovement: String = ""
)