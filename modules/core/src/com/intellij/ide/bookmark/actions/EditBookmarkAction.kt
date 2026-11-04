// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.bookmark.actions

import com.intellij.ide.bookmark.ui.MarkdownDescriptionEditor
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

internal class EditBookmarkAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(event: AnActionEvent): Unit = with(event.presentation) {
    isEnabledAndVisible = process(event, false) != null
  }

  override fun actionPerformed(event: AnActionEvent) {
    process(event, true)
  }

  private fun process(event: AnActionEvent, perform: Boolean): String? {
    if (event.contextBookmarks != null) return null
    val manager = event.bookmarksManager ?: return null
    val project = event.project ?: return null
    val bookmark = event.contextBookmark ?: return null
    val group = manager.getGroups(bookmark).firstOrNull() ?: return null
    val description = group.getDescription(bookmark) ?: return null
    if (perform) MarkdownDescriptionEditor.getInstance(project).open(group, bookmark)
    return description
  }

  init {
    isEnabledInModalContext = true
  }
}
