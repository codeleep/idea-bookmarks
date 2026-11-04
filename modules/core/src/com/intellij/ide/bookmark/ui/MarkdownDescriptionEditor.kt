package com.intellij.ide.bookmark.ui

import com.intellij.ide.bookmark.Bookmark
import com.intellij.ide.bookmark.BookmarkGroup
import com.intellij.ide.bookmark.BookmarkType
import com.intellij.ide.bookmark.BookmarksDevBundle.message
import com.intellij.ide.bookmark.BookmarksListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import org.intellij.plugins.markdown.lang.MarkdownFileType

/** In-memory Markdown files are opened by IDEA's own Markdown editor provider. */
@Service(Service.Level.PROJECT)
class MarkdownDescriptionEditor(private val project: Project) : Disposable {
  private data class Target(val group: BookmarkGroup, val bookmark: Bookmark)
  private class Session(val file: VirtualFile, val document: Document, val lifetime: Disposable)
  private val sessions = mutableMapOf<Target, Session>()

  fun open(group: BookmarkGroup, bookmark: Bookmark) {
    FileEditorManager.getInstance(project).openFile(getDescriptionFile(group, bookmark), true)
  }

  fun getDescriptionFile(group: BookmarkGroup, bookmark: Bookmark): VirtualFile {
    val target = Target(group, bookmark)
    sessions[target]?.let { return it.file }
    val file = LightVirtualFile(message("description.file.name", group.name), MarkdownFileType.INSTANCE,
                                group.getDescription(bookmark).orEmpty())
    val document = runReadAction { requireNotNull(FileDocumentManager.getInstance().getDocument(file)) }
    val lifetime = Disposer.newDisposable("Bookmark Markdown document")
    Disposer.register(this, lifetime)
    sessions[target] = Session(file, document, lifetime)
    document.addDocumentListener(object : DocumentListener {
      override fun documentChanged(event: DocumentEvent) {
        val text = event.document.text
        val previous = group.getDescription(bookmark)
        if (previous == null) group.add(bookmark, BookmarkType.DEFAULT, text)
        else if (previous != text) group.setDescription(bookmark, text)
      }
    }, lifetime)
    return file
  }

  private fun closeMatching(predicate: (Target) -> Boolean) {
    sessions.keys.filter(predicate).forEach { target ->
      val session = sessions.remove(target) ?: return@forEach
      Disposer.dispose(session.lifetime)
      ApplicationManager.getApplication().invokeLater({
        if (!project.isDisposed) FileEditorManager.getInstance(project).closeFile(session.file)
      }, ModalityState.any())
    }
  }

  init {
    val bus = project.messageBus.connect(this)
    bus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
      override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        val entry = sessions.entries.firstOrNull { it.value.file === file } ?: return
        sessions.remove(entry.key)
        Disposer.dispose(entry.value.lifetime)
      }
    })
    bus.subscribe(BookmarksListener.TOPIC, object : BookmarksListener {
      override fun groupRemoved(group: BookmarkGroup) = closeMatching { it.group == group }
      override fun bookmarkRemoved(group: BookmarkGroup, bookmark: Bookmark) = closeMatching { it == Target(group, bookmark) }
      override fun bookmarkChanged(group: BookmarkGroup, bookmark: Bookmark) {
        val target = Target(group, bookmark)
        val session = sessions[target] ?: return
        val document = session.document
        if (document.text == group.getDescription(bookmark)) return
        ApplicationManager.getApplication().invokeLater({
          if (!project.isDisposed && sessions[target] === session) {
            val text = group.getDescription(bookmark) ?: return@invokeLater
            if (document.text != text) WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
          }
        }, ModalityState.any())
      }
    })
  }

  override fun dispose() = Unit

  companion object {
    @JvmStatic fun getInstance(project: Project): MarkdownDescriptionEditor = project.service()
  }
}
