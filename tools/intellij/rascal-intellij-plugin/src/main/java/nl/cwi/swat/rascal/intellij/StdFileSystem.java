/*
 * Copyright (c) 2026, cwi-swat
 * All rights reserved. This file is licensed under the BSD 2-Clause
 * License -- see the LICENSE file in this directory.
 */
package nl.cwi.swat.rascal.intellij;

import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Bridges Rascal's {@code std:///} scheme (the location scheme baked into the
 * compiled standard library, e.g. {@code std:///IO.rsc}) to IntelliJ's VFS by
 * proxying into the actual {@code org/rascalmpl/library/...} entries of the
 * rascal jar on this machine's Maven repository.
 *
 * The rascal-lsp server's checker emits `std:///` locations for stdlib
 * definitions (see org.rascalmpl.uri.StandardLibraryURIResolver); LSP4IJ's
 * UriConverterManager only knows file:/jar:/jrt:/WSL-UNC and otherwise falls
 * back to VirtualFileManager.findFileByUrl, which is exactly what a
 * registered `key="std"` VirtualFileSystem plugs into.
 *
 * Which rascal jar: exactly the one on the project's pom.xml-derived
 * classpath ({@link RascalTerminalSupport#computeClasspath}, shared by both
 * Rascal language servers and the Rascal terminal, reports it via
 * {@link #useClasspath}) -- so Go to Definition into, say,
 * {@code std:///String.rsc} opens the String.rsc of the rascal version the
 * project actually uses. (Previously this picked the "newest" rascal jar in
 * ~/.m2 by plain string comparison, which ranks 0.43.0-RC8 above both
 * 0.43.0-RC15 and the project's 0.42.2 -- confirmed live.) There is no
 * fallback guess. {@code std:///} carries no version and this
 * VFS is application-wide, so with several open projects on different
 * rascal versions the most recently computed classpath's jar wins.
 * <p>
 * The files returned are ordinary JarFileSystem files;
 * {@link RascalFileUriSupport} maps them back to {@code std:///...} when
 * talking to rascal-lsp (see {@link #toStdPath}).
 */
public class StdFileSystem extends DeprecatedVirtualFileSystem {
    public static final String PROTOCOL = "std";
    private static final String LIBRARY_ROOT = "org/rascalmpl/library";

    private static volatile VirtualFile cachedLibraryRoot;
    /** The rascal jar of the most recently started Rascal language server's classpath, if any. */
    private static volatile Path classpathJar;
    private static volatile Path cachedJar;

    @Override
    public @NotNull String getProtocol() {
        return PROTOCOL;
    }

    @Override
    public @Nullable VirtualFile findFileByPath(@NotNull String path) {
        VirtualFile root = getLibraryRoot();
        if (root == null) {
            return null;
        }
        String relative = path.startsWith("/") ? path.substring(1) : path;
        return relative.isEmpty() ? root : root.findFileByRelativePath(relative);
    }

    @Override
    public @Nullable VirtualFile refreshAndFindFileByPath(@NotNull String path) {
        return findFileByPath(path);
    }

    @Override
    public void refresh(boolean asynchronous) {
        // The stdlib jar contents are immutable at runtime; nothing to refresh.
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    /**
     * Called with a project's full (Maven-computed) classpath whenever a
     * Rascal language server is launched for it; remembers its rascal jar.
     */
    static void useClasspath(String classpath) {
        for (String entry : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            Path p = Paths.get(entry);
            if (p.getFileName() != null && isPlainRascalJar(p) && Files.isRegularFile(p)) {
                classpathJar = p;
                return;
            }
        }
    }

    /**
     * If {@code file} is inside the standard library currently served as
     * {@code std:///}, its path relative to that root (e.g. "String.rsc"),
     * otherwise null.
     */
    static @Nullable String toStdPath(@NotNull VirtualFile file) {
        VirtualFile root = getLibraryRoot();
        if (root == null || !com.intellij.openapi.vfs.VfsUtilCore.isAncestor(root, file, false)) {
            return null;
        }
        return com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(file, root, '/');
    }

    static synchronized VirtualFile getLibraryRoot() {
        Path wanted = classpathJar;
        if (cachedLibraryRoot != null && cachedLibraryRoot.isValid()
                && (wanted == null || wanted.equals(cachedJar))) {
            return cachedLibraryRoot;
        }
        if (wanted == null) {
            // No Rascal language server or terminal has computed a project
            // classpath yet -- and std:/// locations only ever come from
            // those, so there is nothing to resolve against. Deliberately no
            // guessing from ~/.m2: the project's pom.xml decides.
            return null;
        }
        File jar = wanted.toFile();
        VirtualFile localJar = LocalFileSystem.getInstance().findFileByIoFile(jar);
        if (localJar == null) {
            return null;
        }
        VirtualFile jarRoot = JarFileSystem.getInstance().getJarRootForLocalFile(localJar);
        if (jarRoot == null) {
            return null;
        }
        cachedLibraryRoot = jarRoot.findFileByRelativePath(LIBRARY_ROOT);
        cachedJar = jar.toPath();
        return cachedLibraryRoot;
    }

    private static boolean isPlainRascalJar(Path p) {
        String name = p.getFileName().toString();
        return name.matches("rascal-[0-9].*\\.jar") && !name.contains("sources") && !name.contains("javadoc");
    }
}
