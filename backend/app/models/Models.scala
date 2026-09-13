package models

import java.time.Instant

// ---------------------------------------------------------------------------
// 领域模型
// ---------------------------------------------------------------------------

final case class AppUser(id: Long, username: String, displayName: String, role: String)

final case class Corpus(id: Long, name: String)

final case class Segment(
    id: Long,
    corpusId: Long,
    languageCode: String,
    content: String,
    charLength: Int,
    externalId: Option[String],
    createdAt: Instant
)

final case class Tag(id: Long, name: String, parentId: Option[Long])

/** 标签树节点（层级展示） */
final case class TagNode(id: Long, name: String, children: Seq[TagNode])

final case class Batch(id: Long, name: String)

final case class AnnotationTask(
    id: Long,
    batchId: Long,
    name: String,
    status: String,
    createdBy: Option[Long],
    createdAt: Instant
)

final case class Assignment(
    id: Long,
    taskId: Long,
    userId: Long,
    batchId: Long,
    assignedAt: Instant,
    status: String
)

final case class AssignmentView(
    id: Long,
    taskId: Long,
    userId: Long,
    username: String,
    batchId: Long,
    assignedAt: Instant,
    status: String
)

final case class AnnotationVersion(
    id: Long,
    assignmentId: Long,
    versionNo: Int,
    status: String,
    createdAt: Instant
)

final case class Annotation(
    id: Long,
    versionId: Long,
    segmentId: Long,
    tagId: Long,
    startOffset: Int,
    endOffset: Int,
    note: Option[String]
)

/** 单条标注来源（合并展示用） */
final case class AnnotationSource(
    annotationId: Long,
    versionId: Long,
    annotator: String,
    note: Option[String]
)

/** 规则1：相同片段同一标签范围的重复标注合并后的展示单元 */
final case class MergedAnnotation(
    tagId: Long,
    tagName: String,
    startOffset: Int,
    endOffset: Int,
    count: Int,
    sources: Seq[AnnotationSource]
)

final case class ConclusionAnnotation(
    id: Long,
    tagId: Long,
    tagName: String,
    startOffset: Int,
    endOffset: Int,
    note: Option[String]
)

final case class Arbitration(
    id: Long,
    segmentId: Long,
    arbitratorId: Long,
    arbitratorName: String,
    comment: Option[String],
    createdAt: Instant,
    sourceVersionIds: Seq[Long],
    conclusions: Seq[ConclusionAnnotation]
)

final case class AnnotationEvent(
    time: Instant,
    eventId: String,
    eventType: String,
    segmentId: Option[Long],
    versionId: Option[Long],
    actorId: Option[Long],
    payload: String
)

// ---------------------------------------------------------------------------
// 请求 DTO
// ---------------------------------------------------------------------------

final case class CreateUserRequest(username: String, displayName: String, role: String)
final case class CreateCorpusRequest(name: String)
final case class CreateSegmentRequest(
    corpusId: Long,
    languageCode: String,
    content: String,
    externalId: Option[String]
)
final case class CreateTagRequest(name: String, parentId: Option[Long])
final case class ReparentRequest(parentId: Option[Long])
final case class CreateBatchRequest(name: String)
final case class CreateTaskRequest(
    batchId: Long,
    name: String,
    createdBy: Option[Long],
    segmentIds: Seq[Long]
)
final case class AssignRequest(userId: Long)
final case class AutoAssignRequest(count: Int)

final case class AnnotationInput(
    segmentId: Long,
    tagId: Long,
    startOffset: Int,
    endOffset: Int,
    note: Option[String]
)
final case class SubmitVersionRequest(assignmentId: Long, annotations: Seq[AnnotationInput])

final case class ConclusionInput(tagId: Long, startOffset: Int, endOffset: Int, note: Option[String])
final case class CreateArbitrationRequest(
    segmentId: Long,
    arbitratorId: Long,
    sourceVersionIds: Seq[Long],
    comment: Option[String],
    conclusions: Seq[ConclusionInput]
)

final case class ImportAnnotation(tag: String, startOffset: Int, endOffset: Int)
final case class ImportSegment(
    externalId: Option[String],
    languageCode: String,
    content: String,
    annotations: Seq[ImportAnnotation]
)
final case class ImportRequest(corpus: String, partial: Option[Boolean], segments: Seq[ImportSegment])
final case class ImportRowError(row: Int, message: String)
final case class ImportResult(imported: Int, skipped: Int, errors: Seq[ImportRowError])

// ---------------------------------------------------------------------------
// 领域错误
// ---------------------------------------------------------------------------

sealed trait DomainError { def message: String }
object DomainError {
  /** 资源不存在 → 404 */
  final case class NotFound(message: String) extends DomainError
  /** 业务规则冲突（环、连续批次、重复分配等）→ 409 */
  final case class RuleConflict(message: String) extends DomainError
  /** 输入校验失败（偏移越界、非法语言代码等）→ 422 */
  final case class InvalidInput(message: String) extends DomainError
}
