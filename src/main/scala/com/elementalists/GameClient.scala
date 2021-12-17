package com.elementalists

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.receptionist.Receptionist
import _root_.com.typesafe.config.ConfigFactory
import scala.io.StdIn
import scala.util.Try
import SessionManager.{RejectUserName, MembersListing, NewConnectionAcknowledgement}

// Client App Runner 
object ClientMain extends App {
    // Load the default configuration from application.conf
    val defaultConfig = ConfigFactory.load()
    // Set the port that the client app should connect to 
    val clientConfig = ConfigFactory.parseString("akka.remote.artery.canonical.port=0").withFallback(defaultConfig)

    val system = ActorSystem(GameClient(), "EarthFireWater", clientConfig)
    // Look up for the server actor so that the client actor can fire a message to it 
    system ! GameClient.ServerLookup
}

object GameClient {
    trait Command
    // Response message from the server app 
    final case class ServerResponse(message: String, serverRef: ActorRef[SessionManager.SessionRequests]) extends Command
    // Response from the Receptionist after requesting for remote actor reference 
    private case class ListingResponse(listing: Receptionist.Listing) extends Command
    final case object ServerLookup extends Command
    final case object ClientNameRegistration extends Command
    final case object BecomeIdle extends Command
    final case class ReceivedGameInvitation(fromPlayerName: String) extends Command
    final case class ReceivedRematchInvitation(opponentName: String) extends Command
    final case class MakeElementSelection(roundCount: Int) extends Command
    final case class RoundLost(score: Int) extends Command
    final case class RoundVictory(score: Int) extends Command 
    final case class RoundTie(score: Int) extends Command
    final case class MatchLost(score: Int) extends Command
    final case class MatchVictory(score: Int) extends Command
    final case class MatchTie(score: Int) extends Command

    // Factory method for the client actor behavior 
    def apply(): Behavior[Command] = Behaviors.setup { context => 
        new GameClient(context) }
}

class GameClient(context: ActorContext[GameClient.Command]) extends AbstractBehavior(context) {
    import GameClient._

    private var player: Option[ActorRef[GameSessionManager.GameSessionResponses]] = None 
    private var playerName: String = ""
    private var sessionServer: Option[ActorRef[SessionManager.SessionRequests]] = None
    private var gameSessionServer: Option[ActorRef[GameSessionManager.GameSessionCommands]] = None 
    private var onlineMembers: Map[String, ActorRef[GameSessionManager.GameSessionResponses]] = Map()

    private var idle: Boolean = true

    override def onMessage(msg: Command): Behavior[Command] = {
        msg match {
            case ServerLookup => 
                println(Console.CYAN + "Looking up for the game server." + Console.RESET)
                val adapter = context.messageAdapter[Receptionist.Listing](ListingResponse)
                context.system.receptionist ! Receptionist.Subscribe(GameServer.ServerKey, adapter)
                Behaviors.same
            case ServerResponse(message, server) =>
                println(Console.CYAN + "Got the session server from the server: " + message + Console.RESET)
                println(Console.CYAN + "Session server path: " + server.path + Console.RESET)
                sessionServer = Some(server)
                context.self ! ClientNameRegistration
                Behaviors.same
            case ListingResponse(GameServer.ServerKey.Listing(listing)) =>
                println(Console.CYAN + "Receptionist responded!" + Console.RESET)
                listing.foreach { serverRef =>
                    println(Console.CYAN + "Sending a session server lookup request to the server." + Console.RESET)
                    serverRef ! GameServer.SessionServerLookup(context.self)
                } 
                Behaviors.same
            case ClientNameRegistration => 
                val clientInput = readClientName()
                println(Console.CYAN + "Checking whether your name is available..." + Console.RESET)
                sessionServer.get ! SessionManager.VerifyUserName(clientInput.replace(" ", ""), context.self)
                Behaviors.same
            case RejectUserName => 
                println(Console.CYAN + "Sorry, the name is taken by other players." + Console.RESET)
                context.self ! ClientNameRegistration
                Behaviors.same
            case MembersListing(players) => 
                println(Console.CYAN + "Member list updated." + Console.RESET)
                onlineMembers = players.removed(playerName)
                if (idle && onlineMembers.size >= 1) { showAvailableOpponents() }
                Behaviors.same
            case NewConnectionAcknowledgement(player, playerName, gameSessionManager, players) => 
                println(Console.CYAN + s"Successfully registered with name $playerName!" + Console.RESET)
                this.player = Some(player)
                this.playerName = playerName
                gameSessionServer = Some(gameSessionManager)
                onlineMembers = players.removed(playerName)
                displayGameMenu()
                Behaviors.same
            case ReceivedGameInvitation(from) =>
                onGameInvitation(Console.CYAN + s"Received game invitation from $from!\nDo you want to play a game with him/her? (Y/N)" + Console.RESET)
                Behaviors.same
            case ReceivedRematchInvitation(opponentName) => 
                onRematchInvitation(Console.CYAN + s"Do you want to rematch with $opponentName? (Y/N)" + Console.RESET)
                Behaviors.same
            case BecomeIdle =>
                println(Console.CYAN + "It seems like your opponent does not want to play the game..." + Console.RESET)
                idle = true
                displayGameMenu()
                Behaviors.same
            case MakeElementSelection(count) => 
                onElementSelectionRequest(count)
                Behaviors.same
            case RoundLost(score) => 
                onRoundLost(score)
                Behaviors.same
            case RoundVictory(score) =>
                onRoundVictory(score)
                Behaviors.same
            case RoundTie(score) => 
                onRoundTie(score)
                Behaviors.same
            case MatchLost(score) => 
                onMatchLost(score)
                Behaviors.same
            case MatchVictory(score) => 
                onMatchVictory(score)
                Behaviors.same 
            case MatchTie(score) => 
                onMatchTie(score)
                Behaviors.same
        }
    }

    private def onMatchLost(score: Int) = {
        println(Console.RED + Console.BOLD + s"You have lost the game. Total score earned in this game: $score" + Console.RESET)
    }

    private def onMatchVictory(score: Int) = {
        println(Console.GREEN + Console.BOLD + s"You have won the game! Total score earned in this game: $score" + Console.RESET)
    }

    private def onMatchTie(score: Int) = {
        println(Console.CYAN + Console.BOLD + s"No one wins the game!. Total score earned in this game: $score" + Console.RESET)
    }

    private def onRoundLost(score: Int) = {
        println(Console.RED + Console.BOLD + s"You lost the last round. Your current score is $score." + Console.RESET)
    }

    private def onRoundVictory(score: Int) = {
        println(Console.GREEN + Console.BOLD + s"You won the last round! Congratulations! Your current score is $score." + Console.RESET)
    }

    private def onRoundTie(score: Int) = {
        println(Console.CYAN + Console.BOLD + s"It was a tie! Your current score is: $score." + Console.RESET)
    }

    private def readClientName(): String = {
        print(Console.CYAN + Console.BOLD + "Please register your name: " + Console.RESET)
        StdIn.readLine()
    }

    private def displayGameMenu(): Unit = {
        println(Console.CYAN + Console.BOLD + "Welcome to the Elementalists!" + Console.RESET)
        
        if (onlineMembers.size > 0) { 
            showAvailableOpponents() 
        } else {
            println(Console.CYAN + Console.BOLD + "Looks like there are no people online... " + Console.RESET)
        }
    }

    private def onGameInvitation(displayMessage: String) = {
        println(displayMessage)
        val selection = StdIn.readLine()
        selection.toUpperCase match {
            case "Y" => 
                idle = false
                player.get ! Player.ClientGameInvitationResponse(true) 
            case _ => 
                player.get ! Player.ClientGameInvitationResponse(false)
        }
    }

    private def onRematchInvitation(displayMessage: String) = {
        println(displayMessage)
        val selection = StdIn.readLine()
        selection.toUpperCase match {
            case "Y" => player.get ! Player.ClientRematchResponse(true)
            case _ => player.get ! Player.ClientRematchResponse(false) 
        }
    }

    private def onElementSelectionRequest(count: Int) = {
        val validResponse  = List("1", "2", "3")
        var selection = ""
        while (!validResponse.contains(selection)) {
            println(Console.CYAN + Console.BOLD + s"Remaining rounds: $count\nPlease make a selection:\n1. Earth\n2. Fire\n3. Water" + Console.RESET)
            selection = StdIn.readLine()
            if (!validResponse.contains(selection)){
                println(Console.RED + Console.BOLD + "Invalid Choice, Try Again\n" + Console.RESET)
            }
        }
        player.get ! Player.UserElementSelection(selection)
    }

    private def showAvailableOpponents(): Unit = {
        val memberArray = onlineMembers.keys.toArray
        var loop = true
        while(loop){
            println(Console.CYAN + Console.BOLD + "Online Members" + Console.RESET)
            for (m <- memberArray) {
                println(Console.CYAN + s"${memberArray.indexOf(m)}.$m" + Console.RESET)
            }
            println("Enter '-1' to not challenge anyone - check for invitation")
            val choice = StdIn.readLine("Enter your choice: ")

            if (Try(choice.toInt).isSuccess){
                val index = choice.toInt
                if (index >= -1 && index < memberArray.length){
                    loop = false
                    if (index != -1) {
                        val selectedName = memberArray(index)
                        gameSessionServer.get ! GameSessionManager.GamePartnerSelection(onlineMembers(selectedName), selectedName)
                        idle = false
                        println(Console.CYAN + Console.BOLD + "Invitation sent! Waiting for the player to accept..." + Console.RESET)
                    }
                } else{
                    println(Console.RED + Console.BOLD + "Invalid Choice, Try Again\n" + Console.RESET)
                }
            } else{
                 println(Console.RED + Console.BOLD + "Invalid Choice, Try Again\n" + Console.RESET)
            }
        }
    }
}