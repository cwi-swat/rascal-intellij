/*
 * Copyright (c) 2026, cwi-swat
 * All rights reserved. This file is licensed under the BSD 2-Clause
 * License -- see the LICENSE file in this directory.
 */
package nl.cwi.swat.rascalterminal;

import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.ServerStatus;
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl;
import org.jetbrains.annotations.NotNull;

/**
 * LSP4IJ language client for the parametric (DSL) server. Its only job is
 * re-sending every DSL registration whenever the server (re)starts: the
 * parametric server keeps registered languages purely in memory, so after
 * any restart (LSP4IJ stopping it once its last DSL file closes, the user
 * restarting it from the LSP console, a crash) it would otherwise come back
 * knowing no languages at all, until the REPL's registerLanguage(...) is run
 * again. LSP4IJ creates a fresh client per server start, and only reports
 * {@code started} after initialize/initialized, which is exactly when the
 * server can accept registrations.
 */
final class RascalParametricLanguageClient extends LanguageClientImpl {

    RascalParametricLanguageClient(@NotNull Project project) {
        super(project);
    }

    @Override
    public void handleServerStatusChanged(@NotNull ServerStatus serverStatus) {
        if (serverStatus == ServerStatus.started && getLanguageServer() instanceof RascalParametricServerApi server) {
            RascalLanguageRegistry.getInstance(getProject()).serverStarted(server, this);
        }
    }
}
