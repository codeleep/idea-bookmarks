package bookmarks.validation;

import com.intellij.ide.bookmark.*;
import com.intellij.ide.bookmark.context.BookmarkContextProvider;
import com.intellij.ide.bookmark.providers.LineBookmarkProvider;
import com.intellij.ide.bookmark.ui.BookmarkWorkbenchPanel;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.options.advanced.AdvancedSettings;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.util.xmlb.XmlSerializer;
import org.jdom.Element;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

public final class BookmarkWorkbenchSmoke {
  private static void edt(Runnable task) { ApplicationManager.getApplication().invokeAndWait(task); }
  private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); System.out.println("PASS " + label); }
  private static void reject(Runnable action, String label) {
    boolean failed = false; try { action.run(); } catch (IllegalArgumentException | IllegalStateException error) { failed = true; }
    check(failed, label);
  }
  private static void restored(BookmarksManagerImpl manager) throws Exception {
    long deadline = System.nanoTime()+10_000_000_000L;
    while (manager.isRestoringBookmarks() && System.nanoTime()<deadline) Thread.sleep(20);
    if (manager.isRestoringBookmarks()) throw new AssertionError("workbench restore timed out");
  }
  private static BookmarkRelation edge(String id, String a, String b, String kind, String note) {
    var e = new BookmarkRelation(); e.setId(id); e.setSourceId(a); e.setTargetId(b); e.setKind(kind); e.setNote(note); return e;
  }
  private static BookmarkState bookmark(String id, String url, String line, String text) {
    var b = new BookmarkState(); b.setId(id); b.setProvider(BookmarkHealth.LINE_PROVIDER); b.setDescription("# " + id + "\n\n[关联](bookmark://b)");
    b.getAttributes().put("url", url); b.getAttributes().put("line", line); b.getAttributes().put("lineText", text); return b;
  }
  private static String json(ManagerState state) { return BookmarkExchange.toJson(state, null); }
  private static List<BookmarkImpact.Capture> captures(Project project, ManagerState state) { return state.getBookmarks().stream().map(b -> BookmarkImpact.capture(project,b)).filter(Objects::nonNull).toList(); }

  public static void verify(Project project, BookmarksManagerImpl manager) throws Exception {
    var original=manager.captureForExchange(); var store=BookmarkWorkspaceStore.getInstance(project); var workspace=store.getState();
    boolean enabled=AdvancedSettings.getBoolean("tasks.enable.bookmark.context.on.branch.switch");
    Path path=Path.of(project.getBasePath(),"workbench/Design.java"); Files.createDirectories(path.getParent());
    String source="class Design {\n    static int entry() {\n        return 1;\n    }\n    static void target() {}\n}\n";
    Files.writeString(path,source); Path moved=path.resolveSibling("Moved.java"); Files.writeString(moved,source.replace("return 1;","return 2;"));
    var state=new ManagerState(); state.setFormatVersion(3);
    state.getBookmarks().add(bookmark("a","file://"+path,"1","    static int entry() {"));
    state.getBookmarks().add(bookmark("b","file://"+path,"4","    static void target() {}"));
    var root=new GroupState();root.setId("wb-root");root.setName("工作台主线");root.setDefault(true);root.getBookmarkIds().addAll(List.of("a","b"));state.getGroups().add(root);
    var child=new GroupState();child.setId("wb-child");child.setParentId("wb-root");child.setName("单独入口");child.getBookmarkIds().add("a");state.getGroups().add(child);
    state.getRelations().add(edge("edge","a","b","CALLS","调用说明\n中文 & <tag>"));
    try {
      edt(() -> {
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);LocalFileSystem.getInstance().refreshAndFindFileByNioFile(moved);
        var text=json(state);var copy=BookmarkExchange.copy(state);copy.getRelations().getFirst().setNote("changed");
        check(json(state).equals(text),"format3 relation copies do not alias canonical state");
        var parsed=BookmarkExchange.fromJson(text,Path.of(project.getBasePath()));
        check(parsed.getRelations().size()==1&&parsed.getRelations().getFirst().getNote().contains("中文 & <tag>"),"portable format3 JSON round trip retains typed relation and original note");
        check(BookmarkExchange.select(state,Set.of("wb-root")).getRelations().size()==1,"subtree export keeps relations whose endpoints are both included");
        check(BookmarkExchange.select(state,Set.of("wb-child")).getRelations().isEmpty(),"partial group export excludes relations to omitted bookmark endpoints");
        check(BookmarkRelations.connected(state,"b").size()==1,"relations can be traversed in reverse from the target");
        var malformed=BookmarkExchange.copy(state);malformed.getRelations().getFirst().setTargetId("missing");
        reject(() -> BookmarkStorageFormat.validate(malformed),"dangling relation endpoints are rejected before applying state");
        var self=BookmarkExchange.copy(state);self.getRelations().getFirst().setTargetId("a");
        reject(() -> BookmarkStorageFormat.validate(self),"self relations are rejected");
        var symmetric=BookmarkExchange.copy(state);symmetric.getRelations().clear();symmetric.getRelations().add(edge("x","a","b","RELATED",""));symmetric.getRelations().add(edge("y","b","a","RELATED",""));
        reject(() -> BookmarkStorageFormat.validate(symmetric),"reverse duplicates of symmetric related edges are rejected");
        var cycle=BookmarkExchange.copy(state);cycle.getRelations().add(edge("reverse","b","a","CALLS",""));BookmarkStorageFormat.validate(cycle);
        check(BookmarkRelations.connected(cycle,"a").size()==2,"directed graph cycles are supported independently of group tree constraints");
        var incoming=BookmarkExchange.copy(state);incoming.getBookmarks().get(1).getAttributes().put("url","file://"+moved);
        var merged=BookmarkExchange.plan(state,incoming,false,false).getState();
        var imported=merged.getBookmarks().stream().filter(b->b.getAttributes().get("url").equals("file://"+moved)).findFirst().get();
        check(!imported.getId().equals("b")&&merged.getRelations().stream().anyMatch(e->e.getTargetId().equals(imported.getId())),"merge remaps relation endpoints when bookmark IDs collide");
        check(merged.getRelations().stream().map(BookmarkRelation::getId).distinct().count()==2,"merge assigns a new edge ID on independent relation ID collision");
        check(imported.getDescription().contains(BookmarkLinks.url(imported.getId())),"imported Markdown bookmark links follow remapped canonical IDs");
        var preferred=BookmarkExchange.plan(state,incoming,false,true).getState();
        var mappedTarget=preferred.getBookmarks().stream().filter(b->b.getAttributes().get("url").equals("file://"+moved)).findFirst().get();
        check(preferred.getBookmarks().stream().filter(b->b.getId().equals("a")).findFirst().get().getDescription().contains(BookmarkLinks.url(mappedTarget.getId())),"preferred imported descriptions remap links even when raw Markdown was initially identical");
        check(BookmarkLinks.decode(BookmarkLinks.url("中文 ID+%/")).equals("中文 ID+%/"),"bookmark links round trip Unicode and reserved ID characters");
        var html=MarkdownDescriptions.renderBody("[关联](bookmark://b) [危险](javascript:alert(1))");
        check(html.contains("bookmark://b")&&!html.contains("javascript:"),"description rendering permits internal bookmark links and still rejects executable links");
        var note=BookmarkExchange.copy(state);note.getRelations().getFirst().setNote("导入说明");
        check(BookmarkExchange.plan(state,note,false,false).getRelationConflicts()==1,"import preview counts conflicting relation notes explicitly");
        var emptyNote=BookmarkExchange.copy(state);emptyNote.getRelations().getFirst().setNote("");
        check(BookmarkExchange.plan(emptyNote,note,false,false).getState().getRelations().getFirst().getNote().equals("导入说明"),"merge supplements empty relation notes without replacing existing content");
        check(BookmarkExchange.plan(state,note,false,false).getState().getRelations().getFirst().getNote().equals(state.getRelations().getFirst().getNote()),"merge retains existing relation note by default");
        check(BookmarkExchange.plan(state,note,false,true).getState().getRelations().getFirst().getNote().equals("导入说明"),"merge applies imported relation notes when selected");
        check(BookmarkExchange.toMarkdown(state,Path.of(project.getBasePath()),"关系").contains("## 书签关联"),"Markdown sharing includes relation summaries");
        var legacy=BookmarkExchange.copy(state);legacy.getRelations().clear();legacy.setFormatVersion(2);BookmarkStorageFormat.validate(legacy);
        check(BookmarkStorageFormat.normalize(legacy).getFormatVersion()==3&&legacy.getFormatVersion()==2,"format2 upgrades on a copy without mutating its input");
        reject(() -> {var bad=BookmarkExchange.copy(state);bad.setFormatVersion(2);BookmarkStorageFormat.validate(bad);},"format2 cannot silently contain typed relations");
        manager.loadState(state);
      });
      restored(manager);
      edt(() -> {
        check(manager.captureForExchange().getRelations().size()==1,"installed live manager retains canonical relations after asynchronous restore");
        var snapshot=manager.captureForExchange();var fresh=captures(project,snapshot);var revision=store.reviewRevision();
        check(BookmarkImpact.inspect(project,snapshot,store.readingState()).stream().allMatch(e->e.getStatus()==BookmarkImpact.Status.UNREVIEWED),"source review starts explicitly unreviewed rather than accepting current code silently");
        store.markReviewed(snapshot,fresh,revision);
        check(store.readingState().getReviews().size()==2&&store.readingState().getSources().size()==1,"multiple bookmarks share one deduplicated private source snapshot");
        check(BookmarkImpact.inspect(project,snapshot,store.readingState()).stream().allMatch(e->e.getStatus()==BookmarkImpact.Status.CURRENT),"explicit source review records an unchanged baseline");
        var document=FileDocumentManager.getInstance().getDocument(LocalFileSystem.getInstance().findFileByNioFile(path));
        WriteCommandAction.runWriteCommandAction(project,()->document.setText(source.replace("return 1;","return 2;")));
        check(manager.getBookmarks().stream().allMatch(b->b.canNavigate()),"changing implementation away from bookmark anchors preserves actual navigation");
        check(BookmarkImpact.inspect(project,snapshot,store.readingState()).stream().allMatch(e->e.getStatus()==BookmarkImpact.Status.CHANGED),"file review detects changed implementation even when both bookmark lines still match");
        reject(() -> store.markReviewed(snapshot,fresh,store.reviewRevision()),"source review refuses a capture whose document changed before commit");
        var tampered=store.readingState();tampered.getSources().getFirst().setText("tampered");
        check(BookmarkImpact.inspect(project,snapshot,tampered).stream().allMatch(e->e.getStatus()==BookmarkImpact.Status.UNREVIEWED),"corrupt source snapshots never produce a false unchanged status");
        var expectedRevision=store.reviewRevision();store.markReviewed(snapshot,captures(project,snapshot),expectedRevision);
        reject(() -> store.clearReviews(Set.of("a"),expectedRevision),"stale baseline revisions cannot overwrite a newer review");
        var route=store.route(snapshot,"wb-root");route.getCompleted().add("a");store.updateRoute(snapshot,route);
        store.clearReviews(Set.of("a"),store.reviewRevision());
        check(store.readingState().getSources().size()==1&&store.readingState().getRoutes().getFirst().getCompleted().contains("a"),"clearing one review keeps shared source snapshots and reading progress");
        store.clearReviews(Set.of("b"),store.reviewRevision());
        check(store.readingState().getSources().isEmpty(),"clearing the last review releases unreferenced source snapshots");
        store.markReviewed(snapshot,captures(project,snapshot),store.reviewRevision());
        var stale=manager.captureForExchange();manager.getGroupById("wb-root").setName("上下文变化");
        reject(() -> store.markReviewed(stale,captures(project,stale),store.reviewRevision()),"source review rejects a changed bookmark context");
        var saved=new Element("context");AdvancedSettings.setBoolean("tasks.enable.bookmark.context.on.branch.switch",true);
        new BookmarkContextProvider().saveContext(project,saved);
        manager.remove();store.restoreReading(new BookmarkReadingState());
        new BookmarkContextProvider().loadContext(project,saved);
      });
      restored(manager);
      edt(() -> {
        check(manager.captureForExchange().getRelations().size()==1&&store.readingState().getReviews().size()==2,"original branch context restores typed relations and private review baselines together");
        var copy=manager.captureForExchange();copy.getBookmarks().getFirst().getAttributes().put("url","file://"+moved);manager.loadState(copy);
      });
      restored(manager);
      edt(() -> {
        check(BookmarkImpact.inspect(project,manager.captureForExchange(),store.readingState()).stream().anyMatch(e->e.getId().equals("a")&&e.getStatus()==BookmarkImpact.Status.MOVED),"review warns when the bookmark moves to another file with identical contents");
        var panel=new BookmarkWorkbenchPanel(project,manager);
        try {check(panel.getReader().getParent()!=null&&panel.getImpact().getParent()!=null&&panel.getRelations().getParent()!=null,"native Swing workbench owns route impact and relation panels without floating dialogs");}
        finally{Disposer.dispose(panel);}
        check(ActionManager.getInstance().getAction("Bookmarks.SourceImpact")!=null&&ActionManager.getInstance().getAction("Bookmarks.Relations")!=null,"workbench impact and relation actions register in the installed plugin");
        var a=manager.getBookmarks().stream().filter(b->"a".equals(manager.getBookmarkId(b))).findFirst().get();
        manager.getGroupById("wb-root").remove(a);
        check(manager.captureForExchange().getRelations().size()==1,"removing one group reference preserves the shared bookmark relation");
        manager.getGroupById("wb-child").remove(a);
        check(manager.captureForExchange().getRelations().isEmpty(),"deleting the last bookmark reference cleans up its connected relations");
        var merging=BookmarkExchange.copy(state);
        var c=bookmark("c","file://"+path,"0","class Design {");c.setDescription("[回到入口](bookmark://a)");merging.getBookmarks().add(c);
        merging.getGroups().getFirst().getBookmarkIds().add("c");merging.getRelations().clear();
        merging.getRelations().add(edge("ac","a","c","CALLS","入口证据"));merging.getRelations().add(edge("bc","b","c","CALLS","目标证据"));
        merging.getRelations().add(edge("ab","a","b","RELATED","合并后自关联应清理"));manager.loadState(merging);
      });
      restored(manager);
      edt(() -> {
        var live=manager.getBookmarks();var a=live.stream().filter(b->"a".equals(manager.getBookmarkId(b))).findFirst().get();
        var b=live.stream().filter(item->"b".equals(manager.getBookmarkId(item))).findFirst().get();
        var replacements=new HashMap<Bookmark,Bookmark>();replacements.put(a,b);manager.update(replacements);
        var merged=manager.captureForExchange();
        check(merged.getRelations().size()==1&&merged.getRelations().getFirst().getSourceId().equals("b"),"live bookmark relocation merge remaps endpoints and removes self and duplicate relations");
        check(merged.getRelations().getFirst().getNote().contains("入口证据")&&merged.getRelations().getFirst().getNote().contains("目标证据"),"live relation coalescing retains evidence from both notes");
        check(merged.getBookmarks().stream().filter(item->"c".equals(item.getId())).findFirst().get().getDescription().contains("bookmark://b"),"live bookmark ID coalescing updates cross links in surviving shared descriptions");
      });
      check(Files.readString(path).equals(source),"review baselines and relations never save or rewrite business source documents");
    } finally {
      edt(()->{manager.loadState(original);store.loadState(workspace);AdvancedSettings.setBoolean("tasks.enable.bookmark.context.on.branch.switch",enabled);});
      restored(manager);
    }
  }

  public static void prepareRestart(Project project, BookmarksManagerImpl manager, com.intellij.openapi.vfs.VirtualFile file) {
    var target=LineBookmarkProvider.Util.find(project).createBookmark(file,2);
    manager.getGroupById("ai-root").add(target,BookmarkType.DEFAULT,"重启关联目标");
    var state=manager.captureForExchange();state.getRelations().add(edge("restart-edge","ai-stable-bookmark",manager.getBookmarkId(target),"DESIGN","跨进程设计依据"));manager.loadState(state);
  }
  public static void prepareReviewRestart(Project project, BookmarksManagerImpl manager) {
    var store=BookmarkWorkspaceStore.getInstance(project);var state=manager.captureForExchange();store.markReviewed(state,captures(project,state),store.reviewRevision());
  }
  public static void verifyRestart(Project project, BookmarksManagerImpl manager) {
    var state=manager.captureForExchange();var store=BookmarkWorkspaceStore.getInstance(project);
    check(state.getRelations().stream().anyMatch(e->e.getId().equals("restart-edge")&&e.getNote().equals("跨进程设计依据")),"actual project restart retains typed canonical relations");
    check(store.readingState().getReviews().size()==2&&store.readingState().getSources().size()==1,"actual project restart retains deduplicated private source baselines");
  }
}
