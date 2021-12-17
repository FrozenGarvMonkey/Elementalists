package com.elementalists

import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import RoundManager.{RoundManagerCommands, RoundManagerResponses, GameStatusUpdate, StartRound}

/* This actor class manages a whole game session containing several rounds between two players. It also asks the player's intention to rematch.  
    Also, this actor class is bound to a specific player, as it is being created and assigned to him during sucessful name registration. The player 
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
    final case class RematchInvitationResponse(response: Player.PlayerResponses) extends GameSessionCommands
    
    trait GameSessionResponses
    // Game invitation request issued by this class to the invited player to create a game 
    final case class GameInvitationRequest(fromPlayerName: String) extends GameSessionResponses
    // Scores update message issued to the player to update their scores 
    final case class AccumulatedScoresUpdate(changeInScore: Int, tie: Boolean) extends GameSessionResponses 
    final case class GameSessionVictory(totalScore: Int) extends GameSessionResponses
    final case class GameSessionLost(totalScore: Int) extends GameSessionResponses
    final case class GameSessionTie(totalScore: Int) extends GameSessionResponses
    // Message fired to collect the rematch invitation response 
    final case class RematchInvitationRequest(opponentName: String) extends GameSessionResponses
    final case class BindGameSession(sesion: ActorRef[GameSessionManager.GameSessionCommands]) extends GameSessionResponses
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
                opponent ! GameInvitationRequest(thisPlayerName)
                thatPlayer = Some(opponent)
                thatPlayerName = name
                this
            case Player.InvitationAccepted => 
                context.self ! GameCreated
                this
            case Player.InvitationRejected | Player.NotResponded =>
                thatPlayer.get ! UnbindGameSession
                thatPlayer = None
                thatPlayerName = ""
                thisPlayer ! MatchmakingFailed
                this 
            case GameCreated => 
                val roundManagerAdapter = context.messageAdapter[RoundManager.RoundManagerResponses](WrappedRoundUpdates.apply)
                val players = Array(thisPlayer, thatPlayer.get)
                roundManager = Some(context.spawn(RoundManager(roundManagerAdapter, players), "Round-Manager"))
                roundManager.get ! StartRound(roundCount)
                roundCount -= 1
                this
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
                        roundWinner ! AccumulatedScoresUpdate(1, tie)
                        roundLoser ! AccumulatedScoresUpdate(0, tie)
                        if (roundCount - 1 >= 0) {
                            roundManager.get ! StartRound(roundCount)
                            roundCount -= 1    
                        } else {
                            if (gameSessionRecord(0) == gameSessionRecord(1)) {
                                thisPlayer ! GameSessionTie(gameSessionRecord(0))
                                thatPlayer.get ! GameSessionTie(gameSessionRecord(1))
                            } else if (gameSessionRecord(0) > gameSessionRecord(1)) {
                                thisPlayer ! GameSessionVictory(gameSessionRecord(0))
                                thatPlayer.get ! GameSessionLost(gameSessionRecord(1))
                            } else if (gameSessionRecord(1) > gameSessionRecord(0)) {
                                thisPlayer ! GameSessionLost(gameSessionRecord(0))
                                thatPlayer.get ! GameSessionVictory(gameSessionRecord(1))
                            }
                            context.self ! RematchInvitation
                            roundCount = 3
                            gameSessionRecord = Array(0, 0)
                        }
                        this
                    case _ => 
                        Behaviors.unhandled
                }
            case RematchInvitation => 
                thisPlayer ! RematchInvitationRequest(thatPlayerName)
                thatPlayer.get ! RematchInvitationRequest(thisPlayerName)
                this
            case RematchInvitationResponse(response) => 
                println("Got a rematch invitation response! " + response.toString())
                rematchIntentionMap = rematchIntentionMap.appended(response)
                if (rematchIntentionMap.size == 2) {
                    
                    println("Got both responses!")
                    if (rematchIntentionMap.contains(Player.InvitationRejected)) { 
                        println("One of the player rejected...")
                        thisPlayer ! MatchmakingFailed
                        thatPlayer.get ! MatchmakingFailed
                        thatPlayer = None 
                        thatPlayerName = ""
                        context.stop(roundManager.get)
                        roundManager = None
                    } else {        
                        println("Both players accepted to rematch...Restarting the game...")
                        roundManager.get ! StartRound(roundCount)
                        roundCount -= 1
                    }
                    rematchIntentionMap = Array()
                }
                this
        }   
    }
}