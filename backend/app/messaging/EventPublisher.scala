package messaging

import com.rabbitmq.client.{Channel, Connection, ConnectionFactory}
import jakarta.inject.{Inject, Singleton}
import play.api.inject.ApplicationLifecycle
import play.api.{Configuration, Logger}

import scala.concurrent.Future
import scala.util.Try

/**
 * RabbitMQ 连接管理 + 事件发布。
 * topic 交换机 `corpus.events`，队列 `event-log` 绑定 `#`。
 * 连接不可用时降级（publish 返回 false，由调用方直接落库）。
 */
@Singleton
class EventPublisher @Inject() (config: Configuration, lifecycle: ApplicationLifecycle) {
  private val logger = Logger(getClass)

  val exchange: String = config.get[String]("rabbitmq.exchange")
  val eventQueue: String = config.get[String]("rabbitmq.eventQueue")

  private val connection: Option[Connection] = Try {
    val f = new ConnectionFactory()
    f.setHost(config.get[String]("rabbitmq.host"))
    f.setPort(config.get[Int]("rabbitmq.port"))
    f.setUsername(config.get[String]("rabbitmq.username"))
    f.setPassword(config.get[String]("rabbitmq.password"))
    f.setAutomaticRecoveryEnabled(true)
    f.setTopologyRecoveryEnabled(true)
    f.newConnection("corpus-review-backend")
  }.fold(
    err => { logger.warn(s"RabbitMQ 连接失败，事件将直接写入 hypertable：${err.getMessage}"); None },
    Some(_)
  )

  private val channel: Option[Channel] = connection.flatMap { conn =>
    Try {
      val ch = conn.createChannel()
      ch.exchangeDeclare(exchange, "topic", true)
      ch.queueDeclare(eventQueue, true, false, false, null)
      ch.queueBind(eventQueue, exchange, "#")
      ch
    }.fold(
      err => { logger.warn(s"RabbitMQ 交换机/队列声明失败：${err.getMessage}"); None },
      Some(_)
    )
  }

  def available: Boolean = channel.exists(_.isOpen)

  /** 发布事件；失败返回 false（调用方降级直接写库） */
  def publish(routingKey: String, body: String): Boolean =
    channel.exists { ch =>
      Try {
        ch.basicPublish(exchange, routingKey, null, body.getBytes("UTF-8"))
      }.fold(
        err => { logger.warn(s"事件发布失败（$routingKey）：${err.getMessage}"); false },
        _ => true
      )
    }

  /** 为消费者创建独立通道 */
  def newConsumerChannel(): Option[Channel] =
    connection.flatMap(c => Try(c.createChannel()).toOption)

  lifecycle.addStopHook { () =>
    Future.successful {
      channel.foreach(ch => Try(ch.close()))
      connection.foreach(c => Try(c.close()))
    }
  }
}
