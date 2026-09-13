package services

import jakarta.inject.{Inject, Singleton}
import models.DomainError.{InvalidInput, NotFound, RuleConflict}
import models.{DomainError, Tag, TagNode}
import repositories.TagRepository

/** 标签层级管理（规则2：层级不能形成环） */
@Singleton
class TagService @Inject() (tagRepo: TagRepository) {

  def list(): Seq[Tag] = tagRepo.list()

  /** 构建标签树（森林） */
  def tree(): Seq[TagNode] = {
    val all = tagRepo.list()
    val byParent = all.groupBy(_.parentId)
    def build(t: Tag): TagNode =
      TagNode(t.id, t.name, byParent.getOrElse(Some(t.id), Nil).sortBy(_.id).map(build))
    byParent.getOrElse(None, Nil).sortBy(_.id).map(build)
  }

  def create(name: String, parentId: Option[Long]): Either[DomainError, Tag] = {
    if (name.trim.isEmpty) Left(InvalidInput("标签名不能为空"))
    else parentId match {
      case Some(pid) if tagRepo.find(pid).isEmpty =>
        Left(NotFound(s"父标签 $pid 不存在"))
      case _ if tagRepo.findByName(parentId, name).isDefined =>
        Left(RuleConflict(s"同一层级下标签「$name」已存在"))
      case _ =>
        Right(tagRepo.create(name.trim, parentId))
    }
  }

  /** 改挂父节点；parentId 为 None 表示提升为根标签 */
  def reparent(tagId: Long, newParentId: Option[Long]): Either[DomainError, Tag] = {
    tagRepo.find(tagId) match {
      case None => Left(NotFound(s"标签 $tagId 不存在"))
      case Some(tag) =>
        newParentId match {
          case Some(pid) if pid == tagId =>
            Left(RuleConflict("标签不能作为自己的父节点（会形成环）"))
          case Some(pid) if tagRepo.find(pid).isEmpty =>
            Left(NotFound(s"父标签 $pid 不存在"))
          case Some(pid) if tagRepo.ancestorContains(pid, tagId) =>
            // 新父节点的祖先链上包含本标签 → 成环
            Left(RuleConflict(s"标签层级不能形成环：标签 $tagId 是父标签 $pid 的祖先"))
          case _ =>
            tagRepo.updateParent(tagId, newParentId)
            Right(tag.copy(parentId = newParentId))
        }
    }
  }
}
