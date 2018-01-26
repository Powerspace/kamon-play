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

package kamon.play.instrumentation

import io.netty.handler.codec.http.{HttpRequest, HttpResponse}
import kamon.Kamon
import kamon.play.instrumentationNetty
import kamon.util.{CallingThreadExecutionContext, Clock}
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation._
import play.api.mvc.RequestHeader

import scala.concurrent.Future

@Aspect
class NettyRequestHandlerInstrumentation {

  //private lazy val filter: EssentialFilter = new OperationNameFilter()

  val clock = new Clock.Default()

  private def span(pjp: ProceedingJoinPoint, verb: String, route: String): Future[HttpResponse] = {
    val now = clock.instant()

    pjp.proceed().asInstanceOf[Future[HttpResponse]]
      .transform(
        response => {
          val responseStatus = response.status()
          val serverSpan = Kamon.buildSpan("http-request")
            .withFrom(now)
            .withMetricTag("http.verb", verb)
            .withMetricTag("http.url", route)
            .withMetricTag("http.status_code", responseStatus.code().toString)
            .start()

          if (instrumentationNetty.isError(responseStatus.code))
            serverSpan.addError(responseStatus.reasonPhrase())

          serverSpan.finish()
          response
        },
        error => {
          Kamon.buildSpan("http-request")
            .withFrom(now)
            .withMetricTag("http.verb", verb)
            .withMetricTag("http.url", route)
            .withMetricTag("http.error", error.getMessage)
            .start()
            .finish()
          error
        }
      )(CallingThreadExecutionContext)
  }

  @Around("execution(* play.core.server.netty.PlayRequestHandler.play$core$server$netty$PlayRequestHandler$$handleAction(..)) && args(*, requestHeader, request, *)")
  def onHandle(pjp: ProceedingJoinPoint, requestHeader: RequestHeader, request: HttpRequest): Any = {
    import play.api.routing.Router.Attrs.HandlerDef
    (for {
      handlerDef <- requestHeader.attrs.get(HandlerDef)
      route = handlerDef.path.replaceAll("<.*?>", "").replaceAll("\\$", ":")
    } yield span(pjp, handlerDef.verb, route)).getOrElse(pjp.proceed().asInstanceOf[Future[HttpResponse]])
  }
/*
  @Around("call(* play.api.http.HttpFilters.filters(..))")
  def filters(pjp: ProceedingJoinPoint): Any = {
    filter +: pjp.proceed().asInstanceOf[Seq[EssentialFilter]]
  }*/
}
