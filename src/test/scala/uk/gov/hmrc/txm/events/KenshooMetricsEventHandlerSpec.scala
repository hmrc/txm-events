/*
 * Copyright 2017 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.txm.events

import java.time.Duration
import java.util
import java.util.Collections
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import com.codahale.metrics._
import com.kenshoo.play.metrics.Metrics
import org.scalatest.{MustMatchers, WordSpec}
import uk.gov.hmrc.play.events.Auditable
import uk.gov.hmrc.play.http.HeaderCarrier

class KenshooMetricsEventHandlerSpec extends WordSpec with MustMatchers {

  class MockMeter(name: String) extends Meter {

    var markedAs: Option[Long] = None

    var marked = false

    override def mark(): Unit = {
      marked = true
    }

    override def mark(n: Long): Unit = {
      marked = true
      markedAs = Some(n)
    }
  }

  class MockTimer(name: String) extends Timer {

    var updated = false

    var durationOf: Option[Duration] = None

    override def update(nanos: Long, unit: TimeUnit): Unit = {
      assert(unit === TimeUnit.NANOSECONDS)
      updated = true
      durationOf = Some(Duration.ofNanos(nanos))
    }

  }

  class MockCounter(name: String) extends Counter {

    var incremented = false

    var incrementedBy: Option[Long] = None

    override def inc(): Unit = {
      incremented = true
    }

    override def inc(n: Long): Unit = {
      incremented = true
      incrementedBy = Some(n)
    }

  }

  class Scenario {
    implicit val hc = HeaderCarrier()

    val meterName = "mock-meter"
    val timerName = "mock-timer"
    val counterName = "mock-counter"

    val mockMeter = new MockMeter(meterName)
    val mockTimer = new MockTimer(timerName)
    val mockCounter = new MockCounter(counterName)

    val registry: MetricRegistry = new MetricRegistry {

      val meters: util.SortedMap[String, Meter] = new util.TreeMap(Map(meterName -> mockMeter).asJava)
      val timers: util.SortedMap[String, Timer] = new util.TreeMap(Map(timerName -> mockTimer).asJava)
      val counters: util.SortedMap[String, Counter] = new util.TreeMap(Map(counterName -> mockCounter).asJava)

      override def meter(name: String): Meter = name match {
        case mock if meterName == name => mockMeter
        case _ => {
          val m = new MockMeter(name)
          meters.put(name, m)
          m
        }
      }

      override def timer(name: String): Timer = name match {
        case mock if timerName == name => mockTimer
        case _ => {
          val t = new MockTimer(name)
          timers.put(name, t)
          t
        }
      }

      override def counter(name: String): Counter = name match {
        case mock if counterName == name => mockCounter
        case _ => {
          val c = new MockCounter(name)
          counters.put(name, c)
          c
        }
      }

      override def getCounters(filter: MetricFilter): util.SortedMap[String, Counter] = Collections.unmodifiableSortedMap(counters)

      override def getTimers: util.SortedMap[String, Timer] = Collections.unmodifiableSortedMap(timers)

      override def getMeters: util.SortedMap[String, Meter] = Collections.unmodifiableSortedMap(meters)
    }

    val metrics = new Metrics {

      var registryAccessed = false

      override def defaultRegistry: MetricRegistry = {
        registryAccessed = true
        registry
      }

      override def toJson: String = """{"foo" : "bar"}"""
    }

    val handler = new KenshooMetricsEventHandler(metrics)
  }

  "kenshoo metrics handler" should {

    "do nothing given an event which is not measurable" in new Scenario {
      val event = new Auditable {

        override def source = "source"

        override def privateData = Map()

        override def name = "name"

        override def tags = Map()

      }
      handler.handle(event)
      metrics.registryAccessed must be(false)
    }

    "update existing timer given kenshoo timer event" in new Scenario {
      val duration = Duration.ofSeconds(42)
      val event = SimpleKenshooTimerEvent("source", timerName, duration)
      handler.handle(event)
      registry.getTimers.size() must be(1) // no new timer was added
      mockTimer.updated must be(true) // the existing timer was updated ...
      mockTimer.durationOf must be(Some(duration)) // ... by 42 ...
    }

    "register and update new timer given kenshoo timer event" in new Scenario {
      val duration = Duration.ofSeconds(42)
      val event = SimpleKenshooTimerEvent("source", "thisIsANewTimer", duration)
      handler.handle(event)
      registry.getTimers.size() must be(2) // one new timer was added
      val newTimer = registry.getTimers.get(event.name).asInstanceOf[MockTimer]
      newTimer.updated must be(true) // the new timer was updated ...
      newTimer.durationOf must be(Some(duration)) // ... by 42 ...
    }

    "mark existing meter event given kenshoo meter event" in new Scenario {
      val value = 42
      val event = SimpleKenshooMeterEvent("source", meterName, value)
      handler.handle(event)
      registry.getMeters.size() must be(1) // no new meter was added
      mockMeter.marked must be(true) // the existing meter was marked ...
      mockMeter.markedAs must be(Some(value)) // ... with 42
    }

    "register and mark new meter event given kenshoo meter event" in new Scenario {
      val value = 42
      val event = SimpleKenshooMeterEvent("source", "thisIsANewMeter", value)
      handler.handle(event)
      registry.getMeters.size() must be(2) // one new meter was added
      val newMeter = registry.getMeters.get(event.name).asInstanceOf[MockMeter]
      newMeter.marked must be(true) // the existing meter was marked ...
      newMeter.markedAs must be(Some(value)) // ... with 42
    }

    "increment existing counter event given kenshoo counter event" in new Scenario {
      val event = SimpleKenshooCounterEvent("source", counterName)
      handler.handle(event)
      registry.getCounters.size() must be(1) // no new counter was added
      mockCounter.incremented must be(true) // the existing counter was incremented
    }

    "register and increment new counter event given kenshoo counter event" in new Scenario {
      val event = SimpleKenshooCounterEvent("source", "thisIsANewCounter")
      handler.handle(event)
      registry.getCounters.size() must be(2) // one new counter was added
      val newCounter = registry.getCounters.get(event.name).asInstanceOf[MockCounter]
      newCounter.incremented must be(true) // the new counter was incremented
    }
  }

}
