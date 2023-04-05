package me.melijn.bot.commands.games

import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.converters.impl.optionalUser
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.types.respond
import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.interactions.components.danger
import dev.minn.jda.ktx.interactions.components.secondary
import dev.minn.jda.ktx.interactions.components.success
import dev.minn.jda.ktx.messages.InlineEmbed
import dev.minn.jda.ktx.messages.InlineMessage
import dev.minn.jda.ktx.messages.MessageEdit
import kotlinx.datetime.Clock
import me.melijn.bot.database.manager.BalanceManager
import me.melijn.bot.database.manager.TicTacToeManager
import me.melijn.bot.events.TICTACTOE_ACCEPT_BUTTON_ID
import me.melijn.bot.events.TICTACTOE_ACTION_PREFIX_ID
import me.melijn.bot.events.TICTACTOE_DENY_BUTTON_ID
import me.melijn.bot.utils.KoinUtil
import me.melijn.bot.utils.KordExUtils.optionalAvailableCurrency
import me.melijn.bot.utils.KordExUtils.publicGuildSlashCommand
import me.melijn.bot.utils.KordExUtils.tr
import me.melijn.bot.utils.embedWithColor
import me.melijn.gen.TicTacToeData
import me.melijn.gen.TicTacToePlayerData
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.interactions.components.buttons.ButtonInteraction
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import net.dv8tion.jda.api.utils.messages.MessageEditData
import org.postgresql.util.GT.tr
import kotlin.random.Random

//@KordExtension
class TicTacToeExtension : Extension() {

    override val name: String = "tic-tac-toe"
    val gameManager by KoinUtil.inject<TicTacToeManager>()

    companion object {
        suspend fun handleButton(gameManager: TicTacToeManager, game: TicTacToeData, interaction: ButtonInteraction) {
            val players = gameManager.ticTacToePlayerManager.getByIndex1(game.gameId)
            val activeUser = players.first { it.isUser1 == game.is_user1_turn }
            val waitingUser = players.firstOrNull { it.isUser1 != game.is_user1_turn }
            if (players.none { it.userId == interaction.user.idLong }) return

            if (interaction.componentId.startsWith(TICTACTOE_ACTION_PREFIX_ID)) { // GAME ACTION
                // check if user that clicked is active player
                if (activeUser.userId != interaction.user.idLong) return

                val board = parseBoard(game.board).toMutableList()
                val location = interaction.componentId.removePrefix(TICTACTOE_ACTION_PREFIX_ID).toInt()

                // update board
                board[location] = if (game.is_user1_turn) TTTState.O
                else TTTState.X
                game.is_user1_turn = !game.is_user1_turn
                game.board = serializeBoard(board)

                // check if game is won
                if (isGameDone(board)) {
                    handleGameDone(interaction, gameManager, game, interaction.user, waitingUser, isGameWon(board))
                    return
                }

                if (waitingUser == null) { // check if bot should now play
                    val options = board.count { it == TTTState.EMPTY }

                    // play random move
                    val randomLoc = Random.nextInt(options)
                    var idx = -1
                    for ((actualId, state) in board.withIndex()) {
                        if (state == TTTState.EMPTY) idx++
                        if (idx == randomLoc) {
                            board[actualId] = TTTState.X
                            break
                        }
                    }

                    game.is_user1_turn = !game.is_user1_turn
                    game.board = serializeBoard(board)

                    // check if game is done
                    if (isGameDone(board)) {
                        handleGameDone(interaction, gameManager, game, interaction.user, null, isGameWon(board))
                        return
                    }
                }

                game.last_played = Clock.System.now()
                gameManager.updateGame(game)

                interaction.editMessage(MessageEdit {
                    if (waitingUser == null) showGameState(game, interaction.user.idLong, TTTState.O)
                    else showGameState(game, waitingUser.userId, if (game.is_user1_turn) TTTState.O else TTTState.X)
                }).await()

            } else if (interaction.componentId == TICTACTOE_ACCEPT_BUTTON_ID) { // START OF THE GAME
                val balanceManager by KoinUtil.inject<BalanceManager>()
                if (players.size < 2 || interaction.user.idLong != players[1].userId.toLong()) return
                val p1 = players[0]

                balanceManager.min(interaction.user, game.bet * 2)
                balanceManager.min(UserSnowflake.fromId(p1.userId.toLong()), game.bet)

                interaction.editMessage(MessageEdit {
                    showGameState(game, p1.userId, TTTState.O)
                }).await()
            } else if (interaction.componentId == TICTACTOE_DENY_BUTTON_ID) {
                interaction.editMessage(MessageEdit {
                    this.builder.setEmbeds(mutableListOf())
                    this.builder.setComponents(mutableListOf())
                    content = when (interaction.user.idLong) {
                        waitingUser?.userId?.toLong() -> "${interaction.user.asMention} denied <@${activeUser.userId}>'s tic-tac-toe invite."
                        activeUser.userId.toLong() -> "${interaction.user.asMention} cancelled their tic-tac-toe invite."
                        else -> return
                    }
                }).await()
                gameManager.delete(game)
            }
        }

        private fun isGameDone(game: List<TTTState>): Boolean = game.all { it != TTTState.EMPTY } || isGameWon(game)

        fun isGameWon(game: List<TTTState>): Boolean {
            var result = false
            for (i in 0 until 3) {
                val column = game.filterIndexed { index, _ -> index % 3 == i }
                val row = game.filterIndexed { index, _ -> i * 3 <= index && index < i * 3 + 3 }
                result = result || column.allSame(TTTState.EMPTY) || row.allSame(TTTState.EMPTY)
            }
            result = result || (game[0] != TTTState.EMPTY && game[0] == game[4] && game[4] == game[8])
            result = result || (game[2] != TTTState.EMPTY && game[2] == game[4] && game[4] == game[6])
            return result
        }

        /**
         * Gives the game winner their mel, changes the game message to display who won, and deletes the game.
         * **/
        private suspend fun handleGameDone(
            interaction: ButtonInteraction,
            gameManager: TicTacToeManager,
            game: TicTacToeData,
            user: User,
            waitingUser: TicTacToePlayerData?,
            won: Boolean
        ) {
            suspend fun updateGameMessage(content: String) = interaction.editMessage(MessageEdit {
                showGameState(game, user.idLong, if (game.is_user1_turn) TTTState.O else TTTState.X)
                this.content = content
            }).await()

            if (!won) {
                val loserTag = waitingUser?.userId?.let { "<@$it>" } ?: "Melijn"
                updateGameMessage("${user.asMention} tied against $loserTag.")
                val balanceManager by KoinUtil.inject<BalanceManager>()
                balanceManager.add(user, game.bet)
                waitingUser?.userId?.let { balanceManager.add(UserSnowflake.fromId(it.toLong()), game.bet) }
            } else {
                if (game.is_user1_turn && waitingUser == null) { // bot just played and won
                    updateGameMessage("Melijn won the game. ${user.asMention} lost")
                } else { // user won, other user or bot lost
                    val melReward = if (game.bet > 0) " and gets ${game.bet * 2} mel" else ""
                    val loserTag = waitingUser?.userId?.let { "<@$it>" } ?: "Melijn"
                    updateGameMessage("${user.asMention} won the game$melReward. $loserTag lost")
                    val balanceManager by KoinUtil.inject<BalanceManager>()
                    balanceManager.add(user, game.bet * 2)
                }
            }
            gameManager.delete(game)
        }

        private fun InlineMessage<MessageEditData>.showGameState(
            game: TicTacToeData,
            nextTurnUserId: Long,
            nextMove: TTTState
        ) {
            this.builder.setEmbeds(mutableListOf())
            this.builder.setComponents(mutableListOf())
            val board = parseBoard(game.board)
            content = "It's <@${nextTurnUserId}>'s turn. You can play a `${nextMove.representation}`"
            for ((y, chunk) in board.chunked(3).withIndex()) {
                actionRow(
                    chunk.withIndex().map{ (x, state) ->
                        val customId = TICTACTOE_ACTION_PREFIX_ID + ((y * 3) + x)
                        secondary(customId, state.representation, disabled = state != TTTState.EMPTY)
                    }
                )
            }
        }

        fun serializeBoard(board: Iterable<TTTState>): String =
            board.joinToString(",") { it.ordinal.toString() }

        fun parseBoard(board: String) =
            board.split(",").map { TTTState.values()[it.toInt()] }
    }

    override suspend fun setup() {
        publicGuildSlashCommand(::TicTacToeArgs) {
            name = "tic-tac-toe"
            description = "Try to get a row of 3 x's or o's to win!"
            check {
                val gameByUser = gameManager.getGameByUser(event.interaction.user)
                failIf(gameByUser != null, tr("ttt.failYourAreInGame"))
            }
            action {
                val bet = arguments.bet
                val opponent = arguments.opponent

                val msgId = respond {
                    embedWithColor {
                        if (opponent == null) {
                            addGameMessage(user.asTag)
                        } else {
                            addGameInviteMessage(user.asTag, opponent.asTag)
                        }
                    }

                }

                gameManager.setupGame(guild!!, channel, msgId, user, opponent, bet ?: 0)
            }
        }
    }

    context(InlineEmbed, InlineMessage<MessageCreateData>)
    private fun addGameInviteMessage(
        inviter: String,
        invitee: String,
    ) {
        title = "tic-tac-toe invite"
        description = "$inviter invites $invitee"

        actionRow(
            success(TICTACTOE_ACCEPT_BUTTON_ID, "Accept"),
            danger(TICTACTOE_DENY_BUTTON_ID, "Deny")
        )
    }

    context(InlineEmbed)
    private fun addGameMessage(
        userTag: String,
    ) {
        title = "tic-tac-toe"
        footer {
            name = "It's ${userTag}'s turn | Plays as " + TTTState.O.representation
        }
    }

    class TicTacToeArgs : Arguments() {
        private val balanceManager by KoinUtil.inject<BalanceManager>()
        private val gameManager by KoinUtil.inject<TicTacToeManager>()
        val opponent by optionalUser {
            name = "opponent"
            description = "Can be omitted if you wish to fight the bot instead."
            validate {
                val opponent = this.value ?: return@validate
                this.failIf(opponent == this.context.user, tr("ttt.failOpponentIsSelf"))
                this.failIf(gameManager.getGameByUser(opponent) != null, tr("ttt.failOpponentIsInGame"))
            }
        }
        val bet by optionalAvailableCurrency(
            "triedBettingNothing", "triedOverBetting"
        ) {
            name = "bet"
            description = "amount to bet"
            validate {
                failIf(tr("ttt.failOpponentInsufficientFunds")) { // check if opponent has enough mel
                    val bet = this.value ?: return@failIf false
                    (opponent?.let { balanceManager.get(it).balance } ?: 0) < bet
                }
            }
        }
    }

    enum class TTTState(val representation: String) {
        EMPTY(" "),
        X("X"),
        O("○"),
    }
}

private fun <T> Iterable<T>.allSame(exclude: T): Boolean {
    val first = this.firstOrNull() ?: return true
    if (first == exclude) return false
    for (s in this) {
        if (first != s) return false
    }
    return true
}
