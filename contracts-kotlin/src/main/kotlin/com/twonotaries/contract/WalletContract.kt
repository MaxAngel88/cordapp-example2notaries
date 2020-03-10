package com.twonotaries.contract

import com.twonotaries.state.IOUState
import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction
import java.security.PublicKey
import com.twonotaries.state.WalletState

class WalletContract : Contract {
    companion object {
        @JvmStatic
        val ID = "com.twonotaries.contract.WalletContract"
    }

    /**
     * The verify() function of all the states' contracts must not throw an exception for a transaction to be
     * considered valid.
     */
    override fun verify(tx: LedgerTransaction) {
        val commands = tx.commandsOfType<Commands>()
        for(command in commands){
            val setOfSigners = command.signers.toSet()
            when(command.value){
                is Commands.Issue -> verifyIssue(tx, setOfSigners)
                is Commands.Update -> verifyUpdate(tx, setOfSigners)
                is Commands.UpdateReg -> verifyUpdateReg(tx, setOfSigners)
                else -> throw IllegalArgumentException("Unrecognised command.")
            }
        }
    }

    private fun verifyIssue(tx: LedgerTransaction, signers: Set<PublicKey>) {
        tx.commands.requireSingleCommand<Commands.Issue>()
        requireThat {
            // Generic constraints around the generic create transaction.
            "No inputs should be consumed" using (tx.inputs.isEmpty())
            "Only one output state should be created." using (tx.outputs.size == 1)
            val wallet = tx.outputsOfType<WalletState>().single()
            "All of the participants must be signers." using (signers.containsAll(wallet.participants.map { it.owningKey }))

            // Wallet-specific constraints.
            "amount cannot be negative" using (wallet.amount >= 0)
            "lastMovement cannot be empty" using (wallet.lastMovement.isNotEmpty())
        }
    }

    private fun verifyUpdate(tx: LedgerTransaction, signers: Set<PublicKey>){
        tx.commands.requireSingleCommand<Commands.Update>()
        requireThat {
            // Generic constraints around the old Wallet transaction.
            "there must be only one wallet input." using (tx.inputs.size == 1)
            val oldWalletState = tx.inputsOfType<WalletState>().single()

            // Generic constraints around the generic update transaction.
            "Only one transaction state should be created." using (tx.outputs.size == 1)
            val newWalletState = tx.outputsOfType<WalletState>().single()
            "All of the participants must be signers." using (signers.containsAll(newWalletState.participants.map { it.owningKey }))

            // Generic constraints around the new Wallet transaction
            "old owner must be the same of the new owner" using (oldWalletState.owner == newWalletState.owner)
            "old amount cannot be the same of the new amount" using (oldWalletState.amount != newWalletState.amount)
            "new amount cannot be negative" using (newWalletState.amount >= 0 )
            "lastMovement cannot be empty" using (newWalletState.lastMovement.isNotEmpty())
        }
    }

    private fun verifyUpdateReg(tx: LedgerTransaction, signers: Set<PublicKey>){
        tx.commands.requireSingleCommand<Commands.UpdateReg>()
        requireThat {
            // Generic constraints around the old Measure transaction.
            "there must be only two input." using (tx.inputs.size == 2)
            val oldIOUState = tx.inputsOfType<IOUState>().single()
            val oldWalletState = tx.inputsOfType<WalletState>().single()

            // Generic constraints around the generic update transaction.
            "Only one transaction state should be created." using (tx.outputs.size == 1)
            val newWalletState = tx.outputsOfType<WalletState>().single()
            "All of the participants must be signers." using (signers.containsAll(newWalletState.participants.map { it.owningKey }))

            // Generic Constraint around the input state IOU
            "iou lender and wallet owner must be the same." using (oldIOUState.lender == oldWalletState.owner)
            "iou amount cannot be grater than wallet amount." using (oldIOUState.value <= oldWalletState.amount)

            // Generic constraints around the new Wallet transaction
            "old owner must be the same of the new owner" using (oldWalletState.owner == newWalletState.owner)
            "old amount cannot be the same of the new amount" using (oldWalletState.amount != newWalletState.amount)
            "new amount cannot be negative" using (newWalletState.amount >= 0 )
            "lastMovement cannot be empty" using (newWalletState.lastMovement.isNotEmpty())
        }
    }

    /**
     * This contract only implements two commands: Issue and Update.
     */
    interface Commands : CommandData {
        class Issue : Commands, TypeOnlyCommandData()
        class Update: Commands, TypeOnlyCommandData()
        class UpdateReg: Commands, TypeOnlyCommandData()
    }
}