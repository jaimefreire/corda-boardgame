package net.corda.samples.tictacthor.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.accounts.workflows.accountService
import com.r3.corda.lib.accounts.workflows.flows.RequestKeyForAccount
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.samples.tictacthor.contracts.BoardContract
import net.corda.samples.tictacthor.states.BoardState

/*
This flow attempts submit a turn in the game.
It must be the initiating node's turn otherwise this will result in a FlowException.
*/

@InitiatingFlow
@StartableByRPC
class SubmitTurnFlow(private val gameId: UniqueIdentifier,
                     private val whoAmI: String,
                     private val whereTo:String,
                     private val x: Int,
                     private val y: Int) : FlowLogic<String>() {

    companion object {
        object GENERATING_KEYS : ProgressTracker.Step("Generating Keys for transactions.")
        object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction for between accounts")
        object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
        object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
        object GATHERING_SIGS : ProgressTracker.Step("Gathering the counterparty's signature.")
        object GATHERING_SIGS_FINISH : ProgressTracker.Step("Finish the counterparty's signature."){
            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
        }

        object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(
                GENERATING_KEYS,
                GENERATING_TRANSACTION,
                VERIFYING_TRANSACTION,
                SIGNING_TRANSACTION,
                GATHERING_SIGS,
                FINALISING_TRANSACTION,
                GATHERING_SIGS_FINISH
        )
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call(): String {

        //loading game board
        val myAccount = accountService.accountInfo(whoAmI).single().state.data

        val targetAccount = accountService.accountInfo(whereTo).single().state.data
        val targetAcctAnonymousParty = subFlow(RequestKeyForAccount(targetAccount))

        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(
            null,
            listOf(gameId),
            Vault.StateStatus.UNCONSUMED, null
        )

        val inputBoardStateAndRef = serviceHub.vaultService.queryBy<BoardState>(queryCriteria)
            .states.singleOrNull() ?: throw FlowException("GameState with id $gameId not found.")
        val inputBoardState = inputBoardStateAndRef.state.data

        // Check that the correct party executed this flow
        if (inputBoardState.getCurrentPlayerParty() != myAccount.host) throw FlowException("It's not your turn!")

        progressTracker.currentStep = GENERATING_TRANSACTION
        val outputBoardState = inputBoardState.returnNewBoardAfterMove(Pair(x, y))

        //Pass along Transaction
        progressTracker.currentStep = SIGNING_TRANSACTION
        val signedTx = verifyAndSign(transaction(inputBoardStateAndRef, outputBoardState))

        //Collect sigs
        progressTracker.currentStep = GATHERING_SIGS
        val signed = collectSignatures(outputBoardState, transaction = signedTx)
        progressTracker.currentStep = FINALISING_TRANSACTION
        val stx = subFlow(FinalityFlow(signed))
        subFlow(SyncGame(outputBoardState.linearId.toString(), targetAccount.host))
        return "rxId: ${stx.id}"
    }

    @Suspendable
    private fun collectSignatures(initialBoardState: BoardState, transaction: SignedTransaction): SignedTransaction {
        val sessions = (initialBoardState.participants - ourIdentity).map { initiateFlow(it) }.toSet()
        return subFlow(CollectSignaturesFlow(transaction, sessions))
    }

    private fun transaction(inputBoardStateAndRef: StateAndRef<BoardState>, outputState: BoardState) =
        TransactionBuilder(notary()).apply {
            addInputState(inputBoardStateAndRef)
            addOutputState(outputState, BoardContract.ID)
            addCommand(
                Command(
                    BoardContract.Commands.SubmitTurn(),
                    inputBoardStateAndRef.state.data.participants.map { it.owningKey })
            )
        }

    private fun notary() = serviceHub.networkMapCache.notaryIdentities.first()

    private fun verifyAndSign(transaction: TransactionBuilder): SignedTransaction {
        transaction.verify(serviceHub)
        return serviceHub.signInitialTransaction(transaction)
    }
}

@InitiatedBy(SubmitTurnFlow::class)
class SubmitTurnFlowResponder(val counterpartySession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(object : SignTransactionFlow(counterpartySession) {
            @Throws(FlowException::class)
            override fun checkTransaction(stx: SignedTransaction) {
                // Custom Logic to validate transaction.
            }
        })
        subFlow(ReceiveFinalityFlow(counterpartySession))
    }
}
