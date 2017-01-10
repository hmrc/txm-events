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

import org.scalatest.AsyncWordSpec
import play.api.http.HeaderNames
import uk.gov.hmrc.play.events.handlers.EventHandler
import uk.gov.hmrc.play.events.{EventRecorder, Recordable}
import uk.gov.hmrc.play.http._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.{Duration, _}

// NB. This is a slightly gunky test. If you can think of a better way, be my guest ...
class TxmThirdPartWebServiceCallMonitorSpec extends AsyncWordSpec {

  class ThirdPartyCall[T](resp: T, executionTime: Duration = 0.milliseconds, failure: Option[Exception] = None) {

    def execute: Future[T] = {
      if (executionTime.gt(0.milliseconds)) {
        Thread.sleep(executionTime.toMillis)
      }
      if (failure.isDefined) Future.failed(failure.get)
      else Future.successful(resp)
    }

  }

  class Scenario(systimeAtCalls: Option[Seq[Long]] = None) {
    var callNumber = 0;
    val userAgent = "some-hmrc-service"
    val theAppName = "txm-foo"
    val theAppComponent = "MyAppComponent"
    val theServiceName = "test-service"
    val recorder = new EventRecorder {

      var recorded: Seq[Recordable] = Seq.empty

      override def eventHandlers: Set[EventHandler] = Set.empty

      override def record(recordable: Recordable)(implicit headerCarrier: HeaderCarrier): Unit = {
        recorded = recorded :+ recordable
      }
    }
    val monitor = new TxmThirdPartWebServiceCallMonitor(recorder) {

      override lazy val appName = theAppName

      override def millis(): Long = {
        if (systimeAtCalls.isDefined && systimeAtCalls.get.length > callNumber) {
          val t = systimeAtCalls.get(callNumber)
          callNumber = callNumber + 1
          return t
        } else {
          super.millis()
        }
      }
    }
  }

  "txm 3rd party web service call monitor" should {

    // we called X and it took time T
    "record execution time metric for successful call" in {
      val s = new Scenario(Some(Seq(10, 20)))
      implicit val hc = HeaderCarrier(otherHeaders = Seq((HeaderNames.USER_AGENT -> s.userAgent)))
      val call = new ThirdPartyCall[String]("foo")
      s.monitor.monitor[String](s.theAppComponent, s.theServiceName) {
        call.execute
      } map {
        case "foo" => {
          val event: SimpleKenshooTimerEvent = s.recorder.recorded.flatMap {
            case timer: SimpleKenshooTimerEvent => Some(timer)
            case _ => None
          }.head
          assert(event.timerValue.toMillis == 10)
          assert(event.source == s.theAppName)
          assert(event.name == s"${s.theAppComponent}.${s.theServiceName}.${s.userAgent}.success.time")
        }
      }
    }

    // we have made N successful calls to X
    "record success count metric for successful call" in {
      val s = new Scenario
      implicit val hc = HeaderCarrier(otherHeaders = Seq((HeaderNames.USER_AGENT -> s.userAgent)))
      val call = new ThirdPartyCall[String]("foo")
      s.monitor.monitor[String](s.theAppComponent, s.theServiceName) {
        call.execute
      } map {
        case "foo" => {
          val event: SimpleKenshooCounterEvent = s.recorder.recorded.flatMap {
            case count: SimpleKenshooCounterEvent => Some(count)
            case _ => None
          }.head
          assert(event.source == s.theAppName)
          assert(event.name == s"${s.theAppComponent}.${s.theServiceName}.${s.userAgent}.success.count")
        }
      }
    }

    // we have made N failed calls to X where we got a response
    "record count metric for failed call that is not gateway timeout, bad gateway, or service unavailable" in {
      val s = new Scenario(Some(Seq(10, 20)))
      implicit val hc = HeaderCarrier(otherHeaders = Seq((HeaderNames.USER_AGENT -> s.userAgent)))
      val ex = new HttpException("Random error", 555)
      val call = new ThirdPartyCall[String]("foo", failure = Some(ex))
      s.monitor.monitor[String](s.theAppComponent, s.theServiceName) {
        call.execute
      } map {
        case _ => assert(false, "Call should have failed!")
      } recover {
        case e: HttpException => {
          val count: SimpleKenshooCounterEvent = s.recorder.recorded.flatMap {
            case count: SimpleKenshooCounterEvent => Some(count)
            case _ => None
          }.head
          val timer: SimpleKenshooTimerEvent = s.recorder.recorded.flatMap {
            case timer: SimpleKenshooTimerEvent => Some(timer)
            case _ => None
          }.head
          assert(count.source == s.theAppName)
          assert(count.name == s"${s.theAppComponent}.${s.theServiceName}.${s.userAgent}.${ex.responseCode}.count")
          assert(timer.source == s.theAppName)
          assert(timer.name == s"${s.theAppComponent}.${s.theServiceName}.${s.userAgent}.${ex.responseCode}.time")
          assert(timer.timerValue.toMillis == 10)
        }
      }
    }

    // we have made N failed calls to X where the fault originated with us
    "record 4xx failure count metric for failed call" in {
      val s = new Scenario
      implicit val hc = HeaderCarrier(otherHeaders = Seq((HeaderNames.USER_AGENT -> s.userAgent)))
      val ex = new Upstream4xxResponse("We asked for the wrong thing", 404, 404)
      val call = new ThirdPartyCall[String]("foo", failure = Some(ex))
      s.monitor.monitor[String](s.theAppComponent, s.theServiceName) {
        call.execute
      } map {
        case _ => assert(false, "Call should have failed!")
      } recover {
        case e: Upstream4xxResponse => {
          val event: SimpleKenshooCounterEvent = s.recorder.recorded.flatMap {
            case count: SimpleKenshooCounterEvent => Some(count)
            case _ => None
          }.head
          assert(event.source == s.theAppName)
          assert(event.name == s"${s.theAppComponent}.${s.theServiceName}.${s.userAgent}.${ex.reportAs}.count")
        }
      }
    }

    "recorder 4xx call time metric for failed call" in {
      val s = new Scenario(Some(Seq(10, 20)))
      implicit val hc = HeaderCarrier(otherHeaders = Seq((HeaderNames.USER_AGENT -> s.userAgent)))
      val ex = new Upstream4xxResponse("We asked for the wrong thing", 404, 404)
      val call = new ThirdPartyCall[String]("foo", failure = Some(ex))
      s.monitor.monitor[String](s.theAppComponent, s.theServiceName) {
        call.execute
      } map {
        case _ => assert(false, "Call should have failed!")
      } recover {
        case e: Upstream4xxResponse => {
          val event: SimpleKenshooTimerEvent = s.recorder.recorded.flatMap {
            case timer: SimpleKenshooTimerEvent => Some(timer)
            case _ => None
          }.head
          assert(event.source == s.theAppName)
          assert(event.name == s"${s.theAppComponent}.${s.theServiceName}.${s.userAgent}.${ex.reportAs}.time")
          assert(event.timerValue.toMillis == 10)
        }
      }
    }

    // we have made N failed calls to X where the fault originated with X
    "record 5xx failure count metric for failed call" in {
      val s = new Scenario
      implicit val hc = HeaderCarrier(otherHeaders = Seq((HeaderNames.USER_AGENT -> s.userAgent)))
      val ex = new Upstream5xxResponse("They broke", 500, 500)
      val call = new ThirdPartyCall[String]("foo", failure = Some(ex))
      s.monitor.monitor[String](s.theAppComponent, s.theServiceName) {
        call.execute
      } map {
        case _ => assert(false, "Call should have failed!")
      } recover {
        case e: Upstream5xxResponse => {
          val event: SimpleKenshooCounterEvent = s.recorder.recorded.flatMap {
            case count: SimpleKenshooCounterEvent => Some(count)
            case _ => None
          }.head
          assert(event.source == s.theAppName)
          assert(event.name == s"${s.theAppComponent}.${s.theServiceName}.${s.userAgent}.${ex.reportAs}.count")
        }
      }
    }

    "recorder 5xx call time metric for failed call" in {
      val s = new Scenario(Some(Seq(10, 20)))
      implicit val hc = HeaderCarrier(otherHeaders = Seq((HeaderNames.USER_AGENT -> s.userAgent)))
      val ex = new Upstream5xxResponse("They broke", 500, 500)
      val call = new ThirdPartyCall[String]("foo", failure = Some(ex))
      s.monitor.monitor[String](s.theAppComponent, s.theServiceName) {
        call.execute
      } map {
        case _ => assert(false, "Call should have failed!")
      } recover {
        case e: Upstream5xxResponse => {
          val event: SimpleKenshooTimerEvent = s.recorder.recorded.flatMap {
            case timer: SimpleKenshooTimerEvent => Some(timer)
            case _ => None
          }.head
          assert(event.source == s.theAppName)
          assert(event.name == s"${s.theAppComponent}.${s.theServiceName}.${s.userAgent}.${ex.reportAs}.time")
          assert(event.timerValue.toMillis == 10)
        }
      }
    }

    // we have made N calls to X where X was down
    "record dedicated 5xx failure count metric for bad gateway" in {
      val s = new Scenario
      implicit val hc = HeaderCarrier(otherHeaders = Seq((HeaderNames.USER_AGENT -> s.userAgent)))
      val ex = new BadGatewayException("They're down")
      val call = new ThirdPartyCall[String]("foo", failure = Some(ex))
      s.monitor.monitor[String](s.theAppComponent, s.theServiceName) {
        call.execute
      } map {
        case _ => assert(false, "Call should have failed!")
      } recover {
        case e: BadGatewayException => {
          val event: SimpleKenshooCounterEvent = s.recorder.recorded.flatMap {
            case count: SimpleKenshooCounterEvent => Some(count)
            case _ => None
          }.head
          val timers: Seq[SimpleKenshooTimerEvent] = s.recorder.recorded.flatMap {
            case timer: SimpleKenshooTimerEvent => Some(timer)
            case _ => None
          }
          assert(timers.isEmpty)
          assert(event.source == s.theAppName)
          assert(event.name == s"${s.theAppComponent}.${s.theServiceName}.${s.userAgent}.${ex.responseCode}.count")
        }
      }
    }

    // we have made N calls to X where X failed to respond in a timely manner
    "record dedicated 5xx failure count metric for gateway timeout" in {
      val s = new Scenario
      implicit val hc = HeaderCarrier(otherHeaders = Seq((HeaderNames.USER_AGENT -> s.userAgent)))
      val ex = new GatewayTimeoutException("They're at the pub")
      val call = new ThirdPartyCall[String]("foo", failure = Some(ex))
      s.monitor.monitor[String](s.theAppComponent, s.theServiceName) {
        call.execute
      } map {
        case _ => assert(false, "Call should have failed!")
      } recover {
        case _ => {
          val event: SimpleKenshooCounterEvent = s.recorder.recorded.flatMap {
            case count: SimpleKenshooCounterEvent => Some(count)
            case _ => None
          }.head
          val timers: Seq[SimpleKenshooTimerEvent] = s.recorder.recorded.flatMap {
            case timer: SimpleKenshooTimerEvent => Some(timer)
            case _ => None
          }
          assert(timers.isEmpty)
          assert(event.source == s.theAppName)
          assert(event.name == s"${s.theAppComponent}.${s.theServiceName}.${s.userAgent}.${ex.responseCode}.count")
        }
      }
    }

    // we have made N calls to X where X refused to service the request
    "record dedicated 5xx failure count metric for service unavailable" in {
      val s = new Scenario
      implicit val hc = HeaderCarrier(otherHeaders = Seq((HeaderNames.USER_AGENT -> s.userAgent)))
      val ex = new ServiceUnavailableException("They're being unfriendly")
      val call = new ThirdPartyCall[String]("foo", failure = Some(ex))
      s.monitor.monitor[String](s.theAppComponent, s.theServiceName) {
        call.execute
      } map {
        case _ => assert(false, "Call should have failed!")
      } recover {
        case e: ServiceUnavailableException => {
          val event: SimpleKenshooCounterEvent = s.recorder.recorded.flatMap {
            case count: SimpleKenshooCounterEvent => Some(count)
            case _ => None
          }.head
          val timers: Seq[SimpleKenshooTimerEvent] = s.recorder.recorded.flatMap {
            case timer: SimpleKenshooTimerEvent => Some(timer)
            case _ => None
          }
          assert(timers.isEmpty)
          assert(event.source == s.theAppName)
          assert(event.name == s"${s.theAppComponent}.${s.theServiceName}.${s.userAgent}.${ex.responseCode}.count")
        }
      }
    }

    // something unexpected happened
    "record unexpected failure count and timer metrics for failed call" in {
      val s = new Scenario(Some(Seq(10,20)))
      implicit val hc = HeaderCarrier(otherHeaders = Seq((HeaderNames.USER_AGENT -> s.userAgent)))
      val ex = new IllegalArgumentException("Who knows why?")
      val call = new ThirdPartyCall[String]("foo", failure = Some(ex))
      s.monitor.monitor[String](s.theAppComponent, s.theServiceName) {
        call.execute
      } map {
        case _ => assert(false, "Call should have failed!")
      } recover {
        case e: IllegalArgumentException => {
          val event: SimpleKenshooCounterEvent = s.recorder.recorded.flatMap {
            case count: SimpleKenshooCounterEvent => Some(count)
            case _ => None
          }.head
          val timer: SimpleKenshooTimerEvent = s.recorder.recorded.flatMap {
            case timer: SimpleKenshooTimerEvent => Some(timer)
            case _ => None
          }.head
          assert(event.source == s.theAppName)
          assert(event.name == s"${s.theAppComponent}.${s.theServiceName}.${s.userAgent}.${ex.getClass.getSimpleName}.count")
          assert(timer.source == s.theAppName)
          assert(timer.name == s"${s.theAppComponent}.${s.theServiceName}.${s.userAgent}.${ex.getClass.getSimpleName}.time")
          assert(timer.timerValue.toMillis == 10)
        }
      }
    }

  }

}
