/*
 * Copyright 2013-2015 Sergey Ignatov, Alexander Zolotov, Mihai Toader, Florin Patan
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

package com.goide.dlv;

import com.goide.GoFileType;
import com.goide.GoLanguage;
import com.goide.dlv.breakpoint.DlvBreakpointHandler;
import com.goide.dlv.breakpoint.DlvBreakpointProperties;
import com.goide.dlv.protocol.DlvRequest;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.util.Consumer;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.io.socketConnection.ConnectionStatus;
import com.intellij.util.io.socketConnection.SocketConnectionListener;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointHandler;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProviderBase;
import org.intellij.lang.annotations.MagicConstant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.debugger.DebugProcessImpl;
import org.jetbrains.debugger.connection.RemoteVmConnection;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.goide.dlv.protocol.DlvApi.*;

public final class DlvDebugProcess extends DebugProcessImpl<RemoteVmConnection> implements Disposable {
  public static final Key<Integer> ID = Key.create("DLV_BP_ID");
  private final AtomicBoolean breakpointsInitiated = new AtomicBoolean();
  private final AtomicBoolean connectedListenerAdded = new AtomicBoolean();
  private final Map<Integer, XBreakpoint<DlvBreakpointProperties>> breakpoints = ContainerUtil.newConcurrentMap();
  static final Consumer<Throwable> THROWABLE_CONSUMER = new Consumer<Throwable>() {
    @Override
    public void consume(@NotNull Throwable throwable) {
      throwable.printStackTrace();
    }
  };

  private final Consumer<DebuggerState> myStateConsumer = new Consumer<DebuggerState>() {
    @Override
    public void consume(@Nullable final DebuggerState o) {
      if (o == null || o.exited) {
        getSession().stop();
        return;
      }

      final XBreakpoint<DlvBreakpointProperties> find = findBreak(o.breakPoint);
      Promise<List<Location>> stackPromise = send(new DlvRequest.Stacktrace());
      stackPromise.processed(new Consumer<List<Location>>() {
        @Override
        public void consume(@NotNull List<Location> locations) {
          DlvSuspendContext context = new DlvSuspendContext(o.currentThread.id, locations, getProcessor());
          if (find == null) {
            getSession().positionReached(context);
          }
          else {
            getSession().breakpointReached(find, null, context);
          }
        }
      });
      stackPromise.rejected(THROWABLE_CONSUMER);
    }

    @Nullable
    private XBreakpoint<DlvBreakpointProperties> findBreak(@Nullable Breakpoint point) {
      return point == null ? null : breakpoints.get(point.id);
    }
  };

  @NotNull
  private <T> Promise<T> send(@NotNull DlvRequest<T> request) {
    return getProcessor().send(request);
  }

  @NotNull
  private DlvCommandProcessor getProcessor() {
    return ((DlvVm)getVm()).getCommandProcessor();
  }

  public DlvDebugProcess(@NotNull XDebugSession session, @NotNull RemoteVmConnection connection) {
    super(session, connection, new MyEditorsProvider(), null, null);
    breakpointHandlers = new XBreakpointHandler[]{new DlvBreakpointHandler(this)};
  }

  @Override
  protected boolean isVmStepOutCorrect() {
    return false;
  }

  @Override
  public void dispose() {
    // todo
  }

  @Override
  public boolean checkCanInitBreakpoints() {
    if (connection.getState().getStatus() == ConnectionStatus.CONNECTED) {
      // breakpointsInitiated could be set in another thread and at this point work (init breakpoints) could be not yet performed
      return initBreakpointHandlersAndSetBreakpoints(false);
    }

    if (connectedListenerAdded.compareAndSet(false, true)) {
      connection.addListener(new SocketConnectionListener() {
        @Override
        public void statusChanged(@NotNull ConnectionStatus status) {
          if (status == ConnectionStatus.CONNECTED) {
            initBreakpointHandlersAndSetBreakpoints(true);
          }
        }
      });
    }
    return false;
  }

  private boolean initBreakpointHandlersAndSetBreakpoints(boolean setBreakpoints) {
    if (!breakpointsInitiated.compareAndSet(false, true)) return false;

    assert getVm() != null : "Vm should be initialized";

    if (setBreakpoints) {
      doSetBreakpoints();
      resume();
    }

    return true;
  }

  private void doSetBreakpoints() {
    AccessToken token = ReadAction.start();
    try {
      getSession().initBreakpoints();
    }
    finally {
      token.finish();
    }
  }

  public void addBreakpoint(@NotNull final XLineBreakpoint<DlvBreakpointProperties> breakpoint) {
    XSourcePosition breakpointPosition = breakpoint.getSourcePosition();
    if (breakpointPosition == null) return;
    VirtualFile file = breakpointPosition.getFile();
    int line = breakpointPosition.getLine();
    Promise<Breakpoint> promise = send(new DlvRequest.SetBreakpoint(file.getCanonicalPath(), line + 1));
    promise.processed(new Consumer<Breakpoint>() {
      @Override
      public void consume(@Nullable Breakpoint b) {
        if (b != null) {
          breakpoint.putUserData(ID, b.id);
          breakpoints.put(b.id, breakpoint);
          getSession().updateBreakpointPresentation(breakpoint, AllIcons.Debugger.Db_verified_breakpoint, null);
        }
      }
    });
    promise.rejected(new Consumer<Throwable>() {
      @Override
      public void consume(@Nullable Throwable t) {
        getSession().updateBreakpointPresentation(breakpoint, AllIcons.Debugger.Db_invalid_breakpoint, t == null ? null : t.getMessage());
      }
    });
  }

  public void removeBreakpoint(@NotNull XLineBreakpoint<DlvBreakpointProperties> breakpoint) {
    XSourcePosition breakpointPosition = breakpoint.getSourcePosition();
    if (breakpointPosition == null) return;
    Integer id = breakpoint.getUserData(ID);
    if (id == null) return; // obsolete
    breakpoint.putUserData(ID, null);
    breakpoints.remove(id);
    Promise<Breakpoint> promise = send(new DlvRequest.ClearBreakpoint(id));
    promise.rejected(THROWABLE_CONSUMER);
  }

  private void command(@NotNull @MagicConstant(stringValues = {NEXT, CONTINUE, HALT, SWITCH_THREAD, STEP}) String name) {
    Promise<DebuggerState> promise = send(new DlvRequest.Command(name));
    promise.processed(myStateConsumer);
    promise.rejected(THROWABLE_CONSUMER);
  }

  @Override
  public void startStepOver() {
    command(NEXT);
  }

  @Override
  public void startStepInto() {
    command(STEP);
  }

  @Override
  public void startStepOut() {
    // todo
  }

  @Override
  public void resume() {
    command(CONTINUE);
  }

  @Override
  public void runToPosition(@NotNull XSourcePosition position) {
    // todo
  }

  @Override
  public void stop() {
    getSession().stop();
  }

  private static class MyEditorsProvider extends XDebuggerEditorsProviderBase {
    @NotNull
    @Override
    public FileType getFileType() {
      return GoFileType.INSTANCE;
    }

    @Override
    protected PsiFile createExpressionCodeFragment(@NotNull Project project,
                                                   @NotNull String text,
                                                   PsiElement context,
                                                   boolean isPhysical) {
      return PsiFileFactory.getInstance(project).createFileFromText("a.go", GoLanguage.INSTANCE, text);
    }
  }
}