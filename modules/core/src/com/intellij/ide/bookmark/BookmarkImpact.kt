package com.intellij.ide.bookmark

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** File-level review detects changes even when the bookmarked line itself still matches. */
object BookmarkImpact {
  const val MAX_FILE_CHARS = 512 * 1024
  const val MAX_TOTAL_CHARS = 8L * 1024 * 1024
  enum class Status(val label: String) {
    UNREVIEWED("未建立复核基线"), CURRENT("已复核，源码未变化"), CHANGED("源码已变化，待复核"),
    MOVED("文件位置变化，待复核"), MISSING("文件不可用"), UNSUPPORTED("此类型暂不支持复核"), TOO_LARGE("文件过大，暂不跟踪")
  }
  data class Capture(val bookmarkId: String, val url: String, val hash: String, val text: String, val stamp: Long)
  data class Entry(val id: String, val status: Status, val descriptionChanged: Boolean)

  @JvmStatic fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

  @JvmStatic fun capture(project: Project, record: BookmarkState): Capture? {
    if (record.provider != BookmarkHealth.LINE_PROVIDER) return null
    val url = BookmarkHealth.expandedUrl(project, record.attributes["url"].orEmpty())
    if (!url.startsWith("file://")) return null
    val file = VirtualFileManager.getInstance().findFileByUrl(url)?.takeIf { it.isValid && !it.isDirectory } ?: return null
    val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
    if (document.textLength > MAX_FILE_CHARS) return null
    val text = document.text
    return Capture(record.id!!, file.url, hash(text), text, document.modificationStamp)
  }

  @JvmStatic fun stillCurrent(project: Project, captures: List<Capture>): Boolean = captures.all {
    val file = VirtualFileManager.getInstance().findFileByUrl(it.url)?.takeIf { file -> file.isValid } ?: return@all false
    val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return@all false
    document.modificationStamp == it.stamp
  }

  @JvmStatic fun inspect(project: Project, state: ManagerState, reading: BookmarkReadingState): List<Entry> {
    val reviews = reading.reviews.associateBy { it.bookmarkId }
    val sources = reading.sources.filter { hash(it.text) == it.id }.associateBy { it.id }
    val files = mutableMapOf<String, Capture?>()
    return state.bookmarks.map { record ->
      ProgressManager.checkCanceled()
      val url = BookmarkHealth.expandedUrl(project, record.attributes["url"].orEmpty())
      val review = reviews[record.id]
      if (record.provider != BookmarkHealth.LINE_PROVIDER || !url.startsWith("file://")) return@map Entry(record.id!!, Status.UNSUPPORTED, false)
      val file = VirtualFileManager.getInstance().findFileByUrl(url)
      val supported = record.provider == BookmarkHealth.LINE_PROVIDER && url.startsWith("file://") && file?.isDirectory != true
      val document = if (supported && file?.isValid == true) FileDocumentManager.getInstance().getDocument(file) else null
      val current = if (document == null) null else files.getOrPut(url) { capture(project, record) }
      val status = when {
        !supported -> Status.UNSUPPORTED
        document == null -> Status.MISSING
        document.textLength > MAX_FILE_CHARS -> Status.TOO_LARGE
        review == null || review.snapshotId !in sources -> Status.UNREVIEWED
        review.url != current?.url -> Status.MOVED
        review.snapshotId != current?.hash -> Status.CHANGED
        else -> Status.CURRENT
      }
      Entry(record.id!!, status, review != null && review.descriptionHash != hash(record.description.orEmpty()))
    }
  }
}
