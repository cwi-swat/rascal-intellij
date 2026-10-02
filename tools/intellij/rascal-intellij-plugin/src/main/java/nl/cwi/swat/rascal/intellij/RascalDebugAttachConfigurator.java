/*
 * Copyright (c) 2026, cwi-swat
 * All rights reserved. This file is licensed under the BSD 2-Clause
 * License -- see the LICENSE file in this directory.
 */
package nl.cwi.swat.rascal.intellij;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration;

import java.util.List;
import java.util.Optional;

/**
 * Writes a discovered debug port straight into the user's existing LSP4IJ
 * DAP run configuration and launches it, instead of leaving them to paste
 * the port into LSP4IJ's Settings UI by hand.
 *
 * There's only one DAPConfigurationType/DAPConfigurationFactory registered
 * by LSP4IJ (id "DAPConfiguration", confirmed by decompiling
 * DAPConfigurationType) -- every DAP run configuration a user creates,
 * whether set up for launch or attach, is an instance of the same public
 * com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration class
 * with plain getAttachPort()/setAttachPort(String) methods. No custom
 * DebugAdapterServerDefinition or RunConfiguration subclass is needed --
 * we just find the one the user already configured and update it.
 */
final class RascalDebugAttachConfigurator {

    private RascalDebugAttachConfigurator() {
    }

    /**
     * @return true if an existing DAP run configuration was found, updated with
     *         the given port, and launched; false if none exists (caller should
     *         fall back to surfacing the port for manual entry).
     */
    static boolean attachUsingExistingConfiguration(Project project, int port) {
        RunManager runManager = RunManager.getInstance(project);

        // DAPConfigurationType itself is package-private in lsp4ij (only its
        // getInstance() method is public, which doesn't help us reference the
        // class), so we can't filter by ConfigurationType directly -- filter
        // getAllSettings() by the (public) DAPRunConfiguration class instead.
        Optional<RunnerAndConfigurationSettings> target = runManager.getAllSettings().stream()
            .filter(settings -> settings.getConfiguration() instanceof DAPRunConfiguration)
            .findFirst();

        if (target.isEmpty()) {
            return false;
        }

        RunnerAndConfigurationSettings settings = target.get();
        DAPRunConfiguration config = (DAPRunConfiguration) settings.getConfiguration();
        config.setAttachAddress("localhost");
        config.setAttachPort(String.valueOf(port));

        runManager.setSelectedConfiguration(settings);
        ExecutionUtil.runConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance());
        return true;
    }
}
