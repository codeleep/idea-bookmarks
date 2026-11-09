package bookmarks.validation;

import com.intellij.ide.bookmark.*;
import com.intellij.ide.bookmark.ui.BookmarksView;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.util.treeView.AbstractTreeNode;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.breakpoints.*;
import com.intellij.xdebugger.impl.breakpoints.ui.BreakpointItem;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Exercises the registered menu actions against real SDK breakpoints and provider nodes. */
public final class BreakpointActionsSmoke {
  public static final class Properties extends XBreakpointProperties<Properties> {
    @Override public Properties getState() { return this; }
    @Override public void loadState(Properties state) {}
  }

  public static final class LineType extends XLineBreakpointType<Properties> {
    public LineType() { super("bookmarks-validation-lines", "Bookmarks validation breakpoints"); }
    @Override public Properties createBreakpointProperties(VirtualFile file, int line) { return new Properties(); }
    @Override public boolean canPutAt(VirtualFile file, int line, Project project) { return true; }
  }

  private static void edt(Runnable task) { ApplicationManager.getApplication().invokeAndWait(task); }
  private static void check(boolean value, String label) {
    if (!value) throw new AssertionError(label);
    System.out.println("PASS " + label);
  }
  private static void await(BooleanSupplier test, String label) throws InterruptedException {
    long until = System.nanoTime() + 10_000_000_000L;
    while (!test.getAsBoolean() && System.nanoTime() < until) Thread.sleep(25);
    check(test.getAsBoolean(), label);
  }

  private static void collect(AbstractTreeNode<?> node, Map<Integer, AbstractTreeNode<?>> result) {
    if (node.getValue() instanceof BreakpointItem item) {
      var position = item.getBreakpoint().getSourcePosition();
      if (position != null && item.getDisplayText().contains("sample.txt")) result.put(position.getLine(), node);
    }
    for (var child : node.getChildren()) collect(child, result);
  }

  private static AnActionEvent event(AnAction action, Project project, BookmarksView view, Object... selection) {
    DataContext context = id -> {
      if (CommonDataKeys.PROJECT.getName().equals(id)) return project;
      if (BookmarksView.Companion.getBOOKMARKS_VIEW().getName().equals(id)) return view;
      if (PlatformDataKeys.SELECTED_ITEMS.getName().equals(id)) return selection;
      return null;
    };
    var event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.BOOKMARKS_VIEW_POPUP, context);
    action.update(event);
    return event;
  }

  private static void awaitStates(List<XLineBreakpoint<Properties>> points, Map<Integer, AbstractTreeNode<?>> nodes,
                                  boolean[] expected, String label) throws InterruptedException {
    await(() -> {
      boolean[] matches = {true};
      edt(() -> {
        for (int i=0; i<expected.length; i++) {
          if (points.get(i).isEnabled()!=expected[i] || ((BreakpointItem)nodes.get(i).getValue()).isEnabled()!=expected[i]) matches[0]=false;
        }
      });
      return matches[0];
    },label);
  }

  private static void present(AbstractTreeNode<?> node, PresentationData data) {
    try {
      var update = node.getClass().getDeclaredMethod("update", PresentationData.class);
      update.setAccessible(true); update.invoke(node, data);
    } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
  }

  public static void verify(Project project, VirtualFile file, BookmarksManagerImpl bookmarks) throws Exception {
    var nativeManager = XDebuggerManager.getInstance(project).getBreakpointManager();
    var type = (LineType)XBreakpointType.EXTENSION_POINT_NAME.getExtensionList().stream()
        .filter(t -> t instanceof LineType).findFirst().orElseThrow();
    var points = new ArrayList<XLineBreakpoint<Properties>>();
    var nodes = new HashMap<Integer, AbstractTreeNode<?>>();
    BookmarksView[] view = new BookmarksView[1];
    BookmarksListProvider[] provider = new BookmarksListProvider[1];
    String original = BookmarkExchange.toJson(bookmarks.captureForExchange(), null);
    try {
      edt(() -> {
        WriteAction.run(() -> {
          for (int line = 0; line < 3; line++) points.add(nativeManager.addLineBreakpoint(type, file.getUrl(), line, new Properties()));
          points.get(1).setEnabled(false);
        });
        view[0] = new BookmarksView(project, false);
        provider[0] = BookmarksListProviderService.Companion.getProviders(project).stream()
            .filter(p -> p.getClass().getName().endsWith(".BreakpointListProvider")).findFirst().orElseThrow();
        provider[0].createNode();
      });
      await(() -> {
        edt(() -> { nodes.clear(); var root = provider[0].createNode(); if (root != null) collect(root, nodes); });
        return nodes.size() == 3;
      }, "actual breakpoint provider lists three native line breakpoints");
      AnAction[] actions = new AnAction[2];
      var a = nodes.get(0); var b = nodes.get(1); var c = nodes.get(2);
      var presentation = new PresentationData();
      edt(() -> {
        var registry = ActionManager.getInstance();
        actions[0] = registry.getAction("Bookmarks.Breakpoints.Enable");
        actions[1] = registry.getAction("Bookmarks.Breakpoints.Disable");
        var stateGroup = registry.getAction("Bookmarks.Breakpoints.StateActions");
        check(actions[0] != null && actions[1] != null, "enable and disable breakpoint actions are registered");
        check(Arrays.asList(BookmarkSmokeStarter.children((ActionGroup)registry.getAction("Bookmarks.ToolWindow.PopupMenu"))).contains(stateGroup), "breakpoint state actions belong to the actual bookmark context menu");
        check(Arrays.asList(BookmarkSmokeStarter.children((ActionGroup)stateGroup)).subList(0,2).equals(List.of(actions[0],actions[1])), "context menu orders explicit enable before disable");
        check(!event(actions[0],project,view[0],a).getPresentation().isEnabled() && event(actions[1],project,view[0],a).getPresentation().isEnabled(), "single enabled breakpoint offers disable");
        actions[1].actionPerformed(event(actions[1],project,view[0],a));
      });
      awaitStates(points,nodes,new boolean[]{false,false,true},"single disable changes only the selected native breakpoint");
      edt(() -> {
        present(a,presentation);
        check(presentation.getForcedTextForeground() != null, "disabled breakpoint row has inactive text");
        check(a.canNavigate(), "disabled breakpoint rows remain navigable");
        actions[0].actionPerformed(event(actions[0],project,view[0],a));
      });
      awaitStates(points,nodes,new boolean[]{true,false,true},"single enable updates the selected native breakpoint");
      edt(() -> {
        present(a,presentation);
        check(presentation.getForcedTextForeground() == null, "enabling restores normal text on the same breakpoint row");
        check(event(actions[0],project,view[0],a,b).getPresentation().isEnabled() && event(actions[1],project,view[0],a,b).getPresentation().isEnabled(), "mixed enabled and disabled selections offer both target states");
        actions[0].actionPerformed(event(actions[0],project,view[0],a,b));
      });
      awaitStates(points,nodes,new boolean[]{true,true,true},"bulk enable unifies a mixed selection without changing the third breakpoint");
      edt(() -> {
        check(!event(actions[0],project,view[0],a,b).getPresentation().isEnabled(), "enable is disabled when every selected breakpoint is already enabled");
        actions[1].actionPerformed(event(actions[1],project,view[0],a,b));
      });
      awaitStates(points,nodes,new boolean[]{false,false,true},"bulk disable changes every selected breakpoint and preserves unselected ones");
      edt(() -> {
        check(!event(actions[1],project,view[0],a,b).getPresentation().isEnabled(), "disable is disabled when every selected breakpoint is already disabled");
        actions[0].actionPerformed(event(actions[0],project,view[0],a,a,b));
      });
      awaitStates(points,nodes,new boolean[]{true,true,true},"duplicate selected references still reach the requested state");
      edt(() -> {
        Object[][] invalidSelections = {new Object[0], new Object[]{provider[0].createNode()}, new Object[]{a, bookmarks.getBookmark(BookmarkType.LETTER_A).createNode()}};
        String[] labels = {"empty selections", "breakpoint groups", "mixed bookmarks and breakpoints"};
        for (int i=0; i<invalidSelections.length; i++) {
          var invalidEvent = event(actions[1],project,view[0],invalidSelections[i]);
          check(!invalidEvent.getPresentation().isVisible(), "breakpoint actions reject " + labels[i]);
          actions[1].actionPerformed(invalidEvent);
        }
        check(points.stream().allMatch(XBreakpoint::isEnabled), "invalid selections do not toggle any breakpoint");
        var foreign = com.intellij.openapi.project.ProjectManager.getInstance().getDefaultProject();
        check(!event(actions[1],foreign,view[0],a).getPresentation().isVisible(), "breakpoint actions reject a different project context");
        check(!event(actions[1],project,null,a).getPresentation().isVisible(), "breakpoint actions require a bookmarks view context");
        WriteAction.run(() -> nativeManager.removeBreakpoint(points.get(0)));
      });
      await(() -> ((BreakpointItem)a.getValue()).getBreakpoint().isDisposed(),"removed breakpoint proxy becomes disposed");
      edt(() -> {
        var stale = event(actions[1],project,view[0],a,b);
        check(!stale.getPresentation().isVisible(), "removed breakpoint selections are rejected before applying a batch");
        actions[1].actionPerformed(stale);
        check(points.get(1).isEnabled() && points.get(2).isEnabled(), "stale batch leaves remaining native breakpoints unchanged");
        check(points.get(1).getLine()==1 && points.get(2).getLine()==2 && points.get(1).getProperties()!=null, "state actions preserve native breakpoint locations and properties");
        check(original.equals(BookmarkExchange.toJson(bookmarks.captureForExchange(),null)), "breakpoint enable actions leave bookmark storage unchanged");
      });
    } finally {
      edt(() -> {
        WriteAction.run(() -> {
          var existing = Arrays.asList(nativeManager.getAllBreakpoints());
          points.stream().filter(existing::contains).forEach(nativeManager::removeBreakpoint);
        });
        if (view[0] != null) Disposer.dispose(view[0]);
      });
    }
  }
}
