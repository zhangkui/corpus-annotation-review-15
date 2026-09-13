package repositories

import jakarta.inject.{Inject, Singleton}
import models.{Annotation, AnnotationSource, AnnotationVersion, MergedAnnotation}
import play.api.db.Database

import java.sql.Connection

@Singleton
class AnnotationRepository @Inject() (db: Database) extends BaseRepository(db) {
  import Jdbc.*

  // ---- 版本 ----

  private val versionMapper: java.sql.ResultSet => AnnotationVersion = rs =>
    AnnotationVersion(
      rs.getLong("id"),
      rs.getLong("assignment_id"),
      rs.getInt("version_no"),
      rs.getString("status"),
      instant(rs, "created_at")
    )

  def findVersion(id: Long): Option[AnnotationVersion] =
    read(c => queryOne(c, "SELECT * FROM annotation_version WHERE id = ?", id)(versionMapper))

  def versionsBySegment(segmentId: Long): List[AnnotationVersion] =
    read(c => query(c,
      """SELECT DISTINCT v.* FROM annotation_version v
         JOIN annotation a ON a.version_id = v.id
         WHERE a.segment_id = ? ORDER BY v.id""", segmentId)(versionMapper))

  def maxVersionNo(assignmentId: Long): Int =
    read(c => queryOne(c,
      "SELECT COALESCE(MAX(version_no), 0) AS v FROM annotation_version WHERE assignment_id = ?",
      assignmentId)(_.getInt("v")).getOrElse(0))

  def createVersionIn(c: Connection, assignmentId: Long, versionNo: Int): Long =
    insertReturningId(c,
      "INSERT INTO annotation_version (assignment_id, version_no) VALUES (?, ?)",
      assignmentId, versionNo)

  def versionsExist(versionIds: Seq[Long]): Set[Long] =
    if (versionIds.isEmpty) Set.empty
    else read { c =>
      val marks = versionIds.map(_ => "?").mkString(",")
      query(c, s"SELECT id FROM annotation_version WHERE id IN ($marks)", versionIds*)(_.getLong("id")).toSet
    }

  /** 规则3：版本所属任务是否覆盖指定片段 */
  def versionCoversSegment(versionId: Long, segmentId: Long): Boolean =
    read(c => queryOne(c,
      """SELECT 1 AS one FROM annotation_version v
         JOIN assignment a   ON a.id = v.assignment_id
         JOIN task_segment ts ON ts.task_id = a.task_id
         WHERE v.id = ? AND ts.segment_id = ?""",
      versionId, segmentId)(_.getInt("one")).isDefined)

  def markVersionsArbitrated(versionIds: Seq[Long]): Unit =
    tx { c =>
      versionIds.foreach { id =>
        update(c, "UPDATE annotation_version SET status = 'arbitrated' WHERE id = ?", id)
      }
    }

  // ---- 标注 ----

  private val annotationMapper: java.sql.ResultSet => Annotation = rs =>
    Annotation(
      rs.getLong("id"),
      rs.getLong("version_id"),
      rs.getLong("segment_id"),
      rs.getLong("tag_id"),
      rs.getInt("start_offset"),
      rs.getInt("end_offset"),
      optString(rs, "note")
    )

  def annotationsOfVersion(versionId: Long): List[Annotation] =
    read(c => query(c,
      "SELECT * FROM annotation WHERE version_id = ? ORDER BY id", versionId)(annotationMapper))

  def insertAnnotationIn(c: Connection, versionId: Long, segmentId: Long, tagId: Long,
                         startOffset: Int, endOffset: Int, note: Option[String]): Long =
    insertReturningId(c,
      """INSERT INTO annotation (version_id, segment_id, tag_id, start_offset, end_offset, note)
         VALUES (?, ?, ?, ?, ?, ?)""",
      versionId, segmentId, tagId, startOffset, endOffset, note)

  /**
   * 规则1：相同片段、同一标签、同一字符范围的重复标注合并展示。
   * 按 (tag, start, end) 分组，聚合计数与来源（标注者 / 版本 / 备注）。
   */
  def mergedBySegment(segmentId: Long): List[MergedAnnotation] =
    read { c =>
      val rows = query(c,
        """SELECT a.tag_id, t.name AS tag_name, a.start_offset, a.end_offset,
                  a.id AS annotation_id, a.version_id, u.username, a.note
           FROM annotation a
           JOIN tag t               ON t.id = a.tag_id
           JOIN annotation_version v ON v.id = a.version_id
           JOIN assignment asg       ON asg.id = v.assignment_id
           JOIN app_user u           ON u.id = asg.user_id
           WHERE a.segment_id = ?
           ORDER BY a.tag_id, a.start_offset, a.end_offset, a.id""",
        segmentId) { rs =>
        (rs.getLong("tag_id"), rs.getString("tag_name"),
          rs.getInt("start_offset"), rs.getInt("end_offset"),
          AnnotationSource(
            rs.getLong("annotation_id"),
            rs.getLong("version_id"),
            rs.getString("username"),
            optString(rs, "note")))
      }
      rows
        .groupBy(r => (r._1, r._2, r._3, r._4))
        .toList
        .sortBy { case ((_, _, s, _), _) => s }
        .map { case ((tagId, tagName, start, end), group) =>
          MergedAnnotation(tagId, tagName, start, end, group.size, group.map(_._5))
        }
    }
}
