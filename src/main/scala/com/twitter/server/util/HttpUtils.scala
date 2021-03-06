package com.twitter.server.util

import com.twitter.finagle.Service
import com.twitter.finagle.http.{MediaType, Status, Version}
import com.twitter.finagle.netty3.BufChannelBuffer
import com.twitter.io.Buf
import com.twitter.util.Future
import org.jboss.netty.handler.codec.http._
import scala.collection.JavaConverters._
import scala.collection.{Map, Seq}

/**
 * Contain all netty3 http types. We avoid using c.t.f.http.{Request, Response}
 * directly because in the absence of an Http{Muxer, Server} written in
 * terms of them, we have to jump through hoops to convert between the types.
 */
private[server] object HttpUtils {
  type Request = HttpRequest
  type Response = HttpResponse

  /**
   * Creates a http [[com.twitter.finagle.Service]] which attempts a
   * request on the given `services`, in order, until a service returns
   * a response with a non-404 status code. If none return a non-404,
   * the response of the last service is used. If the given list of `services`
   * is empty the resulting services will be always answering with 404.
   */
  def combine(services: Seq[Service[Request, Response]]): Service[Request, Response] =
    Service.mk[Request, Response] { req =>
      def loop(services: Seq[Service[Request, Response]]): Future[Response] =
        services match {
          case service +: Nil => service(req)
          case service +: tail =>
            service(req).flatMap { rep =>
              if (rep.getStatus == Status.NotFound)
                loop(tail)
              else
                Future.value(rep)
            }
          case Nil =>
            Future.value(
              new DefaultHttpResponse(req.getProtocolVersion, HttpResponseStatus.NOT_FOUND)
            )
        }

      loop(services)
    }

  /**
   * Determines (by examine the "Accept" header on `req`) if the client accepts given `contentType`.
   *
   * Note that this method simply checks if the given `contentType` is a substring of the "Accept"
   * header.
   */
  def accepts(req: Request, contentType: String): Boolean =
    Option(req.headers.get(HttpHeaders.Names.ACCEPT)).exists(_.contains(contentType))

  /**
   * Determines if the client expects to receive `text/html` content type.
   */
  def expectsHtml(req: Request): Boolean = {
    val decoder = new QueryStringDecoder(req.getUri)
    decoder.getPath.endsWith(".html") || accepts(req, MediaType.Html)
  }

  /**
   * Determines if the client expects to receive `application/json` content type.
   */
  def expectsJson(req: Request): Boolean = {
    val decoder = new QueryStringDecoder(req.getUri)
    decoder.getPath.endsWith(".json") || accepts(req, MediaType.Json)
  }

  /**
   * Create an http response with the give params.
   * Some of the headers like content length are inferred.
   *
   * @param version The HTTP version for this response.
   * @param status The HTTP status code.
   * @param headers Additional headers to include in the response.
   * @param contentType The content type header, defaults to text/plain
   * @param content The content body of the HTTP response.
   */
  def newResponse(
    version: HttpVersion = Version.Http11,
    status: HttpResponseStatus = Status.Ok,
    headers: Iterable[(String, Object)] = Seq(),
    contentType: String,
    content: Buf
  ): Future[Response] = {
    val response = new DefaultHttpResponse(version, status)
    response.setContent(BufChannelBuffer(content))
    for ((k, v) <- headers) response.headers.set(k, v)
    response.headers.set(HttpHeaders.Names.CONTENT_LANGUAGE , "en")
    response.headers.set(HttpHeaders.Names.CONTENT_LENGTH, content.length)
    response.headers.set(HttpHeaders.Names.CONTENT_TYPE, contentType)
    Future.value(response)
  }

  /** Returns a new 200 OK with contents set to `msg` */
  def newOk(msg: String): Future[Response] =
    newResponse(
      contentType = "text/plain;charset=UTF-8",
      content = Buf.Utf8(msg)
    )

  /** Returns a new 404 with contents set to `msg` */
  def new404(msg: String): Future[Response] =
    newResponse(
      status = Status.NotFound,
      contentType = "text/plain;charset=UTF-8",
      content = Buf.Utf8(msg)
    )

  /** Parse uri into (path, params) */
  def parse(uri: String): (String, Map[String, Seq[String]]) = {
    val qsd = new QueryStringDecoder(uri)
    val params = qsd.getParameters.asScala.mapValues { _.asScala.toSeq }
    (qsd.getPath, params)
  }
}
