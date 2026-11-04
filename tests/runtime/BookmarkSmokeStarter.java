package bookmarks.validation;

import com.intellij.ide.bookmark.*;
import com.intellij.ide.bookmark.ui.BookmarksView;
import com.intellij.ide.bookmark.ui.MarkdownDescriptionEditor;
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
