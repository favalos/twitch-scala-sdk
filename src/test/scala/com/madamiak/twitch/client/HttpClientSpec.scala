package com.madamiak.twitch.client

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.ActorMaterializer
import com.madamiak.twitch.model.api.JsonSupport._
import com.madamiak.twitch.model.api.{ Pagination, TwitchPayload }
import com.madamiak.twitch.model.{ RateLimit, TwitchResponse }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ AsyncWordSpec, Matchers }

import scala.concurrent.Future

class HttpClientSpec extends AsyncWordSpec with Matchers with ScalaFutures {

  implicit val system: ActorSystem             = ActorSystem("test-actor-system")
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  private val sut = new TwitchClient

  "twitch client" which {

    "integrates with the API" should {

      "handle building request" when {

        "working with valid input parameters" in {
          val path  = "/resource/segment"
          val query = Query.apply(("id", "123"))

          val httpRequest = sut.payloadRequest(path, query)

          httpRequest.map(_.getUri().toString shouldBe "https://api.twitch.tv/resource/segment?id=123")
          httpRequest.map(_.getHeader("Client-ID").get().value() shouldBe "1234abcd")
        }
      }

      "handle extracting results" when {

        "processing successful request" in {

          val entity = HttpEntity(
            ContentTypes.`application/json`,
            """
              | {
              |  "data":[1, 2, 3, 4, 5],
              |  "pagination":{
              |    "cursor":"refer"
              |  },
              |  "total":15
            }
            """.stripMargin
          )
          val httpResponse = HttpResponse()
            .withEntity(entity)
            .withHeaders(RawHeader("ratelimit-limit", "1"),
                         RawHeader("ratelimit-remaining", "3"),
                         RawHeader("ratelimit-reset", "4"))

          val result = sut.extractPayload[Int](httpResponse)

          whenReady(result) {
            _ shouldBe TwitchResponse(RateLimit(1, 3, 4),
                                      TwitchPayload[Int](
                                        Seq(1, 2, 3, 4, 5),
                                        Some(Pagination("refer")),
                                        Some(15)
                                      ))
          }
        }

        "processing unsuccessful request" in {
          val httpResponse = HttpResponse(status = StatusCodes.BadRequest)

          recoverToSucceededIf[TwitchAPIException](
            sut.extractPayload[Int](httpResponse)
          )
        }
      }

      "handle rate limits" when {

        "exceeded with large delay" in {
          val counter = new AtomicInteger()
          sut
            .recoverWhenRateLimitExceeded {
              if (counter.getAndIncrement() == 0) {
                Future.successful(
                  HttpResponse(status = StatusCodes.TooManyRequests)
                    .withHeaders(
                      RawHeader("ratelimit-limit", "120"),
                      RawHeader("ratelimit-remaining", "0"),
                      RawHeader("ratelimit-reset", Instant.now().plusSeconds(2).getEpochSecond.toString)
                    )
                )
              } else {
                Future.successful(HttpResponse().withStatus(StatusCodes.OK))
              }
            }
            .map(_.status shouldEqual StatusCodes.OK)
        }

        "exceeded with no delay" in {
          val counter = new AtomicInteger()
          sut
            .recoverWhenRateLimitExceeded {
              if (counter.getAndIncrement() == 0) {
                Future.successful(
                  HttpResponse(status = StatusCodes.TooManyRequests)
                    .withHeaders(
                      RawHeader("ratelimit-limit", "120"),
                      RawHeader("ratelimit-remaining", "0"),
                      RawHeader("ratelimit-reset", Instant.now().minusMillis(1).getEpochSecond.toString)
                    )
                )
              } else {
                Future.successful(HttpResponse().withStatus(StatusCodes.OK))
              }
            }
            .map(_.status shouldEqual StatusCodes.OK)
        }
      }
    }
  }
}
