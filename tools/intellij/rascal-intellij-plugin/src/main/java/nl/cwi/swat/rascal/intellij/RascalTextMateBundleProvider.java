/*
 * Copyright (c) 2026, cwi-swat
 * All rights reserved. This file is licensed under the BSD 2-Clause
 * License -- see the LICENSE file in this directory.
 */
package nl.cwi.swat.rascal.intellij;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider;
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider.PluginBundle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * Keyed by plugin version (not a content hash or timestamp) so upgrading
 * the plugin automatically re-extracts into a fresh directory instead of
 * risking a stale grammar left over from an older version.
 */
public final class RascalTextMateBundleProvider implements TextMateBundleProvider {

    private static final Logger LOG = Logger.getInstance(RascalTextMateBundleProvider.class);
    private static final String PLUGIN_ID = "nl.cwi.swat.rascal-intellij";
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
        Path dir = Path.of(PathManager.getSystemPath(), "rascal-textmate-bundle", pluginVersion());
        if (isComplete(dir)) {
            return dir;
        }
        try {
            for (String relative : RESOURCE_FILES) {
                Path target = dir.resolve(relative);
                Files.createDirectories(target.getParent());
                try (InputStream in = RascalTextMateBundleProvider.class
                        .getResourceAsStream("/rascal-textmate-bundle/" + relative)) {
                    if (in == null) {
                        throw new IOException("bundled resource missing: rascal-textmate-bundle/" + relative);
                    }
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return dir;
        } catch (IOException e) {
            LOG.warn("Failed to extract Rascal TextMate bundle to " + dir, e);
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

    private static String pluginVersion() {
        var plugin = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID));
        return plugin != null ? plugin.getVersion() : "dev";
    }
}
