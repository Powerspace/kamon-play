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
import kamon.context.Context
import kamon.play.{OperationNameFilter, instrumentationNetty}
import kamon.trace.Span
import kamon.util.CallingThreadExecutionContext
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation._
import play.api.mvc.{EssentialFilter, RequestHeader}

import scala.concurrent.Future

@Aspect
class NettyRequestHandlerInstrumentation {

  private lazy val filter: EssentialFilter = new OperationNameFilter()

  private def span(pjp: ProceedingJoinPoint, method: String, route: String): Future[HttpResponse] = {
    val serverSpan = Kamon.buildSpan("http-request")
      .withTag("span.kind", "server")
      .withTag("http.method", method)
      .withTag("http.url", route)
      .start()

    Kamon.withContext(Context.create(Span.ContextKey, serverSpan)) {
      pjp.proceed().asInstanceOf[Future[HttpResponse]]
    }.transform(
      response => {
        val responseStatus = response.status()
        serverSpan.tagMetric("http.status_code", responseStatus.code().toString)

        if (instrumentationNetty.isError(responseStatus.code))
          serverSpan.addError(responseStatus.reasonPhrase())

        serverSpan.finish()
        response
      },
      error => {
        serverSpan.addError("error.object", error)
        serverSpan.finish()
        error
      }
    )(CallingThreadExecutionContext)
  }

  @Around("execution(* *(..))")
  def onHandle2(pjp: ProceedingJoinPoint): Any = {
    println(pjp.getSignature)
    pjp.proceed()
  }

  @Around("execution(private * play.core.server.netty.PlayRequestHandler.handleAction(..))")
  def onHandle(pjp: ProceedingJoinPoint): Any = {
    println(pjp.proceed())
  }

  @Around("execution(private * play.core.server.netty.PlayRequestHandler.handleAction(..)) && args(*, requestHeader, request, *)")
  def onHandle(pjp: ProceedingJoinPoint, requestHeader: RequestHeader, request: HttpRequest): Any = {
    import play.api.routing.Router.Attrs.HandlerDef
    println(requestHeader)
    (for {
      handlerDef <- requestHeader.attrs.get(HandlerDef)
      method = handlerDef.method
      routePattern = handlerDef.path
      route = routePattern.replaceAll("<.*?>", "").replaceAll("\\$", ":")
    } yield span(pjp, method, route)).getOrElse(pjp.proceed().asInstanceOf[Future[HttpResponse]])
  }

  @Around("call(* play.api.http.HttpFilters.filters(..))")
  def filters(pjp: ProceedingJoinPoint): Any = {
    filter +: pjp.proceed().asInstanceOf[Seq[EssentialFilter]]
  }
}
