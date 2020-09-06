package net.corda.samples.tictacthor.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.samples.tictacthor.contracts.BoardContract
import net.corda.samples.tictacthor.states.BoardState

/*
This flow ends a game by removing the BoardState from the ledger.
This flow is started through an request from the frontend once the GAME_OVER status is detected on the BoardState.
*/

@InitiatingFlow
@StartableByRPC
class EndGameFlow(
    private val gameId: UniqueIdentifier,
    private val whoAmI: String,
    private val whereTo: String
) : FlowLogic<SignedTransaction>() {

    companion object {
        object GENERATING_KEYS : ProgressTracker.Step("Generating Keys for transactions.")
        object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction for between accounts")
        object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
        object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
        object GATHERING_SIGS : ProgressTracker.Step("Gathering the counterparty's signature.") {
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
            FINALISING_TRANSACTION
        )
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {

        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(uuid = listOf(gameId.id))
        val boardStateRefToEnd = serviceHub.vaultService.queryBy<BoardState>(queryCriteria)
            .states.singleOrNull() ?: throw FlowException("GameState with id $gameId not found.")

        //Pass along Transaction
        progressTracker.currentStep = SIGNING_TRANSACTION
        val signedTx = verifyAndSign(transaction(boardStateRefToEnd))

        //Collect sigs
        progressTracker.currentStep = GATHERING_SIGS
        val signed = collectSignatures(boardStateRefToEnd.state.data, transaction = signedTx)
        progressTracker.currentStep = FINALISING_TRANSACTION

        return subFlow(FinalityFlow(signed))

    }

    @Suspendable
    private fun collectSignatures(initialBoardState: BoardState, transaction: SignedTransaction): SignedTransaction {
        val sessions = (initialBoardState.participants - ourIdentity).map { initiateFlow(it) }.toSet()
        return subFlow(CollectSignaturesFlow(transaction, sessions))
    }

    private fun transaction(boardStateRefToEnd: StateAndRef<BoardState>) = TransactionBuilder(notary()).apply {
        addInputState(boardStateRefToEnd)
        addCommand(Command(BoardContract.Commands.EndGame(), boardStateRefToEnd.state.data.participants.map { it.owningKey }))
    }

    private fun notary() = serviceHub.networkMapCache.notaryIdentities.first()

    private fun verifyAndSign(transaction: TransactionBuilder): SignedTransaction {
        transaction.verify(serviceHub)
        return serviceHub.signInitialTransaction(transaction)
    }
}

@InitiatedBy(EndGameFlow::class)
class EndGameFlowResponder(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val signedTransactionFlow = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(stx: SignedTransaction) {
                requireThat {
                    stx.tx.outputStates.isEmpty()
                }
            }
        }
        val txWeJustSigned = subFlow(signedTransactionFlow)
        return subFlow(ReceiveFinalityFlow(counterpartySession, txWeJustSigned.id))
    }
}
