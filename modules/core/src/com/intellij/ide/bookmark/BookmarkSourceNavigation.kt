package com.intellij.ide.bookmark

import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.ide.util.treeView.SmartElementDescriptor
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.*
import com.intellij.testFramework.LightVirtualFile
import com.intellij.usageView.UsageInfo
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageView
import java.nio.file.Path
import java.util.UUID

/** Native analysis stays in IDEA; only explicitly selected local source locations enter the store. */
object BookmarkSourceNavigation {
  enum class Operation(val label: String, val actionId: String) {
    DEFINITION("快速查看定义", "QuickImplementations"), DOCUMENTATION("查看文档", "QuickJavaDoc"),
    IMPLEMENTATIONS("查找实现", "GotoImplementation"), PARENTS("查看父类／父方法", "GotoSuperMethod"),
    TYPES("类型层次（继承／实现）", "TypeHierarchy"), METHODS("方法重写层次", "MethodHierarchy"),
    CALLS("调用层次", "CallHierarchy"), USAGES("查找引用", "FindUsages"),
    INPUTS("数据从哪里来", "SliceBackward"), OUTPUTS("数据流向哪里", "SliceForward"),
    DIAGRAM("原生类图", "ShowUmlDiagram"), HISTORY("查看代码历史", "Vcs.ShowHistoryForBlock")
  }

  data class Point(val url: String, val offset: Int, val line: Int, val lineText: String, val stamp: Long, val label: String) {
    override fun toString() = "$label · ${url.removePrefix("file://")}:${line + 1}"
  }
  data class Selection(val points: List<Point>, val skipped: Int)
  data class Addition(val ids: List<String>, val created: Int, val references: Int, val backup: Path?)
  data class Symbol(val label: String, val context: DataContext, val offset: Int, val stamp: Long) {
    override fun toString() = label
  }
  const val MAX_SELECTION = 500

  private fun point(project: Project, element: PsiElement?, offset: Int? = null): Point? {
    if (element == null || !element.isValid || element.project != project || element is PsiCompiledElement) return null
    val file = element.containingFile?.virtualFile ?: return null
    if (!file.isInLocalFileSystem || file is LightVirtualFile || file.isDirectory || file.extension == "class") return null
    val doc = FileDocumentManager.getInstance().getDocument(file) ?: return null
    val at = offset ?: (element as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset ?: element.textOffset
    if (at !in 0..doc.textLength) return null
    val line = doc.getLineNumber(at)
    val name = generateSequence(element) { it.parent }.filterIsInstance<PsiNamedElement>().firstOrNull()?.name
    return Point(file.url, at, line, text(doc, line), doc.modificationStamp, name ?: file.name)
  }

  private fun text(doc: Document, line: Int) = doc.charsSequence.subSequence(doc.getLineStartOffset(line), doc.getLineEndOffset(line)).toString()

  /** Usages win over declarations so Find results retain the exact usage range (including merged rows). */
  @JvmStatic fun selected(project: Project, context: DataContext): Selection {
    val points = mutableListOf<Point?>()
    fun usage(info: UsageInfo) { points.add(point(project, info.element, info.navigationOffset.takeIf { it >= 0 })) }
    fun value(item: Any?) {
      when (item) {
        is UsageInfo2UsageAdapter -> item.mergedInfos.forEach(::usage)
        is UsageInfo -> usage(item)
        is PsiElement -> points.add(point(project, item))
        is SmartElementDescriptor -> points.add(point(project, item.psiElement))
        is AbstractTreeNode<*> -> value(item.value)
        is OpenFileDescriptor -> {
          val psi = PsiManager.getInstance(project).findFile(item.file)
          val doc = FileDocumentManager.getInstance().getDocument(item.file)
          val offset = item.offset.takeIf { it >= 0 } ?: doc?.let {
            item.line.takeIf { line -> line in 0 until it.lineCount }?.let { line -> it.getLineStartOffset(line) }
          }
          points.add(if (offset == null) null else point(project, psi, offset))
        }
        else -> points.add(null)
      }
    }
    val usages = context.getData(UsageView.USAGES_KEY)
    val infos = context.getData(UsageView.USAGE_INFO_LIST_KEY)
    val items = context.getData(PlatformDataKeys.SELECTED_ITEMS)
    val elements = context.getData(LangDataKeys.PSI_ELEMENT_ARRAY)
    val navigatables = context.getData(CommonDataKeys.NAVIGATABLE_ARRAY)
    when {
      usages != null -> usages.forEach(::value)
      infos != null -> infos.forEach(::usage)
      items != null -> items.forEach(::value)
      elements != null -> elements.forEach(::value)
      navigatables != null -> navigatables.forEach(::value)
      else -> value(context.getData(CommonDataKeys.PSI_ELEMENT))
    }
    require(points.size <= MAX_SELECTION) { "一次最多整理 $MAX_SELECTION 个位置，请缩小原生查询范围" }
    return Selection(points.filterNotNull().distinctBy { it.url to it.line }, points.count { it == null })
  }

  @JvmStatic fun current(project: Project, points: List<Point>): Boolean = points.all { point ->
    val file = VirtualFileManager.getInstance().findFileByUrl(point.url) ?: return@all false
    val doc = FileDocumentManager.getInstance().getDocument(file) ?: return@all false
    point.stamp == doc.modificationStamp && point.line in 0 until doc.lineCount && text(doc, point.line) == point.lineText
  }

  /** No repair or replacement: reuse identity and shared descriptions; reject a stale existing anchor. */
  @JvmStatic fun add(project: Project, manager: BookmarksManagerImpl, expected: ManagerState, revision: Long,
                     points: List<Point>, groupId: String?, newGroup: String?): Addition {
    require(points.isNotEmpty() && points.size <= MAX_SELECTION) { "请选择需要加入的源码位置" }
    check(current(project, points)) { "源码在预览后发生变化，请重新选择查询结果" }
    val proposed = BookmarkExchange.copy(expected)
    val group = if (newGroup != null) {
      val name = newGroup.trim(); require(name.isNotEmpty()) { "请输入分组名称" }
      require(proposed.groups.none { it.parentId == null && it.name == name }) { "同名根分组已存在，请选择现有分组" }
      GroupState().also { it.id = UUID.randomUUID().toString(); it.name = name; proposed.groups.add(it) }
    } else proposed.groups.singleOrNull { it.id == groupId } ?: error("目标分组已变化")
    var created = 0; var references = 0
    val ids = points.distinctBy { it.url to it.line }.map { point ->
      val record = proposed.bookmarks.firstOrNull { it.provider == BookmarkHealth.LINE_PROVIDER &&
        BookmarkHealth.expandedUrl(project, it.attributes["url"].orEmpty()) == point.url && it.attributes["line"] == point.line.toString() }
        ?.also { require(it.attributes["lineText"] == null || it.attributes["lineText"] == point.lineText) { "已有书签 ${it.id} 的锚点失效，请先巡检修复" } }
        ?: BookmarkState().also {
          it.id = UUID.randomUUID().toString(); it.provider = BookmarkHealth.LINE_PROVIDER; it.type = BookmarkType.DEFAULT
          it.attributes.putAll(mapOf("url" to point.url, "line" to point.line.toString(), "lineText" to point.lineText))
          it.description = point.label; proposed.bookmarks.add(it); created++
        }
      val id = record.id!!
      if (id !in group.bookmarkIds) { group.bookmarkIds.add(id); references++ }; id
    }
    // Even no-ops must reject a different bookmark/branch context.
    check(!manager.isRestoringBookmarks() && revision == manager.stateModificationCount &&
      BookmarkExchange.toJson(manager.captureForExchange(), null) == BookmarkExchange.toJson(expected, null)) { "书签或分支已变化，请重新选择" }
    val backup = if (references == 0) null else manager.applyExchange(expected, revision, proposed)
    return Addition(ids, created, references, backup)
  }

  @JvmStatic fun bookmarkSymbols(project: Project, manager: BookmarksManagerImpl, id: String): List<Symbol> {
    if (manager.isRestoringBookmarks()) return emptyList()
    val bookmark = manager.bookmarks.firstOrNull { manager.getBookmarkId(it) == id } as? FileBookmark ?: return emptyList()
    if (!bookmark.canNavigate() || bookmark.file.isDirectory) return emptyList()
    val doc = FileDocumentManager.getInstance().getDocument(bookmark.file) ?: return emptyList()
    val line = (bookmark as? LineBookmark)?.line ?: 0
    if (line !in 0 until doc.lineCount) return emptyList()
    val start = doc.getLineStartOffset(line); val end = doc.getLineEndOffset(line)
    val at = (start until end).firstOrNull { !doc.charsSequence[it].isWhitespace() } ?: start
    val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, bookmark.file, at), false) ?: return emptyList()
    PsiDocumentManager.getInstance(project).commitDocument(doc)
    val psi = PsiDocumentManager.getInstance(project).getPsiFile(doc) ?: return emptyList()
    return ReadAction.compute<List<Symbol>, RuntimeException> {
      val leaf = psi.findElementAt(at) ?: return@compute emptyList()
      val declaration = generateSequence(leaf) { it.parent }.filterIsInstance<PsiNameIdentifierOwner>().firstOrNull {
        it.nameIdentifier?.textOffset?.let { offset -> offset in start until end } == true
      }
      val targets = linkedMapOf<Int, PsiElement>()
      if (declaration != null) targets[declaration.nameIdentifier!!.textOffset] = declaration
      else {
        var offset = start
        while (offset < end && offset < start + 10000 && targets.size < 100) {
          val token = psi.findElementAt(offset) ?: break
          val reference = psi.findReferenceAt(offset)
          val target = reference?.resolve()
          if (target is PsiNamedElement && target.isValid) {
            val position = reference.element.textRange.startOffset + reference.rangeInElement.startOffset
            if (position in start until end) targets[position] = target
          }
          offset = maxOf(offset + 1, token.textRange.endOffset)
        }
      }
      targets.map { (offset, element) ->
        val name = (element as? PsiNamedElement)?.name ?: bookmark.file.name
        Symbol("$name · 第 ${offset - start + 1} 列", SimpleContext.build(project, psi, editor, element), offset, doc.modificationStamp)
      }
    }
  }

  private object SimpleContext {
    fun build(project: Project, file: PsiFile, editor: com.intellij.openapi.editor.Editor, element: PsiElement): DataContext {
      return com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.EDITOR, editor)
        .add(CommonDataKeys.PSI_FILE, file).add(CommonDataKeys.PSI_ELEMENT, element)
        .add(CommonDataKeys.VIRTUAL_FILE, file.virtualFile).build()
    }
  }
}
