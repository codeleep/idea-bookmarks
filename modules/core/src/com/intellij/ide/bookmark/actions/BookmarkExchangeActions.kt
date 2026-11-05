package com.intellij.ide.bookmark.actions

import com.intellij.ide.bookmark.BookmarkExchange
import com.intellij.ide.bookmark.BookmarksDevBundle.message
import com.intellij.ide.bookmark.BookmarksManagerImpl
import com.intellij.ide.bookmark.ui.BookmarkImportDialog
import com.intellij.ide.bookmark.ui.tree.GroupNode
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path

abstract class BookmarkExchangeAction(text: String) : DumbAwareAction(text) {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT
  override fun update(event: AnActionEvent) {
    val manager = event.bookmarksManager as? BookmarksManagerImpl
    event.presentation.isEnabled = event.project?.basePath != null && manager != null && !manager.isRestoringBookmarks()
  }
}

abstract class ExportBookmarksAction(private val markdown: Boolean) : BookmarkExchangeAction(message(if (markdown) "exchange.markdown" else "exchange.json")) {
  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project ?: return
    val manager = event.bookmarksManager as? BookmarksManagerImpl ?: return
    try {
      val selected = event.bookmarkNodes.orEmpty().filterIsInstance<GroupNode>().mapNotNull { manager.getGroupId(it.value) }.toSet()
      val roots = if (selected.isEmpty()) null else when (Messages.showDialog(project,
        message("exchange.scope.hint"), message("exchange.export.title"),
        arrayOf(message("exchange.scope.selected"), message("exchange.scope.all"), message("exchange.cancel")), 0, Messages.getQuestionIcon())) {
        0 -> selected
        1 -> null
        else -> return
      }
      val state = BookmarkExchange.select(manager.captureForExchange(), roots)
      val base = Path.of(project.basePath!!)
      val extension = if (markdown) "md" else "json"
      val descriptor = FileSaverDescriptor(message("exchange.export.title"), message("exchange.export.hint"), extension)
      val file = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        .save(LocalFileSystem.getInstance().findFileByPath(base.toString()), "bookmarks.$extension") ?: return
      val target = file.file.toPath()
      val content = if (markdown) BookmarkExchange.toMarkdown(state, base, project.name) else BookmarkExchange.toJson(state, base)
      BookmarkExchange.write(target, content)
      Messages.showInfoMessage(project, message("exchange.export.done", target.toString(), state.groups.size, state.bookmarks.size), message("exchange.export.title"))
    }
    catch (error: Exception) { Messages.showErrorDialog(project, error.localizedMessage ?: error.toString(), message("exchange.export.title")) }
  }
}

class ExportBookmarksJsonAction : ExportBookmarksAction(false)
class ExportBookmarksMarkdownAction : ExportBookmarksAction(true)

class ImportBookmarksJsonAction : BookmarkExchangeAction(message("exchange.import")) {
  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project ?: return
    val manager = event.bookmarksManager as? BookmarksManagerImpl ?: return
    try {
      val descriptor = FileChooserDescriptor(true, false, false, false, false, false).apply {
        title = message("exchange.import.title")
        description = message("exchange.import.hint")
        withFileFilter { it.extension.equals("json", true) }
      }
      val file = FileChooser.chooseFile(descriptor, project, null) ?: return
      val path = file.toNioPath()
      require(Files.size(path) <= BookmarkExchange.MAX_BYTES) { message("exchange.too.large") }
      val incoming = BookmarkExchange.fromJson(Files.readString(path), Path.of(project.basePath!!))
      BookmarkImportDialog(project, manager, incoming, path.fileName.toString()).show()
    }
    catch (error: Exception) { Messages.showErrorDialog(project, error.localizedMessage ?: error.toString(), message("exchange.import.title")) }
  }
}
