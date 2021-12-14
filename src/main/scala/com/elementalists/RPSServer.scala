package com.elementalists

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.receptionist.{Receptionist,ServiceKey}
import akka.actor.typed.scaladsl.adapter._
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import akka.discovery.Discovery
import akka.cluster.typed._
import com.typesafe.config.ConfigFactory
import com.elementalists.protocol.JsonSerializable
import scalafx.collections.ObservableHashSet


object RPSServer {
    sealed trait Command extends JsonSerializable

    // chat protocol 
    case class JoinChat(name: String, from: ActorRef[RPSClient.Command]) extends Command
    case class Leave(name: String, from: ActorRef[RPSClient.Command]) extends Command

    val ServerKey: ServiceKey[RPSServer.Command] = ServiceKey("RPSServer")
    val members = new ObservableHashSet[User]()

    members.onChange{(ns, _) =>
        for(member <- ns){
            member.ref ! RPSClient.MemberList(members.toList)
        }
    }

    def apply(): Behavior[RPSServer.Command] = Behaviors.setup { context =>

        context.system.receptionist ! Receptionist.Register(ServerKey, context.self)
        
        Upnp.bindPort(context)

        Behaviors.receiveMessage { message =>
            message match {
                case JoinChat(name, from) =>
                    members += User(name, from)
                    from ! RPSClient.Joined(members.toList)
                    Behaviors.same
                case Leave(name, from) => 
                    members -= User(name, from)
                    Behaviors.same
            }
        }
    }
}

object Server extends App {
    val config = ConfigFactory.load()
    val mainSystem = akka.actor.ActorSystem("HelloSystem", MyConfiguration.askDevConfig().withFallback(config))
    val typedSystem: ActorSystem[Nothing] = mainSystem.toTyped
    val cluster = Cluster(typedSystem)
    cluster.manager ! Join(cluster.selfMember.address)
    AkkaManagement(mainSystem).start()
    //val serviceDiscovery = Discovery(mainSystem).discovery
    ClusterBootstrap(mainSystem).start() 
    //val greeterMain: ActorSystem[RPSServer.Command] = ActorSystem(RPSServer(), "HelloSystem")
    mainSystem.spawn(RPSServer(), "RPSServer")
}
