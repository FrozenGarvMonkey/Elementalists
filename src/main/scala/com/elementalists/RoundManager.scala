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
    // Logic specific to Rock-Paper-Scissors

    sealed trait RockPaperScissorsResults
    final case object Lose extends RockPaperScissorsResults
    final case object Victory extends RockPaperScissorsResults
    final case object Tie extends RockPaperScissorsResults

    sealed trait RockPaperScissorsCommands
    final case object Rock extends RockPaperScissorsCommands
    final case object Paper extends RockPaperScissorsCommands
    final case object Scissors extends RockPaperScissorsCommands
    final case object NotSelected extends RockPaperScissorsCommands

    private def selectRPSWinner(first: RockPaperScissorsCommands, second: RockPaperScissorsCommands): RockPaperScissorsResults = {
        first match {
            case Paper => 
                second match {
                    case Rock | NotSelected => 
                        Victory
                    case Paper => 
                        Tie
                    case Scissors => 
                        Lose
                }
            case Rock => 
                second match {
                    case Paper => 
                        Lose 
                    case Scissors | NotSelected => 
                        Victory 
                    case Rock => 
                        Tie
                }
            case Scissors => 
                second match {
                    case Paper | NotSelected => 
                        Victory
                    case Scissors => 
                        Tie
                    case Rock => 
                        Lose 
                }
            case NotSelected => 
                Lose 
        }
    }

    sealed trait RoundManagerCommands 
    sealed trait RoundManagerResponses extends GameSessionResponses
    // RPS Selection fired by the player 
    final case class RockPaperScissorsSelection(fromPlayer: ActorRef[GameSessionResponses], selection: RockPaperScissorsCommands) extends RoundManagerCommands
    // Request fired to the player to request them to make a selection
    final case class RockPaperScissorsSelectionRequest(roundManager: ActorRef[RoundManagerCommands], remainingRound: Int) extends RoundManagerResponses

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

    // Rock-paper-scissors game states
    private var playerSelectionMap: Map[String, RockPaperScissorsCommands] = Map()
    private val winnerSelectionRule: Map[String, RockPaperScissorsCommands] => RockPaperScissorsResults = (selectionMap) => {
        selectRPSWinner(selectionMap.head._2, selectionMap.last._2)
    }
    
    val thisPlayer = players.head
    val thatPlayer = players.last

    override def onMessage(msg: RoundManagerCommands): Behavior[RoundManagerCommands] = {
        msg match {
            // When one of the player has made a selection
            case RockPaperScissorsSelection(player, selection) =>            
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
                thisPlayer ! RockPaperScissorsSelectionRequest(context.self, remainingRound)
                thatPlayer ! RockPaperScissorsSelectionRequest(context.self, remainingRound)
                this 
        }
    }
}



