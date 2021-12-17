package com.elementalists

import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import GameSessionManager._
import RoundManager.{EarthFireWaterSelectionRequest, EarthFireWaterSelection, EarthFireWaterCommands, Earth, Fire, Water, NotSelected}
import _root_.com.elementalists.GameSessionManager.GameSessionCommands

/* Player class that represents a client after succesful name registration. It holds the accumulated scores of each player. */ 
object Player {
    // Messages used to accept or reject a game invitation
    sealed trait PlayerResponses extends GameSessionCommands
    final case object InvitationAccepted extends PlayerResponses 
    final case object InvitationRejected extends PlayerResponses 
    final case object NotResponded extends PlayerResponses

    sealed trait PlayerRequests extends GameSessionResponses
    final case class ClientGameInvitationResponse(agreed: Boolean) extends PlayerRequests
    final case class ClientRPSSelection(selection: String) extends PlayerRequests
    final case class ClientRematchInvitationResponse(agreed: Boolean) extends PlayerRequests

    def apply(name: String, client: ActorRef[GameClient.Command]): Behavior[GameSessionResponses] = {
        Behaviors.setup { context => 
            new Player(context, name, client)
        }
    }
}

class Player(context: ActorContext[GameSessionResponses], val name: String, val clientRef: ActorRef[GameClient.Command]) extends AbstractBehavior(context) {
    import Player._
    
    private var accumulatedScores = 0
    private var gameSession: Option[ActorRef[GameSessionManager.GameSessionCommands]] = None
    private var roundManager: Option[ActorRef[RoundManager.RoundManagerCommands]] = None

    override def onMessage(msg: GameSessionResponses): Behavior[GameSessionResponses] = { 
        msg match {
            case BindGameSession(session) =>
                gameSession = Some(session)
                this
            case UnbindGameSession => 
                gameSession = None
                this 
            case GameInvitationRequest(fromPlayerName) =>
                clientRef ! GameClient.ReceivedGameInvitation(fromPlayerName)
                this
            case ClientGameInvitationResponse(agreed) => 
                if (agreed) { gameSession.get ! InvitationAccepted } else {
                    gameSession.get ! InvitationRejected
                }
                this 
            case ClientRematchInvitationResponse(agreed) => 
                if (agreed) { gameSession.get ! RematchInvitationResponse(InvitationAccepted) } else { gameSession.get ! RematchInvitationResponse(InvitationRejected) }
                this 
            case EarthFireWaterSelectionRequest(roundManager, roundCount) => 
                this.roundManager = Some(roundManager)
                clientRef ! GameClient.MakeRPSSelection(roundCount)
                this
            case ClientRPSSelection(selection) => 
                var rpsSelection: RoundManager.EarthFireWaterCommands = RoundManager.NotSelected
                selection match {
                    case "1" => rpsSelection = Earth
                    case "2" => rpsSelection = Fire
                    case "3" => rpsSelection = Water
                    case _ => rpsSelection = NotSelected
                }
                roundManager.get ! EarthFireWaterSelection(context.self, rpsSelection)
                this
            case AccumulatedScoresUpdate(change, tie) => 
                if (tie) {
                    clientRef ! GameClient.RoundTie(accumulatedScores)
                } else {
                    if (accumulatedScores + change >= 0) {
                        accumulatedScores +=  change
                    } else {
                        accumulatedScores = 0
                    }
                    if (change == 1) { 
                        clientRef ! GameClient.RoundVictory(accumulatedScores)
                    }
                    else {
                        clientRef ! GameClient.RoundLost(accumulatedScores)
                    }
                }
                this
            case GameSessionLost(totalScore) => 
                clientRef ! GameClient.GameLost(totalScore)
                this 
            case GameSessionVictory(totalScore) => 
                clientRef ! GameClient.GameVictory(totalScore)
                this 
            case GameSessionTie(totalScore) => 
                clientRef ! GameClient.GameTie(totalScore)
                this
            case RematchInvitationRequest(opponentName) => 
                clientRef ! GameClient.ReceivedRematchInvitation(opponentName)
                this
            case MatchmakingFailed =>
                clientRef ! GameClient.BecomeIdle
                roundManager = None
                this
        }
    }
}