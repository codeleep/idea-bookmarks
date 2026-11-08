package bookmarks.validation;

import com.intellij.ide.bookmark.*;
import com.intellij.ide.bookmark.context.BookmarkContextProvider;
import com.intellij.ide.bookmark.ui.BookmarkNavigation;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.options.advanced.AdvancedSettings;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.util.xmlb.XmlSerializer;
import org.jdom.Element;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Uses the installed plugin, live documents, native context provider, backup writer and serializer. */
public final class BookmarkWorkflowsSmoke {
  private static final String PROVIDER = BookmarkHealth.LINE_PROVIDER;
  private static Project project;
  private static BookmarksManagerImpl manager;
  private static BookmarkWorkspaceStore store;
  private static void edt(Runnable task) { ApplicationManager.getApplication().invokeAndWait(task); }
  private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); System.out.println("PASS " + label); }
  private static void reject(Runnable task, String label) {
    boolean rejected = false;
    try { task.run(); } catch (IllegalArgumentException | IllegalStateException error) { rejected = true; }
    check(rejected, label);
  }
  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
    if (!condition.getAsBoolean()) throw new AssertionError("workflow restore timed out");
  }
  private static BookmarkState record(String id, String url, String line, String text, String description) {
    var result = new BookmarkState(); result.setId(id); result.setProvider(PROVIDER); result.setDescription(description);
    result.getAttributes().put("url", url);
    if (line != null) result.getAttributes().put("line", line);
    if (text != null) result.getAttributes().put("lineText", text);
    return result;
  }
  private static String json(ManagerState state) { return BookmarkExchange.toJson(state, null); }

  public static void verify(Project target, BookmarksManagerImpl live) throws Exception {
    project = target; manager = live; store = BookmarkWorkspaceStore.getInstance(project);
    var original = ReadAction.compute(() -> manager.captureForExchange());
    var workspace = store.getState();
    boolean branchSetting = AdvancedSettings.getBoolean("tasks.enable.bookmark.context.on.branch.switch");
    Path directory = Path.of(project.getBasePath(), "workflow"); Files.createDirectories(directory);
    Path source = directory.resolve("中文.txt");
    String content = "    入口();  \n\t处理中文();\t\n重复\n重复\n最后\n";
    Files.writeString(source, content);
    Path moved = directory.resolve("moved.txt"); Files.writeString(moved, "新的文件\n");
    Path changing = directory.resolve("changing.txt"); Files.writeString(changing, "  旧候选  \n");
    var fixture = new ManagerState(); fixture.setFormatVersion(2);
    String url = "file://" + source;
    fixture.getBookmarks().add(record("good", url, "0", "    入口();  ", "# 入口\n\n中文说明"));
    fixture.getBookmarks().add(record("indent", url, "1", "处理中文();", "共享描述\n\n**不能丢失**"));
    fixture.getBookmarks().add(record("range", url, "99", "不存在的原文", null));
    fixture.getBookmarks().add(record("missing", "file://" + directory.resolve("missing.txt"), null, null, "丢失文件"));
    fixture.getBookmarks().add(record("file", url, null, null, " "));
    var other = record("other", "https://example.invalid/item", null, null, "其他提供器"); other.setProvider("example.UnavailableProvider"); fixture.getBookmarks().add(other);
    var root = new GroupState(); root.setId("workflow-root"); root.setName("阅读主线"); root.setDefault(true);
    root.getBookmarkIds().addAll(List.of("good", "indent", "range", "missing", "file", "other")); fixture.getGroups().add(root);
    var child = new GroupState(); child.setId("workflow-child"); child.setParentId(root.getId()); child.setName("子分组");
    child.getBookmarkIds().addAll(List.of("indent", "other")); fixture.getGroups().add(child);
    try {
      edt(() -> {
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source);
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(moved);
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(changing);
        var before = json(fixture);
        var health = BookmarkHealth.inspect(project, fixture);
        check(health.size() == 6 && health.stream().filter(it -> it.getStatus().getInvalid()).count() == 3, "health separates valid invalid and unchecked canonical records");
        check(health.stream().anyMatch(it -> it.getId().equals("good") && it.getStatus() == BookmarkHealth.Status.HEALTHY), "health accepts indentation and trailing spaces exactly");
        check(health.stream().anyMatch(it -> it.getId().equals("indent") && it.getStatus() == BookmarkHealth.Status.ANCHOR_MISMATCH && it.getActual().equals("\t处理中文();\t")), "health exposes lost tabs and Chinese anchor mismatch with original text");
        check(health.stream().anyMatch(it -> it.getId().equals("range") && it.getStatus() == BookmarkHealth.Status.OUT_OF_RANGE), "health reports zero-based out of range lines");
        check(health.stream().anyMatch(it -> it.getId().equals("missing") && it.getStatus() == BookmarkHealth.Status.MISSING_FILE), "health detects missing file bookmarks");
        check(health.stream().anyMatch(it -> it.getId().equals("other") && it.getStatus() == BookmarkHealth.Status.UNSUPPORTED), "unknown providers are unchecked rather than falsely healthy or deleted");
        check(json(fixture).equals(before), "inspection does not modify bookmark records or references");
        var macro = BookmarkExchange.copy(fixture); macro.getBookmarks().getFirst().getAttributes().put("url", "file://$PROJECT_DIR$/workflow/中文.txt");
        check(BookmarkHealth.inspect(project, macro).getFirst().getStatus() == BookmarkHealth.Status.HEALTHY, "health resolves project path macros through IDE path manager");
        var noAnchor = BookmarkExchange.copy(fixture); noAnchor.getBookmarks().getFirst().getAttributes().remove("lineText");
        check(BookmarkHealth.inspect(project, noAnchor).getFirst().getStatus() == BookmarkHealth.Status.HEALTHY, "optional line anchors remain compatible");
        var candidates = BookmarkHealth.candidates(project, fixture.getBookmarks().get(1), url);
        check(candidates.size() == 1 && candidates.getFirst().getText().equals("\t处理中文();\t"), "repair whitespace suggestions preserve complete replacement source line");
        var duplicate = record("duplicate", url, "99", "重复", "");
        check(BookmarkHealth.candidates(project, duplicate, url).stream().map(BookmarkHealth.Candidate::getLine).toList().equals(List.of(2,3)), "ambiguous duplicate anchors return both candidates without silently choosing");
        var filter = new BookmarkFilter(); filter.setQuery("中文 说明");
        check(BookmarkFilters.select(fixture, project.getBasePath(), filter, null, health).stream().map(BookmarkSearchIndex.Entry::getId).toList().equals(List.of("good")), "advanced filters retain full-text multi-keyword AND behavior");
        filter.setQuery(""); filter.setGroupId("workflow-child");
        check(BookmarkFilters.select(fixture, project.getBasePath(), filter, null, health).size() == 2, "subgroup filters select shared canonical records once");
        filter.setGroupId("workflow-root");
        check(BookmarkFilters.select(fixture, project.getBasePath(), filter, null, health).size() == 6, "parent filters include descendants without duplicate shared results");
        filter.setGroupId("absent-branch-group");
        check(BookmarkFilters.select(fixture, project.getBasePath(), filter, null, health).isEmpty(), "saved missing branch groups fail closed instead of widening the search");
        filter.setGroupId(""); filter.setCurrentFile(true);
        check(BookmarkFilters.select(fixture, project.getBasePath(), filter, null, health).isEmpty(), "current-file filter with no open source file returns no results");
        check(BookmarkFilters.select(fixture, project.getBasePath(), filter, url, health).size() == 4, "current-file filter uses exact file identity");
        filter.setCurrentFile(false); filter.setDirectory("workflow");
        check(BookmarkFilters.select(fixture, project.getBasePath(), filter, null, health).size() == 5, "directory filters resolve project-relative paths");
        filter.setDirectory("work");
        check(BookmarkFilters.select(fixture, project.getBasePath(), filter, null, health).isEmpty(), "directory filters respect path segment boundaries");
        filter.setDirectory(""); filter.setHealth("INVALID");
        check(BookmarkFilters.select(fixture, project.getBasePath(), filter, null, health).size() == 3, "invalid filter includes missing files bad lines and anchor mismatches");
        filter.setHealth("UNCHECKED");
        check(BookmarkFilters.select(fixture, project.getBasePath(), filter, null, health).size() == 1, "unchecked filter preserves unsupported provider visibility");
        filter.setHealth("ANY"); filter.setDescription("WITHOUT");
        check(BookmarkFilters.select(fixture, project.getBasePath(), filter, null, health).size() == 2, "description filter recognizes null and whitespace-only descriptions");
        filter.setDescription("ANY"); filter.setKind("FILE");
        check(BookmarkFilters.select(fixture, project.getBasePath(), filter, null, health).size() == 2, "file kind filter does not require a line field");
        filter.setKind("LINE"); filter.setHealth("INVALID"); filter.setGroupId("workflow-child"); filter.setQuery("共享");
        check(BookmarkFilters.select(fixture, project.getBasePath(), filter, null, health).size() == 1, "all advanced conditions compose as an intersection");
        var view = store.saveView("中文组合筛选", filter); filter.setQuery("mutated outside");
        check(store.views().stream().filter(it -> it.getId().equals(view.getId())).findFirst().get().getFilter().getQuery().equals("共享"), "saved view owns a deep copy of filter conditions");
        reject(() -> store.saveView("中文组合筛选", filter), "duplicate view names do not overwrite existing filters");
        var route = store.route(fixture, root.getId());
        check(route.getOrder().equals(root.getBookmarkIds()), "reading route traverses groups and deduplicates shared references");
        Collections.reverse(route.getOrder()); route.getCompleted().add("indent"); route.setCurrentId("indent"); store.updateRoute(fixture, route);
        route.getCompleted().clear();
        check(store.route(fixture, root.getId()).getCompleted().equals(List.of("indent")), "reading progress updates do not alias caller mutable collections");
        var changed = BookmarkExchange.copy(fixture); changed.getGroups().getFirst().setName("重命名仍保留路线");
        check(store.route(changed, root.getId()).getOrder().getFirst().equals("other"), "group rename preserves custom route order by stable group ID");
        var invalid = store.route(fixture, root.getId()); invalid.getOrder().removeFirst();
        reject(() -> store.updateRoute(fixture, invalid), "route updates reject missing or stale step references");
        var roundTrip = XmlSerializer.deserialize(XmlSerializer.serialize(store.getState()), BookmarkWorkspaceData.class);
        check(roundTrip.getViews().getFirst().getName().equals("中文组合筛选") && roundTrip.getReading().getRoutes().getFirst().getCurrentId().equals("indent"), "workspace serializer preserves Chinese saved filters route order and progress");
        var damaged = store.getState();
        damaged.getReading().getRoutes().getFirst().getOrder().add("indent");
        damaged.getReading().getRoutes().getFirst().getCompleted().add("indent");
        store.loadState(damaged);
        var recovered = store.route(fixture, root.getId());
        check(recovered.getOrder().size() == 6 && recovered.getCompleted().equals(List.of("indent")), "reading metadata recovers duplicate steps without duplicating shared bookmarks or progress");
        var pruned = BookmarkExchange.copy(fixture); pruned.getBookmarks().removeIf(it -> it.getId().equals("indent")); pruned.getGroups().forEach(it -> it.getBookmarkIds().remove("indent"));
        var result = store.route(pruned, root.getId());
        check(!result.getOrder().contains("indent") && result.getCompleted().isEmpty() && !result.getCurrentId().equals("indent"), "deleted route bookmarks are pruned without changing surviving step order");
        check(json(fixture).equals(before), "filtering saved views and route progress leave canonical bookmark data unchanged");
        manager.loadState(fixture);
      });
      await(() -> !manager.isRestoringBookmarks());
      edt(() -> {
        var bookmark = manager.getBookmarks().stream().filter(it -> "indent".equals(manager.getBookmarkId(it))).findFirst().get();
        check(!bookmark.canNavigate(), "actual strict provider keeps mismatching anchors invalid before repair");
        var expected = manager.captureForExchange();
        var record = expected.getBookmarks().stream().filter(it -> "indent".equals(it.getId())).findFirst().get();
        var selected = BookmarkHealth.candidates(project, record, url).getFirst();
        try {
          var backup = BookmarkHealth.repair(project, manager, expected, manager.getStateModificationCount(), "indent", selected);
          check(Files.isRegularFile(backup) && Files.readString(backup).equals(BookmarkExchange.toJson(expected, Path.of(project.getBasePath()))), "repair creates an exact portable backup before replacing live state");
        } catch (Exception error) { throw new RuntimeException(error); }
      });
      await(() -> !manager.isRestoringBookmarks());
      edt(() -> {
        var fixed = manager.getBookmarks().stream().filter(it -> "indent".equals(manager.getBookmarkId(it))).findFirst().get();
        check(fixed.canNavigate() && manager.getGroups(fixed).size() == 2, "repaired installed-provider bookmark navigates and retains both shared references");
        check(manager.getGroups(fixed).stream().allMatch(it -> "共享描述\n\n**不能丢失**".equals(it.getDescription(fixed))), "repair preserves full shared Markdown descriptions");
        check(fixed.getAttributes().get("lineText").equals("\t处理中文();\t"), "repaired live bookmark contains exact tabs and trailing whitespace");
        var expected = manager.captureForExchange(); var before = json(expected); var rev = manager.getStateModificationCount();
        var occupied = BookmarkHealth.candidateAt(project, url, 0);
        reject(() -> BookmarkHealth.repair(project, manager, expected, rev, "indent", occupied), "repair refuses a location occupied by another canonical bookmark");
        var aliased = BookmarkHealth.candidateAt(project, url.replace("/workflow/", "/workflow/../workflow/"), 0);
        reject(() -> BookmarkHealth.repair(project, manager, expected, rev, "indent", aliased), "repair canonicalizes path aliases before checking occupied bookmark locations");
        var occupiedFile = BookmarkHealth.candidateAt(project, url, -1);
        reject(() -> BookmarkHealth.repair(project, manager, expected, rev, "missing", occupiedFile), "file repair detects duplicate locations even when line attribute was omitted");
        var staleCandidate = BookmarkHealth.candidateAt(project, "file://"+changing, 0);
        var targetFile = LocalFileSystem.getInstance().findFileByNioFile(changing);
        var document = FileDocumentManager.getInstance().getDocument(targetFile);
        WriteCommandAction.runWriteCommandAction(project, () -> document.setText("  新的候选  \n"));
        reject(() -> BookmarkHealth.repair(project, manager, expected, rev, "indent", staleCandidate), "repair refuses source changed after candidate preview");
        check(json(manager.captureForExchange()).equals(before), "rejected repairs leave live bookmarks and references untouched");
        manager.getGroupById("workflow-root").setName("预览后变化");
        var unused = BookmarkHealth.candidateAt(project, url, 4);
        reject(() -> BookmarkHealth.repair(project, manager, expected, rev, "range", unused), "repair refuses stale bookmark or branch snapshots");
        check(ActionManager.getInstance().getAction("Bookmarks.Inspect") != null && ActionManager.getInstance().getAction("Bookmarks.ReadingRoute") != null, "health and reading route actions register in the installed plugin");
        var context = new BookmarkContextProvider();
        AdvancedSettings.setBoolean("tasks.enable.bookmark.context.on.branch.switch", true);
        var route = store.route(manager.captureForExchange(), "workflow-root"); route.getCompleted().add("good"); route.setCurrentId("good"); store.updateRoute(manager.captureForExchange(), route);
        var saved = new Element("context"); context.saveContext(project, saved);
        store.restoreReading(new BookmarkReadingState());
        context.loadContext(project, saved);
        check(store.readingState().getRoutes().getFirst().getCompleted().contains("good"), "official bookmark context round trip restores route progress");
        check(store.views().stream().anyMatch(it -> it.getName().equals("中文组合筛选")), "branch context restore retains project-wide saved views");
      });
      await(() -> !manager.isRestoringBookmarks());
      edt(() -> {
        var context = new BookmarkContextProvider(); var legacy = new Element("context"); legacy.addContent(XmlSerializer.serialize(manager.getState()));
        context.loadContext(project, legacy);
        check(store.readingState().getRoutes().isEmpty(), "old branch archives without reading metadata start with empty reading progress");
        check(!BookmarkNavigation.locate(project, manager, "absent", null), "route navigation safely rejects absent or restoring bookmarks");
      });
      await(() -> !manager.isRestoringBookmarks());
      check(Files.readString(source).equals(content), "health filters routes and repair preserve fixture source bytes");
    } finally {
      edt(() -> { manager.loadState(original); store.loadState(workspace); AdvancedSettings.setBoolean("tasks.enable.bookmark.context.on.branch.switch", branchSetting); });
      await(() -> !manager.isRestoringBookmarks());
    }
  }

  public static void prepareRestart(Project project, BookmarksManagerImpl manager) {
    var store = BookmarkWorkspaceStore.getInstance(project);
    var route = store.route(manager.captureForExchange(), "ai-root"); route.getCompleted().add("ai-stable-bookmark"); route.setCurrentId("ai-stable-bookmark"); store.updateRoute(manager.captureForExchange(), route);
    var filter = new BookmarkFilter(); filter.setGroupId("ai-root"); filter.setQuery("中文"); filter.setHealth("VALID");
    store.saveView("重启筛选", filter);
  }
  public static void verifyRestart(Project project, BookmarksManagerImpl manager) {
    var store = BookmarkWorkspaceStore.getInstance(project);
    check(store.route(manager.captureForExchange(), "ai-root").getCompleted().equals(List.of("ai-stable-bookmark")), "actual project restart preserves reading progress in workspace.xml");
    check(store.views().stream().anyMatch(it -> it.getName().equals("重启筛选") && it.getFilter().getGroupId().equals("ai-root") && it.getFilter().getHealth().equals("VALID")), "actual project restart preserves saved view conditions");
  }
}
