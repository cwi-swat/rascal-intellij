/*
 * Copyright (c) 2026, cwi-swat
 * All rights reserved. This file is licensed under the BSD 2-Clause
 * License -- see the LICENSE file in this directory.
 */
package nl.cwi.swat.rascal.intellij;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider;
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider.PluginBundle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Registers the Rascal TextMate grammar without any manual setup script.
 *
 * {@link TextMateBundleProvider} needs a real {@link Path} on disk, not a
 * classpath resource (confirmed by decompiling the actual interface --
 * {@code PluginBundle} takes a {@code java.nio.file.Path}, and IntelliJ's
 * own built-in bundles live as loose files next to the platform's jars, not
 * inside any jar). So the grammar files ship as plain jar resources under
 * {@code rascal-textmate-bundle/} (see build.gradle.kts's default resource
 * handling) and get extracted once, on first call, to a real directory
 * under {@link PathManager#getSystemPath()} -- a one-time,
 * idempotent, cache-if-unchanged copy, so nobody has to register the
 * grammar with IntelliJ by hand.
 *
 * Keyed by a hash of the bundled files' contents, so a changed grammar
 * (e.g. after a plugin upgrade) is automatically extracted into a fresh
 * directory instead of reusing a stale one. (Previously keyed by plugin
 * version, read via PluginManagerCore.getPlugin / PluginManager
 * .getPluginByClass -- both @ApiStatus.Internal, which the JetBrains
 * Marketplace rejects; the content hash needs no platform API at all.)
 */
public final class RascalTextMateBundleProvider implements TextMateBundleProvider {

    private static final Logger LOG = Logger.getInstance(RascalTextMateBundleProvider.class);
    private static final String BUNDLE_NAME = "rascal-basic";
    private static final String[] RESOURCE_FILES = {
        "package.json",
        "language-configuration.json",
        "syntaxes/rascal.tmLanguage.json"
    };

    @Override
    public @NotNull List<PluginBundle> getBundles() {
        Path extracted = extractBundleIfNeeded();
        return extracted == null ? List.of() : List.of(new PluginBundle(BUNDLE_NAME, extracted));
    }

    private static Path extractBundleIfNeeded() {
        Path dir = null;
        try {
            byte[][] contents = new byte[RESOURCE_FILES.length][];
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < RESOURCE_FILES.length; i++) {
                contents[i] = readResource(RESOURCE_FILES[i]);
                digest.update(RESOURCE_FILES[i].getBytes(StandardCharsets.UTF_8));
                digest.update(contents[i]);
            }
            String key = HexFormat.of().formatHex(digest.digest()).substring(0, 16);
            dir = Path.of(PathManager.getSystemPath(), "rascal-textmate-bundle", key);
            if (isComplete(dir)) {
                return dir;
            }
            for (int i = 0; i < RESOURCE_FILES.length; i++) {
                Path target = dir.resolve(RESOURCE_FILES[i]);
                Files.createDirectories(target.getParent());
                Files.write(target, contents[i]);
            }
            return dir;
        } catch (IOException | NoSuchAlgorithmException e) {
            LOG.warn("Failed to extract Rascal TextMate bundle" + (dir != null ? " to " + dir : ""), e);
            return null;
        }
    }

    private static boolean isComplete(Path dir) {
        for (String relative : RESOURCE_FILES) {
            if (!Files.isRegularFile(dir.resolve(relative))) {
                return false;
            }
        }
        return true;
    }

    private static byte[] readResource(String relative) throws IOException {
        try (InputStream in = RascalTextMateBundleProvider.class
                .getResourceAsStream("/rascal-textmate-bundle/" + relative)) {
            if (in == null) {
                throw new IOException("bundled resource missing: rascal-textmate-bundle/" + relative);
            }
            return in.readAllBytes();
        }
    }
}
