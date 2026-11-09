package com.intellij.ide.bookmark.ui

import com.intellij.ide.bookmark.*
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.EditorTextField
import com.intellij.util.ui.JBUI
import org.intellij.plugins.markdown.lang.MarkdownFileType
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

/** IDEA's editor and Markdown file type; the note belongs to this route step, not the shared bookmark. */
internal class BookmarkRouteNoteDialog(project: Project, private val manager: BookmarksManagerImpl,
                                       private val state: ManagerState, private val groupId: String,
                                       private val id: String) : DialogWrapper(project, true) {
  private val store = BookmarkWorkspaceStore.getInstance(project)
  private val epoch = store.contextRevision()
  private val before = store.route(state, groupId).notes.firstOrNull { it.bookmarkId == id }?.markdown.orEmpty()
  private val document = EditorFactory.getInstance().createDocument(before)
  private val editor = EditorTextField(document, project, MarkdownFileType.INSTANCE, false, false)
  override fun getPreferredFocusedComponent() = editor
  override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
    preferredSize = Dimension(JBUI.scale(700), JBUI.scale(400))
    add(JLabel("写下本次关注的问题、下一站的理由、源码／测试依据和待确认项。支持 Markdown。"), BorderLayout.NORTH)
    add(editor, BorderLayout.CENTER)
    add(JLabel("仅保存在这条路线，随分支恢复；其他路线和书签共享描述保持独立。"), BorderLayout.SOUTH)
  }
  override fun doOKAction() {
    try { store.saveRouteNote(state, epoch, groupId, id, before, document.text); super.doOKAction() }
    catch (error: Exception) { Messages.showErrorDialog(error.localizedMessage.orEmpty(), title) }
  }
  init { title = "本站说明"; setOKButtonText("保存"); init() }
}
