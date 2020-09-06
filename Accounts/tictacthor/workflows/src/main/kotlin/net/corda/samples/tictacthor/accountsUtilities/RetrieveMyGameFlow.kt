package net.corda.samples.tictacthor.accountsUtilities


import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.accounts.workflows.accountService
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.utilities.ProgressTracker
import net.corda.samples.tictacthor.states.BoardState

// *********
// * Flows *
// *********
@InitiatingFlow
@StartableByRPC
class RetrieveMyGameFlow(private val whoAmI: String) : FlowLogic<BoardState>() {
    override val progressTracker = ProgressTracker()

    @Suspendable
    override fun call(): BoardState {

        val myAccount = accountService.accountInfo(whoAmI).single().state.data
        println ("myAccount: $myAccount")
        val criteria = QueryCriteria.VaultQueryCriteria(
            externalIds = listOf(myAccount.identifier.id)
        )

        return serviceHub.vaultService.queryBy(
                contractStateType = BoardState::class.java,
                criteria = criteria
        ).states.single().state.data
    }
}
