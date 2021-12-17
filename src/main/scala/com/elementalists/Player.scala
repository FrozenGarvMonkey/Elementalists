package com.elementalists

import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import GameSessionManager._
import RoundManager.{ElementSelectionRequest, ElementSelection, EarthFireWaterCommands, Earth, Fire, Water, NotSelected}
import _root_.com.elementalists.GameSessionManager.GameSessionCommands

/* Player class that represents a client after successful name registration. It holds the accumulated scores of each player. */ 
object Player {
    // Messages used to accept or reject a game invitation
    sealed trait PlayerResponses extends GameSessionCommands
    final case object InvitationAccepted extends PlayerResponses 
    final case object InvitationRejected extends PlayerResponses 
    final case object NotResponded extends PlayerResponses

    sealed trait PlayerRequests extends GameSessionResponses
    final case class ClientGameInvitationResponse(agreed: Boolean) extends PlayerRequests
    final case class UserElementSelection(selection: String) extends PlayerRequests
    final case class ClientRematchResponse(agreed: Boolean) extends PlayerRequests

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
                Behaviors.same
            case UnbindGameSession => 
                gameSession = None
                clientRef ! GameClient.BecomeIdle
                Behaviors.same
            case ChallengeRequest(fromPlayerName) =>
                clientRef ! GameClient.ReceivedGameInvitation(fromPlayerName)
                Behaviors.same
            case ClientGameInvitationResponse(agreed) => 
                if (agreed) { gameSession.get ! InvitationAccepted } else {
                    gameSession.get ! InvitationRejected
                }
                Behaviors.same 
            case ClientRematchResponse(agreed) => 
                if (agreed) { gameSession.get ! RematchResponse(InvitationAccepted) } else { gameSession.get ! RematchResponse(InvitationRejected) }
                Behaviors.same 
            case ElementSelectionRequest(roundManager, roundCount) => 
                this.roundManager = Some(roundManager)
                clientRef ! GameClient.MakeElementSelection(roundCount)
                Behaviors.same
            case UserElementSelection(selection) => 
                var EFSSelection: RoundManager.EarthFireWaterCommands = RoundManager.NotSelected
                selection match {
                    case "1" => EFSSelection = Earth
                    case "2" => EFSSelection = Fire
                    case "3" => EFSSelection = Water
                    case _ => EFSSelection = NotSelected
                }
                roundManager.get ! ElementSelection(context.self, EFSSelection)
                Behaviors.same
            case UpdateSumScore(change, tie) => 
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
                Behaviors.same
            case MatchLost(totalScore) => 
                clientRef ! GameClient.MatchLost(totalScore)
                Behaviors.same
            case MatchWin(totalScore) => 
                clientRef ! GameClient.MatchVictory(totalScore)
                Behaviors.same 
            case MatchTie(totalScore) => 
                clientRef ! GameClient.MatchTie(totalScore)
                Behaviors.same
            case RematchRequest(opponentName) => 
                clientRef ! GameClient.ReceivedRematchInvitation(opponentName)
                Behaviors.same
            case MatchmakingFailed =>
                clientRef ! GameClient.BecomeIdle
                roundManager = None
                Behaviors.same
        }
    }
}