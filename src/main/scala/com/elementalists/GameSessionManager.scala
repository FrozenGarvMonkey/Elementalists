package com.elementalists

import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import RoundManager.{RoundManagerCommands, RoundManagerResponses, GameStatusUpdate, StartRound}

/* This actor class manages a whole game session containing several rounds between two players. It also asks the player's intention to rematch.  
    Also, this actor class is bound to a specific player, as it is being created and assigned to him during successful name registration. The player 
    then uses this manager to invite another partner for a game.  */
object GameSessionManager {
    trait GameSessionCommands
    // When the player has selected a game partner 
    final case class GamePartnerSelection(thatPlayer: ActorRef[GameSessionResponses], thatPlayerName: String) extends GameSessionCommands

    final case object GameCreated extends GameSessionCommands
    // Updates fired from the round manager containing the winner and loser
    final case class WrappedRoundUpdates(response: RoundManager.RoundManagerResponses) extends GameSessionCommands

    final case object RematchInvitation extends GameSessionCommands
    // Analogous to the game invitation response, except that this message class is no longer specific to the opponent but this player himself 
    // This is because we need to ask both parties whether to rematch with the current opponent
    final case class RematchResponse(response: Player.PlayerResponses) extends GameSessionCommands
    
    trait GameSessionResponses
    // Game invitation request issued by this class to the invited player to create a game 
    final case class ChallengeRequest(fromPlayerName: String) extends GameSessionResponses
    // Scores update message issued to the player to update their scores 
    final case class UpdateSumScore(changeInScore: Int, tie: Boolean) extends GameSessionResponses 
    final case class MatchWin(totalScore: Int) extends GameSessionResponses
    final case class MatchLost(totalScore: Int) extends GameSessionResponses
    final case class MatchTie(totalScore: Int) extends GameSessionResponses
    // Message fired to collect the rematch invitation response 
    final case class RematchRequest(opponentName: String) extends GameSessionResponses
    final case class BindGameSession(session: ActorRef[GameSessionManager.GameSessionCommands]) extends GameSessionResponses
    final case object UnbindGameSession extends GameSessionResponses
    final case object MatchmakingFailed extends GameSessionResponses

    def apply(thisPlayer: ActorRef[GameSessionResponses], thisPlayerName: String): Behavior[GameSessionCommands] = {
        Behaviors.setup { context => 
            new GameSessionManager(context, thisPlayer, thisPlayerName)
        }
    }
}

class GameSessionManager(context: ActorContext[GameSessionManager.GameSessionCommands], val thisPlayer: ActorRef[GameSessionManager.GameSessionResponses], val thisPlayerName: String) extends AbstractBehavior(context) {
    import GameSessionManager._

    // Private states containing opponent information 
    private var thatPlayer: Option[ActorRef[GameSessionResponses]] = None
    private var thatPlayerName = ""
    // Round-specific information
    private var roundManager: Option[ActorRef[RoundManagerCommands]] = None
    private var roundCount = 3 
    // Game rematch intention states
    private var gameSessionRecord: Array[Int] = Array(0, 0)
    private var rematchIntentionMap: Array[Player.PlayerResponses] = Array()

    override def onMessage(msg: GameSessionCommands): Behavior[GameSessionCommands] = {
        msg match {
            case GamePartnerSelection(opponent, name) => 
                thisPlayer ! BindGameSession(context.self)
                opponent ! BindGameSession(context.self)
                opponent ! ChallengeRequest(thisPlayerName)
                thatPlayer = Some(opponent)
                thatPlayerName = name
                Behaviors.same
            case Player.InvitationAccepted => 
                context.self ! GameCreated
                Behaviors.same
            case Player.InvitationRejected | Player.NotResponded =>
                thatPlayer.get ! UnbindGameSession
                thatPlayer = None
                thatPlayerName = ""
                thisPlayer ! MatchmakingFailed
                Behaviors.same
            case GameCreated => 
                val roundManagerAdapter = context.messageAdapter[RoundManager.RoundManagerResponses](WrappedRoundUpdates.apply)
                val players = Array(thisPlayer, thatPlayer.get)
                roundManager = Some(context.spawn(RoundManager(roundManagerAdapter, players), "Round-Manager"))
                roundManager.get ! StartRound(roundCount)
                roundCount -= 1
                Behaviors.same
            case WrappedRoundUpdates(response) => 
                response match {
                    case GameStatusUpdate(roundWinner, roundLoser, tie) => 
                        if (!tie) {
                            if (roundWinner == thisPlayer) {
                                gameSessionRecord(0) += 1
                            } else {
                                gameSessionRecord(1) += 1
                            }
                        }
                        roundWinner ! UpdateSumScore(1, tie)
                        roundLoser ! UpdateSumScore(0, tie)
                        if (roundCount - 1 >= 0) {
                            roundManager.get ! StartRound(roundCount)
                            roundCount -= 1    
                        } else {
                            if (gameSessionRecord(0) == gameSessionRecord(1)) {
                                thisPlayer ! MatchTie(gameSessionRecord(0))
                                thatPlayer.get ! MatchTie(gameSessionRecord(1))
                            } else if (gameSessionRecord(0) > gameSessionRecord(1)) {
                                thisPlayer ! MatchWin(gameSessionRecord(0))
                                thatPlayer.get ! MatchLost(gameSessionRecord(1))
                            } else if (gameSessionRecord(1) > gameSessionRecord(0)) {
                                thisPlayer ! MatchLost(gameSessionRecord(0))
                                thatPlayer.get ! MatchWin(gameSessionRecord(1))
                            }
                            context.self ! RematchInvitation
                            roundCount = 3
                            gameSessionRecord = Array(0, 0)
                        }
                        Behaviors.same
                    case _ => 
                        Behaviors.unhandled
                }
            case RematchInvitation => 
                thisPlayer ! RematchRequest(thatPlayerName)
                thatPlayer.get ! RematchRequest(thisPlayerName)
                Behaviors.same
            case RematchResponse(response) => 
                println(Console.CYAN + "Got a rematch invitation response! " + response.toString() + Console.RESET)
                rematchIntentionMap = rematchIntentionMap.appended(response)
                if (rematchIntentionMap.size == 2) {
                    
                    println(Console.CYAN + "Got both responses!" + Console.RESET)
                    if (rematchIntentionMap.contains(Player.InvitationRejected)) { 
                        println(Console.CYAN + "One of the player rejected..." + Console.RESET)
                        thisPlayer ! MatchmakingFailed
                        thatPlayer.get ! MatchmakingFailed
                        thatPlayer = None 
                        thatPlayerName = ""
                        context.stop(roundManager.get)
                        roundManager = None
                    } else {        
                        println(Console.CYAN + "Both players accepted to rematch...Restarting the game..." + Console.RESET)
                        roundManager.get ! StartRound(roundCount)
                        roundCount -= 1
                    }
                    rematchIntentionMap = Array()
                }
                Behaviors.same
        }   
    }
}