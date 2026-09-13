package controllers

import jakarta.inject.{Inject, Singleton}
import models.{CreateTagRequest, ReparentRequest}
import models.JsonSupport.given
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import services.TagService

/** 标签层级（规则2：不能形成环） */
@Singleton
class TagController @Inject() (cc: ControllerComponents, tagService: TagService)
    extends ApiController(cc) {

  def list: Action[AnyContent] = Action {
    Ok(Json.toJson(tagService.list()))
  }

  def tree: Action[AnyContent] = Action {
    Ok(Json.toJson(tagService.tree()))
  }

  def create = Action(parse.json) { request =>
    validated[CreateTagRequest](request.body) { req =>
      respond(tagService.create(req.name, req.parentId), created = true)
    }
  }

  def reparent(id: Long) = Action(parse.json) { request =>
    validated[ReparentRequest](request.body) { req =>
      respond(tagService.reparent(id, req.parentId))
    }
  }
}
