// Based on JetBrains' Apache-2.0 BookmarkContextProvider; branch tracking and
// archive ownership remain with the official Tasks / WorkingContextManager.
package com.intellij.ide.bookmark.context

import com.intellij.ide.bookmark.BookmarksManager
import com.intellij.ide.bookmark.BookmarksManagerImpl
import com.intellij.ide.bookmark.ManagerState
import com.intellij.ide.bookmark.providers.LineBookmarkProvider
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupManager
import com.intellij.tasks.context.WorkingContextProvider
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element

/** Same context ID and setting as upstream, with complete tree restoration. */
class BookmarkContextProvider : WorkingContextProvider() {
  override fun getId(): String = "bookmarks"
  override fun getDescription(): String = "Bookmarks"

  override fun saveContext(project: Project, toElement: Element) {
    if (!enabled()) return
    val manager = BookmarksManager.getInstance(project) as? BookmarksManagerImpl ?: return
    toElement.addContent(XmlSerializer.serialize(manager.state))
  }

  override fun loadContext(project: Project, fromElement: Element) {
    if (!enabled()) return
    val element = fromElement.getChild("ManagerState") ?: return
    val state = XmlSerializer.deserialize(element, ManagerState::class.java)
    val manager = BookmarksManager.getInstance(project) as? BookmarksManagerImpl ?: return
    manager.loadState(state)
    val provider = LineBookmarkProvider.Util.find(project) ?: return
    if (StartupManager.getInstance(project).postStartupActivityPassed()) provider.requestValidation()
    else StartupManager.getInstance(project).runAfterOpened { provider.requestValidation() }
  }

  override fun clearContext(project: Project) {
    if (enabled()) BookmarksManager.getInstance(project)?.remove()
  }

  private fun enabled() = AdvancedSettings.getBoolean("tasks.enable.bookmark.context.on.branch.switch")

  companion object {
    /** The built-in adapter flattens groups. Replace that one adapter, retaining
     * the platform's branch listener, archive, settings, and context ID. */
    @JvmStatic @Synchronized
    fun install() {
      val point = EP_NAME.point
      point.extensionList.filter {
        it.javaClass.name == "com.intellij.platform.tasks.impl.bookmarks.BookmarkContextProvider"
      }.forEach { point.unregisterExtension(it.javaClass) }
    }
  }
}
