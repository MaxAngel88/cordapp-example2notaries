package pojo

import net.corda.core.serialization.CordaSerializable

@CordaSerializable
data class IssueRegIOU(
        val iouLinearId: String = "",
        val walletLinearId: String = "",
        val causale: String = ""
)