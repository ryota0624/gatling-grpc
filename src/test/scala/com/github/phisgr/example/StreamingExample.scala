package com.github.phisgr.example

import java.util.UUID
import java.util.concurrent.{ThreadLocalRandom, TimeUnit}

import ch.qos.logback.classic.Level
import com.github.phisgr.example.chat._
import com.github.phisgr.example.util.{ErrorResponseKey, TokenHeaderKey, tuneLogging}
import com.github.phisgr.gatling.generic.SessionCombiner
import com.github.phisgr.gatling.grpc.Predef._
import com.github.phisgr.gatling.grpc.protocol.GrpcProtocol
import com.github.phisgr.gatling.grpc.stream.TimestampExtractor
import com.github.phisgr.gatling.pb._
import com.google.protobuf.empty.Empty
import io.gatling.commons.validation.{Failure, Validation}
import io.gatling.core.Predef._
import io.gatling.core.check.Matcher
import io.gatling.core.session.Expression
import io.grpc.{CallOptions, Status}

import scala.concurrent.duration._

class StreamingExample extends Simulation {
  TestServer.startServer()

  tuneLogging(classOf[GrpcProtocol].getName, Level.INFO)

  val timeExpression: Expression[Long] = { _ =>
    // pretend there's clock differences
    System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(-5, 5)
  }

  val listenCall = grpc("Listen")
    .serverStream(streamName = "listener")
  val chatCall = grpc("Chat")
    .bidiStream(streamName = "chatter")
  val complete = chatCall.copy(requestName = "Complete").complete

  val grpcConf = grpc(managedChannelBuilder(target = "localhost:8080").usePlaintext())
    .shareChannel

  val listener = scenario("Listener")
    .exec(
      listenCall
        .start(ChatServiceGrpc.METHOD_LISTEN)(Empty.defaultInstance)
        .timestampExtractor { (session, message, _) =>
          if (session.userId < 200) message.time - 10 else TimestampExtractor.IgnoreMessage
        }
        .extract(_.username.some)(_ saveAs "previousUsername")
        .sessionCombiner(SessionCombiner.pick("previousUsername"))
        .endCheck(statusCode is Status.Code.OK)
    )
    .repeat(5) {
      pause(10.seconds)
        .exec(listenCall.copy(requestName = "Reconciliate").reconciliate)
        .exec { session =>
          if (session.userId == 100) {
            println(s"previousUsername is ${session.attributes.get("previousUsername")}")
          }
          session
        }
    }
    .exec(listenCall.cancelStream)

  val chatter = scenario("Chatter")
    .exec(_.set("username", UUID.randomUUID().toString))
    .exec(
      chatCall.connect(ChatServiceGrpc.METHOD_CHAT)
        .endCheck(trailer(ErrorResponseKey).notExists)
        .endCheck(statusCode is Status.Code.OK)
    )
    .exec(
      // for code coverage only
      // not used at all because of duplicated stream name
      grpc("Already Exists")
        .bidiStream(streamName = "chatter")
        .connect(ChatServiceGrpc.METHOD_CHAT)
        .extract(_.time.some)(_ gt System.currentTimeMillis())
        .callOptions(CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.HOURS))
        .timestampExtractor(TimestampExtractor.Ignore)
        .sessionCombiner(SessionCombiner.NoOp)
    )
    .exec(
      grpc("Wrong Message")
        .bidiStream(streamName = "chatter")
        .send(Empty.defaultInstance)
    )
    .exec(
      grpc("Wrong Name")
        .bidiStream(streamName = "Chatter")
        .send(Empty.defaultInstance)
    )
    .during(55.seconds) {
      pause(500.millis, 1.second)
        .exec(
          chatCall.send(
            ChatMessage.defaultInstance.updateExpr(
              _.username :~ $("username"),
              _.data :~ "${username} says hi!",
              _.time :~ timeExpression
            )
          )
        )
    }
    .exec(complete)
    .exec(chatCall.copy(requestName = "Send after complete").send(ChatMessage.defaultInstance))
    .exec(complete)


  val endsWithHi: Matcher[String] = new Matcher[String] {
    override protected def doMatch(actual: Option[String]): Validation[Option[String]] =
      if (actual.exists(_.endsWith("hi"))) actual else {
        val actualEnding = actual.map(_.split(' ').last)
        Failure(s"ends with $actualEnding")
      }

    override def name: String = "endsWith(hi)"
  }
  val failure = scenario("Failures")
    .feed(List(true, false).map(b => Map("earlyStop" -> b)).iterator)
    .exec(
      listenCall
        .copy(requestName = "Cannot build")
        .start(ChatServiceGrpc.METHOD_LISTEN)(Empty.defaultInstance)
        .header(TokenHeaderKey)($("whatever"))
    )
    .exec(
      chatCall.copy("Fail")
        .connect(ChatServiceGrpc.METHOD_CHAT)
        .timestampExtractor { (_, message, _) =>
          if (ThreadLocalRandom.current().nextBoolean()) message.time - 100 else throw new IllegalStateException()
        }
        .extract(_.data.some)(_ validate endsWithHi)
        .endCheck(statusCode is Status.Code.CANCELLED)
    )
    .pause(2.seconds)
    .doIf($("earlyStop")) {
      exec(chatCall.complete)
    }

  setUp(
    chatter.inject(atOnceUsers(10)),
    // if a virtual user enters at a second later than all exits, log analyzing fails
    listener.inject(atOnceUsers(100), rampUsers(10000).during(59.seconds)),
    failure.inject(atOnceUsers(2))
  ).protocols(grpcConf).maxDuration(1.minute).exponentialPauses
}
