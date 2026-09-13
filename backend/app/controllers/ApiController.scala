package controllers

import models.DomainError
import play.api.libs.json.{JsError, JsValue, Json, Reads, Writes}
import play.api.mvc.{AbstractController, ControllerComponents, Result}

/** 统一 JSON 响应与领域错误映射 */
abstract class ApiController(cc: ControllerComponents) extends AbstractController(cc) {

  protected def errorJson(message: String): JsValue = Json.obj("error" -> message)

  protected def respond[A: Writes](result: Either[DomainError, A], created: Boolean = false): Result =
    result match {
      case Right(a) => if (created) Created(Json.toJson(a)) else Ok(Json.toJson(a))
      case Left(e)  => statusOf(e)(errorJson(e.message))
    }

  protected def statusOf(e: DomainError): play.api.mvc.Results.Status = e match {
    case _: DomainError.NotFound     => NotFound
    case _: DomainError.RuleConflict => Conflict
    case _: DomainError.InvalidInput => UnprocessableEntity
  }

  protected def validated[A: Reads](body: JsValue)(f: A => Result): Result =
    body.validate[A].fold(
      errs => BadRequest(Json.obj("error" -> "请求体不合法", "details" -> JsError.toJson(errs))),
      f
    )
}
