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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
 * Prototype: locates the newest rascal-*.jar under ~/.m2, not scoped per
 * project/version. Good enough as long as one rascal version is in use.
 */
public class StdFileSystem extends DeprecatedVirtualFileSystem {
    public static final String PROTOCOL = "std";
    private static final String LIBRARY_ROOT = "org/rascalmpl/library";

    private static volatile VirtualFile cachedLibraryRoot;

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

    private static synchronized VirtualFile getLibraryRoot() {
        if (cachedLibraryRoot != null && cachedLibraryRoot.isValid()) {
            return cachedLibraryRoot;
        }
        File jar = locateRascalJar();
        if (jar == null) {
            return null;
        }
        VirtualFile localJar = LocalFileSystem.getInstance().findFileByIoFile(jar);
        if (localJar == null) {
            return null;
        }
        VirtualFile jarRoot = JarFileSystem.getInstance().getJarRootForLocalFile(localJar);
        if (jarRoot == null) {
            return null;
        }
        cachedLibraryRoot = jarRoot.findFileByRelativePath(LIBRARY_ROOT);
        return cachedLibraryRoot;
    }

    private static File locateRascalJar() {
        Path repo = Paths.get(System.getProperty("user.home"), ".m2", "repository", "org", "rascalmpl", "rascal");
        if (!Files.isDirectory(repo)) {
            return null;
        }
        try (Stream<Path> versions = Files.list(repo)) {
            List<Path> sortedVersions = versions
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .collect(Collectors.toList());
            for (Path version : sortedVersions) {
                try (Stream<Path> jars = Files.list(version)) {
                    var match = jars
                            .filter(StdFileSystem::isPlainRascalJar)
                            .findFirst();
                    if (match.isPresent()) {
                        return match.get().toFile();
                    }
                } catch (IOException e) {
                    // try next version
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    private static boolean isPlainRascalJar(Path p) {
        String name = p.getFileName().toString();
        return name.matches("rascal-[0-9].*\\.jar") && !name.contains("sources") && !name.contains("javadoc");
    }
}
