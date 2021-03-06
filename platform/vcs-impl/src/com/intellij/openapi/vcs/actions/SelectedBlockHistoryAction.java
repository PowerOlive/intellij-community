// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.vcs.actions;

import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsBundle;
import com.intellij.openapi.vcs.history.VcsCachingHistory;
import com.intellij.openapi.vcs.history.VcsHistoryProvider;
import com.intellij.openapi.vcs.history.impl.VcsSelectionHistoryDialog;
import com.intellij.openapi.vcs.impl.BackgroundableActionLock;
import com.intellij.openapi.vcs.impl.VcsBackgroundableActions;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcsUtil.VcsSelection;
import com.intellij.vcsUtil.VcsSelectionUtil;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NotNull;

public class SelectedBlockHistoryAction extends AbstractVcsAction {

  protected boolean isEnabled(VcsContext context) {
    Project project = context.getProject();
    if (project == null) return false;

    VcsSelection selection = VcsSelectionUtil.getSelection(context);
    if (selection == null) return false;

    VirtualFile file = FileDocumentManager.getInstance().getFile(selection.getDocument());
    if (file == null) return false;
    FilePath filePath = VcsUtil.getFilePath(file);

    AbstractVcs activeVcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(file);
    if (activeVcs == null) return false;

    VcsHistoryProvider provider = activeVcs.getVcsBlockHistoryProvider();
    if (provider == null) return false;

    BackgroundableActionLock lock =
      VcsCachingHistory.getHistoryLock(activeVcs, VcsBackgroundableActions.HISTORY_FOR_SELECTION, filePath, null);
    if (lock.isLocked()) return false;

    if (!AbstractVcs.fileInVcsByFileStatus(project, filePath)) return false;
    return true;
  }

  @Override
  public void actionPerformed(@NotNull final VcsContext context) {
    try {
      final Project project = context.getProject();
      assert project != null;

      final VcsSelection selection = VcsSelectionUtil.getSelection(context);
      assert selection != null;

      final VirtualFile file = FileDocumentManager.getInstance().getFile(selection.getDocument());
      assert file != null;

      final AbstractVcs activeVcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(file);
      assert activeVcs != null;

      final VcsHistoryProvider provider = activeVcs.getVcsBlockHistoryProvider();
      assert provider != null;

      final int selectionStart = selection.getSelectionStartLineNumber();
      final int selectionEnd = selection.getSelectionEndLineNumber();

      VcsCachingHistory
        .collectInBackground(activeVcs, VcsUtil.getFilePath(file), VcsBackgroundableActions.HISTORY_FOR_SELECTION,
                         session -> {
                           if (session == null) return;
                           final VcsSelectionHistoryDialog vcsHistoryDialog =
                             new VcsSelectionHistoryDialog(project,
                                                           file,
                                                           selection.getDocument(),
                                                           provider,
                                                           session,
                                                           activeVcs,
                                                           Math.min(selectionStart, selectionEnd),
                                                           Math.max(selectionStart, selectionEnd),
                                                           selection.getDialogTitle());

                           vcsHistoryDialog.show();
                         });
    }
    catch (Exception exception) {
      reportError(exception);
    }
  }

  @Override
  protected void update(@NotNull VcsContext context, @NotNull Presentation presentation) {
    Editor editor = context.getEditor();
    if (editor == null) {
      presentation.setEnabledAndVisible(false);
      return;
    }

    presentation.setEnabled(isEnabled(context));
    VcsSelection selection = VcsSelectionUtil.getSelection(context);
    if (selection != null) {
      presentation.setText(selection.getActionName());
    }
  }

  protected static void reportError(Exception exception) {
    Messages.showMessageDialog(exception.getLocalizedMessage(), VcsBundle.message("message.title.could.not.load.file.history"), Messages.getErrorIcon());
  }
}
