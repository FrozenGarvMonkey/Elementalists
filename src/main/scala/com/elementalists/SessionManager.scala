package com.elementalists

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.receptionist.Receptionist
import _root_.com.elementalists.GameSessionManager.GameSessionResponses

/* This actor class is the first point of contact when the client has connected to the server. 
    It checks whether a name is taken and register a player if it's not. */
object SessionManager {
    sealed trait SessionRequests
    final case class VerifyUserName(name: String, actorRef: ActorRef[GameClient.Command]) extends SessionRequests
    final case class PlayerDisconnected(name: String, clientRef: ActorRef[SessionResponses]) extends SessionRequests
    private final case class PlayerCreated(clientRef: ActorRef[SessionResponses]) extends SessionRequests

    sealed trait SessionResponses extends GameClient.Command
    final case object RejectUserName extends SessionResponses
    final case class NewConnectionAcknowledgement(player: ActorRef[GameSessionResponses], playerName: String, gameSessionManager: ActorRef[GameSessionManager.GameSessionCommands], onlineMembers: Map[String, ActorRef[GameSessionManager.GameSessionResponses]]) extends SessionResponses
    final case class MembersListing(members: Map[String, ActorRef[GameSessionManager.GameSessionResponses]]) extends SessionResponses

    def apply(): Behavior[SessionRequests] = {
        Behaviors.setup { context => 
            new SessionManager(context)
        }
    }
}

class SessionManager(context: ActorContext[SessionManager.SessionRequests]) extends AbstractBehavior(context) {
    import SessionManager._

    private var clientNameToPlayerRef: Map[String, ActorRef[GameSessionManager.GameSessionResponses]] = Map()
    private var onlineClients: Set[ActorRef[SessionResponses]] = Set()
    
    override def onMessage(msg: SessionRequests): Behavior[SessionRequests] = {
        msg match {
            case VerifyUserName(name, actorRef) => 
                if (clientNameToPlayerRef.contains(name)) {
                    actorRef ! RejectUserName
                } else {
                    val player = context.spawn(Player(name, actorRef), s"Player-$name")
                    val gameSessionManager = context.spawn(GameSessionManager(player, name), s"Game-Session-Manager-$name")
                    clientNameToPlayerRef += (name -> player)
                    onlineClients += actorRef
                    actorRef ! NewConnectionAcknowledgement(player, name, gameSessionManager, clientNameToPlayerRef)
                    context.self ! PlayerCreated(actorRef)
                }
                Behaviors.same 
            case PlayerCreated(newClient) =>
                println(Console.CYAN + "New player joined the game!" + Console.RESET)
                onlineClients.foreach { client => 
                    println(Console.CYAN + s"Firing updated member listing to ${client.path.name}" + Console.RESET)
                    client ! MembersListing(clientNameToPlayerRef) }
                Behaviors.same
            case PlayerDisconnected(name, clientRef) => 
                clientNameToPlayerRef = clientNameToPlayerRef.removed(name)
                onlineClients -= clientRef
                onlineClients.foreach { _ ! MembersListing(clientNameToPlayerRef)}
                Behaviors.same
        }
    }
}