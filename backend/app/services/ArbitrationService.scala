package services

import jakarta.inject.{Inject, Singleton}
import models.DomainError.{InvalidInput, NotFound}
import models.{Arbitration, CreateArbitrationRequest, DomainError}
import repositories.{AnnotationRepository, ArbitrationRepository, SegmentRepository, TagRepository, Tx, UserRepository}

/** 仲裁（规则3：只能引用已有标注版本并生成新结论） */
@Singleton
class ArbitrationService @Inject() (
    tx: Tx,
    arbitrationRepo: ArbitrationRepository,
    annotationRepo: AnnotationRepository,
    segmentRepo: SegmentRepository,
    tagRepo: TagRepository,
    userRepo: UserRepository,
    events: EventService
) {

  def create(req: CreateArbitrationRequest): Either[DomainError, Arbitration] = {
    // 片段存在
    val segment = segmentRepo.find(req.segmentId) match {
      case None    => return Left(NotFound(s"片段 ${req.segmentId} 不存在"))
      case Some(s) => s
    }
    // 仲裁员存在且角色合法
    userRepo.find(req.arbitratorId) match {
      case None => return Left(NotFound(s"用户 ${req.arbitratorId} 不存在"))
      case Some(u) if u.role != "arbitrator" && u.role != "admin" =>
        return Left(InvalidInput(s"用户 ${u.username} 的角色是 ${u.role}，不能执行仲裁"))
      case _ =>
    }
    // 规则3：必须引用至少一个已存在的标注版本
    if (req.sourceVersionIds.isEmpty)
      return Left(InvalidInput("仲裁必须引用至少一个已有标注版本"))
    val distinctIds = req.sourceVersionIds.distinct
    val existing = annotationRepo.versionsExist(distinctIds)
    val missing = distinctIds.filterNot(existing.contains)
    if (missing.nonEmpty)
      return Left(InvalidInput(s"仲裁只能引用已有标注版本：版本 ${missing.mkString(", ")} 不存在"))
    val notCovering = distinctIds.filterNot(v => annotationRepo.versionCoversSegment(v, req.segmentId))
    if (notCovering.nonEmpty)
      return Left(InvalidInput(s"版本 ${notCovering.mkString(", ")} 的任务不覆盖片段 ${req.segmentId}，不能作为仲裁依据"))
    // 结论校验：标签存在、偏移合法
    if (req.conclusions.isEmpty)
      return Left(InvalidInput("仲裁结论不能为空"))
    val errors = req.conclusions.zipWithIndex.flatMap { case (cl, i) =>
      val prefix = s"第 ${i + 1} 条结论"
      if (tagRepo.find(cl.tagId).isEmpty) Some(s"$prefix：标签 ${cl.tagId} 不存在")
      else if (!Validators.offsetsValid(cl.startOffset, cl.endOffset, segment.charLength))
        Some(s"$prefix：字符偏移 [${cl.startOffset}, ${cl.endOffset}) 越界（片段码点长度 ${segment.charLength}）")
      else None
    }
    if (errors.nonEmpty) return Left(InvalidInput(errors.mkString("；")))

    // 事务：创建仲裁 + 引用 + 新结论，并将被引用版本标记为已仲裁
    val arbitrationId = tx { c =>
      val id = arbitrationRepo.createIn(c, req.segmentId, req.arbitratorId, req.comment)
      distinctIds.foreach(v => arbitrationRepo.addSourceIn(c, id, v))
      req.conclusions.foreach { cl =>
        arbitrationRepo.addConclusionIn(c, id, cl.tagId, cl.startOffset, cl.endOffset, cl.note)
      }
      id
    }
    annotationRepo.markVersionsArbitrated(distinctIds)
    events.record("arbitration.completed",
      segmentId = Some(req.segmentId), actorId = Some(req.arbitratorId),
      extra = Map("arbitrationId" -> arbitrationId.toString,
                  "sourceVersions" -> distinctIds.mkString(",")))
    Right(arbitrationRepo.find(arbitrationId).get)
  }

  def get(id: Long): Either[DomainError, Arbitration] =
    arbitrationRepo.find(id).toRight(NotFound(s"仲裁 $id 不存在"): DomainError)

  def bySegment(segmentId: Long): Either[DomainError, Seq[Arbitration]] =
    segmentRepo.find(segmentId) match {
      case None    => Left(NotFound(s"片段 $segmentId 不存在"))
      case Some(_) => Right(arbitrationRepo.bySegment(segmentId))
    }
}
