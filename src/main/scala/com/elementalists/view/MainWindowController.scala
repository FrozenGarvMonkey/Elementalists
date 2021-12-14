package com.elementalists.view
import akka.actor.typed.ActorRef
import scalafxml.core.macros.sfxml
import scalafx.event.ActionEvent
import scalafx.scene.control.{Alert, ButtonType, DialogPane, Label, ListView, TextField, Button}
import scalafx.scene.control.Alert.AlertType
import com.elementalists.RPSClient
import com.elementalists.RPSClient.getClass
import com.elementalists.User
import com.elementalists.Client
import com.elementalists.Client.stage
import javafx.fxml

import scalafx.collections.ObservableBuffer
import scalafx.Includes._

@sfxml
class MainWindowController(private val txtName: TextField, private val lblStatus: Label, 
private val choiceDisplay: Label, private val listUser: ListView[User], 
private val txtMessage: TextField, private val join: Button) {
    var RPSClientRef: Option[ActorRef[RPSClient.Command]] = None

    def handleJoin(action: ActionEvent): Unit = {
        if(txtName != null) {
            RPSClientRef map (_ ! RPSClient.StartJoin(txtName.text()))
        }
          
    }

    def displayStatus(text: String): Unit = {
        // called when the user receives the Joined message from the server

        // updates status to the passed text ("Connected")
        lblStatus.text = text
        
        // makes the name TextField uneditable but remain clearly visible
        txtName.editable = false
        txtName.mouseTransparent = true

        // makes the Join button uneditable
        join.disable = true
    }

    def displayChoice(text: String): Unit = {
        choiceDisplay.text = text.toUpperCase
    }

    def updateList(x: Iterable[User]): Unit ={
        listUser.items = new ObservableBuffer[User]() ++= x
    }

    def rejectAlert(): Unit = {
        new Alert(AlertType.Information) {
            // initOwner keeps the pop-up window over the client window 
            // and preserves graphics (icon)
            initOwner(stage)
            title = "Username: " + txtName.getText()
            headerText = "Rejected!"
            contentText = "The user you have selected is currently in-game or has rejected your challenge."
        }.showAndWait()
        RPSClient.challengeStatus = "None"
    }

    def challengeAlert(opponentName: String) : Unit = {

        // Create and show confirmation alert
        val alert = new Alert(AlertType.Confirmation) {
            initOwner(stage)
            title = "Username: " + txtName.getText()
            headerText = s"You have received a challenge from ${opponentName.toUpperCase}."
            contentText = "Do you wish to accept this challenge?"
        }

        val result = alert.showAndWait()
        // React to user's selectioon
        result match {
            case Some(ButtonType.OK) => RPSClient.challengeDecision = "yes"
            case _ => RPSClient.challengeDecision = "no"
        }
    }

    def handleChallenge(action: ActionEvent): Unit = {
        if (listUser.selectionModel().selectedIndex.value >= 0) {
            Client.userRef ! RPSClient.SendChallenge(listUser.selectionModel().selectedItem.value.ref)
        }
    }

    def startGameAlert(): String ={
        val ButtonTypeRock = new ButtonType("Rock")
        val ButtonTypePaper = new ButtonType("Paper")
        val ButtonTypeScissors = new ButtonType("Scissors")

        val alert = new Alert(AlertType.Confirmation) {
            initOwner(stage)
            title = "Username: " + txtName.getText()
            headerText = "You have accepted the challenge."
            contentText = "Choose your option: "
            buttonTypes = Seq(ButtonTypeRock, ButtonTypePaper, ButtonTypeScissors, ButtonType.Cancel)
        }

        val result = alert.showAndWait()

        result match {
            case Some(ButtonTypeRock) => 
                displayChoice("rock")
                return "rock"

            case Some(ButtonTypePaper) => 
                displayChoice("paper")
                return "paper"

            case Some(ButtonTypeScissors) => 
                displayChoice("scissors")
                return "scissors"

            case _ => 
                return "reject"
        }
    }

    def endGameAlert(opponentChoice: String): Unit = {
        var selfChoice = new String
        var gameResult = new String
        var opponentResult = new String
        val ButtonTypeRock = new ButtonType("Rock")
        val ButtonTypePaper = new ButtonType("Paper")
        val ButtonTypeScissors = new ButtonType("Scissors")

        val alert = new Alert(AlertType.Confirmation) {
            initOwner(stage)
            title = "Username: " + txtName.getText()
            headerText = "Your opponent has chosen."
            contentText = "Choose your option: "
            buttonTypes = Seq(ButtonTypeRock, ButtonTypePaper, ButtonTypeScissors, ButtonType.Cancel)
        }

        val result = alert.showAndWait()

        result match {
            case Some(ButtonTypeRock) => 
                selfChoice = "rock"
                displayChoice("rock")

            case Some(ButtonTypePaper) => 
                selfChoice = "paper"
                displayChoice("paper")

            case Some(ButtonTypeScissors) => 
                selfChoice = "scissors"
                displayChoice("scissors")

            case _ => 
                Client.userRef ! RPSClient.SendReject(listUser.selectionModel().selectedItem.value.ref)
        }

        // game result evaluation is done by the challenger (not the challenged)
        if (selfChoice == "rock") {
            opponentChoice match {
                case "rock" => gameResult = "draw"
                case "paper" => gameResult = "lose"
                case "scissors" => gameResult = "win"
            }
        }

        if (selfChoice == "paper") {
            opponentChoice match {
                case "rock" => gameResult = "win"
                case "paper" => gameResult = "draw"
                case "scissors" => gameResult = "lose"
            }
        }

        if (selfChoice == "scissors") {
            opponentChoice match {
                case "rock" => gameResult = "lose"
                case "paper" => gameResult = "win"
                case "scissors" => gameResult = "draw"
            }
        }

        gameResult match {
            case "win" => opponentResult = "lose"
            case "lose" => opponentResult = "win"
            case "draw" => opponentResult = "draw"
        }

        // the challenger sends the game results to the challenged
        Client.userRef ! RPSClient.SendResult(listUser.selectionModel().selectedItem.value.ref, opponentChoice, selfChoice, opponentResult)
        displayResult(selfChoice, opponentChoice, gameResult)

    }

    def displayResult(selfChoice: String, opponentChoice: String, gameResult: String): Unit ={
        new Alert(AlertType.Information) {
            initOwner(stage)
            title = "Username: " + txtName.getText()
            headerText = s"You chose ${selfChoice.toUpperCase} while your opponent chose ${opponentChoice.toUpperCase}."
            contentText = s"You ${gameResult.toUpperCase}!"
        }.showAndWait()

        RPSClient.challengeStatus = "None"
        displayChoice("None")
    }

}