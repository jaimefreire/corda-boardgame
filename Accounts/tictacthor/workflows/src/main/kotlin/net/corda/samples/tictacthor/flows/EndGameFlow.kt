package net.corda.samples.tictacthor.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.accounts.workflows.accountService
import com.r3.corda.lib.accounts.workflows.flows.RequestKeyForAccount
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.samples.tictacthor.accountsUtilities.NewKeyForAccount
import net.corda.samples.tictacthor.contracts.BoardContract
import net.corda.samples.tictacthor.states.BoardState

/*
This flow ends a game by removing the BoardState from the ledger.
This flow is started through an request from the frontend once the GAME_OVER status is detected on the BoardState.
*/

@InitiatingFlow
@StartableByRPC
class EndGameFlow(private val gameId: UniqueIdentifier,
                  private val whoAmI: String,
                  private val whereTo:String) : FlowLogic<SignedTransaction>() {

    // TODO: progressTracker
    override val progressTracker = ProgressTracker()

    @Suspendable
    override fun call(): SignedTransaction {

        val notary = serviceHub.networkMapCache.notaryIdentities.first()

        //loading game board
        val myAccount = accountService.accountInfo(whoAmI).single().state.data
        val mykey = subFlow(NewKeyForAccount(myAccount.identifier.id)).owningKey

        val targetAccount = accountService.accountInfo(whereTo).single().state.data
        val targetAcctAnonymousParty = subFlow(RequestKeyForAccount(targetAccount))


        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(uuid = listOf(gameId.id))
        val boardStateRefToEnd = serviceHub.vaultService.queryBy<BoardState>(queryCriteria)
            .states.singleOrNull() ?: throw FlowException("GameState with id $gameId not found.")

        val command = Command(BoardContract.Commands.EndGame(), listOf(mykey, targetAcctAnonymousParty.owningKey))

        val txBuilder = TransactionBuilder(notary)
            .addInputState(boardStateRefToEnd)
            .addCommand(command)
        txBuilder.verify(serviceHub)

        //Pass along Transaction
        progressTracker.currentStep = StartGameFlow.Companion.SIGNING_TRANSACTION
        val signedTx = verifyAndSign(txBuilder)

        //Collect sigs
        progressTracker.currentStep = StartGameFlow.Companion.GATHERING_SIGS
        val signed = collectSignatures(boardStateRefToEnd.state.data, transaction = signedTx)
        progressTracker.currentStep = StartGameFlow.Companion.FINALISING_TRANSACTION

        return subFlow(FinalityFlow(signed))


        //self sign
        val locallySignedTx = serviceHub.signInitialTransaction(txBuilder, listOf(ourIdentity.owningKey, mykey))
        //counter sign
        val sessionForAccountToSendTo = initiateFlow(targetAccount.host)
        val accountToMoveToSignature = subFlow(
            CollectSignatureFlow(
                locallySignedTx, sessionForAccountToSendTo,
                targetAcctAnonymousParty.owningKey
            )
        )
        val signedByCounterParty = locallySignedTx.withAdditionalSignatures(accountToMoveToSignature)

        return subFlow(
            FinalityFlow(
                signedByCounterParty,
                listOf(sessionForAccountToSendTo).filter { it.counterparty != ourIdentity })
        )
    }

    @Suspendable
    private fun collectSignatures(initialBoardState: BoardState, transaction: SignedTransaction): SignedTransaction {
        val sessions = (initialBoardState.participants - ourIdentity).map { initiateFlow(it) }.toSet()
        return subFlow(CollectSignaturesFlow(transaction, sessions))
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
