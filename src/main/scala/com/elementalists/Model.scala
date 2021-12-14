package com.elementalists
import com.elementalists.protocol.JsonSerializable
import akka.actor.typed.ActorRef

case class User(name: String, ref: ActorRef[RPSClient.Command]) extends JsonSerializable {
    override def toString: String = {
        name
    }
}
