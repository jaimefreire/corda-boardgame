package net.corda.samples.tictacthor.states

import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.CordaX500Name
import net.corda.samples.tictacthor.contracts.BoardContract
import net.corda.testing.core.TestIdentity
import org.junit.Test
import kotlin.test.assertEquals

class BoardStateTests {

    private val playerO = UniqueIdentifier()
    private val playerX = UniqueIdentifier()

    @Test
    fun hasPlayerOFieldOfCorrectType() {
        assertEquals(BoardState::class.java.getDeclaredField("playerO").type, UniqueIdentifier::class.java)
    }

    @Test
    fun hasPlayerXFieldOfCorrectType() {
        assertEquals(BoardState::class.java.getDeclaredField("playerX").type, UniqueIdentifier::class.java)
    }

    @Test
    fun hasIsPlayerXTurnFieldOfCorrectType() {
        assertEquals(BoardState::class.java.getDeclaredField("isPlayerXTurn").type, Boolean::class.java)
    }

    @Test
    fun isPlayerXTurnFieldSetToFalse() {
        val partyA = TestIdentity(CordaX500Name("PartyA", "London", "GB")).party
        val partyB = TestIdentity(CordaX500Name("PartyB", "New York", "US")).party
        val playerO = UniqueIdentifier()
        val playerX = UniqueIdentifier()

        val boardState = BoardState(me = partyA, competitor = partyB, playerO = playerO, playerX = playerX)
        assert(!boardState.isPlayerXTurn)
    }

    @Test
    fun hasBoardFieldOfCorrectType() {
        assertEquals(BoardState::class.java.getDeclaredField("board").type, Array<CharArray>::class.java)
    }

    @Test
    fun hasBoardFieldCorrectlyFormatted() {
        val partyA = TestIdentity(CordaX500Name("PartyA", "London", "GB")).party
        val partyB = TestIdentity(CordaX500Name("PartyB", "New York", "US")).party
        val playerO = UniqueIdentifier()
        val playerX = UniqueIdentifier()

        val boardState = BoardState(me = partyA, competitor = partyB, playerO = playerO, playerX = playerX)
        val board = boardState.board
        assert(board.size == 3)
        assert(board[0].size == 3)
        assert(board.flatMap { it.asList() }.distinct().size == 1)
    }

    @Test
    fun hasStatusFieldOfCorrectType() {
        assertEquals(BoardState::class.java.getDeclaredField("status").type, Status::class.java)
    }

    @Test
    fun isStatusFieldSetToGameInProgress() {
        val partyA = TestIdentity(CordaX500Name("PartyA","London","GB")).party
        val partyB = TestIdentity(CordaX500Name("PartyB", "New York", "US")).party
        val boardState = BoardState(me = partyA, competitor = partyB, playerO = playerO, playerX = playerX)
        assert(boardState.status == Status.GAME_IN_PROGRESS)
    }

    @Test
    fun hasLinearIdFieldOfCorrectType() {
        assertEquals(BoardState::class.java.getDeclaredField("linearId").type, UniqueIdentifier::class.java)
    }

    @Test
    fun checkBoardStateParameterOrdering() {
        val fields = BoardState::class.java.declaredFields
        val playerOIdx = fields.indexOf(BoardState::class.java.getDeclaredField("playerO"))
        val playerXIdx = fields.indexOf(BoardState::class.java.getDeclaredField("playerX"))
        val isPlayerXTurnIdx = fields.indexOf(BoardState::class.java.getDeclaredField("isPlayerXTurn"))
        val boardIdx = fields.indexOf(BoardState::class.java.getDeclaredField("board"))
        val statusIdx = fields.indexOf(BoardState::class.java.getDeclaredField("status"))
        val linearIdIdx = fields.indexOf(BoardState::class.java.getDeclaredField("linearId"))
        assert(playerOIdx < playerXIdx)
        assert(playerXIdx < isPlayerXTurnIdx)
        assert(isPlayerXTurnIdx < boardIdx)
        assert(boardIdx < statusIdx)
        assert(statusIdx < linearIdIdx)
    }

    @Test
    fun checkParticipants() {
        val partyA = TestIdentity(CordaX500Name("PartyA","London","GB")).party
        val partyB = TestIdentity(CordaX500Name("PartyB", "New York", "US")).party
        val boardState = BoardState(me = partyA, competitor = partyB, playerO = playerO, playerX = playerX)
        assert(boardState.participants.size == 2)
        assert(boardState.participants.contains(partyA))
        assert(boardState.participants.contains(partyB))
    }

    @Test
    fun checkReturnNewBoardAfterMoveHelperMethod() {
        val partyA = TestIdentity(CordaX500Name("PartyA","London","GB")).party
        val partyB = TestIdentity(CordaX500Name("PartyB", "New York", "US")).party
        val boardState = BoardState(me = partyA, competitor = partyB, playerO = playerO, playerX = playerX)

        assert(boardState.board[0][0] == 'E')
        assert(!boardState.isPlayerXTurn)

        val boardState2 = boardState.returnNewBoardAfterMove(Pair(0,0))
        assert(boardState.board[0][0] == 'E')
        assert(!boardState.isPlayerXTurn)

        assert(boardState2.board[0][0] == 'O')
        assert(boardState2.isPlayerXTurn)

        val boardState3 = boardState2.returnNewBoardAfterMove(Pair(1,0))
        assert(boardState2.board[0][0] == 'O')
        assert(boardState2.isPlayerXTurn)

        assert(boardState3.board[0][1] == 'X')
        assert(!boardState3.isPlayerXTurn)
    }


    @Test
    fun checkIsGameOverMethod() {
        val partyA = TestIdentity(CordaX500Name("PartyA","London","GB")).party
        val partyB = TestIdentity(CordaX500Name("PartyB","New York","US")).party

        var boardState = BoardState(me = partyA, competitor = partyB, playerO = playerO, playerX = playerX)
        assert(!BoardContract.BoardUtils.isGameOver(boardState))
        boardState = boardState.returnNewBoardAfterMove(Pair(0,0))
        boardState = boardState.returnNewBoardAfterMove(Pair(1,0))
        assert(!BoardContract.BoardUtils.isGameOver(boardState))
        boardState = boardState.returnNewBoardAfterMove(Pair(0,1))
        assert(!BoardContract.BoardUtils.isGameOver(boardState))
        boardState = boardState.returnNewBoardAfterMove(Pair(1,1))
        assert(!BoardContract.BoardUtils.isGameOver(boardState))
        boardState = boardState.returnNewBoardAfterMove(Pair(0,2))

        assert(BoardContract.BoardUtils.isGameOver(boardState))
        assert(BoardContract.BoardUtils.getWinner(boardState)!! == playerO)
    }



}


