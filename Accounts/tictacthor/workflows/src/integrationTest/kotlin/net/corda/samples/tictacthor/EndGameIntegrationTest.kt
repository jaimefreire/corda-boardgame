package net.corda.samples.tictacthor

import com.r3.corda.lib.accounts.workflows.flows.CreateAccount
import com.r3.corda.lib.accounts.workflows.flows.OurAccounts
import com.r3.corda.lib.accounts.workflows.flows.ShareAccountInfo
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowException
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.utilities.getOrThrow
import net.corda.samples.tictacthor.contracts.BoardContract
import net.corda.samples.tictacthor.flows.EndGameFlow
import net.corda.samples.tictacthor.flows.EndGameFlowResponder
import net.corda.samples.tictacthor.flows.StartGameFlow
import net.corda.samples.tictacthor.flows.SubmitTurnFlow
import net.corda.samples.tictacthor.states.BoardState
import net.corda.samples.tictacthor.states.Status
import net.corda.testing.internal.chooseIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.MockNodeParameters
import net.corda.testing.node.StartedMockNode
import net.corda.testing.node.TestCordapp
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class EndGameIntegrationTest {

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

    @Before
    fun setup() {
        nodeA = mockNetwork.createNode(MockNodeParameters())
        nodeB = mockNetwork.createNode(MockNodeParameters())
        listOf(nodeA, nodeB).forEach {
            it.registerInitiatedFlow(EndGameFlowResponder::class.java)
        }
    }

    @After
    fun tearDown() = mockNetwork.stopNodes()

    @Test
    fun `end game test (win)`() {

        val partyA = nodeA.info.chooseIdentity()
        val partyB = nodeB.info.chooseIdentity()

        val a1 = nodeA.startFlow(CreateAccount(partyA.name.toString()))
        val a2 = nodeB.startFlow(CreateAccount(partyB.name.toString()))
        mockNetwork.runNetwork()
        a1.get()
        a2.get()

        val ourAccounts = nodeA.startFlow(OurAccounts())
        val theirAccounts = nodeB.startFlow(OurAccounts())
        mockNetwork.runNetwork()

        val s1 = nodeA.startFlow(ShareAccountInfo(ourAccounts.get().single(), listOf(partyB)))
        val s2 = nodeB.startFlow(ShareAccountInfo(theirAccounts.get().single(), listOf(partyA)))
        mockNetwork.runNetwork()
        s1.get()
        s2.get()

        //Setup Game
        val futureWithGameState = nodeA.startFlow(StartGameFlow(partyA.name.toString(), partyB.name.toString()))
        mockNetwork.runNetwork()
        val gameId = futureWithGameState.get()

        assertEquals(getBoardState(nodeA), getBoardState(nodeB))

        var boardState = getBoardState(nodeA)
        assertEquals(boardState.me, partyA)
        assertEquals(boardState.competitor, partyB)
        assert(!BoardContract.BoardUtils.isGameOver(boardState))

        //Move #1
        boardState = makeMoveAndGetNewBoardState(nodeA, gameId, partyA.name.toString(), partyB.name.toString(), 0, 0)
        assertEquals(partyB, boardState.getCurrentPlayerParty())
        assert(!BoardContract.BoardUtils.isGameOver(boardState))

         //Move #2
        boardState = makeMoveAndGetNewBoardState(nodeB, gameId, partyB.name.toString(), partyB.name.toString(), 1, 0)
        assertEquals(partyA, boardState.getCurrentPlayerParty())
        assert(!BoardContract.BoardUtils.isGameOver(boardState))

        // Move #3
        boardState = makeMoveAndGetNewBoardState(nodeA, gameId, partyA.name.toString(), partyB.name.toString(), 0, 1)
        assertEquals(partyB, boardState.getCurrentPlayerParty())
        assert(!BoardContract.BoardUtils.isGameOver(boardState))

        // Move #4
        boardState = makeMoveAndGetNewBoardState(nodeB, gameId, partyB.name.toString(), partyB.name.toString(), 2, 1)
        assertEquals(partyA, boardState.getCurrentPlayerParty())
        assert(!BoardContract.BoardUtils.isGameOver(boardState))

        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(boardState.linearId))
        val boardStateNodeA = nodeA.services.vaultService.queryBy<BoardState>(queryCriteria).states.single()
        val boardStateNodeB = nodeB.services.vaultService.queryBy<BoardState>(queryCriteria).states.single()
        assertEquals(boardStateNodeA.state.data.linearId, boardStateNodeB.state.data.linearId)

         //Move #5
        boardState = makeMoveAndGetNewBoardState(nodeA, gameId, partyA.name.toString(), partyB.name.toString(), 0, 2)
        assertEquals(partyB, boardState.getCurrentPlayerParty())
        assert(BoardContract.BoardUtils.isGameOver(boardState))

        assertEquals(
            nodeA.services.vaultService.queryBy<BoardState>(queryCriteria).states.single().state.data.status,
            Status.GAME_OVER
        )
        assertEquals(
            nodeB.services.vaultService.queryBy<BoardState>(queryCriteria).states.single().state.data.status,
            Status.GAME_OVER
        )

        // End Game
        val futureEndGame = nodeA.startFlow(EndGameFlow(gameId, partyA.name.toString(), partyB.name.toString()))
        mockNetwork.runNetwork()
        val end = futureEndGame.get()

        println("End: $end")

        assert(nodeA.services.vaultService.queryBy<BoardState>(queryCriteria).states.isEmpty())
        assert(nodeB.services.vaultService.queryBy<BoardState>(queryCriteria).states.isEmpty())
    }


    @Test
    fun `end game test (no win)`() {

        val partyA = nodeA.info.chooseIdentity()
        val partyB = nodeB.info.chooseIdentity()

        val a1 = nodeA.startFlow(CreateAccount(partyA.name.toString()))
        val a2 = nodeB.startFlow(CreateAccount(partyB.name.toString()))
        mockNetwork.runNetwork()
        a1.get()
        a2.get()

        val ourAccounts = nodeA.startFlow(OurAccounts())
        val theirAccounts = nodeB.startFlow(OurAccounts())
        mockNetwork.runNetwork()

        val s1 = nodeA.startFlow(ShareAccountInfo(ourAccounts.get().single(), listOf(partyB)))
        val s2 = nodeB.startFlow(ShareAccountInfo(theirAccounts.get().single(), listOf(partyA)))
        mockNetwork.runNetwork()
        s1.get()
        s2.get()


        // Setup Game
        val futureWithGameState = nodeA.startFlow(StartGameFlow(partyB.name.toString(), partyA.name.toString()))
        mockNetwork.runNetwork()
        val gameId = futureWithGameState.get()
        var boardState = getBoardState(nodeB)
//        assertEquals(boardState.playerO, partyA)
//        assertEquals(boardState.playerX, partyB)
        assertEquals(partyB, boardState.getCurrentPlayerParty())
        assert(!BoardContract.BoardUtils.isGameOver(boardState))

        //Move #1
        boardState = makeMoveAndGetNewBoardState(nodeA, gameId, partyB.name.toString(), partyA.name.toString(), 0, 0)
        assertEquals(partyA, boardState.getCurrentPlayerParty())
        assert(!BoardContract.BoardUtils.isGameOver(boardState))

        // Move #2
        boardState = makeMoveAndGetNewBoardState(nodeB, gameId, partyA.name.toString(), partyB.name.toString(), 1, 0)
        assertEquals(partyB, boardState.getCurrentPlayerParty())
        assert(!BoardContract.BoardUtils.isGameOver(boardState))

        // Move #3
        boardState = makeMoveAndGetNewBoardState(nodeA, gameId, partyB.name.toString(), partyA.name.toString(), 2, 0)
        assertEquals(partyA, boardState.getCurrentPlayerParty())
        assert(!BoardContract.BoardUtils.isGameOver(boardState))

        // Move #4
        boardState = makeMoveAndGetNewBoardState(nodeB, gameId, partyA.name.toString(), partyB.name.toString(), 0, 2)
        assertEquals(partyB, boardState.getCurrentPlayerParty())
        assert(!BoardContract.BoardUtils.isGameOver(boardState))

         //Move #5
        boardState = makeMoveAndGetNewBoardState(nodeA, gameId, partyB.name.toString(), partyA.name.toString(), 0, 1)
        assertEquals(partyA, boardState.getCurrentPlayerParty())
        assert(!BoardContract.BoardUtils.isGameOver(boardState))

        // Move #6
        boardState = makeMoveAndGetNewBoardState(nodeB, gameId, partyA.name.toString(), partyB.name.toString(), 1, 1)
        assertEquals(partyB, boardState.getCurrentPlayerParty())
        assert(!BoardContract.BoardUtils.isGameOver(boardState))

        // Move #7
        boardState = makeMoveAndGetNewBoardState(nodeA, gameId, partyB.name.toString(), partyA.name.toString(), 1, 2)
        assertEquals(partyA, boardState.getCurrentPlayerParty())
        assert(!BoardContract.BoardUtils.isGameOver(boardState))

        // Move #8
        boardState = makeMoveAndGetNewBoardState(nodeB, gameId, partyA.name.toString(), partyB.name.toString(), 2, 2)
        assertEquals(partyB, boardState.getCurrentPlayerParty())
        assert(!BoardContract.BoardUtils.isGameOver(boardState))

        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(boardState.linearId))
        val boardStateNodeA = nodeA.services.vaultService.queryBy<BoardState>(queryCriteria).states.single()
        val boardStateNodeB = nodeB.services.vaultService.queryBy<BoardState>(queryCriteria).states.single()
        assertEquals(boardStateNodeA.state.data.linearId, boardStateNodeB.state.data.linearId)

        assertEquals(nodeA.services.vaultService.queryBy<BoardState>(queryCriteria).states.single().state.data.status, Status.GAME_IN_PROGRESS)
        assertEquals(nodeB.services.vaultService.queryBy<BoardState>(queryCriteria).states.single().state.data.status, Status.GAME_IN_PROGRESS)

        // Move #9
        boardState = makeMoveAndGetNewBoardState(nodeA, gameId, partyB.name.toString(), partyA.name.toString(), 2, 1)
        assertEquals(partyA, boardState.getCurrentPlayerParty())
        assert(BoardContract.BoardUtils.isGameOver(boardState))

        assertEquals(nodeA.services.vaultService.queryBy<BoardState>(queryCriteria).states.single().state.data.status, Status.GAME_OVER)
        assertEquals(nodeB.services.vaultService.queryBy<BoardState>(queryCriteria).states.single().state.data.status, Status.GAME_OVER)

        // End Game
        nodeA.startFlow(EndGameFlow(gameId, partyA.name.toString(), partyB.name.toString()))
        mockNetwork.runNetwork()

        assert(nodeA.services.vaultService.queryBy<BoardState>(queryCriteria).states.isEmpty())
        assert(nodeB.services.vaultService.queryBy<BoardState>(queryCriteria).states.isEmpty())
    }


    @Test
    fun `invalid move test`() {

        val partyA = nodeA.info.chooseIdentity()
        val partyB = nodeB.info.chooseIdentity()

        val a1 = nodeA.startFlow(CreateAccount(partyA.name.toString()))
        val a2 = nodeB.startFlow(CreateAccount(partyB.name.toString()))
        mockNetwork.runNetwork()
        a1.get()
        a2.get()

        val ourAccounts = nodeA.startFlow(OurAccounts())
        val theirAccounts = nodeB.startFlow(OurAccounts())
        mockNetwork.runNetwork()

        val s1 = nodeA.startFlow(ShareAccountInfo(ourAccounts.get().single(), listOf(partyB)))
        val s2 = nodeB.startFlow(ShareAccountInfo(theirAccounts.get().single(), listOf(partyA)))
        mockNetwork.runNetwork()
        s1.get()
        s2.get()

        // Setup Game
        val futureWithGameState = nodeA.startFlow(StartGameFlow(partyB.name.toString(), partyA.name.toString()))
        mockNetwork.runNetwork()
        var boardState = getBoardState(nodeA)

        assertEquals(partyB, boardState.getCurrentPlayerParty())
        assert(!BoardContract.BoardUtils.isGameOver(boardState))

        val gameId = futureWithGameState.get()
        //Move #1
        boardState = makeMoveAndGetNewBoardState(nodeB, gameId, partyB.name.toString(), partyA.name.toString(), 0, 0)
        assertEquals(partyA, boardState.getCurrentPlayerParty())
        assert(!BoardContract.BoardUtils.isGameOver(boardState))

        // Move #2
        val future = nodeA.startFlow(SubmitTurnFlow(gameId, partyA.name.toString(), partyB.name.toString(), 0, 0))
        mockNetwork.runNetwork()

        var exception = Exception()
        try {
            future.getOrThrow()
        } catch (e: Exception) {
            exception = e
        }
        assert(exception is TransactionVerificationException)
        assertEquals("java.lang.IllegalArgumentException: Failed requirement: Not valid board update.", exception.cause.toString())
    }


    @Test
    fun `end game when not end game`() {
        val partyA = nodeA.info.chooseIdentity()
        val partyB = nodeB.info.chooseIdentity()

        val a1 = nodeA.startFlow(CreateAccount(partyA.name.toString()))
        val a2 = nodeB.startFlow(CreateAccount(partyB.name.toString()))
        mockNetwork.runNetwork()
        a1.get()
        a2.get()

        val ourAccounts = nodeA.startFlow(OurAccounts())
        val theirAccounts = nodeB.startFlow(OurAccounts())
        mockNetwork.runNetwork()

        val s1 = nodeA.startFlow(ShareAccountInfo(ourAccounts.get().single(), listOf(partyB)))
        val s2 = nodeB.startFlow(ShareAccountInfo(theirAccounts.get().single(), listOf(partyA)))
        mockNetwork.runNetwork()
        s1.get()
        s2.get()



        // Setup Game
        val futureWithGameState = nodeA.startFlow(StartGameFlow(partyB.name.toString(), partyA.name.toString()))
        mockNetwork.runNetwork()
        val gameId = futureWithGameState.get()
        var boardState = getBoardState(nodeB)
        assertEquals(partyB, boardState.getCurrentPlayerParty())
        assert(!BoardContract.BoardUtils.isGameOver(boardState))

        // Move #1
        boardState = makeMoveAndGetNewBoardState(nodeB, gameId, partyB.name.toString(), partyA.name.toString(), 0, 0)
        assertEquals(partyA, boardState.getCurrentPlayerParty())
        assert(!BoardContract.BoardUtils.isGameOver(boardState))

        // Move #2
        val future = nodeA.startFlow(EndGameFlow(gameId, partyA.name.toString(), partyB.name.toString()))
        mockNetwork.runNetwork()

        var exception = Exception()
        try {
            future.getOrThrow()
        }
        catch (e: Exception) {
            exception = e
        }
        assert(exception is TransactionVerificationException)
        assertEquals("java.lang.IllegalArgumentException: Failed requirement: Input board must have status GAME_OVER.", exception.cause.toString())
    }

    @Test
    fun `moves out of order`() {

        val partyA = nodeA.info.chooseIdentity()
        val partyB = nodeB.info.chooseIdentity()

        val a1 = nodeA.startFlow(CreateAccount(partyA.name.toString()))
        val a2 = nodeB.startFlow(CreateAccount(partyB.name.toString()))
        mockNetwork.runNetwork()
        a1.get()
        a2.get()

        val ourAccounts = nodeA.startFlow(OurAccounts())
        val theirAccounts = nodeB.startFlow(OurAccounts())
        mockNetwork.runNetwork()

        val s1 = nodeA.startFlow(ShareAccountInfo(ourAccounts.get().single(), listOf(partyB)))
        val s2 = nodeB.startFlow(ShareAccountInfo(theirAccounts.get().single(), listOf(partyA)))
        mockNetwork.runNetwork()
        s1.get()
        s2.get()
        //  Setup Game
        val futureWithGameState = nodeB.startFlow(StartGameFlow(partyB.name.toString(), partyA.name.toString()))
        mockNetwork.runNetwork()
        val gameId = futureWithGameState.get()
        var boardState = getBoardState(nodeA)
        assertEquals(partyB, boardState.getCurrentPlayerParty())
        assert(!BoardContract.BoardUtils.isGameOver(boardState))

        // Move #1
        boardState = makeMoveAndGetNewBoardState(nodeB, gameId, partyB.name.toString(), partyA.name.toString(), 0, 0)
        assertEquals(partyA, boardState.getCurrentPlayerParty())
        assert(!BoardContract.BoardUtils.isGameOver(boardState))

        // Move #2
        val future = nodeB.startFlow(SubmitTurnFlow(gameId, partyB.name.toString(), partyA.name.toString(), 0, 1))
        mockNetwork.runNetwork()

        var exception = Exception()
        try {
            future.getOrThrow()
        }
        catch (e: Exception) {
            exception = e
        }
        assert(exception is FlowException)
        assertEquals("It's not your turn!", exception.message.toString())
    }

    @Test
    fun `invalid index`() {

        val partyA = nodeA.info.chooseIdentity()
        val partyB = nodeB.info.chooseIdentity()

        val a1 = nodeA.startFlow(CreateAccount(partyA.name.toString()))
        val a2 = nodeB.startFlow(CreateAccount(partyB.name.toString()))
        mockNetwork.runNetwork()
        a1.get()
        a2.get()

        val ourAccounts = nodeA.startFlow(OurAccounts())
        val theirAccounts = nodeB.startFlow(OurAccounts())
        mockNetwork.runNetwork()

        val s1 = nodeA.startFlow(ShareAccountInfo(ourAccounts.get().single(), listOf(partyB)))
        val s2 = nodeB.startFlow(ShareAccountInfo(theirAccounts.get().single(), listOf(partyA)))
        mockNetwork.runNetwork()
        s1.get()
        s2.get()

        // Setup Game
        val futureWithGameState = nodeB.startFlow(StartGameFlow(partyB.name.toString(), partyA.name.toString()))
        mockNetwork.runNetwork()
        var boardState = getBoardState(nodeA)
        val gameId = futureWithGameState.get()
        //assertEquals(boardState.playerO, partyA)
        //assertEquals(boardState.playerX, partyB)
        assertEquals(partyB, boardState.getCurrentPlayerParty())
        assert(!BoardContract.BoardUtils.isGameOver(boardState))

        // Move #1
        boardState = makeMoveAndGetNewBoardState(nodeB, gameId, partyB.name.toString(), partyA.name.toString(), 0, 0)
        assertEquals(partyA, boardState.getCurrentPlayerParty())
        assert(!BoardContract.BoardUtils.isGameOver(boardState))

        // Move #2
        val future = nodeA.startFlow(SubmitTurnFlow(gameId, partyA.name.toString(), partyB.name.toString(), 0, 3))
        mockNetwork.runNetwork()

        var exception = Exception()
        try {
            future.getOrThrow()
        } catch (e: Exception) {
            exception = e
        }
        assert(exception is IllegalStateException)
        assertEquals("Invalid board index.", exception.message.toString())

    }


    private fun makeMoveAndGetNewBoardState(
        node: StartedMockNode,
        gameId: UniqueIdentifier,
        whoAmI: String,
        whereTo: String,
        x: Int,
        y: Int
    ): BoardState {
        val futureWithGameState = node.startFlow(SubmitTurnFlow(gameId, whoAmI, whereTo, x, y))
        mockNetwork.runNetwork()
        futureWithGameState.get()
        return getBoardState(node)
    }

    private fun getBoardState(node: StartedMockNode): BoardState =
        node.services.vaultService.queryBy(BoardState::class.java).states.single().state.data
}