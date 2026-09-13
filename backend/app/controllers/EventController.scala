package controllers

import jakarta.inject.{Inject, Singleton}
import models.JsonSupport.given
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import repositories.EventLogRepository

/** 事件流（TimescaleDB hypertable） */
@Singleton
class EventController @Inject() (cc: ControllerComponents, eventLog: EventLogRepository)
    extends ApiController(cc) {

  def latest(limit: Int): Action[AnyContent] = Action {
    val safeLimit = math.min(math.max(limit, 1), 500)
    Ok(Json.toJson(eventLog.latest(safeLimit)))
  }
}
