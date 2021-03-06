// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.compiler.backwardRefs;

import com.intellij.ProjectTopics;
import com.intellij.compiler.CompilerConfiguration;
import com.intellij.compiler.CompilerReferenceService;
import com.intellij.compiler.backwardRefs.view.DirtyScopeTestInfo;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.compiler.options.ExcludeEntryDescription;
import com.intellij.openapi.compiler.options.ExcludedEntriesListener;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootEvent;
import com.intellij.openapi.roots.ModuleRootListener;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.*;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.messages.MessageBusConnection;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

class DirtyScopeHolder extends UserDataHolderBase implements BulkFileListener {
  private final CompilerReferenceServiceBase<?> myService;
  private final FileDocumentManager myFileDocManager;
  private final PsiDocumentManager myPsiDocManager;
  private final Object myLock = new Object();

  private final Set<Module> myVFSChangedModules = ContainerUtil.newHashSet(); // guarded by myLock
  private final Set<Module> myChangedModulesDuringCompilation = ContainerUtil.newHashSet(); // guarded by myLock
  private final List<ExcludeEntryDescription> myExcludedDescriptions = new SmartList<>(); // guarded by myLock
  private boolean myCompilationPhase; // guarded by myLock
  private volatile GlobalSearchScope myExcludedFilesScope; // calculated outside myLock
  private final Set<String> myCompilationAffectedModules = ContainerUtil.newConcurrentSet(); // used outside myLock
  private final FileTypeRegistry myFileTypeRegistry = FileTypeRegistry.getInstance();


  DirtyScopeHolder(@NotNull CompilerReferenceServiceBase<?> service,
                   @NotNull FileDocumentManager fileDocumentManager,
                   @NotNull PsiDocumentManager psiDocumentManager,
                   @NotNull BiConsumer<? super MessageBusConnection, ? super Set<String>> compilationAffectedModulesSubscription) {
    myService = service;
    myFileDocManager = fileDocumentManager;
    myPsiDocManager = psiDocumentManager;

    if (CompilerReferenceService.isEnabled()) {
      final MessageBusConnection connect = service.getProject().getMessageBus().connect();
      connect.subscribe(ExcludedEntriesListener.TOPIC, new ExcludedEntriesListener() {
        @Override
        public void onEntryAdded(@NotNull ExcludeEntryDescription description) {
          synchronized (myLock) {
            if (myCompilationPhase) {
              myExcludedDescriptions.add(description);
            }
          }
        }
      });

      compilationAffectedModulesSubscription.accept(connect, myCompilationAffectedModules);

      connect.subscribe(ProjectTopics.PROJECT_ROOTS, new ModuleRootListener() {
        @Override
        public void beforeRootsChange(ModuleRootEvent event) {
          final Module[] modules = ModuleManager.getInstance(myService.getProject()).getModules();
          synchronized (myLock) {
            ContainerUtil.addAll(myVFSChangedModules, modules);
          }
        }
      });
    }
  }

  void compilerActivityStarted() {
    final ExcludeEntryDescription[] excludeEntryDescriptions =
      CompilerConfiguration.getInstance(myService.getProject()).getExcludedEntriesConfiguration().getExcludeEntryDescriptions();
    synchronized (myLock) {
      myCompilationPhase = true;
      Collections.addAll(myExcludedDescriptions, excludeEntryDescriptions);
      myExcludedFilesScope = null;
      myCompilationAffectedModules.clear();
    }
  }

  void upToDateChecked(boolean isUpToDate) {
    final Module[] modules = ReadAction.compute(() -> {
      final Project project = myService.getProject();
      if (project.isDisposed()) {
        return null;
      }
      return ModuleManager.getInstance(project).getModules();
    });
    if (modules == null) return;
    compilationFinished(() -> {
      if (!isUpToDate) {
        ContainerUtil.addAll(myVFSChangedModules, modules);
      }
    });
  }

  void compilerActivityFinished() {
    final List<Module> compiledModules = ReadAction.compute(() -> {
      final Project project = myService.getProject();
      if (project.isDisposed()) {
        return null;
      }
      final ModuleManager moduleManager = ModuleManager.getInstance(myService.getProject());
      return myCompilationAffectedModules.stream().map(moduleManager::findModuleByName).collect(Collectors.toList());
    });
    compilationFinished(() -> {
      if (compiledModules == null) return;
      myVFSChangedModules.removeAll(compiledModules);
    });
  }

  private void compilationFinished(@NotNull Runnable action) {
    ExcludeEntryDescription[] descriptions;
    synchronized (myLock) {
      myCompilationPhase = false;
      action.run();
      myVFSChangedModules.addAll(myChangedModulesDuringCompilation);
      myChangedModulesDuringCompilation.clear();
      descriptions = myExcludedDescriptions.toArray(new ExcludeEntryDescription[0]);
      myExcludedDescriptions.clear();
    }
    myCompilationAffectedModules.clear();
    myExcludedFilesScope = ExcludedFromCompileFilesUtil.getExcludedFilesScope(descriptions, myService.getFileTypes(), myService.getProject(), myService.getFileIndex());
  }

  @NotNull
  GlobalSearchScope getDirtyScope() {
    final Project project = myService.getProject();
    return ReadAction.compute(() -> {
      synchronized (myLock) {
        if (myCompilationPhase) {
          return GlobalSearchScope.allScope(project);
        }
        if (project.isDisposed()) throw new ProcessCanceledException();
        return CachedValuesManager.getManager(project).getCachedValue(this, () ->
          CachedValueProvider.Result
            .create(calculateDirtyScope(), PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.getInstance(), myService));
      }
    });
  }

  @NotNull
  private GlobalSearchScope calculateDirtyScope() {
    final Set<Module> dirtyModules = getAllDirtyModules();
    if (dirtyModules.isEmpty()) return myExcludedFilesScope;
    GlobalSearchScope dirtyModuleScope = GlobalSearchScope.union(dirtyModules
                                                                   .stream()
                                                                   .map(Module::getModuleWithDependentsScope)
                                                                   .toArray(GlobalSearchScope[]::new));
    return dirtyModuleScope.union(myExcludedFilesScope);
  }

  @NotNull
  Set<Module> getAllDirtyModules() {
    final Set<Module> dirtyModules = new THashSet<>(myVFSChangedModules);
    for (Document document : myFileDocManager.getUnsavedDocuments()) {
      final VirtualFile file = myFileDocManager.getFile(document);
      if (file == null) continue;
      final Module m = getModuleForSourceContentFile(file);
      if (m != null) dirtyModules.add(m);
    }
    for (Document document : myPsiDocManager.getUncommittedDocuments()) {
      final PsiFile psiFile = myPsiDocManager.getPsiFile(document);
      if (psiFile == null) continue;
      final VirtualFile file = psiFile.getVirtualFile();
      if (file == null) continue;
      final Module m = getModuleForSourceContentFile(file);
      if (m != null) dirtyModules.add(m);
    }
    return dirtyModules;
  }

  public boolean contains(@NotNull VirtualFile file) {
    return getDirtyScope().contains(file);
  }

  @Override
  public void after(@NotNull List<? extends VFileEvent> events) {
    for (VFileEvent event : events) {
      if (event instanceof VFileCreateEvent || event instanceof VFileCopyEvent || event instanceof VFileMoveEvent) {
        VirtualFile file = event.getFile();
        if (file != null) {
          fileChanged(file);
        }
      }
      else if (event instanceof VFilePropertyChangeEvent) {
        VFilePropertyChangeEvent pce = (VFilePropertyChangeEvent)event;
        String propertyName = pce.getPropertyName();
        if (VirtualFile.PROP_NAME.equals(propertyName) || VirtualFile.PROP_SYMLINK_TARGET.equals(propertyName)) {
          fileChanged(pce.getFile());
        }
      }
    }
  }

  @Override
  public void before(@NotNull List<? extends VFileEvent> events) {
    for (VFileEvent event : events) {
      if (event instanceof VFileDeleteEvent || event instanceof VFileMoveEvent || event instanceof VFileContentChangeEvent) {
        VirtualFile file = event.getFile();
        fileChanged(file);
      }
      else if (event instanceof VFilePropertyChangeEvent) {
        VFilePropertyChangeEvent pce = (VFilePropertyChangeEvent)event;
        String propertyName = pce.getPropertyName();
        if (VirtualFile.PROP_NAME.equals(propertyName) || VirtualFile.PROP_SYMLINK_TARGET.equals(propertyName)) {
          final String path = pce.getFile().getPath();
          for (Module module : ModuleManager.getInstance(myService.getProject()).getModules()) {
            if (FileUtil.isAncestor(path, module.getModuleFilePath(), true)) {
              addToDirtyModules(module);
            }
          }
        }
      }
    }
  }

  void installVFSListener() {
    myService.getProject().getMessageBus().connect().subscribe(VirtualFileManager.VFS_CHANGES, this);
  }

  private void fileChanged(@NotNull VirtualFile file) {
    final Module module = getModuleForSourceContentFile(file);
    if (module != null) {
      addToDirtyModules(module);
    }
  }
  private void addToDirtyModules(@NotNull Module module) {
    synchronized (myLock) {
      if (myCompilationPhase) {
        myChangedModulesDuringCompilation.add(module);
      }
      else {
        myVFSChangedModules.add(module);
      }
    }
  }

  private Module getModuleForSourceContentFile(@NotNull VirtualFile file) {
    FileType fileType = myFileTypeRegistry.getFileTypeByFileName(file.getNameSequence());
    if (myService.getFileTypes().contains(fileType) && myService.getFileIndex().isInSourceContent(file)) {
      return myService.getFileIndex().getModuleForFile(file);
    }
    return null;
  }

  @TestOnly
  @NotNull
  Set<Module> getAllDirtyModulesForTest() {
    synchronized (myLock) {
      return getAllDirtyModules();
    }
  }

  @SuppressWarnings("unchecked")
  @NotNull
  DirtyScopeTestInfo getState() {
    synchronized (myLock) {
      final Module[] vfsChangedModules = myVFSChangedModules.toArray(Module.EMPTY_ARRAY);
      final List<Module> unsavedChangedModuleList = new ArrayList<>(getAllDirtyModules());
      ContainerUtil.removeAll(unsavedChangedModuleList, vfsChangedModules);
      final Module[] unsavedChangedModules = unsavedChangedModuleList.toArray(Module.EMPTY_ARRAY);
      final List<VirtualFile> excludedFiles = myExcludedFilesScope instanceof Iterable ? ContainerUtil.newArrayList((Iterable<VirtualFile>)myExcludedFilesScope) : Collections.emptyList();
      return new DirtyScopeTestInfo(vfsChangedModules, unsavedChangedModules, excludedFiles.toArray(VirtualFile.EMPTY_ARRAY), getDirtyScope());
    }
  }
}
