package services

import jakarta.inject.{Inject, Singleton}
import models.DomainError.InvalidInput
import models.{DomainError, ImportRequest, ImportResult, ImportRowError}
import repositories.Jdbc.*
import repositories.{AnnotationRepository, CorpusRepository, SegmentRepository, TagRepository, Tx}

import java.sql.Connection

/**
 * 批量导入（规则5：校验字符偏移与语言代码）。
 * 导入的预标注挂在系统用户 importer 的导入任务下，作为一个标注版本，
 * 与人工标注一起参与合并展示与冲突审查。
 */
@Singleton
class ImportService @Inject() (
    tx: Tx,
    corpusRepo: CorpusRepository,
    segmentRepo: SegmentRepository,
    tagRepo: TagRepository,
    annotationRepo: AnnotationRepository,
    events: EventService
) {
  private val ImportBatch = "import"
  private val ImporterName = "importer"

  def importSegments(req: ImportRequest): Either[DomainError, ImportResult] = {
    if (req.corpus.trim.isEmpty) return Left(InvalidInput("语料库名不能为空"))
    if (req.segments.isEmpty) return Left(InvalidInput("导入内容为空"))
    if (req.segments.size > 5000) return Left(InvalidInput("单次最多导入 5000 条"))
    val partial = req.partial.getOrElse(false)

    // ---- 规则5：逐行校验 ----
    val errors = scala.collection.mutable.ListBuffer.empty[ImportRowError]
    req.segments.zipWithIndex.foreach { case (seg, idx) =>
      if (!Validators.isValidLanguageCode(seg.languageCode))
        errors += ImportRowError(idx, s"非法语言代码「${seg.languageCode}」（需符合 BCP-47 且主子标签受支持）")
      if (seg.content.isEmpty)
        errors += ImportRowError(idx, "内容不能为空")
      val len = Validators.codePointLength(seg.content)
      seg.annotations.zipWithIndex.foreach { case (a, ai) =>
        if (a.tag.trim.isEmpty)
          errors += ImportRowError(idx, s"第 ${ai + 1} 条标注的标签名不能为空")
        if (!Validators.offsetsValid(a.startOffset, a.endOffset, len))
          errors += ImportRowError(idx,
            s"第 ${ai + 1} 条标注字符偏移 [${a.startOffset}, ${a.endOffset}) 越界（文本码点长度 $len）")
      }
    }
    // 负载内部 external_id 重复检测
    req.segments.zipWithIndex
      .flatMap { case (s, i) => s.externalId.map(_ -> i) }
      .groupMap(_._1)(_._2)
      .foreach { case (eid, idxs) =>
        if (idxs.size > 1) idxs.tail.foreach(i =>
          errors += ImportRowError(i, s"external_id「$eid」在本次导入中重复"))
      }

    if (errors.nonEmpty && !partial)
      return Right(ImportResult(imported = 0, skipped = 0, errors = errors.toList))

    val invalidRows = errors.map(_.row).toSet
    val rowsToImport = req.segments.zipWithIndex.filterNot { case (_, i) => invalidRows.contains(i) }

    val corpus = corpusRepo.findOrCreate(req.corpus)

    // 跳过 external_id 已存在的行
    val (duplicated, fresh) = rowsToImport.partition {
      case (seg, _) => seg.externalId.exists(eid => segmentRepo.existsExternal(corpus.id, eid))
    }
    duplicated.foreach { case (seg, idx) =>
      errors += ImportRowError(idx, s"external_id「${seg.externalId.getOrElse("")}」已存在，跳过")
    }

    val imported = tx { c =>
      if (fresh.isEmpty) 0
      else {
        val (taskId, assignmentId) = importContext(c, corpus.name)
        val versionId = annotationRepo.createVersionIn(c, assignmentId, nextImportVersionNo(c, assignmentId))
        fresh.foreach { case (seg, _) =>
          val len = Validators.codePointLength(seg.content)
          val segmentId = segmentRepo.createIn(c, corpus.id, seg.languageCode, seg.content, len, seg.externalId)
          update(c,
            "INSERT INTO task_segment (task_id, segment_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            taskId, segmentId)
          seg.annotations.foreach { a =>
            val tagId = tagRepo.findOrCreateRootIn(c, a.tag.trim)
            annotationRepo.insertAnnotationIn(c, versionId, segmentId, tagId, a.startOffset, a.endOffset, None)
          }
        }
        fresh.size
      }
    }

    events.record("import.completed",
      extra = Map("corpus" -> corpus.name, "imported" -> imported.toString))
    Right(ImportResult(imported = imported, skipped = duplicated.size + invalidRows.size, errors = errors.toList))
  }

  /** 导入上下文：批次 import / 任务 import-<corpus> / 用户 importer / 分配，均幂等创建 */
  private def importContext(c: Connection, corpusName: String): (Long, Long) = {
    val batchId = queryOne(c, "SELECT id FROM batch WHERE name = ?", ImportBatch)(_.getLong("id"))
      .getOrElse(insertReturningId(c, "INSERT INTO batch (name) VALUES (?)", ImportBatch))

    val taskName = s"import-$corpusName"
    val taskId = queryOne(c, "SELECT id FROM task WHERE batch_id = ? AND name = ?", batchId, taskName)(_.getLong("id"))
      .getOrElse(insertReturningId(c,
        "INSERT INTO task (batch_id, name) VALUES (?, ?)", batchId, taskName))

    val importerId = queryOne(c, "SELECT id FROM app_user WHERE username = ?", ImporterName)(_.getLong("id"))
      .getOrElse(insertReturningId(c,
        "INSERT INTO app_user (username, display_name, role) VALUES (?, ?, ?)",
        ImporterName, "数据导入", "admin"))

    val assignmentId =
      queryOne(c, "SELECT id FROM assignment WHERE task_id = ? AND user_id = ?", taskId, importerId)(_.getLong("id"))
        .getOrElse(insertReturningId(c,
          "INSERT INTO assignment (task_id, user_id, batch_id) VALUES (?, ?, ?)",
          taskId, importerId, batchId))
    (taskId, assignmentId)
  }

  private def nextImportVersionNo(c: Connection, assignmentId: Long): Int =
    queryOne(c,
      "SELECT COALESCE(MAX(version_no), 0) + 1 AS v FROM annotation_version WHERE assignment_id = ?",
      assignmentId)(_.getInt("v")).getOrElse(1)
}
