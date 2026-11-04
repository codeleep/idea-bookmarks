// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.bookmark.ui.tree

import com.intellij.ide.bookmark.BookmarkGroup
import com.intellij.ide.bookmark.BookmarksListProviderService
import com.intellij.ide.bookmark.BookmarksManagerImpl
import com.intellij.ide.bookmark.LineBookmark
import com.intellij.ide.bookmark.ui.BookmarksView
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.ide.util.treeView.AbstractTreeNodeCache
import com.intellij.util.containers.ContainerUtil

internal class RootNode(panel: BookmarksView) : AbstractTreeNode<BookmarksView>(panel.project, panel) {
  private val extra
    get() = BookmarksListProviderService.getProviders(project)
      .mapNotNull { it.createNode() }
      .onEach { it.parent = this }

  private val cache = AbstractTreeNodeCache<BookmarkGroup, GroupNode>(this) { GroupNode(project!!, it) }

  private val BookmarkGroup.anyLineBookmark
    get() = bookmarksManager?.groups?.any { group ->
      (bookmarksManager as? BookmarksManagerImpl)?.isGroupInSubtree(group, this) != false && group.getBookmarks().any { it is LineBookmark }
    } == true

  override fun isAlwaysShowPlus(): Boolean = true
  override fun getChildren(): List<AbstractTreeNode<*>> {
    val roots = (bookmarksManager as? BookmarksManagerImpl)?.getChildGroups(null) ?: bookmarksManager?.groups.orEmpty()
    val nodes = cache.getNodes(roots.filter { !value.isPopup || it.anyLineBookmark })
    return when {
      !value.isPopup -> ContainerUtil.concat(nodes, extra)
      nodes.size == 1 -> nodes[0].children.onEach { it.parent = this }
      else -> nodes
    }
  }

  override fun update(presentation: PresentationData) = Unit
}
