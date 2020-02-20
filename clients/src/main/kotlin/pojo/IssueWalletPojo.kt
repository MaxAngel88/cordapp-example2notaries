package pojo

import net.corda.core.serialization.CordaSerializable

@CordaSerializable
data class IssueWalletPojo(
        val amount : Double = 0.0,
        val lastMovement : String = ""
)