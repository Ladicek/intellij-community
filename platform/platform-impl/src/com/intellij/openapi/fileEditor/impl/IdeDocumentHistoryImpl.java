/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.fileEditor.impl;

import com.intellij.ide.ui.UISettings;
import com.intellij.openapi.command.CommandAdapter;
import com.intellij.openapi.command.CommandEvent;
import com.intellij.openapi.command.CommandListener;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.impl.CommandMerger;
import com.intellij.openapi.components.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.util.xmlb.annotations.Transient;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.*;

@State(
    name = "IdeDocumentHistory",
    storages = {@Storage( file = StoragePathMacros.WORKSPACE_FILE)}
)
public class IdeDocumentHistoryImpl extends IdeDocumentHistory implements ProjectComponent, PersistentStateComponent<IdeDocumentHistoryImpl.RecentlyChangedFilesState> {
  private static final Logger LOG = Logger.getInstance("#com.intellij.openapi.fileEditor.impl.IdeDocumentHistoryImpl");

  private static final int BACK_QUEUE_LIMIT = 25;
  private static final int CHANGE_QUEUE_LIMIT = 25;

  private final Project myProject;

  private final EditorFactory myEditorFactory;
  private FileDocumentManager myFileDocumentManager;
  private FileEditorManagerEx myEditorManager;
  private final VirtualFileManager myVfManager;
  private final CommandProcessor myCmdProcessor;
  private final ToolWindowManager myToolWindowManager;

  private final LinkedList<PlaceInfo> myBackPlaces = new LinkedList<PlaceInfo>(); // LinkedList of PlaceInfo's
  private final LinkedList<PlaceInfo> myForwardPlaces = new LinkedList<PlaceInfo>(); // LinkedList of PlaceInfo's
  private boolean myBackInProgress = false;
  private boolean myForwardInProgress = false;
  private Object myLastGroupId = null;

  // change's navigation
  private final LinkedList<PlaceInfo> myChangePlaces = new LinkedList<PlaceInfo>(); // LinkedList of PlaceInfo's
  private int myStartIndex = 0;
  private int myCurrentIndex = 0;
  private PlaceInfo myCurrentChangePlace = null;

  private PlaceInfo myCommandStartPlace = null;
  private boolean myCurrentCommandIsNavigation = false;
  private boolean myCurrentCommandHasChanges = false;
  private final Set<VirtualFile> myChangedFilesInCurrentCommand = new THashSet<VirtualFile>();
  private boolean myCurrentCommandHasMoves = false;

  private final CommandListener myCommandListener = new CommandAdapter() {
    @Override
    public void commandStarted(CommandEvent event) {
      onCommandStarted();
    }

    @Override
    public void commandFinished(CommandEvent event) {
      onCommandFinished(event.getCommandGroupId());
    }
  };

  private RecentlyChangedFilesState myRecentlyChangedFiles = new RecentlyChangedFilesState();

  public IdeDocumentHistoryImpl(@NotNull Project project,
                                @NotNull EditorFactory editorFactory,
                                @NotNull FileEditorManager editorManager,
                                @NotNull VirtualFileManager vfManager,
                                @NotNull CommandProcessor cmdProcessor,
                                @NotNull ToolWindowManager toolWindowManager) {
    myProject = project;
    myEditorFactory = editorFactory;
    myEditorManager = (FileEditorManagerEx)editorManager;
    myVfManager = vfManager;
    myCmdProcessor = cmdProcessor;
    myToolWindowManager = toolWindowManager;
  }

  @Override
  public final void projectOpened() {
    myEditorManager = (FileEditorManagerEx)FileEditorManager.getInstance(myProject);
    EditorEventMulticaster eventMulticaster = myEditorFactory.getEventMulticaster();

    DocumentListener documentListener = new DocumentAdapter() {
      @Override
      public void documentChanged(DocumentEvent e) {
        onDocumentChanged(e);
      }
    };
    eventMulticaster.addDocumentListener(documentListener, myProject);

    CaretListener caretListener = new CaretListener() {
      @Override
      public void caretPositionChanged(CaretEvent e) {
        onCaretPositionChanged(e);
      }
    };
    eventMulticaster.addCaretListener(caretListener,myProject);

    myProject.getMessageBus().connect().subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerAdapter() {
      @Override
      public void selectionChanged(@NotNull FileEditorManagerEvent e) {
        onSelectionChanged();
      }
    });

    VirtualFileListener fileListener = new VirtualFileAdapter() {
      @Override
      public void fileDeleted(@NotNull VirtualFileEvent event) {
        onFileDeleted();
      }
    };
    myVfManager.addVirtualFileListener(fileListener,myProject);
    myCmdProcessor.addCommandListener(myCommandListener,myProject);
  }

  public static class RecentlyChangedFilesState {
    @Transient private List<String> CHANGED_PATHS = new ArrayList<String>();

    public List<String> getChangedFiles() {
      return CHANGED_PATHS;
    }

    public void setChangedFiles(List<String> changed) {
      CHANGED_PATHS = changed;
    }

    public void register(VirtualFile file) {
      final String path = file.getPath();
      CHANGED_PATHS.remove(path);
      CHANGED_PATHS.add(path);
      trimToSize();
    }

    private void trimToSize(){
      final int limit = UISettings.getInstance().RECENT_FILES_LIMIT + 1;
      while(CHANGED_PATHS.size()>limit){
        CHANGED_PATHS.remove(0);
      }
    }
  }

  @Override
  public RecentlyChangedFilesState getState() {
    return myRecentlyChangedFiles;
  }

  @Override
  public void loadState(RecentlyChangedFilesState state) {
    myRecentlyChangedFiles = state;
  }

  public final void onFileDeleted() {
    removeInvalidFilesFromStacks();
  }

  public final void onSelectionChanged() {
    myCurrentCommandIsNavigation = true;
    myCurrentCommandHasMoves = true;
  }

  private void onCaretPositionChanged(CaretEvent e) {
    if (e.getOldPosition().line == e.getNewPosition().line) return;
    Document document = e.getEditor().getDocument();
    if (getFileDocumentManager().getFile(document) != null) {
      myCurrentCommandHasMoves = true;
    }
  }

  private void onDocumentChanged(DocumentEvent e) {
    Document document = e.getDocument();
    final VirtualFile file = getFileDocumentManager().getFile(document);
    if (file != null) {
      myCurrentCommandHasChanges = true;
      myChangedFilesInCurrentCommand.add(file);
    }
  }

  public final void onCommandStarted() {
    myCommandStartPlace = getCurrentPlaceInfo();
    myCurrentCommandIsNavigation = false;
    myCurrentCommandHasChanges = false;
    myCurrentCommandHasMoves = false;
    myChangedFilesInCurrentCommand.clear();
  }

  private PlaceInfo getCurrentPlaceInfo() {
    final Pair<FileEditor,FileEditorProvider> selectedEditorWithProvider = getSelectedEditor();
    if (selectedEditorWithProvider != null) {
      return createPlaceInfo(selectedEditorWithProvider.getFirst (), selectedEditorWithProvider.getSecond ());
    }
    return null;
  }

  public final void onCommandFinished(Object commandGroupId) {
    if (myCommandStartPlace != null) {
      if (myCurrentCommandIsNavigation && myCurrentCommandHasMoves) {
        if (!myBackInProgress) {
          if (!CommandMerger.canMergeGroup(commandGroupId, myLastGroupId)) {
            putLastOrMerge(myBackPlaces, myCommandStartPlace, BACK_QUEUE_LIMIT);
          }
          if (!myForwardInProgress) {
            myForwardPlaces.clear();
          }
        }
        removeInvalidFilesFromStacks();
      }
    }
    myLastGroupId = commandGroupId;

    if (myCurrentCommandHasChanges) {
      setCurrentChangePlace();
    }
    else if (myCurrentCommandHasMoves) {
      pushCurrentChangePlace();
    }
  }


  @Override
  public final void projectClosed() {
  }

  @Override
  public final void includeCurrentCommandAsNavigation() {
    myCurrentCommandIsNavigation = true;
  }

  @Override
  public final void includeCurrentPlaceAsChangePlace() {
    setCurrentChangePlace();
    pushCurrentChangePlace();
  }

  private void setCurrentChangePlace() {
    final Pair<FileEditor,FileEditorProvider> selectedEditorWithProvider = getSelectedEditor();
    if (selectedEditorWithProvider == null) {
      return;
    }
    final PlaceInfo placeInfo = createPlaceInfo(selectedEditorWithProvider.getFirst(), selectedEditorWithProvider.getSecond ());

    final VirtualFile file = placeInfo.getFile();
    if (myChangedFilesInCurrentCommand.contains(file)) {
      myRecentlyChangedFiles.register(file);

      myCurrentChangePlace = placeInfo;
      if (!myChangePlaces.isEmpty()) {
        final PlaceInfo lastInfo = myChangePlaces.get(myChangePlaces.size() - 1);
        if (isSame(placeInfo, lastInfo)) {
          myChangePlaces.removeLast();
        }
      }
      myCurrentIndex = myStartIndex + myChangePlaces.size();
    }
  }

  private void pushCurrentChangePlace() {
    if (myCurrentChangePlace != null) {
      myChangePlaces.add(myCurrentChangePlace);
      if (myChangePlaces.size() > CHANGE_QUEUE_LIMIT) {
        myChangePlaces.removeFirst();
        myStartIndex++;
      }
      myCurrentChangePlace = null;
    }
    myCurrentIndex = myStartIndex + myChangePlaces.size();
  }

  @Override
  public VirtualFile[] getChangedFiles() {
    List<VirtualFile> files = new ArrayList<VirtualFile>();

    final LocalFileSystem lfs = LocalFileSystem.getInstance();
    final List<String> paths = myRecentlyChangedFiles.getChangedFiles();
    for (String path : paths) {
      final VirtualFile file = lfs.findFileByPath(path);
      if (file != null) {
        files.add(file);
      }
    }

    return VfsUtilCore.toVirtualFileArray(files);
  }

  @Override
  public final void clearHistory() {
    clearPlaceList(myBackPlaces);
    clearPlaceList(myForwardPlaces);
    clearPlaceList(myChangePlaces);

    myLastGroupId = null;

    myStartIndex = 0;
    myCurrentIndex = 0;
    if (myCurrentChangePlace != null) {
      myCurrentChangePlace = null;
    }

    if (myCommandStartPlace != null) {
      myCommandStartPlace = null;
    }
  }

  @Override
  public final void back() {
    removeInvalidFilesFromStacks();
    if (myBackPlaces.isEmpty()) return;
    final PlaceInfo info = myBackPlaces.removeLast();

    PlaceInfo current = getCurrentPlaceInfo();
    if (current != null) {
      if (!isSame(current, info)) {
        putLastOrMerge(myForwardPlaces, current, Integer.MAX_VALUE);
      }
    }
    putLastOrMerge(myForwardPlaces, info, Integer.MAX_VALUE);

    myBackInProgress = true;

    executeCommand(new Runnable() {
      @Override
      public void run() {
        gotoPlaceInfo(info);
      }
    }, "", null);

    myBackInProgress = false;
  }

  @Override
  public final void forward() {
    removeInvalidFilesFromStacks();

    final PlaceInfo target = getTargetForwardInfo();
    if (target == null) return;

    myForwardInProgress = true;
    executeCommand(new Runnable() {
      @Override
      public void run() {
        gotoPlaceInfo(target);
      }
    }, "", null);
    myForwardInProgress = false;
  }

  private PlaceInfo getTargetForwardInfo() {
    if (myForwardPlaces.isEmpty()) return null;

    PlaceInfo target = myForwardPlaces.removeLast();
    PlaceInfo current = getCurrentPlaceInfo();

    while (!myForwardPlaces.isEmpty()) {
      if (isSame(current, target)) {
        target = myForwardPlaces.removeLast();
      } else {
        break;
      }
    }
    return target;
  }

  @Override
  public final boolean isBackAvailable() {
    return !myBackPlaces.isEmpty();
  }

  @Override
  public final boolean isForwardAvailable() {
    return !myForwardPlaces.isEmpty();
  }

  @Override
  public final void navigatePreviousChange() {
    removeInvalidFilesFromStacks();
    if (myCurrentIndex == myStartIndex) return;
    int index = myCurrentIndex - 1;
    final PlaceInfo info = myChangePlaces.get(index - myStartIndex);

    executeCommand(new Runnable() {
      @Override
      public void run() {
        gotoPlaceInfo(info);
      }
    }, "", null);
    myCurrentIndex = index;
  }

  @Override
  public final boolean isNavigatePreviousChangeAvailable() {
    return myCurrentIndex > myStartIndex;
  }

  private void removeInvalidFilesFromStacks() {
    removeInvalidFilesFrom(myBackPlaces);

    removeInvalidFilesFrom(myForwardPlaces);
    if (removeInvalidFilesFrom(myChangePlaces)) {
      myCurrentIndex = myStartIndex + myChangePlaces.size();
    }
  }

  private static boolean removeInvalidFilesFrom(final LinkedList<PlaceInfo> backPlaces) {
    boolean removed = false;
    for (Iterator<PlaceInfo> iterator = backPlaces.iterator(); iterator.hasNext();) {
      PlaceInfo info = iterator.next();
      final VirtualFile file = info.myFile;
      if (!file.isValid()) {
        iterator.remove();
        removed = true;
      }
    }

    return removed;
  }

  private void gotoPlaceInfo(@NotNull PlaceInfo info) { // TODO: Msk
    final boolean wasActive = myToolWindowManager.isEditorComponentActive();
    EditorWindow wnd = info.getWindow();
    final Pair<FileEditor[],FileEditorProvider[]> editorsWithProviders;
    if (wnd != null && wnd.isValid()) {
      editorsWithProviders = myEditorManager.openFileWithProviders(info.getFile(), wasActive, wnd);
    } else {
      editorsWithProviders = myEditorManager.openFileWithProviders(info.getFile(), wasActive, false);
    }

    myEditorManager.setSelectedEditor(info.getFile(), info.getEditorTypeId());

    final FileEditor        [] editors   = editorsWithProviders.getFirst();
    final FileEditorProvider[] providers = editorsWithProviders.getSecond();
    for (int i = 0; i < editors.length; i++) {
      String typeId = providers [i].getEditorTypeId();
      if (typeId.equals(info.getEditorTypeId())) {
        editors[i].setState(info.getNavigationState());
      }
    }
  }

  /**
   * @return currently selected FileEditor or null.
   */
  protected Pair<FileEditor,FileEditorProvider> getSelectedEditor() {
    VirtualFile file = myEditorManager.getCurrentFile();
    return file != null ? myEditorManager.getSelectedEditorWithProvider(file) : null;
  }

  private PlaceInfo createPlaceInfo(@NotNull final FileEditor fileEditor, final FileEditorProvider fileProvider) {
    final VirtualFile file = myEditorManager.getFile(fileEditor);
    LOG.assertTrue(file != null);

    final FileEditorState state = fileEditor.getState(FileEditorStateLevel.NAVIGATION);

    return new PlaceInfo(file, state, fileProvider.getEditorTypeId(), myEditorManager.getCurrentWindow());
  }

  private static void clearPlaceList(LinkedList<PlaceInfo> list) {
    list.clear();
  }


  @Override
  @NotNull
  public final String getComponentName() {
    return "IdeDocumentHistory";
  }

  private static void putLastOrMerge(LinkedList<PlaceInfo> list, PlaceInfo next, int limitSizeLimit) {
    if (!list.isEmpty()) {
      PlaceInfo prev = list.get(list.size() - 1);
      if (isSame(prev, next)) {
        list.removeLast();
      }
    }

    list.add(next);
    if (list.size() > limitSizeLimit) {
      list.removeFirst();
    }
  }

  private FileDocumentManager getFileDocumentManager() {
    if (myFileDocumentManager == null) {
      myFileDocumentManager = FileDocumentManager.getInstance();
    }
    return myFileDocumentManager;
  }

  private static final class PlaceInfo {

    private final VirtualFile myFile;
    private final FileEditorState myNavigationState;
    private final String myEditorTypeId;
    private final WeakReference<EditorWindow> myWindow;

    public PlaceInfo(@NotNull VirtualFile file, FileEditorState navigationState, String editorTypeId, @Nullable EditorWindow window) {
      myNavigationState = navigationState;
      myFile = file;
      myEditorTypeId = editorTypeId;
      myWindow = new WeakReference<EditorWindow>(window);
    }

    public EditorWindow getWindow() {
      return myWindow.get();
    }

    public FileEditorState getNavigationState() {
      return myNavigationState;
    }

    @NotNull
    public VirtualFile getFile() {
      return myFile;
    }

    public String getEditorTypeId() {
      return myEditorTypeId;
    }

    public String toString() {
      return getFile().getName() + " " + getNavigationState();
    }

  }

  public LinkedList<PlaceInfo> getBackPlaces() {
    return myBackPlaces;
  }

  public LinkedList<PlaceInfo> getForwardPlaces() {
    return myForwardPlaces;
  }

  @Override
  public final void initComponent() { }

  @Override
  public final void disposeComponent() {
    myLastGroupId = null;
  }

  protected void executeCommand(Runnable runnable, String name, Object groupId) {
    myCmdProcessor.executeCommand(myProject, runnable, name, groupId);
  }

  private static boolean isSame(PlaceInfo first, PlaceInfo second) {
    if (first.getFile().equals(second.getFile())) {
      FileEditorState firstState = first.getNavigationState();
      FileEditorState secondState = second.getNavigationState();
      return firstState.equals(secondState) || firstState.canBeMergedWith(secondState, FileEditorStateLevel.NAVIGATION);
    }

    return false;
  }


}
