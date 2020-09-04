package net.corda.samples.tictacthor

import com.r3.corda.lib.accounts.workflows.flows.CreateAccount
import com.r3.corda.lib.accounts.workflows.flows.OurAccounts
import com.r3.corda.lib.accounts.workflows.flows.ShareAccountInfo
import net.corda.core.identity.Party
import net.corda.core.utilities.getOrThrow
import net.corda.samples.tictacthor.flows.StartGameFlow
import net.corda.samples.tictacthor.flows.SubmitTurnFlow
import net.corda.testing.internal.chooseIdentityAndCert
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.StartedMockNode
import net.corda.testing.node.TestCordapp
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.slf4j.LoggerFactory

class SubmitTurnFlowTests {

    private val mockNetwork = MockNetwork(
        MockNetworkParameters(
            cordappsForAllNodes = listOf(
                TestCordapp.findCordapp("net.corda.samples.tictacthor.contracts"),
                TestCordapp.findCordapp("net.corda.samples.tictacthor.flows"),
                TestCordapp.findCordapp("com.r3.corda.lib.ci"),
                TestCordapp.findCordapp("com.r3.corda.lib.accounts.contracts"),
                TestCordapp.findCordapp("com.r3.corda.lib.accounts.workflows"),
                TestCordapp.findCordapp("com.r3.corda.lib.accounts.workflows.services")

            )
        )
    )

    private lateinit var nodeA: StartedMockNode
    private lateinit var nodeB: StartedMockNode
    private lateinit var partyA: Party
    private lateinit var partyB: Party

    @Before
    fun setup() {
        nodeA = mockNetwork.createNode()
        nodeB = mockNetwork.createNode()
        partyA = nodeA.info.chooseIdentityAndCert().party
        partyB = nodeB.info.chooseIdentityAndCert().party
    }

    @After
    fun tearDown() = mockNetwork.stopNodes()

    @Test
    fun flowReturnsCorrectlyFormedTransaction() {

        val a1 = nodeA.startFlow(CreateAccount(partyA.name.toString()))
        val a2 = nodeB.startFlow(CreateAccount(partyB.name.toString()))
        mockNetwork.runNetwork()

        val ourAccounts = nodeA.startFlow(OurAccounts())
        val theirAccounts = nodeB.startFlow(OurAccounts())
        mockNetwork.runNetwork()

        val s1 = nodeA.startFlow(ShareAccountInfo(ourAccounts.get().single(), listOf(partyB)))
        val s2 = nodeB.startFlow(ShareAccountInfo(theirAccounts.get().single(), listOf(partyA)))
        mockNetwork.runNetwork()

        val gameId = nodeA.startFlow(StartGameFlow(whoAmI = partyA.name.toString(), whereTo = partyB.name.toString()))
        mockNetwork.runNetwork()

        val future = nodeA.startFlow(SubmitTurnFlow(gameId.get(), partyA.name.toString(), partyB.name.toString(), 1, 0))
        mockNetwork.runNetwork()

        a1.get()
        a2.get()
        s1.get()
        s2.get()

        gameId.get()

        val ptx = future.getOrThrow()
        logger.info(ptx)
    }


    companion object {
        private val logger = LoggerFactory.getLogger(SubmitTurnFlowTests::class.java)
    }
}