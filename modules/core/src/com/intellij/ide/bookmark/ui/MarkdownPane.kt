package com.intellij.ide.bookmark.ui

import com.intellij.ide.BrowserUtil
import com.intellij.util.ui.UIUtil
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent

/** Lightweight read-only summary in the tool window; full editing uses the bundled Markdown plugin. */
internal fun markdownPane(): JEditorPane = JEditorPane("text/html", "").apply {
  isEditable = false
  background = UIUtil.getTextFieldBackground()
  putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
  addHyperlinkListener { event ->
    if (event.eventType == HyperlinkEvent.EventType.ACTIVATED && event.url?.protocol in setOf("http", "https", "mailto")) {
      event.url?.let { BrowserUtil.browse(it) }
    }
  }
}
