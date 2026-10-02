/*
 * Copyright (c) 2026, cwi-swat
 * All rights reserved. This file is licensed under the BSD 2-Clause
 * License -- see the LICENSE file in this directory.
 */
package nl.cwi.swat.rascalterminal;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.LanguageServerFactory;
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider;
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Launches Rascal's base LSP server (for editing {@code .rsc} files),
 * registered via LSP4IJ's {@code com.redhat.devtools.lsp4ij.server}
 * extension point (see plugin.xml) -- the same "factory" pattern this
 * plugin already uses for the DAP side
 * ({@link RascalDebugAdapterDescriptorFactory}).
 * <p>
 * The two system properties below are required, taken from how the official
 * VS Code Rascal extension launches the same jar:
 * <ol>
 *   <li>{@code -Drascal.lsp.deploy=true} switches BaseLanguageServer from its
 *   dev-mode TCP-socket-on-port-8888 path to stdio.</li>
 *   <li>{@code -Drascal.fallbackResolver=...FallbackResolver} tells the
 *   server which class to construct as its FallbackResolver singleton --
 *   without it the server crashes on any request with "FallbackResolver
 *   accessed before initialization".</li>
 * </ol>
 * The classpath is computed the exact same way "Run in new Rascal terminal"
 * already computes its own -- see {@link RascalTerminalSupport#computeClasspath}
 * -- so this server always matches whatever project is actually open in
 * IntelliJ. Same for the {@code java} binary itself: launched via
 * {@link RascalTerminalSupport#javaExecutable}'s absolute path rather than a
 * bare {@code "java"}, which would otherwise get resolved against whatever
 * PATH this plugin's own JVM happened to inherit -- not necessarily the same
 * PATH (or even the same java version) a real interactive shell would use.
 */
public final class RascalLanguageServerFactory implements LanguageServerFactory {

    private static final Logger LOG = Logger.getInstance(RascalLanguageServerFactory.class);

    @Override
    public @NotNull StreamConnectionProvider createConnectionProvider(@NotNull Project project) {
        return new OSProcessStreamConnectionProvider(
            rascalLspCommandLine(project, "org.rascalmpl.vscode.lsp.rascal.RascalLanguageServer"));
    }

    /**
     * Package-private: also used by {@link RascalParametricLanguageServerFactory},
     * which launches a different main class out of the same rascal-lsp jar
     * and needs the identical JVM, system properties and classpath.
     */
    static GeneralCommandLine rascalLspCommandLine(@NotNull Project project, String mainClass) {
        String classpath;
        try {
            classpath = RascalTerminalSupport.computeClasspath(project);
        } catch (Exception e) {
            LOG.warn("Failed to compute Rascal classpath for " + mainClass, e);
            throw new RuntimeException("Failed to compute Rascal classpath: " + e.getMessage(), e);
        }

        GeneralCommandLine commandLine = new GeneralCommandLine(List.of(
            RascalTerminalSupport.javaExecutable(),
            "-Drascal.lsp.deploy=true",
            "-Drascal.fallbackResolver=org.rascalmpl.vscode.lsp.uri.FallbackResolver",
            "-cp", classpath,
            mainClass
        ));
        if (project.getBasePath() != null) {
            commandLine.setWorkDirectory(project.getBasePath());
        }
        return commandLine;
    }
}
