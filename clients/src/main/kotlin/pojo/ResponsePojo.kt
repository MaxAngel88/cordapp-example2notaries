package pojo

import net.corda.core.serialization.CordaSerializable

@CordaSerializable
data class ResponsePojo(
        val outcome : String = "",
        val message : String = "",
        val data : Any? = null
)