package bookmarks.validation;

import com.intellij.ide.bookmark.*;
import com.intellij.ide.bookmark.ui.BookmarksView;
import com.intellij.ide.bookmark.ui.MarkdownDescriptionEditor;
import com.intellij.ide.bookmark.ui.BookmarkGroupsTree;
import com.intellij.ide.bookmark.ui.BookmarkDeletion;
import com.intellij.ide.bookmark.ui.GroupBookmarkVisitor;
import com.intellij.ide.bookmark.ui.tree.GroupNode;
import com.intellij.ide.bookmark.ui.tree.RootNode;
import com.intellij.ide.bookmark.providers.LineBookmarkProvider;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.tree.TreeVisitor;
import com.intellij.util.xmlb.XmlSerializer;
import com.intellij.tasks.context.WorkingContextManager;
import com.intellij.tasks.context.WorkingContextProvider;
import com.intellij.tasks.context.BranchContextTracker;
import com.intellij.openapi.options.advanced.AdvancedSettings;
import com.intellij.openapi.vcs.VcsConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

/** Runs the delivered plugin in the unmodified official IDE. */
public final class BookmarkSmokeStarter implements ApplicationStarter {
  private static Project project;
  private static BookmarksManagerImpl manager;
  private static VirtualFile file;
  private static String childId;
  private static String leafId;
  private static String sharedA, sharedB, sharedC;
  private static String migratedDescription;
  private static com.intellij.openapi.editor.Document sharedEditorDocument;
  private static BranchContextTracker branchTracker;
  private static String branchRoot, branchChild, branchBookmark;
  private static boolean previousBranchSetting, previousReloadSetting;
  private static final String MARKDOWN = """
      # CDC 链路

      **入口**：`SourceReader`，保留原文与换行。

      - 定位读取入口
      - 检查提交边界

      ```java
      reader.poll();
      ```

      | 阶段 | 状态 |
      | --- | --- |
      | CDC | 已验证 |

      [JetBrains](https://www.jetbrains.com/)
      """;

  @Override public boolean isHeadless() { return true; }
  @Override public int getRequiredModality() { return NOT_IN_EDT; }
  private static void edt(Runnable action) { ApplicationManager.getApplication().invokeAndWait(action); }

  @Override public void main(List<String> args) {
    try {
      Path root = Path.of(System.getProperty("bookmarks.validation.root"));
      var descriptor = PluginManagerCore.getPlugin(PluginId.getId("intellij.bookmarks.plugin"));
      check(descriptor != null, "plugin registered");
      check(System.getProperty("bookmarks.validation.version").equals(descriptor.getVersion()), "development version selected");
      check(!descriptor.isBundled() && descriptor.getPluginPath().startsWith(root.resolve("plugins")), "plugin loaded from isolated user directory");
      Path filePath = root.resolve("project/sample.txt");
      Files.createDirectories(filePath.getParent());
      Files.writeString(filePath, "first line\nsecond line\nthird line\n");
      if (args.contains("verify-ai-archive")) {
        verifyAiArchiveAfterRestart(root, filePath);
        return;
      }
      AnchorSmokeStarter.verify(root);
      edt(() -> {
        project = ProjectManagerEx.getInstanceEx().newProject("bookmarks-validation", filePath.getParent().toString(), false, false);
        manager = (BookmarksManagerImpl)BookmarksManager.getInstance(project);
        check(manager != null, "project service created");
        var resource = manager.getClass().getResource("BookmarksManagerImpl.class");
        check(resource != null && resource.toString().contains("plugins/platform-bookmarks-plugin/"), "implementation from installed ZIP");
        file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(filePath);
        check(file != null, "virtual file found");
        BookmarkGroup group = manager.addGroup("Source build test", true);
        Bookmark bookmark = manager.createBookmark(file);
        check(group != null && bookmark != null && group.add(bookmark, BookmarkType.LETTER_A, "source-build bookmark"), "file bookmark and group creation");
        check(bookmark.equals(manager.getBookmark(BookmarkType.LETTER_A)), "mnemonic lookup");
        check("source-build bookmark".equals(group.getDescription(bookmark)), "plain description retained");
        group.setName("Renamed group");
        check(manager.getGroup("Renamed group") == group, "group rename");
        ManagerState state = manager.getState();
        manager.remove();
        check(manager.getBookmarks().isEmpty(), "bookmarks removed");
        manager.loadState(state);
      });
      await(() -> !manager.isRestoringBookmarks() && manager.getBookmark(BookmarkType.LETTER_A) != null, "original bookmark state restored");
      BreakpointActionsSmoke.verify(project, file, manager);
      edt(BookmarkSmokeStarter::hierarchyAndMarkdown);
      await(() -> !manager.isRestoringBookmarks() && manager.getBookmark(BookmarkType.LETTER_B) != null, "nested bookmark restored from XML");
      edt(BookmarkSmokeStarter::verifyHierarchyAndTree);
      edt(BookmarkSmokeStarter::multiGroupBookmarks);
      await(() -> !manager.isRestoringBookmarks() && manager.getBookmark(BookmarkType.DIGIT_2) != null && manager.getGroups(manager.getBookmark(BookmarkType.DIGIT_2)).size() == 3,
            "multi-group line bookmark restored from XML");
      edt(BookmarkSmokeStarter::gutterNameTooltip);
      edt(BookmarkSmokeStarter::verifySharedBookmarkRestoreAndDeletion);
      edt(BookmarkSmokeStarter::migrateDifferentDescriptions);
      await(() -> !manager.isRestoringBookmarks() && manager.getGroups().size() == 2 && manager.getGroups().stream().allMatch(g -> !g.getBookmarks().isEmpty()), "legacy duplicate bookmark memberships restored");
      edt(() -> {
        Bookmark bookmark = manager.getGroups().get(0).getBookmarks().get(0);
        migratedDescription = manager.getGroups().get(0).getDescription(bookmark);
        check(migratedDescription.contains("旧说明 A") && migratedDescription.contains("旧说明 B"), "distinct legacy descriptions merged without discarding text");
        check(manager.getGroups().stream().allMatch(g -> migratedDescription.equals(g.getDescription(bookmark))), "migrated description shared by every group");
        manager.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(manager.getState()), ManagerState.class));
      });
      await(() -> !manager.isRestoringBookmarks() && manager.getGroups().size() == 2 && manager.getGroups().stream().allMatch(g -> !g.getBookmarks().isEmpty()), "merged legacy description restored again");
      edt(() -> {
        Bookmark bookmark = manager.getGroups().get(0).getBookmarks().get(0);
        check(migratedDescription.equals(manager.getGroups().get(0).getDescription(bookmark)), "legacy description migration is idempotent");
      });
      edt(BookmarkSmokeStarter::legacyAndDamagedState);
      await(() -> !manager.isRestoringBookmarks() && manager.getBookmark(BookmarkType.LETTER_C) != null, "old flat bookmark migrated");
      edt(() -> {
        check(manager.getChildGroups(null).size() == 1, "old flat list remains a root group");
        check("旧描述\n保留换行".equals(manager.getGroup("Legacy").getDescription(manager.getBookmark(BookmarkType.LETTER_C))), "legacy description preserved exactly");
        ManagerState damaged = new ManagerState();
        damaged.getGroups().add(groupState("a", "b", "A"));
        damaged.getGroups().add(groupState("b", "a", "B"));
        damaged.getGroups().add(groupState("c", "missing", "C"));
        damaged.getGroups().add(groupState("d", "d", "D"));
        manager.loadState(damaged);
        check(manager.getGroups().size() == 4, "damaged hierarchy retains all groups");
        check(manager.getParentGroup(manager.getGroupById("c")) == null && manager.getParentGroup(manager.getGroupById("d")) == null, "missing and self parents recovered as roots");
        check(!manager.isGroupInSubtree(manager.getGroupById("a"), manager.getGroupById("b")) || !manager.isGroupInSubtree(manager.getGroupById("b"), manager.getGroupById("a")), "persisted cycles repaired");
      });
      edt(BookmarkSmokeStarter::normalizedStorage);
      await(() -> !manager.isRestoringBookmarks(), "normalized state restoration completes");
      edt(() -> {
        check(manager.getState().getBookmarks().stream().anyMatch(b -> "unavailable-provider".equals(b.getId())), "unknown provider record retained without data loss");
        var legacy = new ManagerState();
        var group = new GroupState(); group.setName("Legacy unavailable provider");
        var item = new BookmarkState(); item.setProvider("example.unavailable.BookmarkProvider");
        item.getAttributes().put("symbol", "legacy-retained"); group.getBookmarks().add(item); legacy.getGroups().add(group);
        manager.loadState(legacy);
      });
      await(() -> !manager.isRestoringBookmarks(), "legacy unavailable provider migration completes");
      edt(() -> {
        var id = manager.getState().getBookmarks().getFirst().getId();
        check(id != null && id.equals(manager.getState().getBookmarks().getFirst().getId()), "legacy unavailable provider receives a stable ID across saves");
        setupBranchContexts();
      });
      await(() -> !manager.isRestoringBookmarks() && manager.getGroupById(branchChild) != null
          && manager.getBookmark(BookmarkType.DIGIT_7) != null, "official branch tracker restores branch A");
      edt(() -> {
        var bookmark = manager.getBookmark(BookmarkType.DIGIT_7);
        check(manager.getParentGroup(manager.getGroupById(branchChild)) == manager.getGroupById(branchRoot), "branch context preserves parent IDs and same-name subgroups");
        check(manager.getGroups(bookmark).size() == 3 && MARKDOWN.equals(manager.getGroupById(branchChild).getDescription(bookmark)), "branch context restores three references and exact Markdown");
        check(branchBookmark.equals(manager.getBookmarkId(bookmark)), "branch context retains stable bookmark ID");
        branchTracker.branchWillChange("validation-a");
        branchTracker.branchHasChanged("validation-b");
      });
      await(() -> !manager.isRestoringBookmarks() && manager.getBookmark(BookmarkType.DIGIT_8) != null
          && manager.getGroup("Branch B") != null, "official branch tracker restores branch B");
      edt(() -> {
        check(manager.getBookmark(BookmarkType.DIGIT_7) == null && manager.getGroupById(branchRoot) == null, "branch B contains no branch A bookmarks or groups");
        check("分支 B 描述".equals(manager.getGroup("Branch B").getDescription(manager.getBookmark(BookmarkType.DIGIT_8))), "branch-specific Markdown remains independent");
        AdvancedSettings.setBoolean("tasks.enable.bookmark.context.on.branch.switch", false);
        var before = manager.getBookmark(BookmarkType.DIGIT_8);
        var context = new org.jdom.Element("context");
        WorkingContextManager.getInstance(project).saveContext(context);
        WorkingContextManager.getInstance(project).clearContext();
        WorkingContextManager.getInstance(project).loadContext(context);
        check(manager.getBookmark(BookmarkType.DIGIT_8) == before, "original global-mode setting keeps branch context operations as no-ops");
        AdvancedSettings.setBoolean("tasks.enable.bookmark.context.on.branch.switch", previousBranchSetting);
        VcsConfiguration.getInstance(project).RELOAD_CONTEXT = previousReloadSetting;
      });
      var aiState = XmlSerializer.deserialize(com.intellij.openapi.util.JDOMUtil.load(root.resolve("ai-import.xml")), ManagerState.class);
      edt(() -> manager.loadState(aiState));
      await(() -> !manager.isRestoringBookmarks() && manager.getBookmark(BookmarkType.LETTER_Z) != null, "AI JSON writer output restored by actual IDE serializer");
      edt(() -> {
        var bookmark = manager.getBookmark(BookmarkType.LETTER_Z);
        check("ai-stable-bookmark".equals(manager.getBookmarkId(bookmark)) && manager.getGroups(bookmark).size() == 2, "AI import preserves canonical ID and two references");
        check(manager.getParentGroup(manager.getGroupById("ai-child")) == manager.getGroupById("ai-root") && manager.getDefaultGroup() == manager.getGroupById("ai-child"), "AI import restores hierarchy and nested default group");
        check("# AI 原文\n\n**共享** <tag> & 中文\n".equals(manager.getGroupById("ai-child").getDescription(bookmark)), "AI import preserves exact Markdown and special characters");
      });
      edt(BookmarkSmokeStarter::bookmarkSearch);
      edt(BookmarkSmokeStarter::exchangePlans);
      edt(() -> {
        try {
          var current = manager.captureForExchange();
          long revision = manager.getStateModificationCount();
          var bookmark = manager.getBookmark(BookmarkType.LETTER_Z);
          var document = FileDocumentManager.getInstance().getDocument(MarkdownDescriptionEditor.getInstance(project)
              .getDescriptionFile(manager.getGroupById("ai-child"), bookmark));
          var incoming = BookmarkExchange.copy(current);
          incoming.getBookmarks().getFirst().setDescription("# 导入的共享描述\n\n**中文** & <tag>\n");
          var plan = BookmarkExchange.plan(current, incoming, false, true);
          var blocked = filePath.getParent().resolve(".idea/bookmarks-import-backups");
          Files.writeString(blocked, "blocked-backup-directory");
          boolean refused = false;
          try { manager.applyExchange(current, revision, plan.getState()); } catch (Exception expected) { refused = true; }
          check(refused && BookmarkExchange.toJson(current, null).equals(BookmarkExchange.toJson(manager.captureForExchange(), null)), "backup failure prevents any live import mutation");
          Files.delete(blocked);
          var backup = manager.applyExchange(current, revision, plan.getState());
          check(Files.isRegularFile(backup) && BookmarkExchange.toJson(current, filePath.getParent()).equals(Files.readString(backup)), "import saves complete portable backup before modifying the live model");
          WriteCommandAction.runWriteCommandAction(project, () -> document.setText("旧编辑页不能覆盖导入结果"));
          Files.writeString(root.resolve("portable-export.json"), BookmarkExchange.toJson(plan.getState(), filePath.getParent()));
          Files.writeString(root.resolve("shared-bookmarks.md"), BookmarkExchange.toMarkdown(plan.getState(), filePath.getParent(), "验证项目"));
        } catch (Exception error) { throw new RuntimeException(error); }
      });
      await(() -> !manager.isRestoringBookmarks() && manager.getBookmark(BookmarkType.LETTER_Z) != null, "interactive import restores through the official live manager");
      edt(() -> {
        var bookmark = manager.getBookmark(BookmarkType.LETTER_Z);
        check(manager.getGroups(bookmark).size() == 2 && manager.getGroups(bookmark).stream().allMatch(g -> "# 导入的共享描述\n\n**中文** & <tag>\n".equals(g.getDescription(bookmark))), "import applies shared description to both groups and detaches stale editors");
        check("ai-stable-bookmark".equals(manager.getBookmarkId(bookmark)) && manager.getParentGroup(manager.getGroupById("ai-child")) == manager.getGroupById("ai-root"), "live import preserves stable IDs and nested hierarchy");
        var expected = manager.captureForExchange();
        var revision = manager.getStateModificationCount();
        manager.getGroupById("ai-root").setName("预览期间修改");
        reject(() -> manager.applyExchange(expected, revision, expected), "stale import preview rejected without overwriting newer edits");
        check("预览期间修改".equals(manager.getGroupById("ai-root").getName()), "rejected stale preview retains current state");
        manager.getGroupById("ai-root").setName("AI 根组");
      });
      com.intellij.configurationStore.StoreUtil.saveSettings(project, true);
      Path persisted = root.resolve("project/.idea/bookmarks.xml");
      await(() -> Files.isRegularFile(persisted), "new state saved to project bookmarks.xml");
      var persistedText = Files.readString(persisted);
      check(persistedText.contains("formatVersion") && persistedText.contains("bookmarkIds"), "persisted project file uses normalized format");
      check(Files.isRegularFile(root.resolve("project/.idea/bookmarks-legacy-backup.xml")), "original legacy state backed up before migration");
      Files.writeString(root.resolve("result.txt"), "PASS\npluginVersion=" + descriptor.getVersion() + "\npluginPath=" + descriptor.getPluginPath() + "\n");
      System.out.println("BOOKMARKS_SMOKE_PASS");
      System.exit(0);
    } catch (Throwable error) {
      error.printStackTrace();
      System.exit(1);
    }
  }

  private static void reject(Runnable action, String label) {
    boolean rejected = false;
    try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
    check(rejected, label);
  }

  private static void gutterNameTooltip() {
    var bookmark = manager.getBookmark(BookmarkType.DIGIT_2);
    var renderer = new GutterLineBookmarkRenderer((LineBookmark)bookmark);
    var before = BookmarkExchange.toJson(manager.captureForExchange(), null);
    var tooltip = renderer.getTooltipText();
    check(tooltip.contains("CDC 链路") && !tooltip.contains("# CDC"), "real gutter tooltip uses the same Markdown first-line name as the bookmark tree");
    check(!tooltip.contains("reader.poll") && !tooltip.contains("SourceReader"), "gutter hover shows the name without the full Markdown body");
    check(renderer.getClickAction() != null && renderer.getMiddleButtonClickAction() != null && renderer.getIcon() != null, "name tooltip preserves bookmark icon and click actions");
    check(tooltip.contains("2"), "gutter tooltip retains the bookmark mnemonic");
    check(before.equals(BookmarkExchange.toJson(manager.captureForExchange(), null)), "reading the gutter name does not change bookmarks descriptions IDs or references");
    manager.getGroupById(sharedB).setDescription(bookmark, "# 改后的名字 <入口> & 中文\n\n正文不出现在悬停名称里");
    check(renderer.getTooltipText().contains("改后的名字") && !renderer.getTooltipText().contains("CDC 链路"), "editing from another group immediately updates the shared gutter name");
    check(renderer.getTooltipText().contains("<b>改后的名字 &lt;入口&gt; &amp; 中文</b>"), "gutter tooltip emphasizes and safely escapes the bookmark name on its first line");
    manager.getGroupById(sharedA).setDescription(bookmark, "\n  \t\n");
    check(!renderer.getTooltipText().contains("<b>") && !renderer.getTooltipText().contains("null"), "blank descriptions keep the ordinary bookmark tooltip without an empty title");
    manager.getGroupById(sharedA).setDescription(bookmark, MARKDOWN);
    check(renderer.getTooltipText().contains("CDC 链路") && !renderer.getTooltipText().contains("改后的名字"), "restoring the shared description also restores the gutter name");
  }

  private static void bookmarkSearch() {
    var state = BookmarkExchange.copy(manager.captureForExchange());
    var record = state.getBookmarks().getFirst();
    record.setDescription("# 摘要\n\n中文正文：保留完整 Markdown\n\n" + "padding ".repeat(100) + "FAR_NEEDLE at the end\n\n```java\nreadCheckpoint();\n```\n[参考](https://example.invalid)");
    record.getAttributes().put("lineText", "    \t读取锚点();  ");
    var original = BookmarkExchange.toJson(state, null);
    var index = BookmarkSearchIndex.fromState(state, project.getBasePath());
    check(index.search("FAR_NEEDLE").size() == 1, "full-text search finds content beyond the tree summary and long-line limit");
    check(index.search("readCheckpoint").size() == 1, "full-text search includes Markdown fenced code bodies");
    check(index.search("中文正文").size() == 1, "full-text search matches Chinese description text");
    check(index.search("读取锚点").size() == 1, "full-text search includes the original code anchor");
    check(index.search("SAMPLE.TXT").size() == 1, "full-text search matches file paths without case sensitivity");
    check(index.search("根组").size() == 1 && index.search("子组").size() == 1, "full-text search indexes all ancestor and membership group paths");
    check(index.search("中文正文 sample.txt 子组").size() == 1, "multiple search terms can match across description file and group fields");
    check(index.search("  far_needle\tSAMPLE.txt  ").size() == 1, "search terms ignore case and separate whitespace");
    check(index.search("FAR_NEEDLE absent-marker").isEmpty(), "all search terms are required");
    check(index.search(".*").isEmpty() && index.search("[").size() == 1, "search input is literal text rather than a regular expression");
    check(index.search("").size() == 1 && index.getEntries().getFirst().getGroups().size() == 2, "a shared bookmark appears once while retaining both group paths");
    var result = index.search("FAR_NEEDLE").getFirst();
    check(result.snippet("FAR_NEEDLE").contains("FAR_NEEDLE"), "search snippet includes a match near the end of a long line");
    check(result.getLocation().equals("sample.txt:2"), "search results show project-relative location and one-based line number");
    check("ai-child".equals(result.preferredGroup("子组").getId()), "group matches choose the corresponding membership for tree navigation");
    check(index.search("Branch A").isEmpty(), "search uses only the current bookmark context");
    check(original.equals(BookmarkExchange.toJson(state, null)), "search indexing and filtering preserve original Markdown anchors IDs and memberships");
    record.setDescription("İ".repeat(200) + " FAR_NEEDLE");
    check(BookmarkSearchIndex.fromState(state, project.getBasePath()).search("far_needle").getFirst().snippet("far_needle").contains("FAR_NEEDLE"), "Unicode case expansion preserves valid snippet offsets");
    var unknown = new BookmarkState(); unknown.setId("search-unknown"); unknown.setProvider("example.unavailable.Provider");
    unknown.setDescription("unavailable-provider-note"); unknown.getAttributes().put("symbol", "opaqueSymbol");
    state.getBookmarks().add(unknown); state.getGroups().getFirst().getBookmarkIds().add("search-unknown");
    check(BookmarkSearchIndex.fromState(state, project.getBasePath()).search("unavailable-provider-note").size() == 1, "search retains canonical records from unavailable providers");
    var actions = com.intellij.openapi.actionSystem.ActionManager.getInstance();
    var action = actions.getAction("Bookmarks.Search");
    check(action != null && action.getTemplatePresentation().getIcon() != null, "installed plugin registers the full-text search icon action");
    var toolbar = (com.intellij.openapi.actionSystem.ActionGroup)actions.getAction("Bookmarks.ToolWindow.TitleActions");
    var children = java.util.Arrays.asList(toolbar.getChildren(null));
    check(children.indexOf(action) == children.indexOf(actions.getAction("BookmarksView.Create")) + 1, "full-text search action follows Create Bookmark List in the toolbar");
  }

  private static void exchangePlans() {
    var base = Path.of(project.getBasePath());
    var current = manager.captureForExchange();
    check(manager.getBookmark(BookmarkType.LETTER_Z).canNavigate(), "imported JSON fixture resolves a real navigable file with Unicode project paths");
    var text = BookmarkExchange.toJson(current, base);
    check(text.contains("file://$PROJECT_DIR$/sample.txt") && !text.contains(base.toString()), "portable export replaces project file paths and omits local source metadata");
    var decoded = BookmarkExchange.fromJson(text, base);
    try {
      var fixture = Files.readString(Path.of(System.getProperty("bookmarks.validation.root")).resolve("ai-import.json"));
      check(BookmarkExchange.toJson(decoded, null).equals(BookmarkExchange.toJson(BookmarkExchange.fromJson(fixture, base), null)), "IDE JSON decoder accepts the actual Python AI exchange document");
    } catch (Exception error) { throw new RuntimeException(error); }
    check(BookmarkExchange.toJson(current, null).equals(BookmarkExchange.toJson(decoded, null)), "portable JSON round-trip preserves exact descriptions IDs ordering and references");
    var relocated = BookmarkExchange.fromJson(text, base.resolve("other-checkout"));
    check(relocated.getBookmarks().getFirst().getAttributes().get("url").contains("/other-checkout/sample.txt"), "portable import remaps paths to another checkout");
    var selected = BookmarkExchange.select(current, java.util.Set.of("ai-child"));
    check(selected.getGroups().size() == 1 && selected.getGroups().getFirst().getParentId() == null
        && selected.getBookmarks().size() == 1 && selected.getGroups().getFirst().getBookmarkIds().size() == 1, "subtree export detaches parent while retaining canonical shared bookmark");
    check(BookmarkExchange.select(current, java.util.Set.of("ai-root", "ai-child")).getGroups().size() == 2, "overlapping selected subtrees export each group once");
    reject(() -> BookmarkExchange.select(current, java.util.Set.of("missing")), "invalid export selection rejected");
    var sameNames = BookmarkExchange.copy(current);
    sameNames.getGroups().add(groupState("other-root", null, "其他根组"));
    sameNames.getGroups().add(groupState("other-child", "other-root", "AI 子组"));
    sameNames.getGroups().getLast().getBookmarkIds().add("ai-stable-bookmark");
    var roots = BookmarkExchange.select(sameNames, java.util.Set.of("ai-child", "other-child"));
    check(roots.getGroups().stream().map(GroupState::getName).distinct().count() == 2 && roots.getGroups().stream().allMatch(g -> g.getParentId() == null), "selected roots with duplicate names are deterministically renamed without losing references");
    var identical = BookmarkExchange.plan(current, decoded, false, false);
    check(BookmarkExchange.toJson(identical.getState(), null).equals(BookmarkExchange.toJson(current, null)), "reimporting the same JSON is idempotent");
    var incoming = BookmarkExchange.copy(decoded);
    incoming.getBookmarks().getFirst().setId("different-source-id");
    incoming.getBookmarks().getFirst().setDescription("# 新的导入描述\n\n保留中文和换行\n");
    incoming.getGroups().forEach(g -> { g.getBookmarkIds().clear(); g.getBookmarkIds().add("different-source-id"); });
    incoming.getGroups().getFirst().setId("different-root-id");
    incoming.getGroups().get(1).setParentId("different-root-id");
    var merged = BookmarkExchange.plan(current, incoming, false, false);
    check(merged.getState().getGroups().size() == 2 && merged.getState().getBookmarks().size() == 1, "merge reuses same group paths and same code location despite different IDs");
    check(merged.getDescriptionConflicts() == 1 && current.getBookmarks().getFirst().getDescription().equals(merged.getState().getBookmarks().getFirst().getDescription()), "description conflict defaults to preserving existing text");
    var preferred = BookmarkExchange.plan(current, incoming, false, true);
    check(incoming.getBookmarks().getFirst().getDescription().equals(preferred.getState().getBookmarks().getFirst().getDescription()), "description conflict can use imported text");
    check(!current.getBookmarks().getFirst().getDescription().equals(incoming.getBookmarks().getFirst().getDescription()), "preview computation never mutates current model");
    var emptyDescription = BookmarkExchange.copy(current); emptyDescription.getBookmarks().getFirst().setDescription(null);
    check(incoming.getBookmarks().getFirst().getDescription().equals(BookmarkExchange.plan(emptyDescription, incoming, false, false).getState().getBookmarks().getFirst().getDescription()), "merge fills an empty description without destroying a nonempty one");
    var extra = new BookmarkState(); extra.setId("ai-stable-bookmark"); extra.setProvider(current.getBookmarks().getFirst().getProvider());
    extra.getAttributes().put("url", file.getUrl()); extra.getAttributes().put("line", "0"); extra.setType(BookmarkType.LETTER_Z);
    incoming.getBookmarks().getFirst().setType(BookmarkType.DEFAULT);
    incoming.getBookmarks().add(extra); incoming.getGroups().getFirst().getBookmarkIds().add(extra.getId());
    merged = BookmarkExchange.plan(current, incoming, false, false);
    check(merged.getState().getBookmarks().size() == 2 && merged.getState().getBookmarks().stream().map(BookmarkState::getId).distinct().count() == 2, "colliding bookmark ID at another location receives a fresh ID");
    check(merged.getState().getBookmarks().stream().filter(b -> b.getType() == BookmarkType.LETTER_Z).count() == 1
        && merged.getState().getBookmarks().stream().anyMatch(b -> b.getType() == BookmarkType.DEFAULT), "occupied imported mnemonic becomes ordinary bookmark without replacing existing mnemonic");
    var replaced = BookmarkExchange.plan(current, incoming, true, false);
    check(BookmarkExchange.toJson(replaced.getState(), null).equals(BookmarkExchange.toJson(incoming, null)), "replace preview exactly matches imported snapshot");
    var collidingGroup = BookmarkExchange.copy(current); collidingGroup.getGroups().getFirst().setName("新根分组");
    var extraGroups = BookmarkExchange.plan(current, collidingGroup, false, false).getState();
    check(extraGroups.getGroups().size() == 4 && extraGroups.getGroups().stream().map(GroupState::getId).distinct().count() == 4, "colliding group IDs at different paths get new IDs and keep the original subtree");
    var empty = new ManagerState(); empty.setFormatVersion(2);
    check(BookmarkExchange.plan(current, empty, false, false).getState().getBookmarks().size() == 1
        && BookmarkExchange.plan(current, empty, true, false).getState().getBookmarks().isEmpty(), "empty document merges without deletion and replaces only when explicitly selected");
    var unknown = BookmarkExchange.copy(current); unknown.getBookmarks().getFirst().setProvider("example.UnknownProvider");
    unknown.getBookmarks().getFirst().getAttributes().put("opaque", "中文 & <tag>");
    check(BookmarkExchange.fromJson(BookmarkExchange.toJson(unknown, base), base).getBookmarks().getFirst().getAttributes().get("opaque").equals("中文 & <tag>"), "unknown provider and opaque attributes survive JSON interchange");
    var vcs = BookmarkExchange.copy(current); vcs.getBookmarks().getFirst().setProvider("com.intellij.platform.vcs.log.impl.bookmarks.VcsLogBookmarkProvider");
    vcs.getBookmarks().getFirst().getAttributes().clear(); vcs.getBookmarks().getFirst().getAttributes().put("root", base.toString());
    vcs.getBookmarks().getFirst().getAttributes().put("hash", "0123456789abcdef");
    var vcsJson = BookmarkExchange.toJson(vcs, base);
    check(vcsJson.contains("$PROJECT_DIR$") && BookmarkExchange.fromJson(vcsJson, base.resolve("other-checkout")).getBookmarks().getFirst().getAttributes().get("root").equals(base.resolve("other-checkout").toString()), "VCS repository roots are portable between checkouts");
    var external = BookmarkExchange.copy(current); external.getBookmarks().getFirst().getAttributes().put("url", "file:///external/sample.txt");
    check(BookmarkExchange.fromJson(BookmarkExchange.toJson(external, base), base).getBookmarks().getFirst().getAttributes().get("url").equals("file:///external/sample.txt"), "external file paths retained without guessing a replacement");
    var markdown = BookmarkExchange.toMarkdown(current, base, "项目[中文]");
    check(markdown.contains(current.getBookmarks().getFirst().getDescription()) && markdown.contains("AI 根组 / AI 子组"), "Markdown sharing includes full group paths and original Markdown text");
    check(markdown.contains("sample.txt#L2") && !markdown.contains(base.toString()), "Markdown sharing uses project-relative links and one-based line anchors");
    check(markdown.contains("项目\\[中文\\]"), "Markdown generated titles escape formatting characters");
    var withSource = text.replaceFirst("\\{", "{\"_source\":{\"kind\":\"project\"},");
    check(BookmarkExchange.fromJson(withSource, base).getGroups().size() == 2, "IDE accepts version-2 AI interchange metadata without applying source-path locks");
    reject(() -> BookmarkExchange.fromJson(text.replace("\"formatVersion\": 2", "\"formatVersion\": 99"), base), "unknown JSON version rejected");
    reject(() -> BookmarkExchange.fromJson(text.replaceFirst("\\{", "{\"unknown\":true,"), base), "unknown JSON fields rejected");
    reject(() -> BookmarkExchange.fromJson(text.replaceFirst("\\{", "{\"formatVersion\":2,"), base), "duplicate JSON keys rejected");
    reject(() -> BookmarkExchange.fromJson("{\"format\\u0056ersion\":2," + text.substring(1), base), "escaped duplicate JSON keys rejected");
    reject(() -> BookmarkExchange.fromJson(text.replace("sample.txt", "../sample.txt"), base), "project-relative path traversal rejected");
    reject(() -> BookmarkExchange.fromJson(text.replace("sample.txt", "..\\\\sample.txt"), base), "backslash path traversal rejected");
    reject(() -> BookmarkExchange.fromJson(text.replace("\"isDefault\": false", "\"isDefault\": \"false\""), base), "JSON boolean fields reject strings");
    reject(() -> BookmarkExchange.fromJson(text.replace("\"descriptionMarkdown\":", "\"descriptionMarkdown\": 42, \"other\":"), base), "invalid description schema rejected");
    reject(() -> BookmarkExchange.fromJson(text.replace("\"ai-stable-bookmark\"", "\"\""), base), "empty canonical IDs rejected");
    var sameFile = BookmarkExchange.copy(current); sameFile.getBookmarks().getFirst().getAttributes().remove("line");
    var otherFile = new BookmarkState(); otherFile.setId("same-file"); otherFile.setProvider(sameFile.getBookmarks().getFirst().getProvider());
    otherFile.getAttributes().put("url", file.getUrl()); otherFile.getAttributes().put("line", "-1");
    sameFile.getBookmarks().add(otherFile); sameFile.getGroups().getFirst().getBookmarkIds().add("same-file");
    reject(() -> BookmarkExchange.plan(current, sameFile, false, false), "equivalent file locations with absent line and minus-one line cannot create duplicates");
    reject(() -> BookmarkExchange.fromJson("[".repeat(130) + "]".repeat(130), base), "excessive JSON nesting rejected before parser recursion");
    reject(() -> BookmarkExchange.fromJson(" ".repeat(BookmarkExchange.MAX_BYTES + 1), base), "oversized JSON rejected");
    var missingReference = BookmarkExchange.copy(current); missingReference.getGroups().getFirst().getBookmarkIds().add("missing");
    reject(() -> BookmarkExchange.plan(current, missingReference, false, false), "invalid imported references rejected before preview or mutation");
    for (String action : List.of("Bookmarks.ExportJson", "Bookmarks.ExportMarkdown", "Bookmarks.ImportJson")) {
      check(com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction(action) != null, "installed plugin registers " + action);
    }
  }

  private static void hierarchyAndMarkdown() {
    BookmarkGroup root = manager.addGroup("业务A", false);
    BookmarkGroup child = manager.addSubgroup(root, "同步链路", false);
    BookmarkGroup leaf = manager.addSubgroup(child, "CDC", true);
    BookmarkGroup other = manager.addGroup("业务B", false);
    BookmarkGroup sameName = manager.addSubgroup(other, "CDC", false);
    check(child != null && leaf != null && sameName != null, "three-level hierarchy and same names in separate branches");
    check(manager.addSubgroup(child, "CDC", false) == null, "duplicate sibling rejected");
    check(manager.getParentGroup(leaf) == child && manager.getChildGroups(root).contains(child), "parent and child relationships");
    check(manager.getGroups().indexOf(root) < manager.getGroups().indexOf(child) && manager.getGroups().indexOf(child) < manager.getGroups().indexOf(leaf), "preorder matches displayed hierarchy");
    childId = manager.getGroupId(child);
    leafId = manager.getGroupId(leaf);
    root.setName("重命名业务A");
    check(manager.getGroupId(child).equals(childId) && manager.getParentGroup(child) == root, "rename preserves child identity");
    check(!manager.setParentGroup(root, leaf) && !manager.setParentGroup(child, child), "cycles and self-parenting rejected");
    check(!manager.setParentGroup(leaf, other), "move with sibling name collision rejected");
    check(manager.setParentGroup(child, other) && manager.getParentGroup(child) == other, "subtree reparented");
    check(manager.setParentGroup(child, root) && manager.getParentGroup(leaf) == child, "reparent preserves descendants");
    Bookmark bookmark = manager.createBookmark(file);
    check(leaf.add(bookmark, BookmarkType.LETTER_B, MARKDOWN), "Markdown description added in nested group");
    leaf.setDescription(bookmark, MARKDOWN);
    check(MARKDOWN.equals(leaf.getDescription(bookmark)), "multiline Unicode Markdown stored without loss");
    Bookmark line = LineBookmarkProvider.Util.find(project).createBookmark(file, 1);
    check(leaf.add(line, BookmarkType.DIGIT_1, "Line **note**"), "line bookmark added to deep group");
    String html = MarkdownDescriptions.renderBody(MARKDOWN);
    check(html.contains("<h1>") && html.contains("<strong>") && html.contains("<pre>") && html.contains("<table>") && html.contains("https://www.jetbrains.com/"), "Markdown headings lists code tables and links rendered");
    String unsafe = MarkdownDescriptions.renderBody("<script>alert(1)</script>\n\n![image](https://example.com/image.png)\n\n[x](javascript:alert(1))");
    check(!unsafe.contains("<script") && !unsafe.contains("<img") && !unsafe.contains("javascript:"), "preview sanitizes active HTML and automatic image loads");
    check("CDC 链路".equals(MarkdownDescriptions.summary(MARKDOWN)), "tree displays single-line Markdown summary");
    var noteEditor = MarkdownDescriptionEditor.getInstance(project);
    VirtualFile noteFile = noteEditor.getDescriptionFile(leaf, bookmark);
    check("Markdown".equals(noteFile.getFileType().getName()) && noteFile instanceof com.intellij.testFramework.LightVirtualFile,
          "description uses native Markdown file type without a project file");
    check(noteEditor.getDescriptionFile(leaf, bookmark) == noteFile, "repeated editing reuses the same description buffer");
    check(java.util.Arrays.stream(FileEditorProviderManager.getInstance().getProviders(project, noteFile))
            .anyMatch(provider -> provider.getClass().getName().contains("MarkdownSplitEditorProvider")),
          "bundled Markdown split editor provider accepts description buffer");
    var noteDocument = FileDocumentManager.getInstance().getDocument(noteFile);
    check(noteDocument != null && MARKDOWN.equals(noteDocument.getText()), "native Markdown document contains the original description");
    WriteCommandAction.runWriteCommandAction(project, () -> noteDocument.setText(MARKDOWN + "\n补充说明"));
    check((MARKDOWN + "\n补充说明").equals(leaf.getDescription(bookmark)), "native editor changes save to the correct nested bookmark");
    WriteCommandAction.runWriteCommandAction(project, () -> noteDocument.setText(MARKDOWN));
    check(MARKDOWN.equals(leaf.getDescription(bookmark)), "native document restores Markdown without losing formatting");
    var xml = XmlSerializer.serialize(manager.getState());
    ManagerState roundTrip = XmlSerializer.deserialize(xml, ManagerState.class);
    manager.loadState(roundTrip);
  }

  private static void verifyHierarchyAndTree() {
    BookmarkGroup child = manager.getGroupById(childId);
    BookmarkGroup leaf = manager.getGroupById(leafId);
    check(child != null && leaf != null && manager.getParentGroup(leaf) == child, "hierarchy IDs survive XML serialization");
    check(MARKDOWN.equals(leaf.getDescription(manager.getBookmark(BookmarkType.LETTER_B))), "Markdown survives XML serialization");
    check(leaf.isDefault(), "nested default group retained");
    BookmarksView view = new BookmarksView(project, false);
    try {
      RootNode root = new RootNode(view);
      GroupNode parentNode = (GroupNode)root.getChildren().stream().filter(node -> node.getValue() == manager.getParentGroup(child)).findFirst().orElseThrow();
      GroupNode childNode = (GroupNode)parentNode.getChildren().stream().filter(node -> node.getValue() == child).findFirst().orElseThrow();
      GroupNode leafNode = (GroupNode)childNode.getChildren().stream().filter(node -> node.getValue() == leaf).findFirst().orElseThrow();
      check(leafNode.getParent() == childNode && childNode.getParent() == parentNode, "actual tool-window tree contains nested groups");
      TreePath path = new TreePath(new Object[]{new DefaultMutableTreeNode(root), new DefaultMutableTreeNode(parentNode), new DefaultMutableTreeNode(childNode), new DefaultMutableTreeNode(leafNode)});
      check(new GroupBookmarkVisitor(leaf, null).visit(path) == TreeVisitor.Action.INTERRUPT, "nested group selection visitor works beyond fixed depth");
      var visitor = new GroupBookmarkVisitor(leaf, manager.getBookmark(BookmarkType.LETTER_B));
      check(visitor.visit(path.getParentPath()) == TreeVisitor.Action.CONTINUE, "selection traverses ancestor groups");
      var bookmarkNode = leafNode.getChildren().stream().filter(node -> node.getValue() == manager.getBookmark(BookmarkType.LETTER_B)).findFirst().orElseThrow();
      check(visitor.visit(path.pathByAddingChild(new DefaultMutableTreeNode(bookmarkNode))) == TreeVisitor.Action.INTERRUPT, "bookmark selection supports arbitrary nesting");
    } finally { Disposer.dispose(view); }
    BookmarksView popup = new BookmarksView(project, true);
    try {
      RootNode popupRoot = new RootNode(popup);
      check(!popupRoot.getChildren().isEmpty(), "line-bookmark popup retains ancestor groups of nested line bookmarks");
    } finally { Disposer.dispose(popup); }
    BookmarkGroup temporary = manager.addGroup("Delete subtree", false);
    BookmarkGroup sub = manager.addSubgroup(temporary, "Child", false);
    String deletedId = manager.getGroupId(sub);
    Bookmark alias = manager.createBookmark(file);
    sub.add(alias, BookmarkType.LETTER_B, "Alias");
    temporary.remove();
    check(manager.getGroup("Delete subtree") == null && manager.getGroupById(deletedId) == null, "deleting a group removes its subtree");
    check(manager.getGroups(alias).contains(leaf), "subtree deletion preserves bookmarks in other branches");
  }

  private static void legacyAndDamagedState() {
    ManagerState legacy = new ManagerState();
    GroupState group = new GroupState();
    group.setName("Legacy");
    BookmarkState bookmark = new BookmarkState();
    bookmark.setProvider("com.intellij.ide.bookmark.providers.LineBookmarkProvider");
    bookmark.setDescription("旧描述\n保留换行");
    bookmark.setType(BookmarkType.LETTER_C);
    bookmark.getAttributes().put("url", file.getUrl());
    group.getBookmarks().add(bookmark);
    legacy.getGroups().add(group);
    manager.loadState(legacy);
  }

  private static void multiGroupBookmarks() {
    BookmarkGroup a = manager.addGroup("多组 A", false);
    BookmarkGroup b = manager.addSubgroup(a, "多组 B", false);
    BookmarkGroup c = manager.addGroup("多组 C", false);
    sharedA = manager.getGroupId(a); sharedB = manager.getGroupId(b); sharedC = manager.getGroupId(c);
    Bookmark line = LineBookmarkProvider.Util.find(project).createBookmark(file, 2);
    check(a.add(line, BookmarkType.DIGIT_2, MARKDOWN), "shared line bookmark created");
    check(manager.setBookmarkGroups(line, List.of(a, b, c)) && manager.getGroups(line).size() == 3, "same line bookmark assigned to three groups");
    check(manager.getBookmarks().stream().filter(line::equals).count() == 1 && manager.getBookmark(BookmarkType.DIGIT_2).equals(line), "memberships retain one bookmark identity and mnemonic");
    check(!b.add(line, BookmarkType.DIGIT_2, null), "duplicate membership in the same group rejected");
    BookmarkGroupsTree tree = new BookmarkGroupsTree(manager, line);
    check(tree.selectedGroups().size() == 3 && tree.getRowCount() >= 3, "actual checkbox tree restores checked groups");
    tree.setNodeState(tree.getGroupNodes().get(a), false);
    check(tree.selectedGroups().contains(b) && !tree.selectedGroups().contains(a), "parent and child checkboxes are independent");
    var renderer = (com.intellij.ui.CheckboxTreeBase.CheckboxTreeCellRendererBase)tree.getCellRenderer();
    renderer.getTreeCellRendererComponent(tree, tree.getGroupNodes().get(a), false, true, false, 0, false);
    check(!renderer.getCheckbox().isSelected(), "unchecked parent is rendered unchecked even when its child is checked");
    check(!manager.setBookmarkGroups(line, List.of()) && manager.getGroups(line).size() == 3, "empty membership selection does not delete the bookmark");
    var editor = MarkdownDescriptionEditor.getInstance(project);
    VirtualFile note = editor.getDescriptionFile(a, line);
    check(editor.getDescriptionFile(b, line) == note && editor.getDescriptionFile(c, line) == note, "all groups reuse one native Markdown editor buffer");
    var document = FileDocumentManager.getInstance().getDocument(note);
    sharedEditorDocument = document;
    WriteCommandAction.runWriteCommandAction(project, () -> document.setText(MARKDOWN + "\n共享修改"));
    check(manager.getGroups(line).stream().allMatch(g -> (MARKDOWN + "\n共享修改").equals(g.getDescription(line))), "native Markdown edits immediately update every group");
    b.setDescription(line, MARKDOWN);
    check(MARKDOWN.equals(a.getDescription(line)) && MARKDOWN.equals(c.getDescription(line)), "editing a description in any group updates the shared text");
    var occurrence = new BookmarkOccurrence(a, line);
    check(BookmarkDeletion.needsChoice(manager, List.of(occurrence)), "deleting a multiply referenced bookmark requires a scope choice");
    manager.deleteOccurrences(List.of(occurrence), false);
    check(new java.util.HashSet<>(manager.getGroups(line)).equals(java.util.Set.of(b, c)) && MARKDOWN.equals(b.getDescription(line)), "remove-current-group preserves other references and Markdown");
    check(editor.getDescriptionFile(c, line) == note, "removing one reference retains the shared editor buffer");
    check(manager.setBookmarkGroups(line, List.of(a, b, c)), "membership restored without losing shared description");
    Bookmark moved = LineBookmarkProvider.Util.find(project).createBookmark(file, 0);
    var replacements = new java.util.HashMap<Bookmark, Bookmark>(); replacements.put(line, moved);
    manager.update(replacements);
    check(manager.getGroups(moved).size() == 3 && manager.getBookmark(BookmarkType.DIGIT_2).equals(moved), "line relocation updates all references and keeps the mnemonic");
    check(manager.getGroups(moved).stream().allMatch(g -> MARKDOWN.equals(g.getDescription(moved))), "line relocation preserves the shared Markdown");
    manager.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(manager.getState()), ManagerState.class));
  }

  private static void verifySharedBookmarkRestoreAndDeletion() {
    Bookmark line = manager.getBookmark(BookmarkType.DIGIT_2);
    BookmarkGroup a = manager.getGroupById(sharedA), b = manager.getGroupById(sharedB), c = manager.getGroupById(sharedC);
    check(new java.util.HashSet<>(manager.getGroups(line)).equals(java.util.Set.of(a,b,c)) && manager.getGroups(line).stream().allMatch(g -> MARKDOWN.equals(g.getDescription(line))), "restored memberships and descriptions agree in all groups");
    manager.deleteOccurrences(List.of(new BookmarkOccurrence(a,line)), false);
    manager.deleteOccurrences(List.of(new BookmarkOccurrence(c,line)), false);
    check(!BookmarkDeletion.needsChoice(manager, List.of(new BookmarkOccurrence(b,line))), "single remaining reference deletes without prompting");
    manager.deleteOccurrences(List.of(new BookmarkOccurrence(b,line)), false);
    check(manager.getGroups(line).isEmpty() && manager.getBookmark(BookmarkType.DIGIT_2) == null, "deleting the final reference removes bookmark and mnemonic");
    Bookmark another = LineBookmarkProvider.Util.find(project).createBookmark(file, 2);
    a.add(another, BookmarkType.DIGIT_3, MARKDOWN);
    manager.setBookmarkGroups(another, List.of(a,b,c));
    manager.deleteOccurrences(List.of(new BookmarkOccurrence(b,another)), true);
    check(manager.getGroups(another).isEmpty() && manager.getBookmark(BookmarkType.DIGIT_3) == null
            && List.of(a,b,c).stream().noneMatch(g -> g.getBookmarks().contains(another)), "delete-everywhere removes every group reference");
    WriteCommandAction.runWriteCommandAction(project, () -> sharedEditorDocument.setText("已删除书签的旧编辑内容"));
    check(manager.getGroups(another).isEmpty() && manager.getBookmark(BookmarkType.DIGIT_3) == null, "stale Markdown editor cannot recreate a deleted bookmark");
  }

  private static void migrateDifferentDescriptions() {
    ManagerState state = new ManagerState();
    for (String name : List.of("A", "B")) {
      GroupState group = new GroupState(); group.setName(name);
      BookmarkState bookmark = new BookmarkState();
      bookmark.setProvider("com.intellij.ide.bookmark.providers.LineBookmarkProvider");
      bookmark.getAttributes().put("url", file.getUrl());
      bookmark.getAttributes().put("line", "0");
      bookmark.setDescription("旧说明 " + name);
      group.getBookmarks().add(bookmark); state.getGroups().add(group);
    }
    manager.loadState(state);
  }

  private static void normalizedStorage() {
    manager.remove();
    var root = manager.addGroup("Canonical", true);
    var child = manager.addSubgroup(root, "Child", false);
    var other = manager.addGroup("Other", false);
    var bookmark = LineBookmarkProvider.Util.find(project).createBookmark(file, 1);
    root.add(bookmark, BookmarkType.DIGIT_6, MARKDOWN);
    manager.setBookmarkGroups(bookmark, List.of(root, child, other));
    var id = manager.getBookmarkId(bookmark);
    var state = manager.getState();
    check(state.getFormatVersion() == 2 && state.getBookmarks().size() == 1, "normalized store contains one canonical bookmark");
    check(state.getGroups().stream().allMatch(g -> g.getBookmarks().isEmpty() && g.getBookmarkIds().equals(List.of(id))), "groups persist ordered references without description copies");
    check(MARKDOWN.equals(state.getBookmarks().getFirst().getDescription()), "canonical Markdown stored exactly once");
    var moved = LineBookmarkProvider.Util.find(project).createBookmark(file, 0);
    var updates = new java.util.HashMap<Bookmark, Bookmark>(); updates.put(bookmark, moved); manager.update(updates);
    check(id.equals(manager.getBookmarkId(moved)), "stable bookmark ID survives line relocation");
    var invalid = XmlSerializer.deserialize(XmlSerializer.serialize(manager.getState()), ManagerState.class);
    invalid.getGroups().getFirst().getBookmarkIds().add("missing");
    expectRejected(invalid, "dangling references rejected before live state changes");
    invalid = XmlSerializer.deserialize(XmlSerializer.serialize(manager.getState()), ManagerState.class);
    invalid.getGroups().getFirst().setParentId(invalid.getGroups().get(1).getId());
    expectRejected(invalid, "new-format parent cycles rejected");
    invalid = XmlSerializer.deserialize(XmlSerializer.serialize(manager.getState()), ManagerState.class);
    invalid.getGroups().getFirst().getBookmarkIds().clear();
    invalid.getGroups().get(1).getBookmarkIds().clear();
    invalid.getGroups().get(2).getBookmarkIds().clear();
    expectRejected(invalid, "orphan canonical bookmarks rejected");
    invalid = XmlSerializer.deserialize(XmlSerializer.serialize(manager.getState()), ManagerState.class);
    invalid.setFormatVersion(99);
    expectRejected(invalid, "unknown format version rejected");
    check(manager.getGroups(moved).size() == 3 && manager.getBookmark(BookmarkType.DIGIT_6) == moved, "invalid imports retain all existing references");
    var providerState = manager.getState();
    var unknown = new BookmarkState(); unknown.setId("unavailable-provider"); unknown.setProvider("example.unavailable.BookmarkProvider");
    unknown.getAttributes().put("symbol", "retained"); unknown.setDescription("Unavailable provider description");
    providerState.getBookmarks().add(unknown);
    providerState.getGroups().getFirst().getBookmarkIds().add(unknown.getId());
    manager.loadState(providerState);
  }

  private static void expectRejected(ManagerState state, String label) {
    boolean rejected = false;
    try { manager.loadState(state); } catch (IllegalArgumentException expected) { rejected = true; }
    check(rejected, label);
  }

  private static void setupBranchContexts() {
    var providers = WorkingContextProvider.EP_NAME.getExtensionList().stream().filter(p -> p.getId().equals("bookmarks")).toList();
    check(providers.size() == 1 && providers.getFirst().getClass().getName().equals("com.intellij.ide.bookmark.context.BookmarkContextProvider"), "exactly one upgraded bookmarks context adapter registered");
    previousBranchSetting = AdvancedSettings.getBoolean("tasks.enable.bookmark.context.on.branch.switch");
    previousReloadSetting = VcsConfiguration.getInstance(project).RELOAD_CONTEXT;
    AdvancedSettings.setBoolean("tasks.enable.bookmark.context.on.branch.switch", true);
    VcsConfiguration.getInstance(project).RELOAD_CONTEXT = true;
    manager.remove();
    var root = manager.addGroup("Branch A", true);
    var child = manager.addSubgroup(root, "Same name", false);
    var other = manager.addGroup("Another root", false);
    var sameName = manager.addSubgroup(other, "Same name", false);
    branchRoot = manager.getGroupId(root); branchChild = manager.getGroupId(child);
    var bookmark = LineBookmarkProvider.Util.find(project).createBookmark(file, 1);
    root.add(bookmark, BookmarkType.DIGIT_7, MARKDOWN);
    manager.setBookmarkGroups(bookmark, List.of(root, child, sameName));
    branchBookmark = manager.getBookmarkId(bookmark);
    branchTracker = new BranchContextTracker(project);
    branchTracker.branchWillChange("validation-a");
    var contextManager = WorkingContextManager.getInstance(project);
    check(contextManager.hasContext("__branch_context_validation-a"), "original working-context archive saves branch A");
    branchTracker.branchHasChanged("validation-new");
    check(manager.getBookmark(BookmarkType.DIGIT_7) != null, "first visit to an unsaved branch retains upstream behavior");
    contextManager.clearContext();
    var branchB = manager.addGroup("Branch B", true);
    branchB.add(LineBookmarkProvider.Util.find(project).createBookmark(file, 0), BookmarkType.DIGIT_8, "分支 B 描述");
    branchTracker.branchWillChange("validation-b");
    branchTracker.branchHasChanged("validation-a");
  }

  private static void verifyAiArchiveAfterRestart(Path root, Path filePath) throws Exception {
    edt(() -> {
      project = ProjectManagerEx.getInstanceEx().loadProject(filePath.getParent());
      manager = (BookmarksManagerImpl)BookmarksManager.getInstance(project);
    });
    await(() -> !manager.isRestoringBookmarks() && manager.getBookmark(BookmarkType.LETTER_Z) != null, "fresh IDE restores normalized project state from disk");
    edt(() -> {
      check(manager.getGroups(manager.getBookmark(BookmarkType.LETTER_Z)).size() == 2, "fresh IDE preserves two canonical references");
      check(manager.getParentGroup(manager.getGroupById("ai-child")) == manager.getGroupById("ai-root"), "fresh IDE preserves project group hierarchy");
      check(WorkingContextManager.getInstance(project).loadContext("__branch_context_validation-a"), "fresh IDE reads AI-edited original branch archive");
    });
    await(() -> !manager.isRestoringBookmarks() && manager.getBookmark(BookmarkType.DIGIT_7) != null, "AI-edited branch A restored after process restart");
    edt(() -> {
      var bookmark = manager.getBookmark(BookmarkType.DIGIT_7);
      var groups = manager.getGroups(bookmark);
      check(groups.size() == 3 && groups.stream().allMatch(g -> (MARKDOWN+"\nAI branch archive update: 中文 & <tag>").equals(g.getDescription(bookmark))), "AI archive update preserves original Markdown and synchronizes all references");
      check(manager.getChildGroups(manager.getGroup("Branch A")).stream().anyMatch(g -> g.getName().equals("Same name")), "AI archive update preserves branch tree");
      check(WorkingContextManager.getInstance(project).loadContext("__branch_context_validation-b"), "unchanged branch B archive still readable");
    });
    await(() -> !manager.isRestoringBookmarks() && manager.getBookmark(BookmarkType.DIGIT_8) != null, "unchanged branch B restored after AI update");
    edt(() -> {
      var bookmark = manager.getBookmark(BookmarkType.DIGIT_8);
      check(manager.getGroups(bookmark).size() == 1 && "分支 B 描述".equals(manager.getGroups(bookmark).getFirst().getDescription(bookmark)), "AI branch A update leaves branch B data unchanged");
    });
    Files.writeString(root.resolve("second-process-result.txt"), "PASS: project reopen and AI-edited branch archive\n");
    System.out.println("BOOKMARKS_RESTART_SMOKE_PASS");
    System.exit(0);
  }

  private static GroupState groupState(String id, String parentId, String name) {
    GroupState result = new GroupState(); result.setId(id); result.setParentId(parentId); result.setName(name); return result;
  }

  private static void await(BooleanSupplier condition, String label) throws InterruptedException {
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
    check(condition.getAsBoolean(), label);
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
    System.out.println("PASS " + label);
  }
}
