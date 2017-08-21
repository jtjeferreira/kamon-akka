/*
 * =========================================================================================
 * Copyright © 2013-2015 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.instrumentation.akka


import akka.actor.SupervisorStrategy.{Escalate, Restart, Resume, Stop}
import akka.actor._
import akka.testkit.{ImplicitSender, TestKit}
import kamon.Kamon
import kamon.testkit.ContextTesting
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

import scala.concurrent.duration._
import scala.util.control.NonFatal

class ActorSystemMessageInstrumentationSpec extends TestKit(ActorSystem("ActorSystemMessageInstrumentationSpec")) with WordSpecLike
    with ContextTesting with BeforeAndAfterAll with ImplicitSender {
  implicit lazy val executionContext = system.dispatcher

  "the system message passing instrumentation" should {
    "capture and propagate the active span while processing the Create message in top level actors" in {
      Kamon.withContext(contextWithLocal("creating-top-level-actor")) {
        system.actorOf(Props(new Actor {
          testActor ! propagatedBaggage()
          def receive: Actor.Receive = { case any ⇒ }
        }))
      }

      expectMsg("creating-top-level-actor")
    }

    "capture and propagate the active span when processing the Create message in non top level actors" in {
      Kamon.withContext(contextWithLocal("creating-non-top-level-actor")) {
        system.actorOf(Props(new Actor {
          def receive: Actor.Receive = {
            case _ ⇒
              context.actorOf(Props(new Actor {
                testActor ! propagatedBaggage()
                def receive: Actor.Receive = { case _ ⇒ }
              }))
          }
        })) ! "any"
      }

      expectMsg("creating-non-top-level-actor")
    }

    "keep the TraceContext in the supervision cycle" when {
      "the actor is resumed" in {
        val supervisor = supervisorWithDirective(Resume)
        Kamon.withContext(contextWithLocal("fail-and-resume")) {
          supervisor ! "fail"
        }

        expectMsg("fail-and-resume") // From the parent executing the supervision strategy

        // Ensure we didn't tie the actor with the initially captured span
        supervisor ! "baggage"
        expectMsg("MissingContext")
      }

      "the actor is restarted" in {
        val supervisor = supervisorWithDirective(Restart, sendPreRestart = true, sendPostRestart = true)
        Kamon.withContext(contextWithLocal("fail-and-restart")) {
          supervisor ! "fail"
        }

        expectMsg("fail-and-restart") // From the parent executing the supervision strategy
        expectMsg("fail-and-restart") // From the preRestart hook
        expectMsg("fail-and-restart") // From the postRestart hook

        // Ensure we didn't tie the actor with the context
        supervisor ! "baggage"
        expectMsg("MissingContext")
      }

      "the actor is stopped" in {
        val supervisor = supervisorWithDirective(Stop, sendPostStop = true)
        Kamon.withContext(contextWithLocal("fail-and-stop")) {
          supervisor ! "fail"
        }

        expectMsg("fail-and-stop") // From the parent executing the supervision strategy
        expectMsg("fail-and-stop") // From the postStop hook
        expectNoMsg(1 second)
      }

      "the failure is escalated" in {
        val supervisor = supervisorWithDirective(Escalate, sendPostStop = true)
        Kamon.withContext(contextWithLocal("fail-and-escalate")) {
          supervisor ! "fail"
        }

        expectMsg("fail-and-escalate") // From the parent executing the supervision strategy
        expectMsg("fail-and-escalate") // From the grandparent executing the supervision strategy
        expectMsg("fail-and-escalate") // From the postStop hook in the child
        expectMsg("fail-and-escalate") // From the postStop hook in the parent
        expectNoMsg(1 second)
      }
    }
  }

  private def propagatedBaggage(): String =
    Kamon.currentContext().get(StringKey).getOrElse("MissingContext")

  def supervisorWithDirective(directive: SupervisorStrategy.Directive, sendPreRestart: Boolean = false, sendPostRestart: Boolean = false,
    sendPostStop: Boolean = false, sendPreStart: Boolean = false): ActorRef = {
    class GrandParent extends Actor {
      val child = context.actorOf(Props(new Parent))

      override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
        case NonFatal(_) ⇒ testActor ! propagatedBaggage(); Stop
      }

      def receive = {
        case any ⇒ child forward any
      }
    }

    class Parent extends Actor {
      val child = context.actorOf(Props(new Child))

      override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
        case NonFatal(_) ⇒ testActor ! propagatedBaggage(); directive
      }

      def receive: Actor.Receive = {
        case any ⇒ child forward any
      }

      override def postStop(): Unit = {
        if (sendPostStop) testActor ! propagatedBaggage()
        super.postStop()
      }
    }

    class Child extends Actor {
      def receive = {
        case "fail"    ⇒ throw new ArithmeticException("Division by zero.")
        case "baggage" ⇒ sender ! propagatedBaggage()
      }

      override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
        if (sendPreRestart) testActor ! propagatedBaggage()
        super.preRestart(reason, message)
      }

      override def postRestart(reason: Throwable): Unit = {
        if (sendPostRestart) testActor ! propagatedBaggage()
        super.postRestart(reason)
      }

      override def postStop(): Unit = {
        if (sendPostStop) testActor ! propagatedBaggage()
        super.postStop()
      }

      override def preStart(): Unit = {
        if (sendPreStart) testActor ! propagatedBaggage()
        super.preStart()
      }
    }

    system.actorOf(Props(new GrandParent))
  }
}
