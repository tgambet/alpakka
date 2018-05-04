/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.amqp.scaladsl

import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.dispatch.ExecutionContexts
import akka.stream.ActorMaterializer
import akka.stream.alpakka.amqp.{
  AmqpCachedConnectionProvider,
  AmqpConnectionFactoryConnectionProvider,
  AmqpProxyConnection,
  AmqpSinkSettings,
  QueueDeclaration
}
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.rabbitmq.client.{AddressResolver, Connection, ConnectionFactory, ShutdownListener}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpec}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
 * see [[https://github.com/akka/alpakka/issues/883]] and
 * [[https://github.com/akka/alpakka/pull/887]]
 */
class AmqpGraphStageLogicConnectionShutdownSpec
    extends WordSpec
    with Matchers
    with ScalaFutures
    with BeforeAndAfterEach {

  override implicit val patienceConfig = PatienceConfig(10.seconds)
  private implicit val executionContext = ExecutionContexts.sameThreadExecutionContext

  val shutdownsAdded = new AtomicInteger()
  val shutdownsRemoved = new AtomicInteger()

  override def beforeEach() = {
    shutdownsAdded.set(0)
    shutdownsRemoved.set(0)
  }

  trait ShutdownListenerTracking { self: AmqpProxyConnection =>
    override def addShutdownListener(shutdownListener: ShutdownListener): Unit = {
      shutdownsAdded.incrementAndGet()
      self.delegate.addShutdownListener(shutdownListener)
    }

    override def removeShutdownListener(shutdownListener: ShutdownListener): Unit = {
      shutdownsRemoved.incrementAndGet()
      self.delegate.removeShutdownListener(shutdownListener)
    }
  }

  "registers and unregisters a single connection shutdown hook per graph" in {
    // actor system is within this test as it has to be shut down in order
    // to verify graph stage termination
    implicit val system = ActorSystem(this.getClass.getSimpleName + System.currentTimeMillis())
    implicit val materializer = ActorMaterializer()

    val connectionFactory = new ConnectionFactory() {
      override def newConnection(es: ExecutorService, ar: AddressResolver, name: String): Connection =
        new AmqpProxyConnection(super.newConnection(es, ar, name)) with ShutdownListenerTracking
    }
    val connectionProvider = AmqpConnectionFactoryConnectionProvider(connectionFactory, List(("localhost", 5672)))
    val reusableConnectionProvider = AmqpCachedConnectionProvider(provider = connectionProvider)

    def queueName = "amqp-conn-prov-it-spec-simple-queue-" + System.currentTimeMillis()
    val queueDeclaration = QueueDeclaration(queueName)

    val amqpSink = AmqpSink.simple(
      AmqpSinkSettings(reusableConnectionProvider)
        .withRoutingKey(queueName)
        .withDeclarations(queueDeclaration)
    )

    val input = Vector("one", "two", "three", "four")

    Future
      .traverse(input)(in => Source.single(ByteString(in)).runWith(amqpSink))
      .recover {
        case NonFatal(e) => system.terminate().flatMap(_ => Future.failed(e))
      }
      .flatMap(_ => system.terminate())
      .futureValue

    shutdownsAdded.get() should equal(input.size)
    shutdownsRemoved.get() should equal(input.size)
  }
}