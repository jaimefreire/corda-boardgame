package net.corda.samples.tictacthor.flows


import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.accounts.workflows.flows.ShareStateAndSyncAccounts
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.utilities.ProgressTracker
import net.corda.samples.tictacthor.states.BoardState

// *********
// * Flows *
// *********
@InitiatingFlow
@StartableByRPC
class SyncGameFlow(
    private val gameId: String,
    private val party: Party
) : FlowLogic<String>() {
    @Suspendable
    override fun call(): String {
        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(UniqueIdentifier.fromString(gameId)))
        val inputBoardStateAndRef =
            serviceHub.vaultService.queryBy<BoardState>(queryCriteria).states.singleOrNull()
                ?: throw FlowException("GameState with id $gameId not found.")
        subFlow(ShareStateAndSyncAccounts(inputBoardStateAndRef, party))
        return "Game synced"
    }
}
