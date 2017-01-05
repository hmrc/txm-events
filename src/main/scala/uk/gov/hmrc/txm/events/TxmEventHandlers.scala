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

import com.codahale.metrics.{Counter, Meter, Timer}
import com.kenshoo.play.metrics.Metrics
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.events.handlers._
import uk.gov.hmrc.play.events.{Alertable, Loggable, Measurable}

@Singleton
class GuiceLoggerEventHandler extends LoggerEventHandler {

  override def handleLoggable(loggable: Loggable): Unit = DefaultLoggerEventHandler.handleLoggable(loggable)

}

@Singleton
class GuiceAlertEventHandler extends AlertEventHandler {

  override def handleAlertable(alertable: Alertable): Unit = DefaultAlertEventHandler.handleAlertable(alertable)

}

@Singleton
class GuiceAuditEventHandler @Inject()(override val auditConnector: AuditConnector) extends AuditEventHandler

@Singleton
class KenshooMetricsEventHandler @Inject()(metrics: Metrics) extends MetricsEventHandler {

  override def handleMeasurable(measurable: Measurable) {
    measurable match {
      case event: KenshooTimerEvent => findTimer(event).update(event.timerValue.length, event.timerValue.unit)
      case event: KenshooMeterEvent => findMeter(event).mark(event.meterValue)
      case event: KenshooCounterEvent => findCounter(event).inc()
      case _ =>
    }
  }

  private def findCounter(measurable: KenshooCounterEvent): Counter = {
    if (metrics.defaultRegistry.getCounters.containsKey(measurable.name)) metrics.defaultRegistry.getCounters.get(measurable.name)
    else metrics.defaultRegistry.counter(measurable.name)
  }

  private def findMeter(measurable: KenshooMeterEvent): Meter = {
    if (metrics.defaultRegistry.getMeters.containsKey(measurable.name)) metrics.defaultRegistry.getMeters.get(measurable.name)
    else metrics.defaultRegistry.meter(measurable.name)
  }

  private def findTimer(measurable: KenshooTimerEvent): Timer = {
    if (metrics.defaultRegistry.getTimers.containsKey(measurable.name)) metrics.defaultRegistry.getTimers.get(measurable.name)
    else metrics.defaultRegistry.timer(measurable.name)
  }

}
