package controllers

import jakarta.inject.{Inject, Singleton}
import models.SubmitVersionRequest
import models.JsonSupport.given
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import repositories.{AnnotationRepository, SegmentRepository}
import services.AnnotationService

@Singleton
class AnnotationController @Inject() (
    cc: ControllerComponents,
    annotationService: AnnotationService,
    annotationRepo: AnnotationRepository,
    segmentRepo: SegmentRepository
) extends ApiController(cc) {

  /** 规则1：相同片段同一标签范围的重复标注合并展示 */
  def merged(id: Long): Action[AnyContent] = Action {
    respond(annotationService.merged(id))
  }

  def versionsBySegment(id: Long): Action[AnyContent] = Action {
    segmentRepo.find(id) match {
      case None    => NotFound(errorJson(s"片段 $id 不存在"))
      case Some(_) => Ok(Json.toJson(annotationRepo.versionsBySegment(id)))
    }
  }

  def submitVersion = Action(parse.json) { request =>
    validated[SubmitVersionRequest](request.body) { req =>
      respond(annotationService.submitVersion(req), created = true)
    }
  }

  def getVersion(id: Long): Action[AnyContent] = Action {
    annotationService.annotationsOf(id) match {
      case Right((version, annotations)) =>
        Ok(Json.obj("version" -> Json.toJson(version), "annotations" -> Json.toJson(annotations)))
      case Left(e) => statusOf(e)(errorJson(e.message))
    }
  }
}
