package controllers

import jakarta.inject.{Inject, Singleton}
import models.CreateUserRequest
import models.JsonSupport.given
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import repositories.UserRepository

@Singleton
class UserController @Inject() (cc: ControllerComponents, userRepo: UserRepository)
    extends ApiController(cc) {

  private val Roles = Set("annotator", "arbitrator", "admin")

  def list: Action[AnyContent] = Action {
    Ok(Json.toJson(userRepo.list()))
  }

  def create = Action(parse.json) { request =>
    validated[CreateUserRequest](request.body) { req =>
      if (req.username.trim.isEmpty || req.displayName.trim.isEmpty)
        BadRequest(errorJson("用户名与显示名不能为空"))
      else if (!Roles.contains(req.role))
        UnprocessableEntity(errorJson(s"角色必须是 ${Roles.mkString("/")} 之一"))
      else if (userRepo.findByName(req.username.trim).isDefined)
        Conflict(errorJson(s"用户名「${req.username}」已存在"))
      else
        Created(Json.toJson(userRepo.create(req.username.trim, req.displayName.trim, req.role)))
    }
  }
}
