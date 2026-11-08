package com.intellij.ide.bookmark

import com.intellij.ide.bookmark.providers.LineBookmarkProvider
import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import java.nio.file.Path

/** Diagnostics never loosen the provider's exact-text matching or modify source documents. */
object BookmarkHealth {
  const val LINE_PROVIDER = "com.intellij.ide.bookmark.providers.LineBookmarkProvider"
  enum class Status(val label: String, val invalid: Boolean) {
    HEALTHY("正常", false), MISSING_FILE("文件不存在", true), OUT_OF_RANGE("行号越界", true),
    ANCHOR_MISMATCH("源码锚点不匹配", true), UNREADABLE("无法读取文本", true), UNSUPPORTED("未检查：其他提供器或非本地文件", false)
  }
  data class Entry(val id: String, val url: String, val line: Int, val status: Status, val expected: String?, val actual: String?)
  data class Candidate(val url: String, val line: Int, val text: String?, val reason: String)

  @JvmStatic fun expandedUrl(project: Project, url: String): String = PathMacroManager.getInstance(project).expandPath(url) ?: url

  @JvmStatic fun inspect(project: Project, state: ManagerState): List<Entry> = state.bookmarks.map { record ->
    ProgressManager.checkCanceled()
    inspectRecord(project, record)
  }

  private fun inspectRecord(project: Project, record: BookmarkState): Entry {
    val url = expandedUrl(project, record.attributes["url"].orEmpty())
    val line = record.attributes["line"]?.toIntOrNull() ?: -1
    val expected = record.attributes["lineText"]
    fun entry(status: Status, actual: String? = null) = Entry(record.id!!, url, line, status, expected, actual)
    if (record.provider != LINE_PROVIDER || !url.startsWith("file://")) return entry(Status.UNSUPPORTED)
    val file = VirtualFileManager.getInstance().findFileByUrl(url)?.takeIf { it.isValid } ?: return entry(Status.MISSING_FILE)
    if (line < 0) return entry(Status.HEALTHY)
    if (file.isDirectory) return entry(Status.UNREADABLE)
    val document = FileDocumentManager.getInstance().getDocument(file) ?: return entry(Status.UNREADABLE)
    val actual = LineBookmarkProvider.Util.readLineText(document, line) ?: return entry(Status.OUT_OF_RANGE)
    return entry(if (expected != null && expected != actual) Status.ANCHOR_MISMATCH else Status.HEALTHY, actual)
  }

  /** Whitespace similarity is a suggestion only; the chosen replacement stores the complete new source line. */
  @JvmStatic fun candidates(project: Project, record: BookmarkState, targetUrl: String): List<Candidate> {
    if (record.provider != LINE_PROVIDER) return emptyList()
    val url = expandedUrl(project, targetUrl)
    if (!url.startsWith("file://")) return emptyList()
    val file = VirtualFileManager.getInstance().findFileByUrl(url)?.takeIf { it.isValid } ?: return emptyList()
    if ((record.attributes["line"]?.toIntOrNull() ?: -1) < 0) return listOf(Candidate(file.url, -1, null, "文件位置"))
    val document = FileDocumentManager.getInstance().getDocument(file) ?: return emptyList()
    val expected = record.attributes["lineText"]?.takeIf { it.isNotBlank() } ?: return emptyList()
    val exact = mutableListOf<Candidate>(); val similar = mutableListOf<Candidate>()
    for (line in 0 until document.lineCount) {
      ProgressManager.checkCanceled()
      val text = LineBookmarkProvider.Util.readLineText(document, line) ?: continue
      if (text == expected && exact.size < 200) exact.add(Candidate(file.url, line, text, "原文精确匹配"))
      else if (text.trim() == expected.trim() && similar.size < 200) similar.add(Candidate(file.url, line, text, "仅空白不同，需要确认"))
    }
    return (exact + similar).take(200)
  }

  @JvmStatic fun candidateAt(project: Project, targetUrl: String, line: Int): Candidate {
    val url = expandedUrl(project, targetUrl)
    require(url.startsWith("file://")) { "请选择本地文件" }
    val file = VirtualFileManager.getInstance().findFileByUrl(url)?.takeIf { it.isValid } ?: error("目标文件不存在")
    if (line < 0) return Candidate(file.url, -1, null, "手动指定文件")
    require(!file.isDirectory) { "行书签需要文本文件" }
    val document = FileDocumentManager.getInstance().getDocument(file) ?: error("无法读取目标文件")
    val text = LineBookmarkProvider.Util.readLineText(document, line) ?: error("目标行号越界")
    return Candidate(file.url, line, text, "手动指定行，需要确认")
  }

  @JvmStatic fun repair(project: Project, manager: BookmarksManagerImpl, expected: ManagerState, revision: Long,
                        id: String, candidate: Candidate): Path {
    val record = expected.bookmarks.singleOrNull { it.id == id } ?: error("书签已不存在")
    require(record.provider == LINE_PROVIDER) { "仅支持普通本地文件和行书签" }
    val oldLine = record.attributes["line"]?.toIntOrNull() ?: -1
    require((oldLine < 0) == (candidate.line < 0)) { "修复不能改变文件/行书签类型" }
    val fresh = candidateAt(project, candidate.url, candidate.line)
    check(fresh.text == candidate.text) { "目标源码已变化，请重新预览候选位置" }
    require(expected.bookmarks.none { other -> other.id != id && other.provider == LINE_PROVIDER &&
      expandedUrl(project, other.attributes["url"].orEmpty()).let { VirtualFileManager.getInstance().findFileByUrl(it)?.url ?: it } == fresh.url &&
      (other.attributes["line"]?.toIntOrNull() ?: -1) == fresh.line }) { "目标位置已有书签，请使用现有书签的分组引用" }
    val replacement = BookmarkExchange.copy(expected)
    replacement.bookmarks.single { it.id == id }.attributes.apply {
      put("url", fresh.url)
      if (fresh.line < 0) remove("line") else put("line", fresh.line.toString())
      if (fresh.text == null) remove("lineText") else put("lineText", fresh.text)
    }
    // Also rejects collisions with another bookmark, before backup or live mutation.
    BookmarkStorageFormat.validate(replacement)
    return manager.applyExchange(expected, revision, replacement)
  }
}
