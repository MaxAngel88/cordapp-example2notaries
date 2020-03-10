package com.twonotaries.contract

import com.twonotaries.state.IOUState
import com.twonotaries.state.WalletState
import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction
import java.security.PublicKey

/**
 * A implementation of a basic smart contract in Corda.
 *
 * This contract enforces rules regarding the creation of a valid [IOUState], which in turn encapsulates an [IOUState].
 *
 * For a new [IOUState] to be issued onto the ledger, a transaction is required which takes:
 * - Zero input states.
 * - One output state: the new [IOUState].
 * - An Create() command with the public keys of both the lender and the borrower.
 *
 * All contracts must sub-class the [Contract] interface.
 */
class IOUContract : Contract {
    companion object {
        @JvmStatic
        val ID = "com.twonotaries.contract.IOUContract"
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
                is Commands.Delete -> verifyDelete(tx, setOfSigners)
                else -> throw IllegalArgumentException("Unrecognised command.")
            }
        }
    }

    private fun verifyIssue(tx: LedgerTransaction, signers: Set<PublicKey>) {
        tx.commands.requireSingleCommand<Commands.Issue>()
        requireThat {
            // Generic constraints around the IOU transaction.
            "No inputs should be consumed when issuing an IOU." using (tx.inputs.isEmpty())
            "Only one output state should be created." using (tx.outputs.size == 1)
            val out = tx.outputsOfType<IOUState>().single()
            "The lender and the borrower cannot be the same entity." using (out.lender != out.borrower)
            "All of the participants must be signers." using (signers.containsAll(out.participants.map { it.owningKey }))

            // IOU-specific constraints.
            "The IOU's value must be non-negative." using (out.value > 0)
        }
    }

    private fun verifyDelete(tx: LedgerTransaction, signers: Set<PublicKey>) {
        tx.commands.requireSingleCommand<Commands.Delete>()
        requireThat {
            // Generic constraints around the old input IOU.
            "there must be only one two input (IOU and Wallet)." using (tx.inputs.size == 2)
            val oldIOUState = tx.inputsOfType<IOUState>().single()
            val oldWalletState = tx.inputsOfType<WalletState>().single()

            "Only one transaction state should be created." using (tx.outputs.size == 1)
            val newWalletState = tx.outputsOfType<WalletState>().single()

            // Generic constraints around the generic delete transaction.
            //"IOU transaction state should't be created." using (tx.outputs.isEmpty())

            // Constraint around the IOU Registration.
            "IOU lender and Wallet owner must be the same" using (oldIOUState.lender == oldWalletState.owner)
            "iou amount cannot be grater than wallet amount." using (oldIOUState.value <= oldWalletState.amount)
            "old wallet owner must be the same of new wallet owner." using (oldWalletState.owner == newWalletState.owner)

            "All of the participants must be signers." using (signers.containsAll(oldIOUState.participants.map { it.owningKey }))




        }
    }

    /**
     * This contract only implements two commands: Issue and Delete.
     */
    interface Commands : CommandData {
        class Issue : Commands, TypeOnlyCommandData()
        class Delete : Commands, TypeOnlyCommandData()
    }
}
