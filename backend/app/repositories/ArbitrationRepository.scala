package repositories

import jakarta.inject.{Inject, Singleton}
import models.{Arbitration, ConclusionAnnotation}
import play.api.db.Database

import java.sql.Connection

@Singleton
class ArbitrationRepository @Inject() (db: Database) extends BaseRepository(db) {
  import Jdbc.*

  def createIn(c: Connection, segmentId: Long, arbitratorId: Long, comment: Option[String]): Long =
    insertReturningId(c,
      "INSERT INTO arbitration (segment_id, arbitrator_id, comment) VALUES (?, ?, ?)",
      segmentId, arbitratorId, comment)

  def addSourceIn(c: Connection, arbitrationId: Long, versionId: Long): Unit =
    update(c,
      "INSERT INTO arbitration_source (arbitration_id, version_id) VALUES (?, ?)",
      arbitrationId, versionId)

  def addConclusionIn(c: Connection, arbitrationId: Long, tagId: Long,
                      startOffset: Int, endOffset: Int, note: Option[String]): Long =
    insertReturningId(c,
      """INSERT INTO arbitration_annotation (arbitration_id, tag_id, start_offset, end_offset, note)
         VALUES (?, ?, ?, ?, ?)""",
      arbitrationId, tagId, startOffset, endOffset, note)

  def find(id: Long): Option[Arbitration] =
    read { c =>
      queryOne(c,
        """SELECT arb.*, u.username FROM arbitration arb
           JOIN app_user u ON u.id = arb.arbitrator_id
           WHERE arb.id = ?""", id) { rs =>
        buildFrom(c, rs)
      }
    }

  def bySegment(segmentId: Long): List[Arbitration] =
    read { c =>
      query(c,
        """SELECT arb.*, u.username FROM arbitration arb
           JOIN app_user u ON u.id = arb.arbitrator_id
           WHERE arb.segment_id = ? ORDER BY arb.id""", segmentId) { rs =>
        buildFrom(c, rs)
      }
    }

  private def buildFrom(c: Connection, rs: java.sql.ResultSet): Arbitration = {
    val id = rs.getLong("id")
    val sources = query(c,
      "SELECT version_id FROM arbitration_source WHERE arbitration_id = ? ORDER BY version_id",
      id)(_.getLong("version_id"))
    val conclusions = query(c,
      """SELECT aa.*, t.name AS tag_name FROM arbitration_annotation aa
         JOIN tag t ON t.id = aa.tag_id
         WHERE aa.arbitration_id = ? ORDER BY aa.id""", id) { r2 =>
      ConclusionAnnotation(
        r2.getLong("id"), r2.getLong("tag_id"), r2.getString("tag_name"),
        r2.getInt("start_offset"), r2.getInt("end_offset"), optString(r2, "note"))
    }
    Arbitration(
      id,
      rs.getLong("segment_id"),
      rs.getLong("arbitrator_id"),
      rs.getString("username"),
      optString(rs, "comment"),
      instant(rs, "created_at"),
      sources,
      conclusions
    )
  }
}
