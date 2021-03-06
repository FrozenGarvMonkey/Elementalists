package com.elementalists

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.cluster.typed._
import _root_.com.typesafe.config.ConfigFactory

// Server App Runner
object ServerMain extends App {
    val defaultConfig = ConfigFactory.load()
    // Set the port that the server should connect to 
    val serverConfig = ConfigFactory.parseString("akka.remote.artery.canonical.port=5020").withFallback(defaultConfig)
    val serverSystem = ActorSystem(GameServer(), "EarthFireWater", serverConfig)
}

object GameServer {
    sealed trait Command
    // Client request message that this server can process 
    final case class SessionServerLookup(clientRef: ActorRef[GameClient.ServerResponse]) extends Command
    
    // Server key to register in the receptionist service so that the remote client can get the actor for the server 
    val ServerKey: ServiceKey[Command] = ServiceKey("EarthFireWater")

    def apply(): Behavior[Command] = {
        Behaviors.setup { context => 
            context.system.receptionist ! Receptionist.Register(ServerKey, context.self)
            new GameServer(context) 
        }
    }
    
    private var session: Option[ActorRef[SessionManager.SessionRequests]] = None
}

class GameServer(context: ActorContext[GameServer.Command]) extends AbstractBehavior(context) {
    import GameServer._
    override def onMessage(msg: Command): Behavior[Command] = {
        msg match {
            case SessionServerLookup(ref) =>
                println(Console.CYAN + "Received a message from client.\nSent back a message: Hello Client! " + Console.RESET)
                ref ! GameClient.ServerResponse("Hello Client!", sessionManagerReference)
                Behaviors.same
        }
    }

    // Singleton pattern for session manager 
    private def sessionManagerReference: ActorRef[SessionManager.SessionRequests] = {
        session match {
            case Some(manager) => 
                manager
            case None => 
                val manager = context.spawn(SessionManager(), "Session-Manager")
                session = Some(manager)
                manager
        }
    }
}