package net.corda.samples.tictacthor.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.accounts.workflows.accountService
import com.r3.corda.lib.accounts.workflows.flows.RequestKeyForAccount
import net.corda.core.contracts.Command
import net.corda.core.contracts.Requirements.using
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.samples.tictacthor.accountsUtilities.NewKeyForAccount
import net.corda.samples.tictacthor.contracts.BoardContract
import net.corda.samples.tictacthor.states.BoardState


/*
This flow starts a game with another node by creating an new BoardState.
The responding node cannot decline the request to start a game.
The request is only denied if the responding node is already participating in a game.
*/

@InitiatingFlow
@StartableByRPC
class StartGameFlow(
    private val whoAmI: String,
    private val whereTo: String
) : FlowLogic<UniqueIdentifier>() {

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
    override fun call(): UniqueIdentifier {

        //Generate key for transaction
        progressTracker.currentStep = GENERATING_KEYS
        val myAccount = accountService.accountInfo(whoAmI).single().state.data
        val otherAccount = accountService.accountInfo(whereTo).single().state.data

        val mykey = subFlow(NewKeyForAccount(myAccount.identifier.id))
        val targetKey = subFlow(RequestKeyForAccount(otherAccount))


        // If this node is already participating in an active game, decline the request to start a new one
        val criteria = QueryCriteria.VaultQueryCriteria(
            externalIds = listOf(myAccount.identifier.id)
        )
        val results = serviceHub.vaultService.queryBy(
            contractStateType = BoardState::class.java,
            criteria = criteria
        ).states

        requireThat { results.isEmpty() }

        progressTracker.currentStep = GENERATING_TRANSACTION
        val initialBoardState = BoardState(
            isPlayerXTurn = true,
            playerX = UniqueIdentifier(mykey.toString()),
            playerO = UniqueIdentifier(targetKey.toString()),
            me = myAccount.host,
            competitor = otherAccount.host
        )

        //Pass along Transaction
        progressTracker.currentStep = SIGNING_TRANSACTION
        val signedTx = verifyAndSign(transaction(initialBoardState))

        //Collect sigs
        progressTracker.currentStep = GATHERING_SIGS
        val signed = collectSignatures(initialBoardState, transaction = signedTx)
        progressTracker.currentStep = FINALISING_TRANSACTION
        println("Signed: $signed")
        return initialBoardState.linearId
    }

    @Suspendable
    private fun collectSignatures(initialBoardState: BoardState, transaction: SignedTransaction): SignedTransaction {
        val sessions = (initialBoardState.participants - ourIdentity).map { initiateFlow(it) }.toSet()
        return subFlow(FinalityFlow(subFlow(CollectSignaturesFlow(transaction, sessions)), sessions))
    }

    private fun transaction(initialBoardState: BoardState) = TransactionBuilder(notary()).apply {
        addOutputState(initialBoardState, BoardContract.ID)
        addCommand(Command(BoardContract.Commands.StartGame(), initialBoardState.participants.map { it.owningKey }))
    }

    private fun notary() = serviceHub.networkMapCache.notaryIdentities.first()

    private fun verifyAndSign(transaction: TransactionBuilder): SignedTransaction {
        transaction.verify(serviceHub)
        return serviceHub.signInitialTransaction(transaction)
    }
}


@InitiatedBy(StartGameFlow::class)
class StartGameFlowResponder(val counterpartySession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(object : SignTransactionFlow(counterpartySession) {
            @Throws(FlowException::class)
            override fun checkTransaction(stx: SignedTransaction) {
                val output = stx.tx.outputs.single().data
                "This must be a TicTacToe transaction." using (output is BoardState)
            }
        })
        subFlow(ReceiveFinalityFlow(counterpartySession))
    }
}
