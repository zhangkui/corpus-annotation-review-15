package services

import jakarta.inject.{Inject, Singleton}
import models.DomainError.{InvalidInput, NotFound, RuleConflict}
import models.{Assignment, DomainError}
import repositories.{TaskRepository, UserRepository}

/** 任务分配（规则4：避免同一用户连续获得同一批次） */
@Singleton
class AssignmentService @Inject() (
    taskRepo: TaskRepository,
    userRepo: UserRepository,
    events: EventService
) {

  def assign(taskId: Long, userId: Long): Either[DomainError, Assignment] = {
    val task = taskRepo.findTask(taskId)
    val user = userRepo.find(userId)
    (task, user) match {
      case (None, _) => Left(NotFound(s"任务 $taskId 不存在"))
      case (_, None) => Left(NotFound(s"用户 $userId 不存在"))
      case (_, Some(u)) if u.role != "annotator" && u.role != "admin" =>
        Left(InvalidInput(s"用户 ${u.username} 的角色是 ${u.role}，不能分配标注任务"))
      case (Some(t), Some(u)) =>
        if (taskRepo.assignmentExists(taskId, userId))
          Left(RuleConflict(s"用户 ${u.username} 已被分配任务 $taskId"))
        else {
          // 规则4：检查该用户最近一次分配的批次
          taskRepo.latestAssignmentOf(userId) match {
            case Some(last) if last.batchId == t.batchId =>
              Left(RuleConflict(
                s"同一用户不能连续获得同一批次：用户 ${u.username} 最近一次分配（任务 ${last.taskId}）" +
                s"已属于批次 ${t.batchId}"))
            case _ =>
              val a = taskRepo.createAssignment(taskId, userId, t.batchId)
              events.record("task.assigned", actorId = Some(userId),
                extra = Map("taskId" -> taskId.toString, "batchId" -> t.batchId.toString))
              Right(a)
          }
        }
    }
  }

  /** 自动分配：只挑选最近批次与当前批次不同的标注员 */
  def autoAssign(taskId: Long, count: Int): Either[DomainError, Seq[Assignment]] = {
    if (count <= 0) Left(InvalidInput("分配数量必须为正数"))
    else taskRepo.findTask(taskId) match {
      case None => Left(NotFound(s"任务 $taskId 不存在"))
      case Some(t) =>
        val candidates = taskRepo.eligibleAnnotators(t.batchId, count + 10) // 多取一些，过滤已分配者
        val chosen = candidates.filterNot(uid => taskRepo.assignmentExists(taskId, uid)).take(count)
        if (chosen.isEmpty)
          Left(RuleConflict(s"没有可分配的标注员（规则4：最近批次不能与本批次 ${t.batchId} 相同）"))
        else {
          val created = chosen.map(uid => taskRepo.createAssignment(taskId, uid, t.batchId))
          created.foreach(a => events.record("task.assigned", actorId = Some(a.userId),
            extra = Map("taskId" -> taskId.toString, "batchId" -> t.batchId.toString, "auto" -> "true")))
          Right(created)
        }
    }
  }
}
