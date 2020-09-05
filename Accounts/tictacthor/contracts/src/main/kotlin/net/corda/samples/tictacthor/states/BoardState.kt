package net.corda.samples.tictacthor.states

import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import net.corda.samples.tictacthor.contracts.BoardContract

@CordaSerializable
enum class Status {
    GAME_IN_PROGRESS, GAME_OVER
}

@BelongsToContract(BoardContract::class)
@CordaSerializable
data class BoardState(
    val playerO: UniqueIdentifier,
    val playerX: UniqueIdentifier,
    val me: Party,
    val competitor: Party,
    val isPlayerXTurn: Boolean = false,
    val board: Array<CharArray> = Array(3) { charArrayOf('E', 'E', 'E') },
    val status: Status = Status.GAME_IN_PROGRESS,
    override val linearId: UniqueIdentifier = UniqueIdentifier()
): LinearState {

    override val participants: List<AbstractParty> = listOfNotNull(me, competitor).map { it }

    fun isGameOver() = status != Status.GAME_IN_PROGRESS

    // Returns the party of the current player
    fun getCurrentPlayerParty(): Party = if (isPlayerXTurn) me else competitor

    // Get deep copy of board
    private fun Array<CharArray>.copy() = Array(size) { get(it).clone() }

    // Returns a copy of a BoardState object after a move at Pair<x,y>
    fun returnNewBoardAfterMove(pos: Pair<Int, Int>): BoardState {
        if (pos.first > 2 || pos.second > 2) throw IllegalStateException("Invalid board index.")

        val newBoard = board.copy()
        if (isPlayerXTurn) newBoard[pos.second][pos.first] = 'X'
        else newBoard[pos.second][pos.first] = 'O'

        val newBoardState = copy(board = newBoard, isPlayerXTurn = !isPlayerXTurn)
        if (BoardContract.BoardUtils.isGameOver(newBoardState)) return newBoardState.copy(status = Status.GAME_OVER)
        return newBoardState
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BoardState

        if (playerO != other.playerO) return false
        if (playerX != other.playerX) return false
        if (me != other.me) return false
        if (competitor != other.competitor) return false
        if (isPlayerXTurn != other.isPlayerXTurn) return false
        if (!board.contentDeepEquals(other.board)) return false
        if (status != other.status) return false
        if (linearId != other.linearId) return false
        if (participants != other.participants) return false

        return true
    }

    override fun hashCode(): Int {
        var result = playerO.hashCode()
        result = 31 * result + playerX.hashCode()
        result = 31 * result + me.hashCode()
        result = 31 * result + competitor.hashCode()
        result = 31 * result + isPlayerXTurn.hashCode()
        result = 31 * result + board.contentDeepHashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + linearId.hashCode()
        result = 31 * result + participants.hashCode()
        return result
    }
}

