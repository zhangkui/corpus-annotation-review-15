package controllers

import jakarta.inject.{Inject, Singleton}
import models.{AssignRequest, AutoAssignRequest, CreateBatchRequest, CreateTaskRequest}
import models.JsonSupport.given
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import repositories.{SegmentRepository, TaskRepository, UserRepository}
import services.AssignmentService

@Singleton
class TaskController @Inject() (
    cc: ControllerComponents,
    taskRepo: TaskRepository,
    segmentRepo: SegmentRepository,
    userRepo: UserRepository,
    assignmentService: AssignmentService
) extends ApiController(cc) {

  // ---- 批次 ----

  def listBatches: Action[AnyContent] = Action {
    Ok(Json.toJson(taskRepo.listBatches()))
  }

  def createBatch = Action(parse.json) { request =>
    validated[CreateBatchRequest](request.body) { req =>
      if (req.name.trim.isEmpty) BadRequest(errorJson("批次名不能为空"))
      else if (taskRepo.listBatches().exists(_.name == req.name.trim))
        Conflict(errorJson(s"批次「${req.name}」已存在"))
      else Created(Json.toJson(taskRepo.createBatch(req.name.trim)))
    }
  }

  // ---- 任务 ----

  def listTasks: Action[AnyContent] = Action {
    Ok(Json.toJson(taskRepo.listTasks()))
  }

  def getTask(id: Long): Action[AnyContent] = Action {
    taskRepo.findTask(id) match {
      case None => NotFound(errorJson(s"任务 $id 不存在"))
      case Some(t) =>
        Ok(Json.obj(
          "task" -> Json.toJson(t),
          "segmentIds" -> Json.toJson(taskRepo.taskSegmentIds(id))
        ))
    }
  }

  def createTask = Action(parse.json) { request =>
    validated[CreateTaskRequest](request.body) { req =>
      if (req.name.trim.isEmpty) BadRequest(errorJson("任务名不能为空"))
      else if (taskRepo.findBatch(req.batchId).isEmpty)
        NotFound(errorJson(s"批次 ${req.batchId} 不存在"))
      else if (req.createdBy.exists(uid => userRepo.find(uid).isEmpty))
        NotFound(errorJson(s"创建人 ${req.createdBy.get} 不存在"))
      else {
        val missing = req.segmentIds.filter(sid => segmentRepo.find(sid).isEmpty)
        if (missing.nonEmpty)
          UnprocessableEntity(errorJson(s"片段不存在：${missing.mkString(", ")}"))
        else
          Created(Json.toJson(taskRepo.createTask(req.batchId, req.name.trim, req.createdBy, req.segmentIds.distinct)))
      }
    }
  }

  def taskSegments(id: Long): Action[AnyContent] = Action {
    if (taskRepo.findTask(id).isEmpty) NotFound(errorJson(s"任务 $id 不存在"))
    else {
      val segments = taskRepo.taskSegmentIds(id).flatMap(segmentRepo.find)
      Ok(Json.toJson(segments))
    }
  }

  // ---- 分配（规则4） ----

  def assignments(id: Long): Action[AnyContent] = Action {
    if (taskRepo.findTask(id).isEmpty) NotFound(errorJson(s"任务 $id 不存在"))
    else Ok(Json.toJson(taskRepo.assignmentsOfTask(id)))
  }

  def assign(id: Long) = Action(parse.json) { request =>
    validated[AssignRequest](request.body) { req =>
      respond(assignmentService.assign(id, req.userId), created = true)
    }
  }

  def autoAssign(id: Long) = Action(parse.json) { request =>
    validated[AutoAssignRequest](request.body) { req =>
      respond(assignmentService.autoAssign(id, req.count), created = true)
    }
  }
}
