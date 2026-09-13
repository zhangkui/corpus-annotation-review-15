package services

import jakarta.inject.{Inject, Singleton}
import models.DomainError.{InvalidInput, NotFound}
import models.{AnnotationInput, DomainError, MergedAnnotation, SubmitVersionRequest}
import repositories.{AnnotationRepository, SegmentRepository, TagRepository, TaskRepository, Tx}

/** 标注版本提交与合并展示（规则1） */
@Singleton
class AnnotationService @Inject() (
    tx: Tx,
    annotationRepo: AnnotationRepository,
    segmentRepo: SegmentRepository,
    tagRepo: TagRepository,
    taskRepo: TaskRepository,
    events: EventService
) {

  /** 规则1：相同片段同一标签范围的重复标注合并展示 */
  def merged(segmentId: Long): Either[DomainError, Seq[MergedAnnotation]] =
    segmentRepo.find(segmentId) match {
      case None    => Left(NotFound(s"片段 $segmentId 不存在"))
      case Some(_) => Right(annotationRepo.mergedBySegment(segmentId))
    }

  /** 提交一个标注版本（版本号自增，标注不可变） */
  def submitVersion(req: SubmitVersionRequest): Either[DomainError, models.AnnotationVersion] = {
    val assignment = taskRepo.findAssignment(req.assignmentId)
    if (assignment.isEmpty) return Left(NotFound(s"分配 ${req.assignmentId} 不存在"))
    if (req.annotations.isEmpty) return Left(InvalidInput("标注不能为空"))
    val taskId = assignment.get.taskId

    // 校验：片段属于任务、标签存在、偏移在片段码点长度内
    val errors = req.annotations.zipWithIndex.flatMap { case (a, i) =>
      val prefix = s"第 ${i + 1} 条标注"
      segmentRepo.find(a.segmentId) match {
        case None => Some(s"$prefix：片段 ${a.segmentId} 不存在")
        case Some(seg) if !taskRepo.taskContainsSegment(taskId, a.segmentId) =>
          Some(s"$prefix：片段 ${a.segmentId} 不属于任务 $taskId")
        case Some(seg) if tagRepo.find(a.tagId).isEmpty =>
          Some(s"$prefix：标签 ${a.tagId} 不存在")
        case Some(seg) if !Validators.offsetsValid(a.startOffset, a.endOffset, seg.charLength) =>
          Some(s"$prefix：字符偏移 [${a.startOffset}, ${a.endOffset}) 越界（片段码点长度 ${seg.charLength}）")
        case _ => None
      }
    }
    if (errors.nonEmpty) return Left(InvalidInput(errors.mkString("；")))

    val versionId = tx { c =>
      val versionNo = annotationRepo.maxVersionNo(req.assignmentId) + 1
      val vid = annotationRepo.createVersionIn(c, req.assignmentId, versionNo)
      req.annotations.foreach { a =>
        annotationRepo.insertAnnotationIn(c, vid, a.segmentId, a.tagId, a.startOffset, a.endOffset, a.note)
      }
      vid
    }
    taskRepo.markSubmitted(req.assignmentId)
    val v = annotationRepo.findVersion(versionId).get
    events.record("annotation.version_submitted",
      versionId = Some(v.id), actorId = Some(assignment.get.userId),
      extra = Map("assignmentId" -> req.assignmentId.toString, "versionNo" -> v.versionNo.toString))
    Right(v)
  }

  def annotationsOf(versionId: Long): Either[DomainError, (models.AnnotationVersion, Seq[models.Annotation])] =
    annotationRepo.findVersion(versionId) match {
      case None    => Left(NotFound(s"版本 $versionId 不存在"))
      case Some(v) => Right((v, annotationRepo.annotationsOfVersion(versionId)))
    }
}
