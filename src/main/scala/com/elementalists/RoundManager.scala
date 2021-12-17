package com.elementalists

import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.Behavior
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import akka.util.Timeout
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import GameSessionManager.{GameSessionResponses, GameSessionCommands}

/* This actor class takes care of the game logic and determines a round winner, then it reports back to the game session manager. */ 
object RoundManager {
    // Logic specific to Earth-Fire-Water

    sealed trait EarthFireWaterResults
    final case object Lose extends EarthFireWaterResults
    final case object Victory extends EarthFireWaterResults
    final case object Tie extends EarthFireWaterResults

    sealed trait EarthFireWaterCommands
    final case object Earth extends EarthFireWaterCommands
    final case object Fire extends EarthFireWaterCommands
    final case object Water extends EarthFireWaterCommands
    final case object NotSelected extends EarthFireWaterCommands

    private def selectRPSWinner(first: EarthFireWaterCommands, second: EarthFireWaterCommands): EarthFireWaterResults = {
        first match {
            case Fire => 
                second match {
                    case Earth | NotSelected => 
                        Victory
                    case Fire => 
                        Tie
                    case Water => 
                        Lose
                }
            case Earth => 
                second match {
                    case Fire => 
                        Lose 
                    case Water | NotSelected => 
                        Victory 
                    case Earth => 
                        Tie
                }
            case Water => 
                second match {
                    case Fire | NotSelected => 
                        Victory
                    case Water => 
                        Tie
                    case Earth => 
                        Lose 
                }
            case NotSelected => 
                Lose 
        }
    }

    sealed trait RoundManagerCommands 
    sealed trait RoundManagerResponses extends GameSessionResponses
    // RPS Selection fired by the player 
    final case class EarthFireWaterSelection(fromPlayer: ActorRef[GameSessionResponses], selection: EarthFireWaterCommands) extends RoundManagerCommands
    // Request fired to the player to request them to make a selection
    final case class EarthFireWaterSelectionRequest(roundManager: ActorRef[RoundManagerCommands], remainingRound: Int) extends RoundManagerResponses

    final case object AllPlayersSelected extends RoundManagerCommands
    final case class StartRound(remainingRound: Int) extends RoundManagerCommands

    // When the round winner and loser are both identified
    final case class GameStatusUpdate(roundWinner: ActorRef[GameSessionResponses], roundLoser: ActorRef[GameSessionResponses], tie: Boolean) extends RoundManagerResponses
    
    def apply(gameSessionManager: ActorRef[RoundManagerResponses], players: Seq[ActorRef[GameSessionResponses]]): Behavior[RoundManagerCommands] = {
        Behaviors.setup { context => new RoundManager(context, gameSessionManager, players)}
    }
}

class RoundManager(context: ActorContext[RoundManager.RoundManagerCommands], gameSessionManager: ActorRef[RoundManager.RoundManagerResponses], players: Seq[ActorRef[GameSessionResponses]]) extends AbstractBehavior(context) {
    import RoundManager._

    // Earth-Fire-Water game states
    private var playerSelectionMap: Map[String, EarthFireWaterCommands] = Map()
    private val winnerSelectionRule: Map[String, EarthFireWaterCommands] => EarthFireWaterResults = (selectionMap) => {
        selectRPSWinner(selectionMap.head._2, selectionMap.last._2)
    }
    
    val thisPlayer = players.head
    val thatPlayer = players.last

    override def onMessage(msg: RoundManagerCommands): Behavior[RoundManagerCommands] = {
        msg match {
            // When one of the player has made a selection
            case EarthFireWaterSelection(player, selection) =>            
                playerSelectionMap += (player.path.toString() -> selection)
                // If both of the players have already responded
                if (!playerSelectionMap.valuesIterator.contains(NotSelected)) {
                    context.self ! AllPlayersSelected
                }
                this
            case AllPlayersSelected => 
                val result = winnerSelectionRule(playerSelectionMap)
                result match {
                    case Lose => 
                        gameSessionManager ! GameStatusUpdate(thatPlayer, thisPlayer, false)
                    case Victory => 
                        gameSessionManager ! GameStatusUpdate(thisPlayer, thatPlayer, false)
                    case Tie => 
                        gameSessionManager ! GameStatusUpdate(thisPlayer, thatPlayer, true)
                }
                this
            case StartRound(remainingRound) => 
                playerSelectionMap += (thisPlayer.path.toString() -> NotSelected)
                playerSelectionMap += (thatPlayer.path.toString() -> NotSelected)
                thisPlayer ! EarthFireWaterSelectionRequest(context.self, remainingRound)
                thatPlayer ! EarthFireWaterSelectionRequest(context.self, remainingRound)
                this 
        }
    }
}



