package bookmarks.validation;

import com.intellij.ide.bookmark.*;
import com.intellij.ide.bookmark.actions.BookmarkSourceMenus;
import com.intellij.ide.hierarchy.*;
import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.*;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.psi.*;
import com.intellij.psi.search.searches.*;
import com.intellij.usageView.UsageInfo;
import com.intellij.usages.*;
import com.intellij.util.xmlb.XmlSerializer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Native indexed project, real Find adapters, and the installed plugin's transactional writer. */
public final class BookmarkSourceNavigationSmoke {
  private static void edt(Runnable r) { ApplicationManager.getApplication().invokeAndWait(r); }
  private static void check(boolean ok,String label) { if(!ok)throw new AssertionError(label);System.out.println("PASS "+label); }
  private static void reject(Runnable r,String label) {boolean rejected=false;try{r.run();}catch(IllegalArgumentException|IllegalStateException e){rejected=true;}check(rejected,label);}
  private static void restored(BookmarksManagerImpl manager)throws Exception {long end=System.nanoTime()+10_000_000_000L;while(manager.isRestoringBookmarks()&&System.nanoTime()<end)Thread.sleep(20);check(!manager.isRestoringBookmarks(),"source result additions finish native asynchronous restoration");}
  private static String json(BookmarksManagerImpl manager){return BookmarkExchange.toJson(manager.captureForExchange(),null);}
  private static void awaitIndexes(Project project)throws Exception {
    var startup=com.intellij.openapi.startup.StartupManager.getInstance(project).getAllActivitiesPassedFuture();
    var scanner=UnindexedFilesScannerExecutor.getInstance(project);
    var dumb=DumbService.getInstance(project);
    long deadline=System.nanoTime()+90_000_000_000L;
    // Smart mode may briefly occur between scanning and the queued indexer on a fresh Linux profile.
    // Wait for startup and the scanner as well; the search assertions below remain unconditional.
    while(System.nanoTime()<deadline) {
      if(startup.isCompleted()&&!scanner.getHasQueuedTasks()&&!scanner.isRunning().getValue()) {
        dumb.waitForSmartMode(1000);
        edt(()->{});
        if(!dumb.isDumb()&&!scanner.getHasQueuedTasks()&&!scanner.isRunning().getValue()) {
          check(true,"project startup scanning and indexing complete before native source queries");return;
        }
      }
      Thread.sleep(20);
    }
    throw new AssertionError("Source project index readiness timed out: startup="+startup.isCompleted()+", scanning="+scanner.isRunning().getValue()+", queued="+scanner.getHasQueuedTasks()+", dumb="+dumb.isDumb());
  }
  public static void verify(Path root)throws Exception {
    Path path=root.resolve("source-project");Files.createDirectories(path.resolve(".idea"));Files.createDirectories(path.resolve("src"));
    Files.writeString(path.resolve(".idea/modules.xml"),"<project version=\"4\"><component name=\"ProjectModuleManager\"><modules><module fileurl=\"file://$PROJECT_DIR$/probe.iml\" filepath=\"$PROJECT_DIR$/probe.iml\"/></modules></component></project>");
    Files.writeString(path.resolve("probe.iml"),"<module type=\"JAVA_MODULE\" version=\"4\"><component name=\"NewModuleRootManager\"><content url=\"file://$MODULE_DIR$\"><sourceFolder url=\"file://$MODULE_DIR$/src\" isTestSource=\"false\"/></content><orderEntry type=\"sourceFolder\" forTests=\"false\"/></component></module>");
    var sources=Map.of("Reader","public interface Reader {\n    void read();\n}\n","FileReader","public class FileReader implements Reader {\n\tpublic void read() {}  \t\n}\n","SocketReader","public class SocketReader implements Reader {\n    public void read() {}\n}\n","Client","public class Client {\n    // 中文调用点\n    void run(Reader reader) {\n        reader.read();  \t\n    }\n}\n");
    for(var entry:sources.entrySet())Files.writeString(path.resolve("src/"+entry.getKey()+".java"),entry.getValue());
    Objects.requireNonNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)).refresh(false,true);
    Project project=ProjectManagerEx.getInstanceEx().openProject(path,new OpenProjectTask(true,null,false,false));
    check(project!=null,"source analysis opens an actual Java module in an isolated project");
    awaitIndexes(project);
    var manager=(BookmarksManagerImpl)BookmarksManager.getInstance(project);
    var points=new AtomicReference<List<BookmarkSourceNavigation.Point>>();
    var usagePoint=new AtomicReference<BookmarkSourceNavigation.Point>();
    edt(()->{manager.addGroup("研究入口",true);manager.addGroup("另一条路线",false);BookmarkSourceMenus.installNativeMenus();});
    try {
      DumbService.getInstance(project).runReadActionInSmartMode(()->{
        var index=com.intellij.openapi.roots.ProjectFileIndex.getInstance(project);
        check(sources.keySet().stream().allMatch(name->index.isInSourceContent(Objects.requireNonNull(LocalFileSystem.getInstance().findFileByNioFile(path.resolve("src/"+name+".java"))))) ,"every Java fixture belongs to the actual module source roots");
        var vf=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path.resolve("src/Reader.java"));
        var file=(PsiJavaFile)PsiManager.getInstance(project).findFile(vf);var type=file.getClasses()[0];var method=type.getMethods()[0];
        var definitions=DefinitionsScopedSearch.search(type).findAll();
        check(definitions.size()==2,"native implementation search finds both concrete classes");
        var context=SimpleDataContext.builder().add(CommonDataKeys.PROJECT,project).add(LangDataKeys.PSI_ELEMENT_ARRAY,definitions.toArray(PsiElement.EMPTY_ARRAY)).build();
        var selection=BookmarkSourceNavigation.selected(project,context);points.set(selection.getPoints());
        check(selection.getPoints().size()==2&&selection.getSkipped()==0,"multiple native implementation results become declaration locations");
        var references=ReferencesSearch.search(method).findAll();check(references.size()==1,"native index finds the real interface method call");
        var usages=references.stream().map(ref->new UsageInfo2UsageAdapter(new UsageInfo(ref.getElement()))).toArray(Usage[]::new);
        var usageContext=SimpleDataContext.builder().add(CommonDataKeys.PROJECT,project).add(UsageView.USAGES_KEY,usages).add(LangDataKeys.PSI_ELEMENT_ARRAY,new PsiElement[]{method}).build();
        var calls=BookmarkSourceNavigation.selected(project,usageContext).getPoints();usagePoint.set(calls.getFirst());
        check(calls.size()==1&&calls.getFirst().getLine()==3&&calls.getFirst().getLineText().equals("        reader.read();  \t"),"Find usage selection wins over declaration and preserves indentation tabs and trailing whitespace");
        var types=LanguageTypeHierarchy.INSTANCE.forLanguage(type.getLanguage());
        check(types.getTarget(SimpleDataContext.builder().add(CommonDataKeys.PROJECT,project).add(CommonDataKeys.PSI_ELEMENT,type).build())!=null,"native type hierarchy accepts the selected source symbol");
        var callProvider=LanguageCallHierarchy.INSTANCE.forLanguage(method.getLanguage());
        check(callProvider.getTarget(SimpleDataContext.builder().add(CommonDataKeys.PROJECT,project).add(CommonDataKeys.PSI_ELEMENT,method).build())!=null,"native call hierarchy accepts the selected method");
        var invalid=PsiFileFactory.getInstance(project).createFileFromText("scratch.txt",com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE,"temporary");
        var empty=BookmarkSourceNavigation.selected(project,SimpleDataContext.builder().add(CommonDataKeys.PSI_ELEMENT,invalid).build());
        check(empty.getPoints().isEmpty()&&empty.getSkipped()==1,"temporary and nonlocal source selections remain unbookmarked");
      });
      for(var operation:BookmarkSourceNavigation.Operation.values())check(ActionManager.getInstance().getAction(operation.getActionId())!=null,"native source operation resolves: "+operation.name());
      var action=ActionManager.getInstance().getAction("Bookmarks.AddSourceResults");
      for(String group:List.of("UsageView.Popup","HierarchyPopupMenu.Common","UML.DefaultGraphPopup"))check(Arrays.asList(((ActionGroup)ActionManager.getInstance().getAction(group)).getChildren(null)).contains(action),"result action joins native menu "+group);
      String target=manager.getGroupId(manager.getGroup("研究入口"));String other=manager.getGroupId(manager.getGroup("另一条路线"));
      var result=new AtomicReference<BookmarkSourceNavigation.Addition>();
      edt(()->result.set(BookmarkSourceNavigation.add(project,manager,manager.captureForExchange(),manager.getStateModificationCount(),points.get(),target,null)));
      restored(manager);check(result.get().getCreated()==2&&Files.exists(result.get().getBackup()),"selected source results are backed up before two native bookmarks are added");
      check(manager.getBookmarks().stream().allMatch(Bookmark::canNavigate),"every imported source result remains valid and navigable under the strict provider");
      var state=manager.captureForExchange();var first=state.getBookmarks().getFirst();String firstId=first.getId();String description=first.getDescription();
      edt(()->result.set(BookmarkSourceNavigation.add(project,manager,manager.captureForExchange(),manager.getStateModificationCount(),points.get(),other,null)));
      restored(manager);
      check(result.get().getCreated()==0&&result.get().getReferences()==2&&manager.getBookmarks().size()==2,"a second group reuses canonical source bookmarks rather than cloning them");
      check(manager.captureForExchange().getBookmarks().stream().anyMatch(b->b.getId().equals(firstId)&&Objects.equals(b.getDescription(),description)),"source additions retain existing IDs and shared descriptions");
      edt(()->result.set(BookmarkSourceNavigation.add(project,manager,manager.captureForExchange(),manager.getStateModificationCount(),points.get(),other,null)));
      check(result.get().getBackup()==null&&result.get().getReferences()==0,"adding the same native results again is a lossless no-op");
      edt(()->{
        var file=LocalFileSystem.getInstance().findFileByNioFile(path.resolve("src/Reader.java"));
        var bookmark=com.intellij.ide.bookmark.providers.LineBookmarkProvider.Util.find(project).createBookmark(file,1);
        manager.getGroup("研究入口").add(bookmark,BookmarkType.DEFAULT,"接口方法");
        var symbols=BookmarkSourceNavigation.bookmarkSymbols(project,manager,manager.getBookmarkId(bookmark));
        check(symbols.size()==1&&symbols.getFirst().getContext().getData(CommonDataKeys.PSI_ELEMENT) instanceof PsiMethod,"bookmark analysis targets the declared method name rather than its indentation or modifiers");
      });
      edt(()->result.set(BookmarkSourceNavigation.add(project,manager,manager.captureForExchange(),manager.getStateModificationCount(),List.of(usagePoint.get()),target,null)));
      restored(manager);
      check(manager.getBookmarks().stream().allMatch(Bookmark::canNavigate),"indented usage anchors with trailing tabs also import as valid native bookmarks");
      edt(()->{
        var symbols=BookmarkSourceNavigation.bookmarkSymbols(project,manager,result.get().getIds().getFirst());
        check(symbols.size()>=2&&symbols.stream().anyMatch(s->s.getContext().getData(CommonDataKeys.PSI_ELEMENT) instanceof PsiMethod),"a call line exposes receiver and method as distinct native symbol choices");
      });
      String before=json(manager);var stale=manager.captureForExchange();long revision=manager.getStateModificationCount();
      edt(()->manager.getGroup("研究入口").setName("研究入口已改名"));
      edt(()->reject(()->BookmarkSourceNavigation.add(project,manager,stale,revision,List.of(usagePoint.get()),target,null),"a stale source-result dialog cannot overwrite newer bookmark changes"));
      var client=LocalFileSystem.getInstance().findFileByNioFile(path.resolve("src/Client.java"));var document=FileDocumentManager.getInstance().getDocument(client);
      edt(()->WriteCommandAction.runWriteCommandAction(project,()->document.insertString(0,"// 源码变化\n")));
      var unchanged=json(manager);
      edt(()->reject(()->BookmarkSourceNavigation.add(project,manager,manager.captureForExchange(),manager.getStateModificationCount(),List.of(usagePoint.get()),target,null),"source changed after preview is rejected before any bookmark mutation"));
      check(json(manager).equals(unchanged),"rejected source selections leave the full canonical store unchanged");
      edt(()->WriteCommandAction.runWriteCommandAction(project,()->document.setText(sources.get("Client"))));
      var store=BookmarkWorkspaceStore.getInstance(project);state=manager.captureForExchange();var snapshot=state;
      var progress=store.route(state,target);long epoch=store.contextRevision();String markdown="# 为什么看这里\n\n保留 **中文**、Tab\t及证据。\n";
      edt(()->store.saveRouteNote(snapshot,epoch,target,firstId,"",markdown));
      check(store.route(state,target).getNotes().getFirst().getMarkdown().equals(markdown)&&store.route(state,other).getNotes().isEmpty(),"route step explanations remain independent for a shared bookmark in different routes");
      progress.getCompleted().add(firstId);progress.setCurrentId(firstId);
      edt(()->store.updateRouteProgress(snapshot,progress,epoch));
      check(store.route(state,target).getNotes().getFirst().getMarkdown().equals(markdown)&&store.route(state,target).getCompleted().contains(firstId),"navigation progress retains the newest route explanation");
      var reading=XmlSerializer.deserialize(XmlSerializer.serialize(store.readingState()),BookmarkReadingState.class);
      check(reading.getRoutes().stream().flatMap(r->r.getNotes().stream()).anyMatch(n->n.getMarkdown().equals(markdown)),"route explanations round trip with the existing branch reading-state serializer");
      edt(()->reject(()->store.saveRouteNote(snapshot,epoch,target,firstId,"","stale"),"a stale route-note editor cannot overwrite another edit"));
      edt(()->store.restoreReading(reading));
      edt(()->reject(()->store.saveRouteNote(snapshot,epoch,target,firstId,markdown,"wrong branch"),"branch context restoration invalidates an old route-note editor"));
      check(manager.captureForExchange().getBookmarks().stream().anyMatch(b->b.getId().equals(firstId)&&Objects.equals(b.getDescription(),description)),"route explanations never change canonical shared descriptions");
      for(var entry:sources.entrySet())check(Files.readString(path.resolve("src/"+entry.getKey()+".java")).equals(entry.getValue()),"source reading preserves source bytes: "+entry.getKey());
    } finally {edt(()->ProjectManagerEx.getInstanceEx().forceCloseProject(project));}
  }
}
