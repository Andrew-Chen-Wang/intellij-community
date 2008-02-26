package com.intellij.execution.ui.layout.impl;

import com.intellij.util.xmlb.XmlSerializer;
import com.intellij.openapi.util.Key;
import com.intellij.execution.ui.layout.RunnerLayoutUi;
import org.jdom.Element;

public class View {

  public static final Key<String> ID = Key.create("ViewID");

  private String myID;

  private Tab myTab;
  private int myTabIndex;

  private RunnerLayoutUi.PlaceInGrid myPlaceInGrid;

  private boolean myMinimizedInGrid;

  public View(String id, Tab tab, final RunnerLayoutUi.PlaceInGrid placeInGrid, boolean minimizedInGrid) {
    myID = id;
    myTab = tab;
    myPlaceInGrid = placeInGrid;
    myMinimizedInGrid = minimizedInGrid;
  }

  public View(RunnerLayout settings, Element element) {
    XmlSerializer.deserializeInto(this, element);
    assignTab(settings.getOrCreateTab(myTabIndex));
  }

  public void write(final Element content) {
    content.addContent(XmlSerializer.serialize(this));
  }

  public Tab getTab() {
    return myTab;
  }

  public RunnerLayoutUi.PlaceInGrid getPlaceInGrid() {
    return myPlaceInGrid;
  }


  public boolean isMinimizedInGrid() {
    return myMinimizedInGrid;
  }

  public void setID(final String ID) {
    myID = ID;
  }

  public String getID() {
    return myID;
  }


  public void setMinimizedInGrid(final boolean minimizedInGrid) {
    myMinimizedInGrid = minimizedInGrid;
  }

  public void setPlaceInGrid(RunnerLayoutUi.PlaceInGrid placeInGrid) {
    myPlaceInGrid = placeInGrid;
  }

  public void assignTab(final Tab tab) {
    myTab = tab;
  }

  public int getTabIndex() {
    return myTab != null ? myTab.getIndex() : myTabIndex;
  }

  public void setTabIndex(final int tabIndex) {
    myTabIndex = tabIndex;
  }

  public static class Default {

    private String myID;
    private int myTabID;
    private RunnerLayoutUi.PlaceInGrid myPlaceInGrid;
    private boolean myMinimizedInGrid;

    public Default(final String ID, final int tabID, final RunnerLayoutUi.PlaceInGrid placeInGrid, final boolean minimizedInGrid) {
      myID = ID;
      myTabID = tabID;
      myPlaceInGrid = placeInGrid;
      myMinimizedInGrid = minimizedInGrid;
    }

    public View createView(RunnerLayout settings) {
      final Tab tab = myTabID == Integer.MAX_VALUE ? settings.createNewTab() : settings.getOrCreateTab(myTabID);
      return new View(myID, tab, myPlaceInGrid, myMinimizedInGrid);
    }

    public RunnerLayoutUi.PlaceInGrid getPlaceInGrid() {
      return myPlaceInGrid;
    }
  }

}
