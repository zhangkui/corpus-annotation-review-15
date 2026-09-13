package repositories

import jakarta.inject.{Inject, Singleton}
import models.Segment
import play.api.db.Database

import java.sql.Connection

@Singleton
class SegmentRepository @Inject() (db: Database) extends BaseRepository(db) {
  import Jdbc.*

  private val mapper: java.sql.ResultSet => Segment = rs =>
    Segment(
      rs.getLong("id"),
      rs.getLong("corpus_id"),
      rs.getString("language_code"),
      rs.getString("content"),
      rs.getInt("char_length"),
      optString(rs, "external_id"),
      instant(rs, "created_at")
    )

  def list(corpusId: Option[Long], language: Option[String], offset: Int, limit: Int): List[Segment] =
    read { c =>
      val sql = new StringBuilder("SELECT * FROM segment WHERE 1 = 1")
      val params = scala.collection.mutable.ListBuffer.empty[Any]
      corpusId.foreach { id => sql.append(" AND corpus_id = ?"); params += id }
      language.foreach { l => sql.append(" AND language_code = ?"); params += l }
      sql.append(" ORDER BY id LIMIT ? OFFSET ?")
      params += limit; params += offset
      query(c, sql.toString, params.toSeq*)(mapper)
    }

  def find(id: Long): Option[Segment] =
    read(c => queryOne(c, "SELECT * FROM segment WHERE id = ?", id)(mapper))

  def create(corpusId: Long, languageCode: String, content: String, charLength: Int, externalId: Option[String]): Segment =
    tx { c =>
      val id = insertReturningId(c,
        """INSERT INTO segment (corpus_id, language_code, content, char_length, external_id)
           VALUES (?, ?, ?, ?, ?)""",
        corpusId, languageCode, content, charLength, externalId)
      queryOne(c, "SELECT * FROM segment WHERE id = ?", id)(mapper).get
    }

  /** 导入用：在已有事务连接内插入 */
  def createIn(c: Connection, corpusId: Long, languageCode: String, content: String,
               charLength: Int, externalId: Option[String]): Long =
    insertReturningId(c,
      """INSERT INTO segment (corpus_id, language_code, content, char_length, external_id)
         VALUES (?, ?, ?, ?, ?)""",
      corpusId, languageCode, content, charLength, externalId)

  def existsExternal(corpusId: Long, externalId: String): Boolean =
    read(c => queryOne(c,
      "SELECT 1 AS one FROM segment WHERE corpus_id = ? AND external_id = ?",
      corpusId, externalId)(_.getInt("one")).isDefined)

  def contentOf(id: Long): Option[String] =
    read(c => queryOne(c, "SELECT content FROM segment WHERE id = ?", id)(_.getString("content")))
}
