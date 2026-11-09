// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.bookmark

import com.intellij.util.xmlb.annotations.XCollection
import com.intellij.util.xmlb.annotations.XMap


class ManagerState {
  var formatVersion: Int = 0

  @get:XCollection
  val bookmarks: MutableList<BookmarkState> = mutableListOf()

  @get:XCollection
  val groups: MutableList<GroupState> = mutableListOf()

  @get:XCollection
  val relations: MutableList<BookmarkRelation> = mutableListOf()
}

class BookmarkRelation {
  var id: String = ""
  var sourceId: String = ""
  var targetId: String = ""
  var kind: String = "RELATED"
  var note: String = ""
}


class GroupState {
  var id: String? = null
  var parentId: String? = null
  var name: String = ""
  var isDefault: Boolean = false

  @get:XCollection
  val bookmarkIds: MutableList<String> = mutableListOf()

  @get:XCollection
  val bookmarks: MutableList<BookmarkState> = mutableListOf()
}


class BookmarkState {
  var id: String? = null
  var provider: String? = null
  var description: String? = null
  var type: BookmarkType = BookmarkType.DEFAULT

  @get:XMap
  val attributes: MutableMap<String, String> = mutableMapOf()

  override fun toString(): String = StringBuilder("BookmarkState").apply {
    append(": provider=").append(provider)
    append(", description=").append(description)
    append(", type=").append(type)
    attributes.forEach { append(", ").append(it.key).append("=").append(it.value) }
  }.toString()
}
