package com.elementalists

import akka.actor.typed.{ActorRef, ActorSystem, Behavior, PostStop}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.cluster.typed._
import akka.{actor => classic}
import akka.discovery.{Discovery, Lookup, ServiceDiscovery}
import akka.discovery.ServiceDiscovery.Resolved
import akka.actor.typed.scaladsl.adapter._
import com.elementalists.protocol.JsonSerializable
import scalafx.collections.ObservableHashSet
import scalafx.application.Platform
import akka.cluster.ClusterEvent.ReachabilityEvent
import akka.cluster.ClusterEvent.ReachableMember
import akka.cluster.ClusterEvent.UnreachableMember
import akka.cluster.ClusterEvent.MemberEvent
import akka.actor.Address

object RPSClient {

    sealed trait Command extends JsonSerializable

    //internal protocol
    case object start extends Command
    case class StartJoin(name: String) extends Command
    final case object FindTheServer extends Command
    private case class ListingResponse(listing: Receptionist.Listing) extends Command
    private final case class ReachabilityChange(reachabilityEvent: ReachabilityEvent) extends Command
    final case class MemberList(list: Iterable[User]) extends Command
    final case class Joined(list: Iterable[User]) extends Command

    // RPS protocol
    final case class Challenge(from: ActorRef[RPSClient.Command]) extends Command
    final case class Reject() extends Command
    final case class Choice(choice: String) extends Command
    final case class Result(selfChoice: String, opponentChoice: String, gameResult: String) extends Command
    final case class SendChallenge(target: ActorRef[RPSClient.Command]) extends Command
    final case class SendReject(target: ActorRef[RPSClient.Command]) extends Command
    final case class SendResult(target: ActorRef[RPSClient.Command], selfChoice: String, opponentChoice: String, gameResult: String) extends Command

    val members = new ObservableHashSet[User]()

    val unreachables = new ObservableHashSet[Address]()

    unreachables.onChange{(ns, _) =>
        Platform.runLater {
            Client.control.updateList(members.toList.filter(y => ! unreachables.exists (x => x == y.ref.path.address)))
        }
    }

    members.onChange{(ns, _) =>
        Platform.runLater {
            Client.control.updateList(ns.toList.filter(y => ! unreachables.exists (x => x == y.ref.path.address)))
        }  
    }

    var defaultBehavior: Option[Behavior[RPSClient.Command]] = None
    var remoteOpt: Option[ActorRef[RPSServer.Command]] = None 
    var nameOpt: Option[String] = None

    // user challenge states
    var challengeStatus = "None"  // Status on whether user is being challenged
    var challengeDecision = "None" // Status on whether user accepts or rejects challenge

    def messageStarted(): Behavior[RPSClient.Command] = Behaviors.receive[RPSClient.Command] { (context, message) => 
        message match {
            case SendChallenge(target) =>
                challengeStatus = "yes"
                target ! Challenge(context.self)
                Behaviors.same

            case Challenge(from) =>
                // if challenged user already has a challenge, reject the incoming one
                if (challengeStatus != "None") {
                    from ! Reject()    
                } else {
                    // show user the alert to accept or reject challenge
                    Platform.runLater {
                        var opponentName = ""
                        try {
                            opponentName = members.toList.filter(_.ref==from)(0).name
                        } catch {
                            case e: Exception => opponentName = "unknown"
                        }
                        
                        Client.control.challengeAlert(opponentName)
                    }

                    // challenge decision will change to "yes" or "no" after user select
                    while (challengeDecision == "None") { Thread.sleep(1000) }

                    // handle challenge decision
                    if (challengeDecision == "yes") { // user accepts challenge
                        var selection = "None"
                        Platform.runLater {
                            selection = Client.control.startGameAlert()
                        }

                        // selection will change to "rock", "paper", "scissors" or "reject"
                        // after interaction with alert window
                        while (selection == "None") { Thread.sleep(1000) }

                        if (selection == "reject") { // user decided to reject
                            from ! Reject()
                            challengeStatus = "None"
                        } else { // user selected "rock", "paper" or "scissors"
                            from ! Choice(selection)
                        }
                    } else { // user decided to reject
                        from ! Reject()
                        challengeStatus = "None"
                    }
                    // reset challenge decision
                    challengeDecision = "None"
                }
                Behaviors.same

            // controller sends this message which tells the user to send 
            // Reject message to the opponent
            case SendReject(target) =>
                target ! Reject()
                challengeStatus = "None"
                Behaviors.same

            // Reject message means the opponent has rejected the challenge
            // or abandoned partway through
            case Reject() =>
                Platform.runLater {
                    Client.control.rejectAlert()
                    challengeStatus = "None"
                }
                Behaviors.same

            case Choice(choice) =>
                Platform.runLater {
                    Client.control.endGameAlert(choice)
                }
                Behaviors.same

            case SendResult(target, selfChoice, opponentChoice, gameResult) =>
                target ! Result(selfChoice, opponentChoice, gameResult)
                Behaviors.same

            case Result(selfChoice, opponentChoice, gameResult) =>
                Platform.runLater {
                    Client.control.displayResult(selfChoice, opponentChoice, gameResult)
                }
                Behaviors.same

            case MemberList(list: Iterable[User]) =>
                members.clear()
                members ++= list
                Behaviors.same
            
            case _=>
                    Behaviors.unhandled
        }
    }.receiveSignal {
        case (context, PostStop) =>
            for (name <- nameOpt; remote <- remoteOpt) {
                remote ! RPSServer.Leave(name, context.self)
            }
            defaultBehavior.getOrElse(Behaviors.same)
    }

    def apply(): Behavior[RPSClient.Command] =
        Behaviors.setup { context =>
        // (1) a ServiceKey is a unique identifier for this actor
        // var remoteOpt:Option[ActorRef[RPSServer.Command]] = None 

        Upnp.bindPort(context)
          
        val reachabilityAdapter = context.messageAdapter(ReachabilityChange)
        Cluster(context.system).subscriptions ! Subscribe(reachabilityAdapter, classOf[ReachabilityEvent])

        // (2) create an ActorRef that can be thought of as a Receptionist
        // Listing “adapter.” this will be used in the next line of code.
        // the RPSClient.ListingResponse(listing) part of the code tells the
        // Receptionist how to get back in touch with us after we contact
        // it in Step 4 below.
        // also, this line of code is long, so i wrapped it onto two lines
        val listingAdapter: ActorRef[Receptionist.Listing] =
            context.messageAdapter { listing =>
                println(s"listingAdapter:listing: ${listing.toString}")
                RPSClient.ListingResponse(listing)
            }

        //(3) send a message to the Receptionist saying that we want
        // to subscribe to events related to ServerHello.ServerKey, which
        // represents the RPSClient actor.
        context.system.receptionist ! Receptionist.Subscribe(RPSServer.ServerKey, listingAdapter)
        //context.actorOf(RemoteRouterConfig(RoundRobinPool(5), addresses).props(Props[RPSClient.TestActorClassic]()), "testA")
        defaultBehavior = Some(Behaviors.receiveMessage { message =>
            message match {
                case RPSClient.start =>
                    context.self ! FindTheServer 
                    Behaviors.same

                // (4) send a Find message to the Receptionist, saying
                // that we want to find any/all listings related to
                // Mouth.MouthKey, i.e., the Mouth actor.
                case FindTheServer =>
                    println(s"Client Hello: got a FindTheServer message")
                    context.system.receptionist !
                        Receptionist.Find(RPSServer.ServerKey, listingAdapter)
                    Behaviors.same

                // (5) after Step 4, the Receptionist sends us this
                // ListingResponse message. the `listings` variable is
                // a Set of ActorRef of type ServerHello.Command, which
                // you can interpret as “a set of ServerHello ActorRefs.” for
                // this example i know that there will be at most one
                // ServerHello actor, but in other cases there may be more
                // than one actor in this set.
                case ListingResponse(RPSServer.ServerKey.Listing(listings)) =>
                    val xs: Set[ActorRef[RPSServer.Command]] = listings
                    for (x <- xs) {
                        remoteOpt = Some(x)
                    }
                    Behaviors.same

                case StartJoin(name) =>
                    nameOpt = Option(name)
                    remoteOpt.map ( _ ! RPSServer.JoinChat(name, context.self))
                    Behaviors.same

                case RPSClient.Joined(x) =>
                    Platform.runLater {
                        Client.control.displayStatus("Connected")
                    }
                    members.clear()
                    members ++= x
                    messageStarted()

                case ReachabilityChange(reachabilityEvent) =>
                    reachabilityEvent match {
                        case UnreachableMember(member) =>
                            unreachables += member.address
                            Behaviors.same
                        case ReachableMember(member) =>
                            unreachables -= member.address
                            Behaviors.same
                    }

                case _=>
                    Behaviors.unhandled
            }
        })
        defaultBehavior.get
    }
}
