package controllers

import jakarta.inject.{Inject, Singleton}
import models.ImportRequest
import models.JsonSupport.given
import play.api.libs.json.Json
import play.api.mvc.{Action, ControllerComponents}
import services.ImportService

/** 批量导入（规则5：校验字符偏移与语言代码） */
@Singleton
class ImportController @Inject() (cc: ControllerComponents, importService: ImportService)
    extends ApiController(cc) {

  def importSegments = Action(parse.json) { request =>
    validated[ImportRequest](request.body) { req =>
      importService.importSegments(req) match {
        case Left(e) => statusOf(e)(errorJson(e.message))
        case Right(result) =>
          val partial = req.partial.getOrElse(false)
          if (result.errors.nonEmpty && !partial)
            // 全量模式：任何一行校验失败则整体拒绝
            UnprocessableEntity(Json.toJson(result))
          else
            Ok(Json.toJson(result))
      }
    }
  }
}
