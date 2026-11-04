package com.intellij.ide.bookmark.ui

import com.intellij.ide.bookmark.BookmarkOccurrence
import com.intellij.ide.bookmark.BookmarksDevBundle.message
import com.intellij.ide.bookmark.BookmarksManagerImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

object BookmarkDeletion {
  @JvmStatic
  fun needsChoice(manager: BookmarksManagerImpl, occurrences: List<BookmarkOccurrence>): Boolean =
    occurrences.any { manager.getGroups(it.bookmark).size > 1 }

  fun delete(project: Project, manager: BookmarksManagerImpl, occurrences: List<BookmarkOccurrence>) {
    if (occurrences.isEmpty()) return
    if (!needsChoice(manager, occurrences)) {
      manager.deleteOccurrences(occurrences, false)
      return
    }
    val paths = occurrences.map { manager.getGroupPath(it.group) }.distinct()
    val current = message(if (paths.size == 1) "bookmark.delete.current" else "bookmark.delete.selected")
    val choice = Messages.showDialog(project, message("bookmark.delete.references", paths.joinToString("、")), message("bookmark.delete.title"),
                                    arrayOf(current, message("bookmark.delete.all"), message("bookmark.delete.cancel")),
                                    0, Messages.getQuestionIcon())
    when (choice) {
      0 -> manager.deleteOccurrences(occurrences, false)
      1 -> manager.deleteOccurrences(occurrences, true)
    }
  }
}
