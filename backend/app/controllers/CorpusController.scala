package controllers

import jakarta.inject.{Inject, Singleton}
import models.CreateCorpusRequest
import models.JsonSupport.given
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import repositories.CorpusRepository

@Singleton
class CorpusController @Inject() (cc: ControllerComponents, corpusRepo: CorpusRepository)
    extends ApiController(cc) {

  def list: Action[AnyContent] = Action {
    Ok(Json.toJson(corpusRepo.list()))
  }

  def create = Action(parse.json) { request =>
    validated[CreateCorpusRequest](request.body) { req =>
      if (req.name.trim.isEmpty) BadRequest(errorJson("语料库名不能为空"))
      else if (corpusRepo.findByName(req.name.trim).isDefined)
        Conflict(errorJson(s"语料库「${req.name}」已存在"))
      else Created(Json.toJson(corpusRepo.create(req.name.trim)))
    }
  }
}
