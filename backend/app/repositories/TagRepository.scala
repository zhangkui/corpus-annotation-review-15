package repositories

import jakarta.inject.{Inject, Singleton}
import models.Tag
import play.api.db.Database

import java.sql.Connection

@Singleton
class TagRepository @Inject() (db: Database) extends BaseRepository(db) {
  import Jdbc.*

  private val mapper: java.sql.ResultSet => Tag = rs =>
    Tag(rs.getLong("id"), rs.getString("name"), optLong(rs, "parent_id"))

  def list(): List[Tag] =
    read(c => query(c, "SELECT * FROM tag ORDER BY id")(mapper))

  def find(id: Long): Option[Tag] =
    read(c => queryOne(c, "SELECT * FROM tag WHERE id = ?", id)(mapper))

  def findByName(parentId: Option[Long], name: String): Option[Tag] =
    read { c =>
      parentId match {
        case Some(pid) => queryOne(c, "SELECT * FROM tag WHERE parent_id = ? AND name = ?", pid, name)(mapper)
        case None      => queryOne(c, "SELECT * FROM tag WHERE parent_id IS NULL AND name = ?", name)(mapper)
      }
    }

  def create(name: String, parentId: Option[Long]): Tag =
    tx(c => Tag(insertReturningId(c, "INSERT INTO tag (name, parent_id) VALUES (?, ?)", name, parentId), name, parentId))

  /** 导入用：在已有事务连接内按名字查找或创建（根层级） */
  def findOrCreateRootIn(c: Connection, name: String): Long =
    queryOne(c, "SELECT * FROM tag WHERE parent_id IS NULL AND name = ?", name)(mapper)
      .map(_.id)
      .getOrElse(insertReturningId(c, "INSERT INTO tag (name, parent_id) VALUES (?, NULL)", name))

  def updateParent(tagId: Long, parentId: Option[Long]): Unit =
    tx(c => update(c, "UPDATE tag SET parent_id = ? WHERE id = ?", parentId, tagId))

  /**
   * 规则2：环检测。
   * 沿 `startId` 的父链向上走（递归 CTE），若链上包含 `targetId` 则返回 true。
   * 用于判断「把 tag 挂到 parent 下」是否会成环：parent 的祖先中包含 tag 自身即成环。
   */
  def ancestorContains(startId: Long, targetId: Long): Boolean =
    read { c =>
      queryOne(c,
        """WITH RECURSIVE ancestors AS (
             SELECT t.id, t.parent_id FROM tag t WHERE t.id = ?
             UNION ALL
             SELECT t.id, t.parent_id FROM tag t
             JOIN ancestors a ON t.id = a.parent_id
           )
           SELECT 1 AS one FROM ancestors WHERE id = ? LIMIT 1""",
        startId, targetId)(_.getInt("one")).isDefined
    }
}
