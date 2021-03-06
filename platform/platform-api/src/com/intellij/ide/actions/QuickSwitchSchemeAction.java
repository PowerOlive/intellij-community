/*
 * Copyright 2000-2016 JetBrains s.r.o.
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

package com.intellij.ide.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.util.ui.EmptyIcon;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * @author max
 */
public abstract class QuickSwitchSchemeAction extends AnAction implements DumbAware {

  protected static final Icon ourCurrentAction = AllIcons.Diff.CurrentLine;
  protected static final Icon ourNotCurrentAction = EmptyIcon.create(ourCurrentAction.getIconWidth(), ourCurrentAction.getIconHeight());

  protected String myActionPlace = ActionPlaces.UNKNOWN;

  private final boolean myShowPopupWithNoActions;

  protected QuickSwitchSchemeAction() {
    this(false);
  }

  protected QuickSwitchSchemeAction(boolean showPopupWithNoActions) {
    myShowPopupWithNoActions = showPopupWithNoActions;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getData(CommonDataKeys.PROJECT);
    DefaultActionGroup group = new DefaultActionGroup();
    fillActions(project, group, e.getDataContext());
    showPopup(e, group);
  }

  protected abstract void fillActions(Project project, @NotNull DefaultActionGroup group, @NotNull DataContext dataContext);

  private void showPopup(AnActionEvent e, DefaultActionGroup group) {
    if (!myShowPopupWithNoActions && group.getChildrenCount() == 0) return;
    JBPopupFactory.ActionSelectionAid aid = getAidMethod();

    ListPopup popup = JBPopupFactory.getInstance().createActionGroupPopup(
      getPopupTitle(e), group, e.getDataContext(), aid, true, null, -1,
      (a) -> a.getTemplatePresentation().getIcon() != ourCurrentAction,
      myActionPlace);

    showPopup(e, popup);
  }

  protected void showPopup(AnActionEvent e, ListPopup popup) {
    Project project = e.getProject();
    if (project != null) {
      popup.showCenteredInCurrentWindow(project);
    }
    else {
      popup.showInBestPositionFor(e.getDataContext());
    }
  }

  protected JBPopupFactory.ActionSelectionAid getAidMethod() {
    return JBPopupFactory.ActionSelectionAid.NUMBERING;
  }

  protected String getPopupTitle(@NotNull AnActionEvent e) {
    return e.getPresentation().getText();
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    super.update(e);
    e.getPresentation().setEnabled(e.getData(CommonDataKeys.PROJECT) != null && isEnabled());
  }

  protected boolean isEnabled() {
    return true;
  }
}
