/*
 * Copyright (c) 2026, cwi-swat
 * All rights reserved. This file is licensed under the BSD 2-Clause
 * License -- see the LICENSE file in this directory.
 */
package nl.cwi.swat.rascal.intellij;

import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.redhat.devtools.lsp4ij.client.features.FileUriSupportBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * How files inside jars are named when talking to rascal-lsp -- set on both
 * Rascal language servers' LSPClientFeatures.
 * <p>
 * Without this, a library module opened from a jar (e.g. via Go to
 * Definition on {@code import String;}, which rascal-lsp answers with
 * {@code std:///String.rsc}, resolved by {@link StdFileSystem} to a
 * JarFileSystem file) is announced to the server by LSP4IJ's default as
 * {@code jar:file:/...!/...}: an opaque URI, which rascal-lsp 2.22.4 rejects
 * on every request ("Opaque URI schemes are not supported" /
 * "No previous parse tree without errors for |opaque-lsp-://jar/...|",
 * confirmed live in the LSP console), so the file got no CodeLenses,
 * highlighting or navigation. Instead:
 * <ul>
 *   <li>files of the standard library {@link StdFileSystem} serves become
 *   {@code std:///<path>} -- exactly the location rascal-lsp handed out, and
 *   what VS Code uses;</li>
 *   <li>any other jar entry becomes Rascal's own hierarchical
 *   {@code jar+file:///<jar>!/<entry>} scheme;</li>
 * </ul>
 * and both are mapped back to the same IntelliJ files. Everything else keeps
 * LSP4IJ's default behavior.
 */
final class RascalFileUriSupport extends FileUriSupportBase {

    private static final String JAR_FILE_SCHEME = "jar+file";

    @Override
    public @Nullable URI getFileUri(@NotNull VirtualFile file) {
        URI rascalUri = toRascalUri(file);
        return rascalUri != null ? rascalUri : super.getFileUri(file);
    }

    @Override
    public String toString(@NotNull VirtualFile file) {
        URI rascalUri = toRascalUri(file);
        return rascalUri != null ? rascalUri.toASCIIString() : super.toString(file);
    }

    @Override
    public @Nullable VirtualFile findFileByUri(@NotNull String fileUri) {
        if (fileUri.startsWith(StdFileSystem.PROTOCOL + ":")) {
            String path = URI.create(fileUri).getPath();
            VirtualFile file = VirtualFileManager.getInstance().getFileSystem(StdFileSystem.PROTOCOL)
                .findFileByPath(path == null ? "" : path);
            if (file != null) {
                return file;
            }
        } else if (fileUri.startsWith(JAR_FILE_SCHEME + ":")) {
            // jar+file:///home/x/lib.jar!/a/B.rsc -> JarFileSystem path "/home/x/lib.jar!/a/B.rsc"
            String path = URI.create(fileUri).getPath();
            if (path != null) {
                VirtualFile file = JarFileSystem.getInstance().findFileByPath(path);
                if (file != null) {
                    return file;
                }
            }
        }
        return super.findFileByUri(fileUri);
    }

    private static @Nullable URI toRascalUri(@NotNull VirtualFile file) {
        if (!(file.getFileSystem() instanceof JarFileSystem)) {
            return null;
        }
        try {
            String stdPath = StdFileSystem.toStdPath(file);
            if (stdPath != null) {
                return new URI(StdFileSystem.PROTOCOL, "", "/" + stdPath, null);
            }
            // JarFileSystem paths look like "/home/x/lib.jar!/a/B.rsc".
            return new URI(JAR_FILE_SCHEME, "", file.getPath(), null);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
