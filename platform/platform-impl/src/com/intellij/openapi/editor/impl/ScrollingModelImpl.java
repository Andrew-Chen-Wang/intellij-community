// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.editor.impl;

import com.intellij.ide.RemoteDesktopService;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.VisibleAreaEvent;
import com.intellij.openapi.editor.event.VisibleAreaListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.ScrollingModelEx;
import com.intellij.openapi.editor.ex.util.EditorUtil;
import com.intellij.openapi.fileEditor.impl.text.AsyncEditorLoader;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.DirtyUI;
import com.intellij.ui.components.Interpolable;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.Animator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public final class ScrollingModelImpl implements ScrollingModelEx {
  private static final Logger LOG = Logger.getInstance(ScrollingModelImpl.class);

  @NotNull private final ScrollingModel.Supplier mySupplier;
  private final List<VisibleAreaListener> myVisibleAreaListeners = ContainerUtil.createLockFreeCopyOnWriteList();
  private final List<ScrollRequestListener> myScrollRequestListeners = ContainerUtil.createLockFreeCopyOnWriteList();

  private AnimatedScrollingRunnable myCurrentAnimationRequest;
  private boolean myAnimationDisabled;

  private int myAccumulatedXOffset = -1;
  private int myAccumulatedYOffset = -1;
  private boolean myAccumulateViewportChanges;
  private boolean myViewportPositioned;

  private final DocumentListener myDocumentListener = new DocumentListener() {
    @Override
    public void beforeDocumentChange(@NotNull DocumentEvent e) {
      if (!mySupplier.getEditor().getDocument().isInBulkUpdate()) {
        cancelAnimatedScrolling(true);
      }
    }
  };

  private final ChangeListener myViewportChangeListener = new MyChangeListener();

  public ScrollingModelImpl(EditorImpl editor) {
    this(new DefaultEditorSupplier(editor));
  }

  public ScrollingModelImpl(@NotNull ScrollingModel.Supplier supplier) {
    mySupplier = supplier;
    mySupplier.getScrollPane().getViewport().addChangeListener(myViewportChangeListener);
    mySupplier.getEditor().getDocument().addDocumentListener(myDocumentListener);
  }

  /**
   * Corrects viewport position if necessary on initial editor showing.
   *
   * @return {@code true} if the vertical viewport position has been adjusted; {@code false} otherwise
   */
  private boolean adjustVerticalOffsetIfNecessary() {
    Editor editor = mySupplier.getEditor();
    // There is a possible case that the editor is configured to show virtual space at file bottom and requested position is located
    // somewhere around. We don't want to position viewport in a way that most of its area is used to represent that virtual empty space.
    // So, we tweak vertical offset if necessary.
    int maxY = Math.max(editor.getLineHeight(), editor.getDocument().getLineCount() * editor.getLineHeight());
    int minPreferredY = maxY - getVisibleArea().height * 2 / 3;
    final int currentOffset = getVerticalScrollOffset();
    int offsetToUse = Math.min(minPreferredY, currentOffset);
    if (offsetToUse != currentOffset) {
      scroll(getHorizontalScrollOffset(), offsetToUse);
      return true;
    }
    return false;
  }

  @NotNull
  @Override
  public Rectangle getVisibleArea() {
    assertIsDispatchThread();
    return mySupplier.getScrollPane().getViewport().getViewRect();
  }

  @NotNull
  @Override
  public Rectangle getVisibleAreaOnScrollingFinished() {
    assertIsDispatchThread();
    if (EditorCoreUtil.isTrueSmoothScrollingEnabled()) {
      Rectangle viewRect = mySupplier.getScrollPane().getViewport().getViewRect();
      return new Rectangle(getOffset(getHorizontalScrollBar()), getOffset(getVerticalScrollBar()), viewRect.width, viewRect.height);
    }
    if (myCurrentAnimationRequest != null) {
      return myCurrentAnimationRequest.getTargetVisibleArea();
    }
    return getVisibleArea();
  }

  @Override
  public void scrollToCaret(@NotNull ScrollType scrollType) {
    if (LOG.isTraceEnabled()) {
      LOG.trace(new Throwable());
    }
    assertIsDispatchThread();

    Editor editor = mySupplier.getEditor();
    AsyncEditorLoader.performWhenLoaded(editor, () -> scrollTo(editor.getCaretModel().getVisualPosition(), scrollType));
  }

  private void scrollTo(@NotNull VisualPosition pos, @NotNull ScrollType scrollType) {
    Editor editor = mySupplier.getEditor();
    for (ScrollRequestListener listener : myScrollRequestListeners) {
      listener.scrollRequested(editor.visualToLogicalPosition(pos), scrollType);
    }
    Point targetLocation = mySupplier.getScrollingHelper().calculateScrollingLocation(editor, pos);
    scrollTo(targetLocation, scrollType);
  }

  private void scrollTo(@NotNull Point targetLocation, @NotNull ScrollType scrollType) {
    AnimatedScrollingRunnable canceledThread = cancelAnimatedScrolling(false);
    Rectangle viewRect = canceledThread != null ? canceledThread.getTargetVisibleArea() : getVisibleArea();
    Point p = calcOffsetsToScroll(targetLocation, scrollType, viewRect);
    scroll(p.x, p.y);
  }

  @Override
  public void scrollTo(@NotNull LogicalPosition pos, @NotNull ScrollType scrollType) {
    assertIsDispatchThread();
    Editor editor = mySupplier.getEditor();
    AsyncEditorLoader.performWhenLoaded(editor, () -> {
      for (ScrollRequestListener listener : myScrollRequestListeners) {
        listener.scrollRequested(pos, scrollType);
      }
      scrollTo(mySupplier.getScrollingHelper().calculateScrollingLocation(editor, pos), scrollType);
    });
  }

  private static void assertIsDispatchThread() {
    ApplicationManager.getApplication().assertIsDispatchThread();
  }

  @Override
  public void runActionOnScrollingFinished(@NotNull Runnable action) {
    assertIsDispatchThread();

    if (myCurrentAnimationRequest != null) {
      myCurrentAnimationRequest.addPostRunnable(action);
      return;
    }

    action.run();
  }

  public boolean isAnimationEnabled() {
    return !myAnimationDisabled;
  }

  @Override
  public void disableAnimation() {
    myAnimationDisabled = true;
  }

  @Override
  public void enableAnimation() {
    myAnimationDisabled = false;
  }

  private Point calcOffsetsToScroll(Point targetLocation, ScrollType scrollType, Rectangle viewRect) {
    Editor editor = mySupplier.getEditor();
    if (editor.getSettings().isRefrainFromScrolling() && viewRect.contains(targetLocation)) {
      if (scrollType == ScrollType.CENTER ||
          scrollType == ScrollType.CENTER_DOWN ||
          scrollType == ScrollType.CENTER_UP) {
        scrollType = ScrollType.RELATIVE;
      }
    }

    int spaceWidth = EditorUtil.getSpaceWidth(Font.PLAIN, editor);
    int xInsets = editor.getSettings().getAdditionalColumnsCount() * spaceWidth;

    int hOffset = scrollType == ScrollType.CENTER ||
                  scrollType == ScrollType.CENTER_DOWN ||
                  scrollType == ScrollType.CENTER_UP ? 0 : viewRect.x;
    if (targetLocation.x < hOffset) {
      int inset = 4 * spaceWidth;
      if (scrollType == ScrollType.MAKE_VISIBLE && targetLocation.x < viewRect.width - inset) {
        // if we need to scroll to the left to make target position visible,
        // let's scroll to the leftmost position (if that will make caret visible)
        hOffset = 0;
      }
      else {
        hOffset = Math.max(0, targetLocation.x - inset);
      }
    }
    else if (viewRect.width > 0 && targetLocation.x >= hOffset + viewRect.width) {
      hOffset = targetLocation.x - Math.max(0, viewRect.width - xInsets);
    }

    // the following code tries to keeps 1 line above and 1 line below if available in viewRect
    int lineHeight = editor.getLineHeight();
    // to avoid 'hysteresis', minAcceptableY should be always less or equal to maxAcceptableY
    int minAcceptableY = viewRect.y + Math.max(0, Math.min(lineHeight, viewRect.height - 3 * lineHeight));
    int maxAcceptableY = viewRect.y + (viewRect.height <= lineHeight ? 0 :
                                       viewRect.height - (viewRect.height <= 2 * lineHeight ? lineHeight : 2 * lineHeight));
    int scrollUpBy = minAcceptableY - targetLocation.y;
    int scrollDownBy = targetLocation.y - maxAcceptableY;
    int centerPosition = targetLocation.y - viewRect.height / 3;

    int vOffset = viewRect.y;
    if (scrollType == ScrollType.CENTER) {
      vOffset = centerPosition;
    }
    else if (scrollType == ScrollType.CENTER_UP) {
      if (scrollUpBy > 0 || scrollDownBy > 0 || vOffset > centerPosition) {
        vOffset = centerPosition;
      }
    }
    else if (scrollType == ScrollType.CENTER_DOWN) {
      if (scrollUpBy > 0 || scrollDownBy > 0 || vOffset < centerPosition) {
        vOffset = centerPosition;
      }
    }
    else if (scrollType == ScrollType.RELATIVE) {
      if (scrollUpBy > 0) {
        vOffset = viewRect.y - scrollUpBy;
      }
      else if (scrollDownBy > 0) {
        vOffset = viewRect.y + scrollDownBy;
      }
    }
    else if (scrollType == ScrollType.MAKE_VISIBLE) {
      if (scrollUpBy > 0 || scrollDownBy > 0) {
        vOffset = centerPosition;
      }
    }

    JScrollPane scrollPane = mySupplier.getScrollPane();
    hOffset = Math.max(0, hOffset);
    vOffset = Math.max(0, vOffset);
    hOffset = Math.min(scrollPane.getHorizontalScrollBar().getMaximum() - getExtent(scrollPane.getHorizontalScrollBar()), hOffset);
    vOffset = Math.min(scrollPane.getVerticalScrollBar().getMaximum() - getExtent(scrollPane.getVerticalScrollBar()), vOffset);

    return new Point(hOffset, vOffset);
  }

  @Nullable
  public JScrollBar getVerticalScrollBar() {
    assertIsDispatchThread();
    JScrollPane scrollPane = mySupplier.getScrollPane();
    return scrollPane.getVerticalScrollBar();
  }

  @Nullable
  public JScrollBar getHorizontalScrollBar() {
    assertIsDispatchThread();
    return mySupplier.getScrollPane().getHorizontalScrollBar();
  }

  @Override
  public int getVerticalScrollOffset() {
    return getOffset(getVerticalScrollBar());
  }

  @Override
  public int getHorizontalScrollOffset() {
    return getOffset(getHorizontalScrollBar());
  }

  private static int getOffset(JScrollBar scrollBar) {
    return scrollBar == null ? 0 :
           scrollBar instanceof Interpolable ? ((Interpolable)scrollBar).getTargetValue() : scrollBar.getValue();
  }

  private static int getExtent(JScrollBar scrollBar) {
    return scrollBar == null ? 0 : scrollBar.getModel().getExtent();
  }

  @Override
  public void scrollVertically(int scrollOffset) {
    scroll(getHorizontalScrollOffset(), scrollOffset);
  }

  private void _scrollVertically(int scrollOffset) {
    assertIsDispatchThread();

    JScrollBar scrollbar = mySupplier.getScrollPane().getVerticalScrollBar();

    scrollbar.setValue(scrollOffset);
  }

  @Override
  public void scrollHorizontally(int scrollOffset) {
    scroll(scrollOffset, getVerticalScrollOffset());
  }

  private void _scrollHorizontally(int scrollOffset) {
    assertIsDispatchThread();

    JScrollBar scrollbar = mySupplier.getScrollPane().getHorizontalScrollBar();
    scrollbar.setValue(scrollOffset);
  }

  @Override
  public void scroll(int hOffset, int vOffset) {
    if (myAccumulateViewportChanges) {
      myAccumulatedXOffset = hOffset;
      myAccumulatedYOffset = vOffset;
      return;
    }

    cancelAnimatedScrolling(false);

    Editor editor = mySupplier.getEditor();
    boolean useAnimation;
    //System.out.println("myCurrentCommandStart - myLastCommandFinish = " + (myCurrentCommandStart - myLastCommandFinish));
    if (!editor.getSettings().isAnimatedScrolling() || myAnimationDisabled || RemoteDesktopService.isRemoteSession()) {
      useAnimation = false;
    }
    else if (CommandProcessor.getInstance().getCurrentCommand() == null) {
      useAnimation = editor.getComponent().isShowing();
    }
    else {
      VisibleEditorsTracker editorTracker = VisibleEditorsTracker.getInstance();
      if (editorTracker.getCurrentCommandStart() - editorTracker.getLastCommandFinish() <
          AnimatedScrollingRunnable.SCROLL_DURATION) {
        useAnimation = false;
      }
      else {
        useAnimation = editorTracker.wasEditorVisibleOnCommandStart(editor);
      }
    }

    cancelAnimatedScrolling(false);

    if (useAnimation) {
      //System.out.println("scrollToAnimated: " + endVOffset);

      int startHOffset = getHorizontalScrollOffset();
      int startVOffset = getVerticalScrollOffset();

      if (startHOffset == hOffset && startVOffset == vOffset) {
        return;
      }

      //System.out.println("startVOffset = " + startVOffset);

      try {
        myCurrentAnimationRequest = new AnimatedScrollingRunnable(startHOffset, startVOffset, hOffset, vOffset);
      }
      catch (NoAnimationRequiredException e) {
        _scrollHorizontally(hOffset);
        _scrollVertically(vOffset);
      }
    }
    else {
      _scrollHorizontally(hOffset);
      _scrollVertically(vOffset);
    }
  }

  @Override
  public void addVisibleAreaListener(@NotNull VisibleAreaListener listener) {
    myVisibleAreaListeners.add(listener);
  }

  @Override
  public void removeVisibleAreaListener(@NotNull VisibleAreaListener listener) {
    boolean success = myVisibleAreaListeners.remove(listener);
    LOG.assertTrue(success);
  }

  public void finishAnimation() {
    cancelAnimatedScrolling(true);
  }

  @Nullable
  private AnimatedScrollingRunnable cancelAnimatedScrolling(boolean scrollToTarget) {
    AnimatedScrollingRunnable request = myCurrentAnimationRequest;
    myCurrentAnimationRequest = null;
    if (request != null) {
      request.cancel(scrollToTarget);
    }
    return request;
  }

  public void dispose() {
    mySupplier.getEditor().getDocument().removeDocumentListener(myDocumentListener);
    mySupplier.getScrollPane().getViewport().removeChangeListener(myViewportChangeListener);
  }

  public void beforeModalityStateChanged() {
    cancelAnimatedScrolling(true);
  }

  public boolean isScrollingNow() {
    return myCurrentAnimationRequest != null;
  }

  @Override
  public void accumulateViewportChanges() {
    myAccumulateViewportChanges = true;
  }

  @Override
  public void flushViewportChanges() {
    myAccumulateViewportChanges = false;
    if (myAccumulatedXOffset >= 0 && myAccumulatedYOffset >= 0) {
      scroll(myAccumulatedXOffset, myAccumulatedYOffset);
      myAccumulatedXOffset = myAccumulatedYOffset = -1;
      cancelAnimatedScrolling(true);
    }
  }

  void onBulkDocumentUpdateStarted() {
    cancelAnimatedScrolling(true);
  }

  public void addScrollRequestListener(ScrollRequestListener scrollRequestListener, Disposable parentDisposable) {
    myScrollRequestListeners.add(scrollRequestListener);
    Disposer.register(parentDisposable, () -> myScrollRequestListeners.remove(scrollRequestListener));
  }

  private final class AnimatedScrollingRunnable {
    private static final int SCROLL_DURATION = 100;
    private static final int SCROLL_INTERVAL = 10;

    private final int myStartHOffset;
    private final int myStartVOffset;
    private final int myEndHOffset;
    private final int myEndVOffset;

    private final ArrayList<Runnable> myPostRunnables = new ArrayList<>();

    private final int myMaxDistToScroll;
    private final double myTotalDist;

    private final int myStepCount;
    private final double myPow;
    private final Animator myAnimator;

    AnimatedScrollingRunnable(int startHOffset,
                                     int startVOffset,
                                     int endHOffset,
                                     int endVOffset) throws NoAnimationRequiredException {
      myStartHOffset = startHOffset;
      myStartVOffset = startVOffset;
      myEndHOffset = endHOffset;
      myEndVOffset = endVOffset;

      int HDist = Math.abs(myEndHOffset - myStartHOffset);
      int VDist = Math.abs(myEndVOffset - myStartVOffset);

      Editor editor = mySupplier.getEditor();
      myMaxDistToScroll = editor.getLineHeight() * 50;
      myTotalDist = Math.sqrt((double)HDist * HDist + (double)VDist * VDist);
      double scrollDist = Math.min(myTotalDist, myMaxDistToScroll);
      int animationDuration = calcAnimationDuration();
      if (animationDuration < SCROLL_INTERVAL * 2) {
        throw new NoAnimationRequiredException();
      }
      myStepCount = animationDuration / SCROLL_INTERVAL - 1;
      double firstStepTime = 1.0 / myStepCount;
      double firstScrollDist = 5.0;
      if (myTotalDist > scrollDist) {
        firstScrollDist *= myTotalDist / scrollDist;
        firstScrollDist = Math.min(firstScrollDist, editor.getLineHeight() * 5);
      }
      myPow = scrollDist > 0 ? setupPow(firstStepTime, firstScrollDist / scrollDist) : 1;

      myAnimator = new Animator("Animated scroller", myStepCount, SCROLL_DURATION, false, true) {
        @Override
        public void paintNow(int frame, int totalFrames, int cycle) {
          double time = (frame + 1.0) / totalFrames;
          double fraction = timeToFraction(time);

          final int hOffset = (int)(myStartHOffset + (myEndHOffset - myStartHOffset) * fraction + 0.5);
          final int vOffset = (int)(myStartVOffset + (myEndVOffset - myStartVOffset) * fraction + 0.5);

          _scrollHorizontally(hOffset);
          _scrollVertically(vOffset);
        }

        @Override
        protected void paintCycleEnd() {
          if (!isDisposed()) { // Animator will invoke paintCycleEnd() even if it was disposed
            finish(true);
          }
        }
      };

      myAnimator.resume();
    }

    @NotNull
    Rectangle getTargetVisibleArea() {
      Rectangle viewRect = getVisibleArea();
      return new Rectangle(myEndHOffset, myEndVOffset, viewRect.width, viewRect.height);
    }

    public void cancel(boolean scrollToTarget) {
      assertIsDispatchThread();
      finish(scrollToTarget);
    }

    void addPostRunnable(Runnable runnable) {
      myPostRunnables.add(runnable);
    }

    private void finish(boolean scrollToTarget) {
      if (scrollToTarget || !myPostRunnables.isEmpty()) {
        _scrollHorizontally(myEndHOffset);
        _scrollVertically(myEndVOffset);
        executePostRunnables();
      }

      Disposer.dispose(myAnimator);
      if (myCurrentAnimationRequest == this) {
        myCurrentAnimationRequest = null;
      }
    }

    private void executePostRunnables() {
      for (Runnable runnable : myPostRunnables) {
        runnable.run();
      }
    }

    private double timeToFraction(double time) {
      if (time > 0.5) {
        return 1 - timeToFraction(1 - time);
      }

      double fraction = Math.pow(time * 2, myPow) / 2;

      if (myTotalDist > myMaxDistToScroll) {
        fraction *= myMaxDistToScroll / myTotalDist;
      }

      return fraction;
    }

    private double setupPow(double inTime, double moveBy) {
      double pow = Math.log(2 * moveBy) / Math.log(2 * inTime);
      if (pow < 1) pow = 1;
      return pow;
    }

    private int calcAnimationDuration() {
      int lineHeight = mySupplier.getEditor().getLineHeight();
      double lineDist = myTotalDist / lineHeight;
      double part = (lineDist - 1) / 10;
      if (part > 1) part = 1;
      //System.out.println("duration = " + duration);
      return (int)(part * SCROLL_DURATION);
    }
  }

  private static final class NoAnimationRequiredException extends Exception {
  }

  @DirtyUI
  private final class MyChangeListener implements ChangeListener {
    private Rectangle myLastViewRect;

    @DirtyUI
    @Override
    public void stateChanged(ChangeEvent event) {
      Rectangle viewRect = getVisibleArea();
      VisibleAreaEvent visibleAreaEvent = new VisibleAreaEvent(mySupplier.getEditor(), myLastViewRect, viewRect);
      if (!myViewportPositioned && viewRect.height > 0) {
        myViewportPositioned = true;
        if (adjustVerticalOffsetIfNecessary()) {
          return;
        }
      }
      myLastViewRect = viewRect;
      for (VisibleAreaListener listener : myVisibleAreaListeners) {
        listener.visibleAreaChanged(visibleAreaEvent);
      }
    }
  }

  private static final class DefaultEditorSupplier implements ScrollingModel.Supplier {

    private final EditorEx myEditor;

    private final ScrollingHelper myScrollingHelper = new ScrollingHelper() {
      @Override
      public @NotNull Point calculateScrollingLocation(@NotNull Editor editor, @NotNull VisualPosition pos) {
        return editor.visualPositionToXY(pos);
      }

      @Override
      public @NotNull Point calculateScrollingLocation(@NotNull Editor editor, @NotNull LogicalPosition pos) {
        return editor.logicalPositionToXY(pos);
      }
    };

    private DefaultEditorSupplier(@NotNull EditorEx editor) { myEditor = editor; }

    @Override
    public @NotNull Editor getEditor() {
      return myEditor;
    }

    @Override
    public @NotNull JScrollPane getScrollPane() {
      return myEditor.getScrollPane();
    }

    @Override
    public @NotNull ScrollingHelper getScrollingHelper() {
      return myScrollingHelper;
    }
  }
}
