/*
 * Copyright (c) 2026, Periteleios
 * All rights reserved. This file is licensed under the BSD 2-Clause
 * License -- see the LICENSE file in this directory.
 */
package com.periteleios.rascalterminal;

import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.HyperlinkInfo;
import com.intellij.execution.filters.OpenFileHyperlinkInfo;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes Rascal's own source-location literals -- e.g.
 * {@code |file:///.../Sanity.rsc|(1214,7,<30,17>,<30,24>)}, exactly what the
 * interpreter prints for compile errors and stack traces in a "Run in new
 * Rascal terminal" session, and what rascal-lsp logs (e.g. ParseErrors) in
 * LSP4IJ's LSP console -- clickable, jumping straight to the right file and
 * line/column. Every ConsoleView using the predefined filters gets this,
 * the LSP console included.
 *
 * Without this, IntelliJ's own generic terminal file-link detector
 * (registered by the bundled Terminal plugin) tries to guess a path out of
 * this text and gets it wrong -- it doesn't know Rascal's {@code |uri|(loc)}
 * syntax -- producing a "Cannot find file" dialog instead of navigating
 * anywhere. This filter is registered via the standard
 * {@code com.intellij.consoleFilterProvider} extension point, which the
 * Terminal plugin's own output reuses (see
 * org.jetbrains.plugins.terminal.hyperlinks.filter
 * .TerminalGenericFileFilterProvider, registered the same way).
 *
 * The offset/length pair is unused here (IntelliJ navigates by line/column,
 * not character offset); only the URI and the begin line/column matter.
 * The position suffix is optional -- a bare {@code |uri|} (no location
 * literal after it, as Rascal prints for some whole-module references)
 * still links, just to the top of the file.
 *
 * Resolving the URI through {@link VirtualFileManager#findFileByUrl} (rather
 * than assuming {@code file:}) means this also works for any other scheme a
 * registered VirtualFileSystem understands -- including this plugin's own
 * {@code std:///} bridge (see {@link StdFileSystem}) for locations pointing
 * into the standard library.
 */
final class RascalLocationLinkFilter implements Filter {

    private static final Pattern LOCATION_PATTERN = Pattern.compile(
            "\\|([a-zA-Z][\\w+.-]*://[^|]*)\\|" +
            "(?:\\((\\d+),(\\d+),<(\\d+),(\\d+)>,<(\\d+),(\\d+)>\\))?"
    );

    private final Project project;

    RascalLocationLinkFilter(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nullable Result applyFilter(@NotNull String line, int entireLength) {
        Matcher matcher = LOCATION_PATTERN.matcher(line);
        List<ResultItem> items = null;
        int lineStart = entireLength - line.length();

        while (matcher.find()) {
            HyperlinkInfo hyperlink = toHyperlink(matcher);
            if (hyperlink == null) {
                continue;
            }
            if (items == null) {
                items = new ArrayList<>();
            }
            // Emitted as three adjacent pieces -- "|", the bare URI, and
            // "|(offset,length,<l,c>,<l,c>)" -- sharing one target, rather
            // than one link over the whole literal. IntelliJ's generic
            // UrlFilter also links the "file:///..." inside it, but wrongly
            // includes the closing "|" ("Cannot find file .../x.ptl|", seen
            // in the LSP console), and when links overlap the platform
            // follows the *smallest* one at the clicked offset
            // (EditorHyperlinkSupport#choosePreferredLink). The bare-URI
            // piece is one character shorter than UrlFilter's link, and the
            // other two pieces are shorter still, so ours wins everywhere.
            int start = lineStart + matcher.start();
            int uriStart = lineStart + matcher.start(1);
            int uriEnd = lineStart + matcher.end(1);
            int end = lineStart + matcher.end();
            items.add(new ResultItem(start, uriStart, hyperlink));
            items.add(new ResultItem(uriStart, uriEnd, hyperlink));
            items.add(new ResultItem(uriEnd, end, hyperlink));
        }

        return items == null ? null : new Result(items);
    }

    private @Nullable HyperlinkInfo toHyperlink(Matcher matcher) {
        String uri = matcher.group(1);
        VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(uri);
        if (file == null) {
            return null;
        }
        if (matcher.group(4) == null) {
            // Bare |uri|, no (offset,length,<line,col>,<line,col>) suffix.
            return new OpenFileHyperlinkInfo(project, file, 0);
        }
        int beginLine = Integer.parseInt(matcher.group(4)) - 1; // Rascal: 1-based; IntelliJ: 0-based.
        int beginColumn = Integer.parseInt(matcher.group(5));   // Both already 0-based.
        return new OpenFileHyperlinkInfo(project, file, beginLine, beginColumn);
    }
}
