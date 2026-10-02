/*
 * Copyright (c) 2026, cwi-swat
 * All rights reserved. This file is licensed under the BSD 2-Clause
 * License -- see the LICENSE file in this directory.
 */
package nl.cwi.swat.rascal.intellij;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.redhat.devtools.lsp4ij.dap.DebugMode;
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfiguration;
import com.redhat.devtools.lsp4ij.templates.ServerMappingSettings;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Auto-creates the "Rascal Attach" LSP4IJ DAP Run/Debug configuration on
 * project open, so no one has to create it by hand and -- critically --
 * so no one can forget the Mappings-tab entry the way this session did:
 * that omission caused a completely silent breakpoint failure (no error,
 * no gutter dot, nothing to go on) that took decompiling LSP4IJ's own
 * bytecode to even diagnose. See {@link RascalDebugAttachConfigurator} for
 * how {@code RascalTerminalSupport} finds and updates this same
 * configuration by name once it exists (unchanged by this class).
 * <p>
 * Registered via the {@code com.intellij.postStartupActivity} extension
 * point. {@link ProjectActivity#execute} is a Kotlin suspend function;
 * this overrides its Java-visible erasure directly (returning
 * {@link Unit#INSTANCE}) rather than adding a Kotlin source file, since
 * the work here is synchronous and needs no coroutine machinery.
 */
public final class RascalProjectActivity implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(RascalProjectActivity.class);
    private static final String CONFIGURATION_NAME = "Rascal Attach";
    private static final String DAP_CONFIGURATION_TYPE_ID = "DAPConfiguration";
    private static final String SERVER_ID = "rascal";

    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        try {
            ensureAttachConfigurationExists(project);
        } catch (Exception e) {
            // Best-effort: a user can still create this by hand (see the
            // README's "advanced/manual setup" section) if this ever fails
            // on some platform/version combination -- don't block project
            // open over it.
            LOG.warn("Failed to auto-create the \"" + CONFIGURATION_NAME + "\" DAP configuration", e);
        }
        return Unit.INSTANCE;
    }

    private static void ensureAttachConfigurationExists(Project project) {
        RunManager runManager = RunManager.getInstance(project);

        // Match RascalDebugAttachConfigurator's own check: any existing
        // DAPRunConfiguration counts, not just one literally named
        // CONFIGURATION_NAME -- otherwise a differently-named config from
        // before this shipped would go undetected here, and we'd create a
        // redundant second one that attachUsingExistingConfiguration's own
        // findFirst() would then have to arbitrarily pick between.
        boolean alreadyExists = runManager.getAllSettings().stream()
            .anyMatch(settings -> settings.getConfiguration() instanceof DAPRunConfiguration);
        if (alreadyExists) {
            return;
        }

        ConfigurationType dapType = ConfigurationTypeUtil.findConfigurationType(DAP_CONFIGURATION_TYPE_ID);
        if (dapType == null) {
            LOG.warn("LSP4IJ's \"" + DAP_CONFIGURATION_TYPE_ID + "\" configuration type is not registered "
                + "-- is LSP4IJ installed and enabled?");
            return;
        }

        RunnerAndConfigurationSettings settings =
            runManager.createConfiguration(CONFIGURATION_NAME, dapType.getConfigurationFactories()[0]);
        DAPRunConfiguration configuration = (DAPRunConfiguration) settings.getConfiguration();

        configuration.setServerId(SERVER_ID);
        configuration.setDebugMode(DebugMode.ATTACH);
        configuration.setAttachAddress("localhost");
        configuration.setAttachPort("0"); // overwritten before every launch, see RascalDebugAttachConfigurator
        configuration.setServerMappings(List.of(
            ServerMappingSettings.createFileNamePatternsMappingSettings(List.of("*.rsc"), SERVER_ID)
        ));

        runManager.addConfiguration(settings);
        LOG.info("Auto-created the \"" + CONFIGURATION_NAME + "\" DAP configuration for " + project.getName());
    }
}
