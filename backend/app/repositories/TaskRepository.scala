package repositories

import jakarta.inject.{Inject, Singleton}
import models.{AnnotationTask, Assignment, AssignmentView, Batch}
import play.api.db.Database

import java.sql.Connection

@Singleton
class TaskRepository @Inject() (db: Database) extends BaseRepository(db) {
  import Jdbc.*

  // ---- 批次 ----

  private val batchMapper: java.sql.ResultSet => Batch = rs =>
    Batch(rs.getLong("id"), rs.getString("name"))

  def listBatches(): List[Batch] =
    read(c => query(c, "SELECT * FROM batch ORDER BY id")(batchMapper))

  def findBatch(id: Long): Option[Batch] =
    read(c => queryOne(c, "SELECT * FROM batch WHERE id = ?", id)(batchMapper))

  def createBatch(name: String): Batch =
    tx(c => Batch(insertReturningId(c, "INSERT INTO batch (name) VALUES (?)", name), name))

  // ---- 任务 ----

  private val taskMapper: java.sql.ResultSet => AnnotationTask = rs =>
    AnnotationTask(
      rs.getLong("id"),
      rs.getLong("batch_id"),
      rs.getString("name"),
      rs.getString("status"),
      optLong(rs, "created_by"),
      instant(rs, "created_at")
    )

  def listTasks(): List[AnnotationTask] =
    read(c => query(c, "SELECT * FROM task ORDER BY id")(taskMapper))

  def findTask(id: Long): Option[AnnotationTask] =
    read(c => queryOne(c, "SELECT * FROM task WHERE id = ?", id)(taskMapper))

  def createTask(batchId: Long, name: String, createdBy: Option[Long], segmentIds: Seq[Long]): AnnotationTask =
    tx { c =>
      val id = insertReturningId(c,
        "INSERT INTO task (batch_id, name, created_by) VALUES (?, ?, ?)", batchId, name, createdBy)
      segmentIds.foreach { sid =>
        update(c, "INSERT INTO task_segment (task_id, segment_id) VALUES (?, ?) ON CONFLICT DO NOTHING", id, sid)
      }
      AnnotationTask(id, batchId, name, "open", createdBy, java.time.Instant.now())
    }

  def taskSegmentIds(taskId: Long): List[Long] =
    read(c => query(c, "SELECT segment_id FROM task_segment WHERE task_id = ? ORDER BY segment_id", taskId)(_.getLong("segment_id")))

  def taskContainsSegment(taskId: Long, segmentId: Long): Boolean =
    read(c => queryOne(c,
      "SELECT 1 AS one FROM task_segment WHERE task_id = ? AND segment_id = ?",
      taskId, segmentId)(_.getInt("one")).isDefined)

  // ---- 分配 ----

  private val assignmentMapper: java.sql.ResultSet => Assignment = rs =>
    Assignment(
      rs.getLong("id"),
      rs.getLong("task_id"),
      rs.getLong("user_id"),
      rs.getLong("batch_id"),
      instant(rs, "assigned_at"),
      rs.getString("status")
    )

  def findAssignment(id: Long): Option[Assignment] =
    read(c => queryOne(c, "SELECT * FROM assignment WHERE id = ?", id)(assignmentMapper))

  def assignmentsOfTask(taskId: Long): List[AssignmentView] =
    read(c => query(c,
      """SELECT a.*, u.username FROM assignment a
         JOIN app_user u ON u.id = a.user_id
         WHERE a.task_id = ? ORDER BY a.id""", taskId) { rs =>
      AssignmentView(
        rs.getLong("id"), rs.getLong("task_id"), rs.getLong("user_id"),
        rs.getString("username"), rs.getLong("batch_id"),
        instant(rs, "assigned_at"), rs.getString("status"))
    })

  def assignmentExists(taskId: Long, userId: Long): Boolean =
    read(c => queryOne(c,
      "SELECT 1 AS one FROM assignment WHERE task_id = ? AND user_id = ?",
      taskId, userId)(_.getInt("one")).isDefined)

  /** 规则4：用户最近一次分配（按 id 倒序即按时间倒序） */
  def latestAssignmentOf(userId: Long): Option[Assignment] =
    read(c => queryOne(c,
      "SELECT * FROM assignment WHERE user_id = ? ORDER BY id DESC LIMIT 1",
      userId)(assignmentMapper))

  def createAssignment(taskId: Long, userId: Long, batchId: Long): Assignment =
    tx { c =>
      val id = insertReturningId(c,
        "INSERT INTO assignment (task_id, user_id, batch_id) VALUES (?, ?, ?)",
        taskId, userId, batchId)
      queryOne(c, "SELECT * FROM assignment WHERE id = ?", id)(assignmentMapper).get
    }

  /** 在已有事务连接内插入分配（自动分配批量用） */
  def createAssignmentIn(c: Connection, taskId: Long, userId: Long, batchId: Long): Long =
    insertReturningId(c,
      "INSERT INTO assignment (task_id, user_id, batch_id) VALUES (?, ?, ?)",
      taskId, userId, batchId)

  /**
   * 规则4（自动分配）：挑选最近一批次 != batchId（或从未分配过）的标注员，
   * 按最久未分配排序。
   */
  def eligibleAnnotators(batchId: Long, limit: Int): List[Long] =
    read(c => query(c,
      """SELECT u.id FROM app_user u
         WHERE u.role = 'annotator'
           AND NOT EXISTS (
             SELECT 1 FROM assignment a
             WHERE a.user_id = u.id
               AND a.batch_id = ?
               AND a.id = (SELECT MAX(a2.id) FROM assignment a2 WHERE a2.user_id = u.id)
           )
         ORDER BY (SELECT MAX(a3.id) FROM assignment a3 WHERE a3.user_id = u.id) ASC NULLS FIRST
         LIMIT ?""",
      batchId, limit)(_.getLong("id")))

  def markSubmitted(assignmentId: Long): Unit =
    tx(c => update(c, "UPDATE assignment SET status = 'submitted' WHERE id = ?", assignmentId))
}
