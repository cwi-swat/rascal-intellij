/*
 * Copyright (c) 2026, cwi-swat
 * All rights reserved. This file is licensed under the BSD 2-Clause
 * License -- see the LICENSE file in this directory.
 */
package nl.cwi.swat.rascalterminal;

import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.diagnostic.Logger;
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor;
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptorFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Registered via the com.redhat.devtools.lsp4ij.debugAdapterServer extension
 * point (see plugin.xml) as a distinct "Rascal Debugger" DAP server choice,
 * alongside LSP4IJ's built-in generic/"undefined" one. Choosing it in a DAP
 * run configuration's server dropdown is what causes LSP4IJ to construct a
 * {@link RascalDebugAdapterDescriptor} (and, through that,
 * {@link RascalBreakpointHandler}) for the session instead of its own
 * DefaultDebugAdapterDescriptor/DAPBreakpointHandler -- that's the only way
 * to get the breakpoint-handling fix applied; a session created from the
 * plain generic "Debug Adapter Protocol" configuration type instead (with no
 * server selected) is not affected by this class at all.
 */
public final class RascalDebugAdapterDescriptorFactory extends DebugAdapterDescriptorFactory {

    private static final Logger LOG = Logger.getInstance(RascalDebugAdapterDescriptorFactory.class);

    @Override
    public DebugAdapterDescriptor createDebugAdapterDescriptor(@NotNull RunConfigurationOptions options,
                                                                @NotNull ExecutionEnvironment environment) {
        LOG.info("RascalDebugAdapterDescriptorFactory.createDebugAdapterDescriptor called -- constructing RascalDebugAdapterDescriptor");
        return new RascalDebugAdapterDescriptor(options, environment, getServerDefinition());
    }
}
