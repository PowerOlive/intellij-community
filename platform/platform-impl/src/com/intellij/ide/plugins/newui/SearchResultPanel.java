// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.plugins.newui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Alexander Lobas
 */
public abstract class SearchResultPanel {
  public final SearchPopupController controller;
  public final int tabIndex;
  public final int backTabIndex;

  private final PluginsGroupComponent myPanel;
  private final PluginsGroup myGroup = new PluginsGroup("Search Results");
  private String myQuery;
  private AtomicBoolean myRunQuery;
  private boolean myEmpty = true;

  public SearchResultPanel(@Nullable SearchPopupController controller,
                           @NotNull PluginsGroupComponent panel,
                           int tabIndex,
                           int backTabIndex) {
    this.controller = controller;
    myPanel = panel;
    this.tabIndex = tabIndex;
    this.backTabIndex = backTabIndex;
    panel.getEmptyText().setText("Nothing to show");
  }

  @NotNull
  public JComponent createScrollPane() {
    JBScrollPane pane = new JBScrollPane(myPanel);
    pane.setBorder(JBUI.Borders.empty());
    return pane;
  }

  public boolean isEmpty() {
    return myEmpty;
  }

  @NotNull
  public String getQuery() {
    return StringUtil.defaultIfEmpty(myQuery, "");
  }

  public void setQuery(@NotNull String query) {
    if (query.equals(myQuery)) {
      return;
    }

    if (myRunQuery != null) {
      myRunQuery.set(false);
      myRunQuery = null;
      loading(false);
    }

    removeGroup();
    myQuery = query;

    if (!(myEmpty = query.isEmpty())) {
      handleQuery(query);
    }
  }

  private void handleQuery(@NotNull String query) {
    myGroup.clear();

    if (myPanel instanceof PluginsGroupComponentWithProgress) {
      loading(true);

      AtomicBoolean runQuery = myRunQuery = new AtomicBoolean(true);

      ApplicationManager.getApplication().executeOnPooledThread(() -> {
        handleQuery(myQuery, myGroup);

        ApplicationManager.getApplication().invokeLater(() -> {
          if (!runQuery.get()) {
            return;
          }
          myRunQuery = null;

          loading(false);

          if (!myGroup.descriptors.isEmpty()) {
            myPanel.addGroup(myGroup);
            myGroup.titleWithCount();
          }

          myPanel.initialSelection();
          fullRepaint();
        }, ModalityState.any());
      });
    }
    else {
      handleQuery(query, myGroup);

      if (!myGroup.descriptors.isEmpty()) {
        myPanel.addGroup(myGroup);
        myGroup.titleWithCount();
        //myPanel.initialSelection();
      }

      fullRepaint();
    }
  }

  protected abstract void handleQuery(@NotNull String query, @NotNull PluginsGroup result);

  private void loading(boolean start) {
    PluginsGroupComponentWithProgress panel = (PluginsGroupComponentWithProgress)myPanel;
    if (start) {
      panel.startLoading();
    }
    else {
      panel.stopLoading();
    }
  }

  private void removeGroup() {
    if (myGroup.ui != null) {
      for (CellPluginComponent component : myGroup.ui.plugins) {
        component.close();
      }
      myPanel.removeGroup(myGroup);
      fullRepaint();
    }
  }

  private void fullRepaint() {
    myPanel.doLayout();
    myPanel.revalidate();
    myPanel.repaint();
  }
}