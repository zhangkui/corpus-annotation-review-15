package repositories

import play.api.db.Database

import java.sql.{Connection, PreparedStatement, ResultSet, Statement, Timestamp, Types}
import java.time.{Instant, OffsetDateTime, ZoneOffset}
import scala.collection.mutable.ListBuffer

/** 极简 JDBC 帮助函数（避免引入额外 ORM 依赖） */
object Jdbc {

  def query[A](conn: Connection, sql: String, params: Any*)(row: ResultSet => A): List[A] = {
    val ps = conn.prepareStatement(sql)
    try {
      bind(ps, params)
      val rs = ps.executeQuery()
      val buf = ListBuffer.empty[A]
      while (rs.next()) buf += row(rs)
      buf.toList
    } finally ps.close()
  }

  def queryOne[A](conn: Connection, sql: String, params: Any*)(row: ResultSet => A): Option[A] =
    query(conn, sql, params*)(row).headOption

  def update(conn: Connection, sql: String, params: Any*): Int = {
    val ps = conn.prepareStatement(sql)
    try {
      bind(ps, params)
      ps.executeUpdate()
    } finally ps.close()
  }

  def insertReturningId(conn: Connection, sql: String, params: Any*): Long = {
    val ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
    try {
      bind(ps, params)
      ps.executeUpdate()
      val rs = ps.getGeneratedKeys
      if (rs.next()) rs.getLong(1)
      else throw new IllegalStateException("INSERT 未返回生成的主键")
    } finally ps.close()
  }

  private def bind(ps: PreparedStatement, params: Seq[Any]): Unit =
    params.zipWithIndex.foreach { case (p, i) =>
      val idx = i + 1
      p match {
        case null            => ps.setNull(idx, Types.OTHER)
        case None            => ps.setNull(idx, Types.OTHER)
        case Some(x)         => bindOne(ps, idx, x)
        case other           => bindOne(ps, idx, other)
      }
    }

  private def bindOne(ps: PreparedStatement, idx: Int, v: Any): Unit = v match {
    case s: String     => ps.setString(idx, s)
    case l: Long       => ps.setLong(idx, l)
    case n: Int        => ps.setInt(idx, n)
    case b: Boolean    => ps.setBoolean(idx, b)
    case t: Instant    => ps.setObject(idx, OffsetDateTime.ofInstant(t, ZoneOffset.UTC))
    case d: BigDecimal => ps.setBigDecimal(idx, d.bigDecimal)
    case other         => ps.setObject(idx, other)
  }

  // ---- ResultSet 读取帮助 ----

  def optLong(rs: ResultSet, col: String): Option[Long] = {
    val v = rs.getLong(col)
    if (rs.wasNull()) None else Some(v)
  }

  def optString(rs: ResultSet, col: String): Option[String] = Option(rs.getString(col))

  def instant(rs: ResultSet, col: String): Instant =
    rs.getObject(col, classOf[OffsetDateTime]).toInstant
}

/** 提供事务边界的仓储基类 */
abstract class BaseRepository(protected val db: Database) {

  /** 只读 */
  protected def read[A](f: Connection => A): A = db.withConnection(f)

  /** 事务写：异常回滚 */
  protected def tx[A](f: Connection => A): A = db.withConnection { conn =>
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
