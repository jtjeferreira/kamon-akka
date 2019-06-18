/* =========================================================================================
 * Copyright © 2013-2017 the kamon project <http://kamon.io/>
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

import akka.actor._
import akka.routing.RoundRobinPool
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import ActorMetricsTestActor.Block
import kamon.instrumentation.akka.AkkaMetrics._
import kamon.tag.TagSet
import kamon.testkit.{InstrumentInspection, MetricInspection}
import kamon.util.Filter
import kamon.util.Filter.Glob
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.duration._

class ActorGroupMetricsSpec extends TestKit(ActorSystem("ActorGroupMetricsSpec")) with WordSpecLike with MetricInspection.Syntax with InstrumentInspection.Syntax with Matchers
    with BeforeAndAfterAll with ImplicitSender with Eventually {

  "the Kamon actor-group metrics" should {
    "increase the member count when an actor matching the pattern is created" in new ActorGroupMetricsFixtures {
      val trackedActor1 = watch(createTestActor("group-of-actors-1"))
      val trackedActor2 = watch(createTestActor("group-of-actors-2"))
      val trackedActor3 = watch(createTestActor("group-of-actors-3"))
      val nonTrackedActor = createTestActor("someone-else")

      eventually {
        GroupMembers.withTags(groupTags("group-of-actors")).distribution().max shouldBe(3)
      }

      system.stop(trackedActor1)
      expectTerminated(trackedActor1)
      system.stop(trackedActor2)
      expectTerminated(trackedActor2)
      system.stop(trackedActor3)
      expectTerminated(trackedActor3)

      eventually(GroupMembers.withTags(groupTags("group-of-actors")).distribution().max shouldBe (0))
    }


    "increase the member count when a routee matching the pattern is created" in new ActorGroupMetricsFixtures {
      val trackedRouter = createTestPoolRouter("group-of-routees")
      val nonTrackedRouter = createTestPoolRouter("group-non-tracked-router")

      eventually(GroupMembers.withTags(groupTags("group-of-routees")).distribution().max shouldBe(5))

      val trackedRouter2 = createTestPoolRouter("group-of-routees-2")
      val trackedRouter3 = createTestPoolRouter("group-of-routees-3")

      eventually(GroupMembers.withTags(groupTags("group-of-routees")).distribution().max shouldBe(15))

      system.stop(trackedRouter)
      system.stop(trackedRouter2)
      system.stop(trackedRouter3)

      eventually(GroupMembers.withTags(groupTags("group-of-routees")).distribution(resetState = true).max shouldBe(0))
    }

    "allow defining groups by configuration" in {
      AkkaInstrumentation.matchingActorGroups("system/user/group-provided-by-code-actor") shouldBe empty
      AkkaInstrumentation.defineActorGroup("group-by-code", Filter.fromGlob("*/user/group-provided-by-code-actor")) shouldBe true
      AkkaInstrumentation.matchingActorGroups("system/user/group-provided-by-code-actor") should contain only("group-by-code")
      AkkaInstrumentation.removeActorGroup("group-by-code")
      AkkaInstrumentation.matchingActorGroups("system/user/group-provided-by-code-actor") shouldBe empty
    }

    "Cleanup pending-messages metric on member shutdown" in new ActorGroupMetricsFixtures {
      val trackedActor1 = watch(createTestActor("group-of-actors-1"))
      eventually(GroupMembers.withTags(groupTags("group-of-actors")).distribution().max shouldBe (1))

      val hangQueue = Block(1 milliseconds)
      (1 to 10000).foreach(_ => trackedActor1 ! hangQueue)

      system.stop(trackedActor1)
      expectTerminated(trackedActor1)

      eventually(GroupPendingMessages.withTags(groupTags("group-of-actors")).distribution().max shouldBe (0))
    }
  }

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(timeout = 5 seconds, interval = 5 milliseconds)

  override protected def afterAll(): Unit = shutdown()

  def groupTags(group: String): TagSet =
    TagSet.from(
      Map(
        "group" -> group,
        "system" -> "ActorGroupMetricsSpec"
      )
    )

  trait ActorGroupMetricsFixtures {
    def createTestActor(name: String): ActorRef = {
      val actor = system.actorOf(Props[ActorMetricsTestActor], name)
      val initialiseListener = TestProbe()

      // Ensure that the actor has been created before returning.
      actor.tell(ActorMetricsTestActor.Ping, initialiseListener.ref)
      initialiseListener.expectMsg(ActorMetricsTestActor.Pong)

      actor
    }

    def createTestPoolRouter(routerName: String): ActorRef = {
      val router = system.actorOf(RoundRobinPool(5).props(Props[RouterMetricsTestActor]), routerName)
      val initialiseListener = TestProbe()

      // Ensure that the router has been created before returning.
      router.tell(RouterMetricsTestActor.Ping, initialiseListener.ref)
      initialiseListener.expectMsg(RouterMetricsTestActor.Pong)

      router
    }
  }
}
