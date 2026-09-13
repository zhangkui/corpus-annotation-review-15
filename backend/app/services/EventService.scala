package services

import jakarta.inject.{Inject, Singleton}
import messaging.EventPublisher
import play.api.Logger
import play.api.libs.json.Json
import repositories.EventLogRepository

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.util.Try

/**
 * 领域事件记录：优先经 RabbitMQ（由消费者落 hypertable），
 * 消息中间件不可用时降级为直接写库，保证功能可用。
 */
@Singleton
class EventService @Inject() (
    publisher: EventPublisher,
    eventLog: EventLogRepository
)(implicit ec: ExecutionContext) {
  private val logger = Logger(getClass)

  def record(eventType: String,
             segmentId: Option[Long] = None,
             versionId: Option[Long] = None,
             actorId: Option[Long] = None,
             extra: Map[String, String] = Map.empty): Unit = {
    val eventId = UUID.randomUUID()
    val base = Json.obj(
      "eventId"   -> eventId.toString,
      "eventType" -> eventType,
      "segmentId" -> segmentId,
      "versionId" -> versionId,
      "actorId"   -> actorId
    )
    val payload = base ++ play.api.libs.json.JsObject(extra.view.mapValues(v => play.api.libs.json.JsString(v)).toMap)

    // 异步执行，避免阻塞请求线程
    ec.execute(() => {
      val published = Try(publisher.publish(eventType, Json.stringify(payload))).getOrElse(false)
      if (!published) {
        Try(eventLog.insert(eventId, eventType, segmentId, versionId, actorId, Json.stringify(payload)))
          .failed.foreach(e => logger.error(s"事件落库失败：${e.getMessage}"))
      }
    })
  }
}
