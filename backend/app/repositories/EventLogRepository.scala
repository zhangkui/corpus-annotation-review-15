package repositories

import jakarta.inject.{Inject, Singleton}
import models.AnnotationEvent
import play.api.db.Database

import java.util.UUID

/** 事件日志 —— 写入 TimescaleDB hypertable `annotation_event` */
@Singleton
class EventLogRepository @Inject() (db: Database) extends BaseRepository(db) {
  import Jdbc.*

  def insert(eventId: UUID, eventType: String, segmentId: Option[Long],
             versionId: Option[Long], actorId: Option[Long], payload: String): Unit =
    tx { c =>
      update(c,
        """INSERT INTO annotation_event (event_id, event_type, segment_id, version_id, actor_id, payload)
           VALUES (?::uuid, ?, ?, ?, ?, ?::jsonb)
           ON CONFLICT (time, event_id) DO NOTHING""",
        eventId.toString, eventType, segmentId, versionId, actorId, payload)
    }

  def latest(limit: Int): List[AnnotationEvent] =
    read(c => query(c,
      """SELECT time, event_id, event_type, segment_id, version_id, actor_id,
                payload::text AS payload_text
         FROM annotation_event ORDER BY time DESC LIMIT ?""", limit) { rs =>
      AnnotationEvent(
        instant(rs, "time"),
        rs.getString("event_id"),
        rs.getString("event_type"),
        optLong(rs, "segment_id"),
        optLong(rs, "version_id"),
        optLong(rs, "actor_id"),
        rs.getString("payload_text")
      )
    })
}
