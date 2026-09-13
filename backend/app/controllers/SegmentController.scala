package controllers

import jakarta.inject.{Inject, Singleton}
import models.CreateSegmentRequest
import models.JsonSupport.given
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import repositories.{CorpusRepository, SegmentRepository}
import services.Validators

@Singleton
class SegmentController @Inject() (
    cc: ControllerComponents,
    segmentRepo: SegmentRepository,
    corpusRepo: CorpusRepository
) extends ApiController(cc) {

  def list(corpusId: Option[Long], language: Option[String], offset: Int, limit: Int): Action[AnyContent] =
    Action {
      val safeLimit = math.min(math.max(limit, 1), 200)
      val safeOffset = math.max(offset, 0)
      Ok(Json.toJson(segmentRepo.list(corpusId, language, safeOffset, safeLimit)))
    }

  def get(id: Long): Action[AnyContent] = Action {
    segmentRepo.find(id) match {
      case Some(seg) => Ok(Json.toJson(seg))
      case None      => NotFound(errorJson(s"片段 $id 不存在"))
    }
  }

  def create = Action(parse.json) { request =>
    validated[CreateSegmentRequest](request.body) { req =>
      if (!Validators.isValidLanguageCode(req.languageCode))
        UnprocessableEntity(errorJson(s"非法语言代码「${req.languageCode}」（需符合 BCP-47 且主子标签受支持）"))
      else if (req.content.isEmpty)
        UnprocessableEntity(errorJson("内容不能为空"))
      else if (corpusRepo.find(req.corpusId).isEmpty)
        NotFound(errorJson(s"语料库 ${req.corpusId} 不存在"))
      else if (req.externalId.exists(eid => segmentRepo.existsExternal(req.corpusId, eid)))
        Conflict(errorJson(s"external_id「${req.externalId.get}」在该语料库中已存在"))
      else {
        val len = Validators.codePointLength(req.content)
        val seg = segmentRepo.create(req.corpusId, req.languageCode, req.content, len, req.externalId)
        Created(Json.toJson(seg))
      }
    }
  }
}
