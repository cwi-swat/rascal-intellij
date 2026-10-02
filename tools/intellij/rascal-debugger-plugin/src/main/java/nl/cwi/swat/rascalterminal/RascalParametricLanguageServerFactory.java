/*
 * Copyright (c) 2026, cwi-swat
 * All rights reserved. This file is licensed under the BSD 2-Clause
 * License -- see the LICENSE file in this directory.
 */
package nl.cwi.swat.rascalterminal;

import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.LanguageServerFactory;
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl;
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures;
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider;
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider;
import org.eclipse.lsp4j.services.LanguageServer;
import org.jetbrains.annotations.NotNull;

/**
 * Launches rascal-lsp's language-parametric server
 * (org.rascalmpl.vscode.lsp.parametric.ParametricLanguageServer): the one
 * server process that hosts every DSL registered from a Rascal REPL via
 * util::LanguageServer::registerLanguage -- the IntelliJ counterpart of
 * the VS Code extension's ParameterizedLanguageServer.ts. Same JVM, same
 * system properties and same project classpath as the .rsc server (see
 * {@link RascalLanguageServerFactory#rascalLspCommandLine}).
 * <p>
 * Registered in plugin.xml with no file mappings at all: which files it
 * serves is only known at runtime, once a REPL registers a language --
 * {@link RascalLanguageRegistry} adds a {@code *.<ext>} mapping per
 * registered extension then.
 */
public final class RascalParametricLanguageServerFactory implements LanguageServerFactory {

    @Override
    public @NotNull StreamConnectionProvider createConnectionProvider(@NotNull Project project) {
        return new OSProcessStreamConnectionProvider(RascalLanguageServerFactory.rascalLspCommandLine(
            project, "org.rascalmpl.vscode.lsp.parametric.ParametricLanguageServer"));
    }

    @Override
    public @NotNull LanguageClientImpl createLanguageClient(@NotNull Project project) {
        return new RascalParametricLanguageClient(project);
    }

    @Override
    public @NotNull LSPClientFeatures createClientFeatures() {
        return new RascalParametricClientFeatures();
    }

    @Override
    public @NotNull Class<? extends LanguageServer> getServerInterface() {
        return RascalParametricServerApi.class;
    }
}
