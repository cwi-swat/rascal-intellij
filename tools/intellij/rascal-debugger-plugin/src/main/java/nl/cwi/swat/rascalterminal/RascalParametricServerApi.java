/*
 * Copyright (c) 2026, cwi-swat
 * All rights reserved. This file is licensed under the BSD 2-Clause
 * License -- see the LICENSE file in this directory.
 */
package nl.cwi.swat.rascalterminal;

import com.google.gson.JsonObject;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageServer;

import java.util.concurrent.CompletableFuture;

/**
 * The two rascal-lsp-specific requests the parametric (DSL) language server
 * accepts on top of plain LSP, taken from rascal-lsp 2.22.4's
 * org.rascalmpl.vscode.lsp.IBaseLanguageServerExtensions. LSP4IJ builds its
 * server proxy from this interface (see
 * {@link RascalParametricLanguageServerFactory#getServerInterface()}).
 * <p>
 * The argument is rascal-lsp's LanguageParameter
 * (pathConfig/name/extensions/mainModule/mainFunction/precompiledParser),
 * kept as a raw JsonObject: it is received from the REPL and forwarded
 * as-is, so nothing here needs to track that class's exact shape.
 */
public interface RascalParametricServerApi extends LanguageServer {

    @JsonRequest("rascal/sendRegisterLanguage")
    CompletableFuture<Void> sendRegisterLanguage(JsonObject lang);

    @JsonRequest("rascal/sendUnregisterLanguage")
    CompletableFuture<Void> sendUnregisterLanguage(JsonObject lang);
}
