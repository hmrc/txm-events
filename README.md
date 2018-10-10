
# TxM Events

THIS IS NOT USED

[ ![Download](https://api.bintray.com/packages/hmrc/releases/txm-events/images/download.svg) ](https://bintray.com/hmrc/releases/txm-events/_latestVersion)

This library aims to provide a supporting framework to ease consistent implementation of non-functional
platform concerns, including metrics, audit, alerting, and logging, within concrete Play 2.5 microservice applications. 
It is primarily a wrapper around [play-events](https://github.com/hmrc/play-events). However, the latter has been
modified, extended, and implemented where necessary for the purposes of the TxM team.

## Using as a Play 2.5 Module

TxM Events provides a Play 2.5 module which extends and replaces the Kenshoo Metrics module 
`com.kenshoo.play.metrics.PlayModule`. Out-of-the-box, this module makes available the following components:

* `LoggerEventHandler`: via implementation `GuiceLoggerEventHandler`, a simple implementation which
delegates to the `DefaultLoggerEventHandler` from [play-events](https://github.com/hmrc/play-events).
* `AlertEventHandler`: via implementation `GuiceAlertEventHandler`, a simple implementation which delegates
to the `DefaultAlertEventHandler` from [play-events](https://github.com/hmrc/play-events).
* `MetricsEventHandler`: via implementation `KenshooMetricsEventHandler`
* `AuditEventHandler`: via implementation `GuiceAuditEventHandler`, a simple implementation which delegates
to the `AuditEventHandler` from from [play-events](https://github.com/hmrc/play-events).
* `EventRecorder`: via implementation `TxmEventRecorder` which defines the above components as the set of
event handlers.
* `TxmMonitor`: via implementation `TxmThirdPartWebServiceCallMonitor`, a monitor designed to produce
appropriate metrics and audits when wrapped around a call to a 3rd party web service.

### Monitoring HTTP calls via TxM Monitor

```scala
@Singleton
class MyController @Inject()(txmMonitor: TxmMonitor) extends BaseController with WSGet {

  private implicit val auditStrategy = MyAuditStrategy

  def doSomething = Action.async { implicit req =>
    val hc = HeaderCarrier.fromHeadersAndSession(req.headers)
    txmMonitor.monitor[TheirResponse]("MyComponentName", "the-transaction-name") {
      GET[TheirResponse]("http://foo.bar/baz")
    } recover {
      // exceptions thrown during monitoring bubble up so we can handle them here if necessary
      case e: Exception => throw Upstream5xxResponse("Uh oh!", 500, 500)
    }
  }

}

// Default implementation does no auditing. We can override to modify details and tags on success and failure
object MyAuditStrategy extends AuditStrategy[TheirResponse] {

  override def auditDataOnSuccess(resp: TheirResponse): Map[String, String] = Map("SomeKey" -> resp.someValue)

}
```

As a result of wrapping the `GET` above with monitoring, we get count and timer metrics for the operation
 (using the component and transaction name) and an audit message containing `resp.someValue` if the call is successful.
 
### Using for explicit metrics, alerts, logging, and auditing
 
The main [play-events](https://github.com/hmrc/play-events) APIs are used. TxM events just provides event
recorders and handlers as DI-able components via the Guice module. Refer to the [play-events](https://github.com/hmrc/play-events)
documentation.

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
