/*
 * Copyright (c) 2026, cwi-swat
 * All rights reserved. This file is licensed under the BSD 2-Clause
 * License -- see the LICENSE file in this directory.
 */
package nl.cwi.swat.rascal.intellij;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.redhat.devtools.lsp4ij.dap.breakpoints.DAPBreakpointHandler;
import com.redhat.devtools.lsp4ij.dap.breakpoints.DAPBreakpointProperties;
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.jetbrains.annotations.NotNull;

import static com.redhat.devtools.lsp4ij.dap.DAPIJUtils.getFileName;
import static com.redhat.devtools.lsp4ij.dap.DAPIJUtils.getFilePath;

/**
 * Fixes a real bug in LSP4IJ 0.21.0's {@code BreakpointHandlerBase.unregisterBreakpoint}:
 *
 * <pre>
 *   breakpoints.remove(breakpoint);
 *   sendBreakpoints(null,
 *           breakpoints.isEmpty() ? new TemporaryBreakpoint(sourcePosition, false) : null);
 * </pre>
 *
 * {@code breakpoints.isEmpty()} there checks the *entire project's* tracked
 * breakpoints across all files, not just the file the removed/disabled
 * breakpoint was in. So if any other breakpoint is still active anywhere
 * else in the project, disabling/removing a breakpoint in file A never
 * sends an updated `setBreakpoints` request for file A at all -- the debug
 * adapter (Rascal's RascalDebugAdapter, which fully replaces a file's
 * breakpoint list from whatever it's told each time) keeps honoring the
 * stale list, and execution keeps stopping at a breakpoint the user just
 * disabled.
 *
 * The natural fix -- construct the same {@code TemporaryBreakpoint} the
 * base class uses, scoped per-file instead of project-wide -- isn't
 * reachable from here: it's a protected record nested in a different
 * package, and Java only allows invoking a protected constructor from a
 * different package via {@code super(...)}, which doesn't apply to a
 * record no subclass of it could exist anyway. Simpler and just as
 * correct: tell every attached debug adapter directly, unconditionally,
 * that this file now has zero breakpoints, then let the normal rebuild
 * (`sendBreakpoints(null, null)`, i.e. the same path every other
 * register/unregister already goes through) immediately re-populate it
 * from the current list if any breakpoints remain in it -- a harmless,
 * instantly-superseded round trip in that case, and the fix everywhere
 * else.
 *
 * Filed upstream: https://github.com/redhat-developer/lsp4ij (not yet
 * reported as of this writing -- see this project's README.md).
 */
final class RascalBreakpointHandler extends DAPBreakpointHandler {

    private static final Logger LOG = Logger.getInstance(RascalBreakpointHandler.class);

    RascalBreakpointHandler(@NotNull XDebugSession debugSession,
                             @NotNull DebugAdapterDescriptor debugAdapterDescriptor,
                             @NotNull Project project) {
        super(debugSession, debugAdapterDescriptor, project);
        LOG.info("RascalBreakpointHandler constructed");
    }

    @Override
    public void unregisterBreakpoint(@NotNull XLineBreakpoint<DAPBreakpointProperties> breakpoint,
                                     boolean temporary) {
        LOG.info("unregisterBreakpoint called for " + breakpoint + " (temporary=" + temporary + ")");
        XSourcePosition sourcePosition = breakpoint.getSourcePosition();
        if (!supportsBreakpoint(breakpoint) || sourcePosition == null) {
            LOG.info("unregisterBreakpoint bailing early: supportsBreakpoint=" + supportsBreakpoint(breakpoint)
                + " sourcePosition=" + sourcePosition);
            return;
        }
        breakpoints.remove(breakpoint);

        Source source = new Source();
        source.setPath(getFilePath(sourcePosition.getFile()));
        source.setName(getFileName(sourcePosition.getFile()));
        SetBreakpointsArguments clearArgs = new SetBreakpointsArguments();
        clearArgs.setSource(source);
        clearArgs.setLines(new int[0]);
        clearArgs.setBreakpoints(new SourceBreakpoint[0]);
        LOG.info("Sending explicit empty setBreakpoints for " + source.getPath() + " to "
            + debugProtocolServers.size() + " server(s)");
        for (IDebugProtocolServer server : debugProtocolServers) {
            server.setBreakpoints(clearArgs)
                .whenComplete((response, error) -> {
                    if (error != null) {
                        LOG.warn("Explicit empty setBreakpoints for " + source.getPath() + " failed", error);
                    } else {
                        LOG.info("Explicit empty setBreakpoints for " + source.getPath() + " succeeded: " + response);
                    }
                });
        }

        sendBreakpoints(null, null);
    }
}
