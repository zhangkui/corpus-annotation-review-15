package models

import play.api.libs.json.*

import java.time.Instant

/** 全部 JSON 序列化/反序列化 givens（Play JSON, Scala 3 宏派生） */
object JsonSupport {

  given Format[Instant] = Format(
    Reads(js => js.validate[String].map(s => Instant.parse(s))),
    Writes(i => JsString(i.toString))
  )

  // ---- 领域模型（写） ----
  given Writes[AppUser]             = Json.writes
  given Writes[Corpus]              = Json.writes
  given Writes[Segment]             = Json.writes
  given Writes[Tag]                 = Json.writes
  // 递归类型手写 Writes，避免宏自引用问题
  given Writes[TagNode] = Writes { node => tagNodeJson(node) }
  private def tagNodeJson(node: TagNode): JsValue = Json.obj(
    "id"       -> node.id,
    "name"     -> node.name,
    "children" -> node.children.map(tagNodeJson)
  )
  given Writes[Batch]               = Json.writes
  given Writes[AnnotationTask]      = Json.writes
  given Writes[Assignment]          = Json.writes
  given Writes[AssignmentView]      = Json.writes
  given Writes[AnnotationVersion]   = Json.writes
  given Writes[Annotation]          = Json.writes
  given Writes[AnnotationSource]    = Json.writes
  given Writes[MergedAnnotation]    = Json.writes
  given Writes[ConclusionAnnotation] = Json.writes
  given Writes[Arbitration]         = Json.writes
  given Writes[AnnotationEvent]     = Json.writes
  given Writes[ImportRowError]      = Json.writes
  given Writes[ImportResult]        = Json.writes

  // ---- 请求 DTO（读） ----
  given Reads[CreateUserRequest]        = Json.reads
  given Reads[CreateCorpusRequest]      = Json.reads
  given Reads[CreateSegmentRequest]     = Json.reads
  given Reads[CreateTagRequest]         = Json.reads
  given Reads[ReparentRequest]          = Json.reads
  given Reads[CreateBatchRequest]       = Json.reads
  given Reads[CreateTaskRequest]        = Json.reads
  given Reads[AssignRequest]            = Json.reads
  given Reads[AutoAssignRequest]        = Json.reads
  given Reads[AnnotationInput]          = Json.reads
  given Reads[SubmitVersionRequest]     = Json.reads
  given Reads[ConclusionInput]          = Json.reads
  given Reads[CreateArbitrationRequest] = Json.reads
  given Reads[ImportAnnotation]         = Json.reads
  given Reads[ImportSegment]            = Json.reads
  given Reads[ImportRequest]            = Json.reads
}
