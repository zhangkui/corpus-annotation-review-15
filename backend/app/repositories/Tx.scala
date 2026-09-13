package repositories

import jakarta.inject.{Inject, Singleton}
import play.api.db.Database

import java.sql.Connection

/** 服务层跨仓储事务边界：同一 Connection 上执行多个仓储的 *In 方法 */
@Singleton
class Tx @Inject() (db: Database) {
  def apply[A](f: Connection => A): A = db.withConnection { conn =>
    conn.setAutoCommit(false)
    try {
      val r = f(conn)
      conn.commit()
      r
    } catch {
      case t: Throwable =>
        conn.rollback()
        throw t
    } finally {
      conn.setAutoCommit(true)
    }
  }
}
