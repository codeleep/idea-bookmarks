package bookmarks.validation;

import com.intellij.ide.bookmark.*;
import com.intellij.ide.bookmark.providers.LineBookmarkProvider;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.ui.SimpleTextAttributes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

/** Also runs against installed .6: uses no APIs introduced by the .7 UI. */
public final class AnchorSmokeStarter implements ApplicationStarter {
  @Override public boolean isHeadless() { return true; }
  @Override public int getRequiredModality() { return NOT_IN_EDT; }

  private static void check(boolean value, String label) {
    if (!value) throw new AssertionError(label);
    System.out.println("PASS " + label);
  }

  public static void verify(Path root) throws Exception {
    var descriptor = com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId("intellij.bookmarks.plugin"));
    check(descriptor != null && System.getProperty("bookmarks.validation.version").equals(descriptor.getVersion()), "anchor checks load the requested plugin version");
    check(!descriptor.isBundled() && descriptor.getPluginPath().startsWith(root.resolve("plugins")), "anchor checks use the isolated user plugin ZIP");
    Project[] project = new Project[1];
    BookmarksManagerImpl[] manager = new BookmarksManagerImpl[1];
    project[0] = ProjectManagerEx.getInstanceEx().loadProject(root.resolve("anchor-project"));
    ApplicationManager.getApplication().invokeAndWait(() -> {
      manager[0] = (BookmarksManagerImpl)BookmarksManager.getInstance(project[0]);
    });
    long deadline = System.nanoTime() + 15_000_000_000L;
    while ((manager[0].isRestoringBookmarks() || manager[0].getBookmarks().size() != 5) && System.nanoTime() < deadline) Thread.sleep(25);
    ApplicationManager.getApplication().invokeAndWait(() -> {
      var actual = manager[0];
      check(actual.getBookmarks().size() == 5, "exact anchor import restores all five records from tool-written project XML");
      var provider = LineBookmarkProvider.Util.find(project[0]);
      for (var record : actual.getState().getBookmarks()) {
        var bookmark = actual.getBookmarks().stream().filter(b -> record.getId().equals(actual.getBookmarkId(b))).findFirst().orElseThrow();
        check(bookmark instanceof LineBookmark && bookmark.canNavigate(), "exact anchor is navigable: " + record.getId());
        check(actual.getGroups(bookmark).size() == 2, "exact anchor import preserves shared references: " + record.getId());
        var presentation = presentation(bookmark);
        check(!presentation.getColoredText().isEmpty() && presentation.getColoredText().stream().anyMatch(t -> !SimpleTextAttributes.GRAYED_ATTRIBUTES.equals(t.getAttributes())), "exact anchor has normal line-node presentation: " + record.getId());
        if (record.getId().equals("anchor-optional")) continue;
        var attributes = new HashMap<>(record.getAttributes());
        var source = record.getAttributes().get("lineText");
        var document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(((LineBookmark)bookmark).getFile());
        check(source.equals(LineBookmarkProvider.Util.readLineText(document, ((LineBookmark)bookmark).getLine())), "IDE Document preserves exact source whitespace: " + record.getId());
        attributes.put("lineText", source.strip()); // Deliberately reproduce the AI bug; never persist it.
        var bad = provider.createBookmark(attributes);
        check(!(bad instanceof LineBookmark) && !bad.canNavigate(), "original strict provider rejects trimmed anchor: " + record.getId());
        var invalid = presentation(bad);
        check(!invalid.getColoredText().isEmpty() && invalid.getColoredText().stream().allMatch(t -> SimpleTextAttributes.GRAYED_ATTRIBUTES.equals(t.getAttributes())), "trimmed anchor reproduces fully gray invalid node: " + record.getId());
      }
      check(actual.getGroupById("anchor-child") != null && actual.getParentGroup(actual.getGroupById("anchor-child")) == actual.getGroupById("anchor-root"), "exact anchor import preserves group hierarchy and IDs");
    });
    Files.writeString(root.resolve("anchor-result.txt"), "PASS: exact anchors display normally; trimmed anchors stay invalid\n");
  }

  private static PresentationData presentation(Bookmark bookmark) {
    var node = bookmark.createNode();
    var result = new PresentationData();
    try {
      var method = node.getClass().getDeclaredMethod("update", PresentationData.class);
      method.setAccessible(true);
      method.invoke(node, result);
    }
    catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
    return result;
  }

  @Override public void main(List<String> args) {
    try {
      verify(Path.of(System.getProperty("bookmarks.validation.root")));
      System.exit(0);
    } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
  }
}
