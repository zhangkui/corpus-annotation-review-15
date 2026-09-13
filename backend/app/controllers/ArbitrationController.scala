package controllers

import jakarta.inject.{Inject, Singleton}
import models.CreateArbitrationRequest
import models.JsonSupport.given
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import services.ArbitrationService

/** 仲裁（规则3：只能引用已有标注版本并生成新结论） */
@Singleton
class ArbitrationController @Inject() (cc: ControllerComponents, arbitrationService: ArbitrationService)
    extends ApiController(cc) {

  def create = Action(parse.json) { request =>
    validated[CreateArbitrationRequest](request.body) { req =>
      respond(arbitrationService.create(req), created = true)
    }
  }

  def get(id: Long): Action[AnyContent] = Action {
    respond(arbitrationService.get(id))
  }

  def bySegment(id: Long): Action[AnyContent] = Action {
    respond(arbitrationService.bySegment(id))
  }
}
