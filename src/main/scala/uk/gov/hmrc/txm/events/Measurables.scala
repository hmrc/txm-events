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

import uk.gov.hmrc.play.events.Measurable


trait KenshooCounterEvent extends Measurable

trait KenshooMeterEvent extends Measurable {

  def meterValue: Long

}

trait KenshooTimerEvent extends Measurable {

  def timerValue: Duration

}

case class SimpleKenshooTimerEvent(source: String,
                                   name: String,
                                   timerValue: Duration,
                                   data: Map[String, String] = Map.empty) extends KenshooTimerEvent

case class SimpleKenshooMeterEvent(source: String,
                                   name: String,
                                   meterValue: Long,
                                   data: Map[String, String] = Map.empty) extends KenshooMeterEvent

case class SimpleKenshooCounterEvent(source: String,
                                     name: String,
                                     data: Map[String, String] = Map.empty) extends KenshooCounterEvent
