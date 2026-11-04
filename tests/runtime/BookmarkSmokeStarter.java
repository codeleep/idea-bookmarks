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
      await(() -> manager.getBookmark(BookmarkType.LETTER_A) != null, "original bookmark state restored");
      edt(BookmarkSmokeStarter::hierarchyAndMarkdown);
      await(() -> manager.getBookmark(BookmarkType.LETTER_B) != null, "nested bookmark restored from XML");
      edt(BookmarkSmokeStarter::verifyHierarchyAndTree);
      edt(BookmarkSmokeStarter::multiGroupBookmarks);
      await(() -> manager.getBookmark(BookmarkType.DIGIT_2) != null && manager.getGroups(manager.getBookmark(BookmarkType.DIGIT_2)).size() == 3,
            "multi-group line bookmark restored from XML");
      edt(BookmarkSmokeStarter::verifySharedBookmarkRestoreAndDeletion);
      edt(BookmarkSmokeStarter::migrateDifferentDescriptions);
      await(() -> manager.getGroups().size() == 2 && manager.getGroups().stream().allMatch(g -> !g.getBookmarks().isEmpty()), "legacy duplicate bookmark memberships restored");
      edt(() -> {
        Bookmark bookmark = manager.getGroups().get(0).getBookmarks().get(0);
        migratedDescription = manager.getGroups().get(0).getDescription(bookmark);
        check(migratedDescription.contains("旧说明 A") && migratedDescription.contains("旧说明 B"), "distinct legacy descriptions merged without discarding text");
        check(manager.getGroups().stream().allMatch(g -> migratedDescription.equals(g.getDescription(bookmark))), "migrated description shared by every group");
        manager.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(manager.getState()), ManagerState.class));
      });
      await(() -> manager.getGroups().size() == 2 && manager.getGroups().stream().allMatch(g -> !g.getBookmarks().isEmpty()), "merged legacy description restored again");
      edt(() -> {
        Bookmark bookmark = manager.getGroups().get(0).getBookmarks().get(0);
        check(migratedDescription.equals(manager.getGroups().get(0).getDescription(bookmark)), "legacy description migration is idempotent");
      });
      edt(BookmarkSmokeStarter::legacyAndDamagedState);
      await(() -> manager.getBookmark(BookmarkType.LETTER_C) != null, "old flat bookmark migrated");
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
