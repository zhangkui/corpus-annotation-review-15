package messaging

import com.rabbitmq.client.{CancelCallback, DeliverCallback}
import jakarta.inject.{Inject, Singleton}
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import repositories.EventLogRepository

import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.util.Try

/**
 * 消费 `event-log` 队列，把事件写入 TimescaleDB hypertable `annotation_event`。
 * 若 RabbitMQ 不可用则不启动（发布方已降级为直接落库）。
 */
@Singleton
class EventConsumer @Inject() (publisher: EventPublisher, eventLog: EventLogRepository) {
  private val logger = Logger(getClass)

  private val started: Boolean =
    if (!publisher.available) {
      logger.warn("RabbitMQ 不可用，事件消费者未启动（事件将由发布方直接落库）")
      false
    } else {
      publisher.newConsumerChannel() match {
        case Some(ch) =>
          val deliver: DeliverCallback = (_, delivery) => {
            Try {
              val json: JsValue = Json.parse(new String(delivery.getBody, StandardCharsets.UTF_8))
              val eventId = UUID.fromString((json \ "eventId").as[String])
              val eventType = (json \ "eventType").as[String]
              val segmentId = (json \ "segmentId").asOpt[Long]
              val versionId = (json \ "versionId").asOpt[Long]
              val actorId = (json \ "actorId").asOpt[Long]
              eventLog.insert(eventId, eventType, segmentId, versionId, actorId, Json.stringify(json))
              ch.basicAck(delivery.getEnvelope.getDeliveryTag, false)
            }.recover { case e =>
              logger.error(s"事件消费失败：${e.getMessage}")
              ch.basicNack(delivery.getEnvelope.getDeliveryTag, false, false)
            }
          }
          val cancel: CancelCallback = _ => logger.warn("事件消费者被取消")
          ch.basicConsume(publisher.eventQueue, false, deliver, cancel)
          logger.info(s"事件消费者已启动，监听队列 ${publisher.eventQueue}")
          true
        case None =>
          logger.warn("无法创建消费者通道")
          false
      }
    }

  def isRunning: Boolean = started
}
