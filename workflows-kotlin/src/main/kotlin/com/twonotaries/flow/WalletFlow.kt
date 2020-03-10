package com.twonotaries.flow

import co.paralleluniverse.fibers.Suspendable
import com.twonotaries.contract.WalletContract
import com.twonotaries.state.WalletState
import net.corda.core.contracts.Command
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
import net.corda.core.utilities.ProgressTracker.Step
import java.security.SecureRandom
import java.time.Instant
import java.util.*
import kotlin.math.abs

object WalletFlow {

    /**
     *
     * Issue Wallet Flow ------------------------------------------------------------------------------------
     *
     * */
    @InitiatingFlow
    @StartableByRPC
    class Issuer(val amount: Double,
                 val lastMovement: String) : FlowLogic<WalletState>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : Step("Generating transaction based on new Measure.")
            object VERIFYING_TRANSACTION : Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : Step("Signing transaction with our private key.")
            object GATHERING_SIGS : Step("Gathering the otherNode signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING_TRANSACTION : Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    GENERATING_TRANSACTION,
                    VERIFYING_TRANSACTION,
                    SIGNING_TRANSACTION,
                    GATHERING_SIGS,
                    FINALISING_TRANSACTION
            )
        }

        override val progressTracker = tracker()

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        override fun call(): WalletState {
            // Create random 32 bit - positive number with SecureRandom and make two-module to balance the use of notaries
            val randomNotaryIndex : Int = abs(SecureRandom().nextInt() % serviceHub.networkMapCache.notaryIdentities.size)
            // Obtain a reference to the notary we want to use.
            val notary = serviceHub.networkMapCache.notaryIdentities[randomNotaryIndex]

            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION

            val myLegalIdentity: Party = serviceHub.myInfo.legalIdentities.first()

            // Generate an unsigned transaction.
            val walletState = WalletState(
                    myLegalIdentity,
                    Instant.now(),
                    Instant.now(),
                    amount,
                    lastMovement,
                    UniqueIdentifier(id = UUID.randomUUID()))

            val txCommand = Command(WalletContract.Commands.Issue(), walletState.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                    .addOutputState(walletState, WalletContract.ID)
                    .addCommand(txCommand)

            // Stage 2.
            progressTracker.currentStep = VERIFYING_TRANSACTION
            // Verify that the transaction is valid.
            txBuilder.verify(serviceHub)

            // Stage 3.
            progressTracker.currentStep = SIGNING_TRANSACTION
            // Sign the transaction.
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 4.
            progressTracker.currentStep = GATHERING_SIGS
            // gathering_sigs in this case is useless..

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            subFlow(FinalityFlow(partSignedTx, emptyList(), FINALISING_TRANSACTION.childProgressTracker()))

            return walletState
        }
    }

    @InitiatedBy(Issuer::class)
    class Receiver(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val output = stx.tx.outputs.single().data
                    "This must be an wallet transaction." using (output is WalletState)
                    val wallet = output as WalletState
                    /* "other rule wallet" using (wallet is new rule) */
                    "amount cannot be negative" using (wallet.amount >= 0)
                    "lastMovement cannot be empty" using (wallet.lastMovement.isNotEmpty())
                }
            }
            val txId = subFlow(signTransactionFlow).id

            return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
        }
    }

    /***
     *
     * Update Wallet Flow -----------------------------------------------------------------------------------
     *
     * */
    @InitiatingFlow
    @StartableByRPC
    class Updater(val walletLinearId: String,
                  val newAddAmount: Double,
                  val lastMovement: String) : FlowLogic<WalletState>() {
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

            object FINALISING_TRANSACTION : Step("Obtaining notary signature and recording transaction.") {
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

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        override fun call(): WalletState {
            // Obtain a reference to the notary we want to use.
            //val notary = serviceHub.networkMapCache.notaryIdentities[0]
            val myLegalIdentity : Party = serviceHub.myInfo.legalIdentities.first()

            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION
            // Generate an unsigned transaction.
            var criteria: QueryCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            var customCriteria = QueryCriteria.LinearStateQueryCriteria(uuid = listOf(UUID.fromString(walletLinearId)))
            criteria = criteria.and(customCriteria)

            val oldWalletStateList = serviceHub.vaultService.queryBy<WalletState>(
                    criteria,
                    PageSpecification(1, MAX_PAGE_SIZE),
                    Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
            ).states

            if (oldWalletStateList.size > 1 || oldWalletStateList.isEmpty()) throw FlowException("No wallet state with UUID: " + UUID.fromString(walletLinearId) + " found.")

            val oldWalletStateRef = oldWalletStateList[0]
            val oldWalletState = oldWalletStateRef.state.data

            if (myLegalIdentity != oldWalletState.owner) throw FlowException("$myLegalIdentity it's different from ${oldWalletState.owner}. Only the owner can update his/her wallet.")

            val newWalletState = WalletState(
                    myLegalIdentity,
                    oldWalletState.timeCreation,
                    Instant.now(),
                    oldWalletState.amount + newAddAmount,
                    lastMovement,
                    oldWalletState.linearId
            )

            val txCommand = Command(WalletContract.Commands.Update(), newWalletState.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(oldWalletStateRef.state.notary)
                    .addInputState(oldWalletStateRef)
                    .addOutputState(newWalletState, WalletContract.ID)
                    .addCommand(txCommand)

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
            // gathering_sigs in this case is useless..

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            subFlow(FinalityFlow(partSignedTx, emptyList(), FINALISING_TRANSACTION.childProgressTracker()))

            return newWalletState
        }

        @InitiatedBy(Updater::class)
        class UpdateAcceptor(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
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
    }

}