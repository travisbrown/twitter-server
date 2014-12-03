package com.twitter.server.handler

import com.twitter.finagle.client.ClientRegistry
import com.twitter.finagle.Service
import com.twitter.finagle.util.StackRegistry
import com.twitter.io.Buf
import com.twitter.server.util.HttpUtils._
import com.twitter.server.util.MetricSource
import com.twitter.server.view.StackRegistryView
import com.twitter.util.Future

private object ClientRegistryHandler {
  /**
   * Render an overview of `clients` in an html template.
   * @param title The page title.
   * @param clients A tuple where the first element represents
   * the success rate of the client entry.
   */
  def render(
    title: String,
    clients: Seq[(Double, StackRegistry.Entry)]
  ): String =
    s"""<h4 class="header text-center">${title}</h4>
        <hr/>
        <div id="clients" class="row">
        ${
          (for ((sr, StackRegistry.Entry(name, addr, _, _)) <- clients) yield {
            s"""<div class="col-md-3">
                  <div class="client">
                    <h4><a href="/admin/clients/$name">${name}</a></h4>
                    <p class="dest text-muted">$addr</p>
                    <hr/>
                    <div class="row">
                      <h6 class="success-rate-header col-xs-6">success rate</h6>
                      ${
                         val css = if (sr < 0.9) "success-rate-bad"
                            else if (sr < 0.99) "success-rate-poor"
                            else "success-rate-good"
                         f"<h3 class='success-rate-text col-xs-6 $css'>${sr*100.0}%2.2f%%</h3>"
                       }
                    </div>
                  </div>
                </div>"""
          }).mkString("\n")
         }
        </div>"""

}

/**
 * Renders information about clients registered to Finagle's ClientRegistry in
 * an html fragment. Client's can be queried by passing in the client name as
 * part of the uri (ex. "/admin/clients/myclient").
 */
class ClientRegistryHandler(
  source: MetricSource = new MetricSource,
  registry: StackRegistry = ClientRegistry
) extends Service[Request, Response] {
  // Search the metrics source for the stat scope that includes `clientName`.
  // The search namespace includes both "$clientName/" and "clnt/$clientName"
  // to take into account finagle's ClientStatsReceiver. Note, unnamed clients are
  // ignored as we can't dissambiguate their stats.
  private[this] def findClientScope(clientName: String): Option[String] = {
    val k0 = s"$clientName"
    val k1 = s"clnt/$clientName"
    if (source.contains(s"$k0/loadbalancer/adds")) Some(k0)
    else if (source.contains(s"$k1/loadbalancer/adds")) Some(k1)
    else None
  }

  // Finagle's StatFilter usually reports to the root of the `clientScope`.
  // However, the ClientBuilder API provides an easy to install retry filter
  // with a separate StatsFilter that reports to "tries".
  private[this] def findReqScope(clientScope: String): Option[String] = {
    val k0 = s"$clientScope/tries"
    val k1 = s"$clientScope"
    if (source.contains(s"$k0/requests")) Some(k0)
    else if (source.contains(s"$k1/requests")) Some(k1)
    else None
  }

  // Compute the success rate for each client in the registry. Note, this only takes
  // into consideration the request path. We should expose some quick stats regarding
  // service acquisition in order to quickly surface clients that are having trouble
  // establishing connections. Currently, these clients show up with a success rate of 0.0%
  // but it would be more informative to expose "/service_creation/failures" too.
  private[this] def clientProfiles: Seq[(Double, StackRegistry.Entry)] =
    (registry.registrants flatMap {
      case e: StackRegistry.Entry if e.name.nonEmpty =>
        for {
          scope <- findClientScope(e.name)
          reqScope <- findReqScope(scope)
          req <- source.get(s"$reqScope/requests")
          reqFail <- source.get(s"$reqScope/failures")
            .orElse(Some(MetricSource.Entry("", 0.0, 0.0)))
        } yield if (req.value == 0) (0.0, e) else (1.0 - (reqFail.value/req.value), e)

      case _ => Nil
    }).toSeq

  private[this] val profileOrdering: Ordering[(Double, StackRegistry.Entry)] =
    Ordering.by(tup => tup._1)

  def apply(req: Request): Future[Response] = {
    val (path, _) = parse(req.getUri)
    path.split('/').last match {
      case idx@("index.html" | "index.htm" | "index.txt" | "clients") =>
        val leastPerformant = clientProfiles.sorted(profileOrdering).take(4)
        val html = ClientRegistryHandler.render("Least Performant Downstream Clients", leastPerformant)
        // This is useful to avoid the returned fragment being wrapped
        // with an index in the context of an ajax call.
        val typ = if (idx.endsWith(".txt")) "text/plain" else "text/html"
        newResponse(
          contentType = s"$typ;charset=UTF-8",
          content = Buf.Utf8(html)
        )

      case name =>
        val entries = registry.registrants filter { _.name == name }
        if (entries.isEmpty) new404(s"$name could not be found.") else {
          val client = entries.head
          val scope = findClientScope(client.name)
          val html = StackRegistryView.render(client, scope)
          newResponse(
            contentType = "text/html;charset=UTF-8",
            content = Buf.Utf8(html)
          )
        }
    }
  }
}