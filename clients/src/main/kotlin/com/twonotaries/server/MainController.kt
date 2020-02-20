package com.twonotaries.server

import com.twonotaries.flow.ExampleFlow.Initiator
import com.twonotaries.flow.WalletFlow.Issuer
//import com.twonotaries.flow.WalletFlow.Mover
import com.twonotaries.flow.WalletFlow.Updater
import com.twonotaries.schema.WalletSchemaV1
import com.twonotaries.state.IOUState
import com.twonotaries.state.WalletState
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.*
import net.corda.core.utilities.getOrThrow
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType.*
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pojo.*

val SERVICE_NAMES = listOf("Notary", "Network Map Service")

/**
 *  A Spring Boot Server API controller for interacting with the node via RPC.
 */

@RestController
@RequestMapping("/api/example2notaries/") // The paths for GET and POST requests are relative to this base path.
class MainController(rpc: NodeRPCConnection) {

    companion object {
        private val logger = LoggerFactory.getLogger(RestController::class.java)
    }

    private val myLegalName = rpc.proxy.nodeInfo().legalIdentities.first().name
    private val myOwningKey = rpc.proxy.nodeInfo().legalIdentities.first().owningKey
    private val proxy = rpc.proxy

    /**
     * Returns the node's name.
     */
    @GetMapping(value = [ "me" ], produces = [ APPLICATION_JSON_VALUE ])
    fun whoami() = mapOf("me" to myLegalName, "owningKey" to myOwningKey)

    /**
     * Returns all parties registered with the network map service. These names can be used to look up identities using
     * the identity service.
     */
    @GetMapping(value = [ "peers" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getPeers(): Map<String, List<Any>> {
        val nodeInfo = proxy.networkMapSnapshot()
        return mapOf("peers" to nodeInfo.map { it.legalIdentities.first().name }
                //filter out myself, notary and eventual network map started by driver
                .filter { it.organisation !in (SERVICE_NAMES + myLegalName.organisation) })
    }

    /**
     ******************************************* IOU API ************************************************
     */

    /**
     * Displays all IOU states that exist in the node's vault.
     */
    @GetMapping(value = [ "ious" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getIOUs() : ResponseEntity<List<StateAndRef<IOUState>>> {
        return ResponseEntity.ok(proxy.vaultQueryBy<IOUState>().states)
    }

    /**
     * Initiates a flow to agree an IOU between two parties.
     *
     * Once the flow finishes it will have written the IOU to ledger. Both the lender and the borrower will be able to
     * see it when calling /spring/api/ious on their respective nodes.
     *
     * This end-point takes a Party name parameter as part of the path. If the serving node can't find the other party
     * in its network map cache, it will return an HTTP bad request.
     *
     * The flow is invoked asynchronously. It returns a future when the flow's call() method returns.
     */

    @PostMapping(value = [ "create-iou" ], produces = [ APPLICATION_JSON_VALUE ], headers = [ "Content-Type=application/json" ])
    fun createIOU(
            @RequestBody
            createIOUPojo : CreateIOUPojo): ResponseEntity<ResponsePojo> {
        val iouValue = createIOUPojo.iouValue.toInt()
        val partyName = createIOUPojo.partyName

        if(partyName.isNullOrEmpty()){
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "partyName cannot be empty..", data = null))
        }
        if (iouValue <= 0 ) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "iouValue cannot be empty..", data = null))
        }
        val partyX500Name = CordaX500Name.parse(partyName)
        val otherParty = proxy.wellKnownPartyFromX500Name(partyX500Name) ?: return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "$partyName cannot be found..", data = null))

        return try {
            val signedTx = proxy.startTrackedFlow(::Initiator, iouValue, otherParty).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body(ResponsePojo(outcome = "SUCCESS", message = "Transaction id ${signedTx.linearId.id} committed to ledger.\n", data = signedTx))

        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERRROR", message =  ex.message!!, data = null))
        }
    }

    /**
     * Displays all IOU states that only this node has been involved in.
     */
    @GetMapping(value = [ "my-ious" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getMyIOUs(): ResponseEntity<List<StateAndRef<IOUState>>>  {
        val myious = proxy.vaultQueryBy<IOUState>().states.filter { it.state.data.lender.equals(proxy.nodeInfo().legalIdentities.first()) }
        return ResponseEntity.ok(myious)
    }

    /**
     ******************************************* WALLET API ************************************************
     */

    /**
     * Displays My WalletState.
     */
    @GetMapping(value = [ "getMyWallet" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getLastMeasures() : ResponseEntity<List<StateAndRef<WalletState>>> {
        return ResponseEntity.ok(proxy.vaultQueryBy<WalletState>(paging = PageSpecification(pageNumber = DEFAULT_PAGE_NUM, pageSize = DEFAULT_PAGE_SIZE))
                .states.filter { it.state.data.owner.equals(proxy.nodeInfo().legalIdentities.first()) })
    }

    /**
     * Initiates a flow to agree an Wallet.
     *
     * Once the flow finishes it will have written the Measure to ledger. Both NodeA, NodeB are able to
     * see it when calling /spring/example.com/api/ on their respective nodes.
     *
     * This end-point takes a Party name parameter as part of the path. If the serving node can't find the other party
     * in its network map cache, it will return an HTTP bad request.
     *
     * The flow is invoked asynchronously. It returns a future when the flow's call() method returns.
     */
    @PostMapping(value = [ "issue-wallet" ], produces = [ APPLICATION_JSON_VALUE ], headers = [ "Content-Type=application/json" ])
    fun issueWallet(
            @RequestBody
            issueWalletPojo : IssueWalletPojo): ResponseEntity<ResponsePojo> {
        val amount = issueWalletPojo.amount
        val lastMovement = issueWalletPojo.lastMovement

        if(amount.isNaN()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "amount must be a number", data = null))
        }

        if(lastMovement.isEmpty()){
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "lastMovement cannot be empty", data = null))
        }

        return try {
            val wallet = proxy.startTrackedFlow(::Issuer, amount, lastMovement).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body(ResponsePojo(outcome = "SUCCESS", message = "Transaction id ${wallet.linearId.id} committed to ledger.\n", data = wallet))
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = ex.message!!, data = null))
        }
    }

    /**
     * Displays History Wallet that exist in the node's vault.
     */
    @GetMapping(value = [ "getHistoryMyWallet" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getHistoryMyWallet() : ResponseEntity<List<StateAndRef<WalletState>>> {

        // setting the criteria for retrieve CONSUMED - UNCONSUMED state
        var historyCriteria : QueryCriteria = QueryCriteria.VaultCustomQueryCriteria(expression = builder { WalletSchemaV1.PersistentWallet::owner.equal(proxy.nodeInfo().legalIdentities.first().toString()) }, status = Vault.StateStatus.ALL, contractStateTypes = setOf(WalletState::class.java))

        val foundHistoryWallet = proxy.vaultQueryBy<WalletState>(
                criteria = historyCriteria,
                paging = PageSpecification(pageNumber = DEFAULT_PAGE_NUM, pageSize = 1000),
                sorting = Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states

        return ResponseEntity.ok(foundHistoryWallet)
    }

    /***
     *
     * Update Wallet
     *
     */
    @PostMapping(value = [ "update-wallet" ], consumes = [APPLICATION_JSON_VALUE], produces = [ APPLICATION_JSON_VALUE], headers = [ "Content-Type=application/json" ])
    fun updateWallet(
            @RequestBody
            updateWalletPojo: UpdateWalletPojo): ResponseEntity<ResponsePojo> {

        val walletLinearId = updateWalletPojo.walletLinearId
        val newAmount = updateWalletPojo.newAmount
        val lastMovement = updateWalletPojo.lastMovement

        if(walletLinearId.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "walletLinearId cannot be empty", data = null))
        }

        if(newAmount.isNaN()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "newAmount must be a number", data = null))
        }

        if(lastMovement.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "lastMovement cannot be empty", data = null))
        }

        return try {
            val updatedWallet = proxy.startTrackedFlow(::Updater, walletLinearId, newAmount, lastMovement).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body(ResponsePojo(outcome = "SUCCESS", message = "Wallet with id: $walletLinearId update correctly. New WalletState with id: ${updatedWallet.linearId.id}  created.. ledger updated.\n", data = updatedWallet))
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = ex.message!!, data = null))
        }
    }
}
