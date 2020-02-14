package pojo

import net.corda.core.serialization.CordaSerializable

@CordaSerializable
data class CreateIOUPojo(
        val iouValue : String = "",
        val partyName : String = ""
)