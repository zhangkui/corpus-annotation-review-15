package repositories

import jakarta.inject.{Inject, Singleton}
import models.{AppUser, Corpus}
import play.api.db.Database

@Singleton
class UserRepository @Inject() (db: Database) extends BaseRepository(db) {
  import Jdbc.*

  private val mapper: java.sql.ResultSet => AppUser = rs =>
    AppUser(rs.getLong("id"), rs.getString("username"), rs.getString("display_name"), rs.getString("role"))

  def list(): List[AppUser] =
    read(c => query(c, "SELECT * FROM app_user ORDER BY id")(mapper))

  def find(id: Long): Option[AppUser] =
    read(c => queryOne(c, "SELECT * FROM app_user WHERE id = ?", id)(mapper))

  def findByName(username: String): Option[AppUser] =
    read(c => queryOne(c, "SELECT * FROM app_user WHERE username = ?", username)(mapper))

  def create(username: String, displayName: String, role: String): AppUser =
    tx { c =>
      val id = insertReturningId(c,
        "INSERT INTO app_user (username, display_name, role) VALUES (?, ?, ?)",
        username, displayName, role)
      AppUser(id, username, displayName, role)
    }
}

@Singleton
class CorpusRepository @Inject() (db: Database) extends BaseRepository(db) {
  import Jdbc.*

  private val mapper: java.sql.ResultSet => Corpus = rs =>
    Corpus(rs.getLong("id"), rs.getString("name"))

  def list(): List[Corpus] =
    read(c => query(c, "SELECT * FROM corpus ORDER BY id")(mapper))

  def find(id: Long): Option[Corpus] =
    read(c => queryOne(c, "SELECT * FROM corpus WHERE id = ?", id)(mapper))

  def findByName(name: String): Option[Corpus] =
    read(c => queryOne(c, "SELECT * FROM corpus WHERE name = ?", name)(mapper))

  def create(name: String): Corpus =
    tx(c => Corpus(insertReturningId(c, "INSERT INTO corpus (name) VALUES (?)", name), name))

  /** 存在则复用（导入用） */
  def findOrCreate(name: String): Corpus =
    findByName(name).getOrElse(create(name))
}
