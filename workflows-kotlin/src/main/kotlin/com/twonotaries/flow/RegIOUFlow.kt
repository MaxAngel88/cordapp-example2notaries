package com.twonotaries.flow

import co.paralleluniverse.fibers.Suspendable
import com.twonotaries.contract.IOUContract
import com.twonotaries.contract.WalletContract
import com.twonotaries.state.IOUState
import com.twonotaries.state.WalletState
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.time.Instant
import java.util.*

object RegIOUFlow {

    /**
     *
     * Issue RegIOU Flow ------------------------------------------------------------------------------------
     *
     * */

    @InitiatingFlow
    @StartableByRPC
    class RegIssuer(val iouLinearId: String,
                    val walletLinearId: String,
                    val causale: String) : FlowLogic<WalletState>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on transfert Message.")
            object VERIFYIGN_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
            object GATHERING_SIGS : ProgressTracker.Step("Gathering the destinatario's signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    GENERATING_TRANSACTION,
                    VERIFYIGN_TRANSACTION,
                    SIGNING_TRANSACTION,
                    GATHERING_SIGS,
                    FINALISING_TRANSACTION
            )
        }

        override val progressTracker = tracker()

        @Suspendable
        override fun call(): WalletState {
            // Obtain a reference to the notary we want to use.
            //val notary = serviceHub.networkMapCache.notaryIdentities[0]
            val myLegalIdentity : Party = serviceHub.myInfo.legalIdentities.first()

            // Stage 1.
            // Find first input state (IOUState)
            var customCriteriaIOU = QueryCriteria.LinearStateQueryCriteria(uuid = listOf(UUID.fromString(iouLinearId)), status = Vault.StateStatus.UNCONSUMED)

            val oldIOUStateList = serviceHub.vaultService.queryBy<IOUState>(
                    customCriteriaIOU,
                    PageSpecification(1, MAX_PAGE_SIZE),
                    Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
            ).states

            if (oldIOUStateList.size > 1 || oldIOUStateList.isEmpty()) throw FlowException("No IOU state with UUID: " + UUID.fromString(iouLinearId) + " found.")

            val oldIOUStateRef = oldIOUStateList[0]
            val oldIOUState = oldIOUStateRef.state.data

            if (myLegalIdentity != oldIOUState.lender) throw FlowException("$myLegalIdentity it's different from ${oldIOUState.lender}. Only the lender can reg. his/her IOU.")

            // Find second input state (WalletState)
            var customCriteriaWallet = QueryCriteria.LinearStateQueryCriteria(uuid = listOf(UUID.fromString(walletLinearId)), status = Vault.StateStatus.UNCONSUMED)

            val oldWalletStateList = serviceHub.vaultService.queryBy<WalletState>(
                    customCriteriaWallet,
                    PageSpecification(1, MAX_PAGE_SIZE),
                    Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
            ).states

            if (oldWalletStateList.size > 1 || oldWalletStateList.isEmpty()) throw FlowException("No Wallet state with UUID: " + UUID.fromString(walletLinearId) + " found.")

            val oldWalletStateRef = oldWalletStateList[0]
            val oldWalletState = oldWalletStateRef.state.data

            if (myLegalIdentity != oldWalletState.owner) throw FlowException("$myLegalIdentity it's different from ${oldWalletState.owner}. Only owner can update his/her wallet")

            // Generate an unsigned transaction.
            val newWalletState = WalletState(
                    myLegalIdentity,
                    oldWalletState.timeCreation,
                    Instant.now(),
                    oldWalletState.amount - oldIOUState.value,
                    "update wallet. Causale: $causale",
                    UniqueIdentifier(id = UUID.randomUUID())
            )

            // check notary on oldIOUState to match with the notary of oldWalletState
            val checkedIouState =  notaryChangeIOU(iouStateRef = oldIOUStateRef, selectedNotary = oldWalletStateRef.state.notary)

            val txCommandDeleteIOU = Command(IOUContract.Commands.Delete(), oldIOUState.participants.map { it.owningKey })
            val txCommandUpdateReg = Command(WalletContract.Commands.UpdateReg(), newWalletState.participants.map { it.owningKey })

            val txBuilder = TransactionBuilder(oldWalletStateRef.state.notary)
                    .addInputState(checkedIouState)
                    .addInputState(oldWalletStateRef)
                    .addOutputState(newWalletState, WalletContract.ID)
                    .addCommand(txCommandDeleteIOU)
                    .addCommand(txCommandUpdateReg)

            // Stage 2.
            progressTracker.currentStep = VERIFYIGN_TRANSACTION
            // Verify that the transaction is valid.
            txBuilder.verify(serviceHub)

            // Stage 3.
            progressTracker.currentStep = SIGNING_TRANSACTION
            // Sign the transaction.
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 4.
            progressTracker.currentStep = GATHERING_SIGS
            val otherPartySession = initiateFlow(oldIOUState.borrower)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(otherPartySession), GATHERING_SIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            subFlow(FinalityFlow(fullySignedTx, setOf(otherPartySession), FINALISING_TRANSACTION.childProgressTracker()))

            return newWalletState

        }

        @InitiatedBy(RegIssuer::class)
        class RegIssuerAcceptor(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
            @Suspendable
            override fun call(): SignedTransaction {
                val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
                    override fun checkTransaction(stx: SignedTransaction) = requireThat {
                        val output = stx.tx.outputs.single().data
                        "This must be an wallet transaction." using (output is WalletState)
                        val wallet = output as WalletState
                        /* "other rule wallet" using (output is new rule) */
                        "amount cannot be negative" using (wallet.amount >= 0)
                        "lastMovement cannot be empty" using (wallet.lastMovement.isNotEmpty())
                    }
                }
                val txId = subFlow(signTransactionFlow).id

                return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
            }
        }

        @Suspendable
        private fun notaryChangeIOU(
                iouStateRef: StateAndRef<IOUState>,
                selectedNotary: Party
        ): StateAndRef<IOUState> = if (iouStateRef.state.notary != selectedNotary) { subFlow(NotaryChangeFlow(iouStateRef, selectedNotary)) } else { iouStateRef }
    }
}