package controllers

import jakarta.inject.{Inject, Singleton}
import messaging.EventPublisher
import play.api.db.Database
import play.api.libs.json.Json
import play.api.mvc.ControllerComponents

import scala.util.Try

@Singleton
class HealthController @Inject() (
    cc: ControllerComponents,
    db: Database,
    publisher: EventPublisher
) extends ApiController(cc) {

  def health = Action {
    val dbOk = Try(db.withConnection(c => c.isValid(2))).getOrElse(false)
    val json = Json.obj(
      "status"   -> (if (dbOk) "ok" else "degraded"),
      "database" -> (if (dbOk) "up" else "down"),
      "rabbitmq" -> (if (publisher.available) "up" else "down")
    )
    if (dbOk) Ok(json) else ServiceUnavailable(json)
  }
}
