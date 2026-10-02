/*
 * Copyright (c) 2026, cwi-swat
 * All rights reserved. This file is licensed under the BSD 2-Clause
 * License -- see the LICENSE file in this directory.
 */
package nl.cwi.swat.rascalterminal;

import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures;
import com.redhat.devtools.lsp4ij.server.DefaultLauncherBuilder;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensDelta;
import org.eclipse.lsp4j.SemanticTokensEdit;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.MessageConsumer;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage;
import org.eclipse.lsp4j.services.LanguageServer;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Works around a crash between rascal-lsp 2.22.4 and LSP4IJ 0.21.0 on
 * semantic tokens from DSL grammars.
 * <p>
 * rascal-lsp's SemanticTokenizer.TokenTypes#tokenTypeForName encodes any
 * {@code @category} that is neither a standard LSP token type nor one of
 * Rascal's legacy categories as token type <b>-1</b>. VS Code ignores such
 * tokens. LSP4IJ's SemanticTokensData#tokenType only guards
 * {@code index >= legend size}, so -1 throws IndexOutOfBoundsException
 * (confirmed live: "Index -1 out of bounds for length 25", reported as an
 * LSP4IJ internal error), losing highlighting for the whole file. DSL
 * grammars are free to use any category, so this has to be tolerated.
 * <p>
 * Fix: before a response reaches LSP4IJ, every negative token type is
 * rewritten to {@link Integer#MAX_VALUE} -- an index past the end of any
 * legend, which LSP4IJ already maps to "no token type" and leaves
 * uncoloured, i.e. exactly VS Code's behavior. Only the type slot (every
 * 4th of 5 integers) changes; types are absolute values, not deltas, so
 * the decoding of every other token is unaffected.
 */
final class RascalParametricClientFeatures extends LSPClientFeatures {

    @Override
    public <S extends LanguageServer> @NotNull Launcher.Builder<S> createLauncherBuilder() {
        return new DefaultLauncherBuilder<>(this) {
            @Override
            protected MessageConsumer wrapMessageConsumer(MessageConsumer consumer) {
                // Ours goes innermost: LSP4IJ's own wrapper (logging, async
                // dispatch) still runs first, then this, then the endpoint
                // that completes the pending request's future.
                return super.wrapMessageConsumer(message -> {
                    if (message instanceof ResponseMessage response) {
                        sanitize(response.getResult());
                    }
                    consumer.consume(message);
                });
            }
        };
    }

    private static void sanitize(Object result) {
        if (result instanceof Either<?, ?> either) {
            sanitize(either.isLeft() ? either.getLeft() : either.getRight());
        } else if (result instanceof SemanticTokens tokens && tokens.getData() != null) {
            tokens.setData(sanitizeTypes(tokens.getData(), 0));
        } else if (result instanceof SemanticTokensDelta delta && delta.getEdits() != null) {
            for (SemanticTokensEdit edit : delta.getEdits()) {
                if (edit.getData() != null) {
                    edit.setData(sanitizeTypes(edit.getData(), edit.getStart()));
                }
            }
        }
    }

    /** @param offset index of {@code data.get(0)} within the whole token array (for delta edits). */
    private static List<Integer> sanitizeTypes(List<Integer> data, int offset) {
        List<Integer> result = null;
        for (int i = 0; i < data.size(); i++) {
            Integer value = data.get(i);
            if ((offset + i) % 5 == 3 && value != null && value < 0) {
                if (result == null) {
                    result = new ArrayList<>(data);
                }
                result.set(i, Integer.MAX_VALUE);
            }
        }
        return result != null ? result : data;
    }
}
