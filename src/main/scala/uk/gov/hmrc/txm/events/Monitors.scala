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

import javax.inject.{Inject, Singleton}

import play.api.http.HeaderNames
import uk.gov.hmrc.play.config.AppName
import uk.gov.hmrc.play.events.monitoring.EventSource
import uk.gov.hmrc.play.events.{DefaultEventRecorder, EventRecorder}
import uk.gov.hmrc.play.http._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

trait SystemTimeProvider {

  def millis(): Long = System.currentTimeMillis()

}

trait TxmMonitor extends EventSource with DefaultEventRecorder {

  def monitor[T](componentName: String, targetServiceName: String)(future: Future[T])(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[T] = future

}

@Singleton
class TxmThirdPartWebServiceCallMonitor @Inject()(eventRecorder: EventRecorder) extends TxmMonitor with SystemTimeProvider with AppName {

  override def source: String = appName

  override def monitor[T](componentName: String, targetServiceName: String)(future: Future[T])(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[T] = {
    super.monitor(componentName, targetServiceName) {
      val ua = hc.headers.toMap.get(HeaderNames.USER_AGENT).getOrElse("undefined")
      val t0 = millis()
      future andThen {
        case Success(t) => {
          recordEndpointExecutionTime(componentName, targetServiceName, ua, "success", t0)
          eventRecorder.record(SimpleKenshooCounterEvent(source, s"$componentName.$targetServiceName.$ua.success.count"))
        }
        case Failure(e: Exception) => {
          val status = exceptionToStatus(e)
          if (time(e)) recordEndpointExecutionTime(componentName, targetServiceName, ua, status, t0)
          recordEndpointFailureCount(componentName, targetServiceName, ua, status)
        }
      }
    }
  }

  private def exceptionToStatus(e: Exception): Any = e match {
    case http: HttpException => http.responseCode
    case _4xx: Upstream4xxResponse => _4xx.reportAs
    case _5xx: Upstream5xxResponse => _5xx.reportAs
    case e : Exception => e.getClass.getSimpleName
  }

  private def time(e: Exception): Boolean = e match {
    case no @ (_ : BadGatewayException | _ : GatewayTimeoutException | _ : ServiceUnavailableException) => false
    case _ => true
  }

  private def recordEndpointFailureCount(componentName: String, targetServiceName: String, ua: String, status: Any)(implicit hc: HeaderCarrier, ec: ExecutionContext): Unit = {
    eventRecorder.record(SimpleKenshooCounterEvent(source, s"$componentName.$targetServiceName.$ua.$status.count"))
  }

  private def recordEndpointExecutionTime(componentName: String, targetServiceName: String, ua: String, status: Any, startTime: Long)(implicit hc: HeaderCarrier, ec: ExecutionContext): Unit = {
    eventRecorder.record(SimpleKenshooTimerEvent(source, s"$componentName.$targetServiceName.$ua.$status.time", (millis() - startTime).milliseconds))
  }

}
