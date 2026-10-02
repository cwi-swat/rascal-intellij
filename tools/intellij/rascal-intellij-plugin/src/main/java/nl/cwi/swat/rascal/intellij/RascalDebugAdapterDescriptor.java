/*
 * Copyright (c) 2026, cwi-swat
 * All rights reserved. This file is licensed under the BSD 2-Clause
 * License -- see the LICENSE file in this directory.
 */
package nl.cwi.swat.rascal.intellij;

import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebugSession;
import com.redhat.devtools.lsp4ij.dap.breakpoints.DAPBreakpointHandlerBase;
import com.redhat.devtools.lsp4ij.dap.definitions.DebugAdapterServerDefinition;
import com.redhat.devtools.lsp4ij.dap.descriptors.DefaultDebugAdapterDescriptor;
import org.jetbrains.annotations.NotNull;

/**
 * Only override needed here: substitute {@link RascalBreakpointHandler} (fixes
 * the per-file breakpoint sync bug -- see its own javadoc) for LSP4IJ's stock
 * DAPBreakpointHandler. Everything else about a plain DAP "Attach" session
 * (starting/attaching, stack frames, variables, stepping) is unaffected.
 */
final class RascalDebugAdapterDescriptor extends DefaultDebugAdapterDescriptor {

    private static final Logger LOG = Logger.getInstance(RascalDebugAdapterDescriptor.class);

    RascalDebugAdapterDescriptor(@NotNull RunConfigurationOptions options,
                                  @NotNull ExecutionEnvironment environment,
                                  @NotNull DebugAdapterServerDefinition serverDefinition) {
        super(options, environment, serverDefinition);
        LOG.info("RascalDebugAdapterDescriptor constructed for server definition id=" + serverDefinition.getId());
    }

    @Override
    public @NotNull DAPBreakpointHandlerBase<?> createBreakpointHandler(@NotNull XDebugSession session, Project project) {
        LOG.info("RascalDebugAdapterDescriptor.createBreakpointHandler called -- constructing RascalBreakpointHandler");
        return new RascalBreakpointHandler(session, this, project);
    }
}
