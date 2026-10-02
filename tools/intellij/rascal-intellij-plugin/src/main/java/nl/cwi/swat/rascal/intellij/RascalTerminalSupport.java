/*
 * Copyright (c) 2026, cwi-swat
 * All rights reserved. This file is licensed under the BSD 2-Clause
 * License -- see the LICENSE file in this directory.
 */
package nl.cwi.swat.rascal.intellij;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.util.EnvironmentUtil;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;
import org.jetbrains.plugins.terminal.TerminalToolWindowManager;

import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Shared logic for the rascalmpl.importModule / rascalmpl.runMain LSP
 * commands: open a new terminal tab running a plain interactive Rascal REPL
 * (org.rascalmpl.shell.RascalShell, no module argument -- the exact same
 * class VS Code's own "Rascal terminal" ultimately delegates to, per
 * org.rascalmpl.vscode.lsp.terminal.LSPTerminalREPL#main), then type
 * commands into it the same way a human would at the `rascal&gt;` prompt.
 * Both CodeLens titles literally say "new Rascal terminal" -- matching that,
 * every invocation opens a fresh terminal rather than trying to reuse one.
 *
 * The classpath is computed from the currently open project's own pom.xml
 * (rascal/rascal-lsp/typepal plus everything any interpreted module might
 * touch via @javaClass, e.g. a project's own FFI glue needing org.json/
 * io.socket/Jetty) -- see {@link #computeDependencyClasspath(Path)}. This
 * works for any Maven-based Rascal project, with no dependency on any
 * specific project's own tooling.
 */
final class RascalTerminalSupport {

    private static final Logger LOG = Logger.getInstance(RascalTerminalSupport.class);

    private RascalTerminalSupport() {
    }

    static void importThenMaybeDebug(Project project, String moduleName, boolean startDebugging) {
        ShellTerminalWidget widget = TerminalToolWindowManager.getInstance(project)
            .createLocalShellWidget(project.getBasePath(), "Rascal: " + moduleName);

        // Classpath computation can shell out to `mvn` (several seconds on a
        // cold/stale cache) -- keep it off the EDT along with everything
        // else that follows.
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String cp;
            Path argFile;
            try {
                cp = computeClasspath(project);
                argFile = Files.createTempFile("rascal-shell-", ".args");
                argFile.toFile().deleteOnExit();
                Files.writeString(argFile, "-cp \"" + cp + "\"\n"
                    + languageRegistryArgument(project)
                    + "org.rascalmpl.shell.RascalShell\n");
            } catch (Exception e) {
                LOG.warn("Failed to compute Rascal classpath", e);
                notify(project, "Failed to start Rascal terminal for " + moduleName,
                        e.getClass().getSimpleName() + ": " + e.getMessage() + " (see idea.log)",
                        NotificationType.ERROR);
                return;
            }

            // `exec` replaces the shell process image instead of forking a
            // child, so the terminal's own process becomes the JVM itself
            // -- letting us read its real PID below (needed to find the
            // debug port later; see RascalDebugPortFinder). Uses the exact
            // same JVM running this plugin (see javaExecutable()) rather
            // than a bare "java" resolved by the shell's own PATH -- a real
            // machine had an ancient JDK 8 on that PATH, which crashed
            // RascalShell with UnsupportedClassVersionError before it ever
            // reached ":set debugging true", with nothing surfaced anywhere
            // IntelliJ's own logging could see (it's all inside the PTY).
            runInTerminal(widget, "exec \"" + javaExecutable() + "\" @\"" + argFile + "\"");

            // ShellTerminalWidget wires up its ProcessTtyConnector
            // asynchronously -- reading it synchronously right after
            // createLocalShellWidget() returns null (confirmed live: NPE
            // from getProcessTtyConnector()). Retry briefly instead of a
            // single point-in-time read.
            long pid = capturePidWithRetry(widget, Duration.ofSeconds(3));
            Set<Integer> before = pid > 0 ? RascalDebugPortFinder.listeningPorts(pid) : Set.of();

            // No artificial delay before typing the next commands: PTY input
            // is queued at the OS level independently of whether the reading
            // process (RascalShell, still booting/importing) is ready for it
            // yet -- `exec` even preserves that queue across replacing bash
            // with java. A fixed delay here previously guessed wrong for a
            // heavier import (BuildAction.rsc pulls in FFI.rsc and others;
            // confirmed live the REPL was still mid-import 6.5s in).
            runInTerminal(widget, "import " + moduleName + ";");
            if (startDebugging) {
                runInTerminal(widget, ":set debugging true");
                if (pid <= 0) {
                    notify(project, "Rascal debug port lookup skipped for " + moduleName,
                        "Could not determine the terminal's process id (see idea.log for RascalTerminalSupport) -- find the port with `lsof -i -P -n -p <pid>` (macOS) or `ss -ltnp` (Linux) and paste it into LSP4IJ's Attach config manually.",
                        NotificationType.WARNING);
                    return;
                }
                findAndReportDebugPort(project, moduleName, pid, before);
            }
        });
    }

    /**
     * The JVM option that lets util::LanguageServer::registerLanguage in
     * this REPL reach {@link RascalLanguageRegistry} (read once by rascal-lsp's
     * RascalInterface; without it every call prints "Could not register
     * language: no connection"). Best-effort: if the registry can't listen,
     * the REPL still starts, just without DSL registration.
     */
    private static String languageRegistryArgument(Project project) {
        try {
            return "-Drascal.languageRegistryPort=" + RascalLanguageRegistry.getInstance(project).port() + "\n";
        } catch (IOException e) {
            LOG.warn("Could not start the Rascal language registry; registerLanguage will not work in this terminal", e);
            return "";
        }
    }

    /** Package-private: also called by {@link RascalLanguageServerFactory}. */
    static String computeClasspath(Project project) throws IOException, InterruptedException {
        Path projectRoot = Path.of(project.getBasePath());
        String projectClasses = projectRoot.resolve("target").resolve("classes").toString();
        String dependencies = computeDependencyClasspath(projectRoot);
        // So std:/// (e.g. Go to Definition on `import String;`) opens the
        // rascal version this project's pom.xml uses -- see StdFileSystem.
        StdFileSystem.useClasspath(dependencies);
        return dependencies + java.io.File.pathSeparator + projectClasses;
    }

    /**
     * Absolute path to the JVM currently running this plugin (i.e. IntelliJ's
     * own bundled runtime), for launching Rascal's own jars with. Package-
     * private so it can also be called by {@link RascalLanguageServerFactory}
     * -- deliberately avoiding a bare {@code "java"}, which gets resolved
     * against whatever's first on the *shell's* PATH and could be an
     * unrelated, arbitrarily old JDK (confirmed live: JDK 8, far too old for
     * Rascal's own jars, crashing with UnsupportedClassVersionError before
     * ever reaching any of our own code). IntelliJ's own bundled runtime is
     * guaranteed modern enough, since IntelliJ itself needs it to run.
     */
    static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /**
     * Computes (and caches) a Maven-based Rascal project's full resolved
     * dependency classpath. Package-private so it can also be called
     * directly by {@link RascalLanguageServerFactory}, which needs the
     * identical classpath but launches the LSP server jar instead of a
     * REPL -- keeping this as one shared computation avoids the earlier
     * failure mode of two hand-maintained jar lists drifting apart (a fix
     * for a missing-jar NoClassDefFoundError landing in one copy while the
     * bug kept happening via the other).
     * <p>
     * Caching: reused from {@code <projectRoot>/target/rascal-ide-
     * classpath.txt} as long as it's newer than pom.xml.
     */
    static String computeDependencyClasspath(Path projectRoot) throws IOException, InterruptedException {
        Path pom = projectRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            throw new IOException("no pom.xml found at " + pom
                + " -- can't auto-compute a Maven classpath for this project");
        }

        Path target = projectRoot.resolve("target");
        Path cacheFile = target.resolve("rascal-ide-classpath.txt");
        if (Files.isRegularFile(cacheFile)
            && Files.getLastModifiedTime(cacheFile).compareTo(Files.getLastModifiedTime(pom)) > 0) {
            return Files.readString(cacheFile).strip();
        }

        Files.createDirectories(target);
        // A GUI-launched IDE process (as opposed to one started from a
        // terminal) inherits a minimal PATH on macOS -- typically missing
        // wherever Homebrew/SDKMAN put `mvn` -- so "mvn" resolves fine for
        // "Run in new Rascal terminal" (a real login shell) but can fail
        // here. Confirmed live via a full "Caused by:" chain down to a raw
        // ENOENT from forkAndExec: merely setting the child's environment
        // (builder.environment().putAll(...)) does NOT fix this by itself
        // -- resolving a bare command name happens against the *launching*
        // process's own environment, not whatever you hand the child, so
        // "mvn" still isn't found even once the child's PATH is correct.
        // PathEnvironmentVariableUtil.findInPath pre-resolves the absolute
        // path instead, sidestepping that lookup entirely -- and (per its
        // own bytecode) already searches using EnvironmentUtil's corrected
        // PATH, not the JVM's raw launch-time one, so no separate PATH
        // string needs threading through here.
        File mvnExecutable = PathEnvironmentVariableUtil.findInPath("mvn");
        String mvnCommand = mvnExecutable != null ? mvnExecutable.getAbsolutePath() : "mvn";
        ProcessBuilder builder = new ProcessBuilder(
            mvnCommand, "-q", "-f", pom.toString(),
            "dependency:build-classpath",
            "-Dmdep.outputFile=" + cacheFile
        ).redirectErrorStream(true);
        // Still worth keeping: the resolved mvn binary itself may depend on
        // other PATH entries (e.g. a JAVA_HOME-relative java) or other env
        // vars sourced from the login shell, which a bare fork+exec of an
        // absolute path won't otherwise inherit from a GUI-launched IDE.
        builder.environment().putAll(EnvironmentUtil.getEnvironmentMap());
        Process process = builder.start();

        String output;
        try (var reader = process.inputReader()) {
            output = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
        }
        int exit = process.waitFor();
        if (exit != 0) {
            Files.deleteIfExists(cacheFile);
            throw new IOException("mvn dependency:build-classpath failed (exit " + exit + ")"
                + (output.isBlank() ? "" : ": " + output));
        }
        if (!Files.isRegularFile(cacheFile)) {
            throw new IOException("mvn dependency:build-classpath exited 0 but did not produce " + cacheFile);
        }
        return Files.readString(cacheFile).strip();
    }

    private static long capturePidWithRetry(ShellTerminalWidget widget, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                long pid = widget.getProcessTtyConnector().getProcess().pid();
                LOG.info("Captured Rascal terminal PID " + pid);
                return pid;
            } catch (Exception e) {
                last = e;
                if (!sleep(100)) {
                    return -1;
                }
            }
        }
        // Best-effort: if the terminal API shape changes underneath us or the
        // connector never attaches in time, fall back to "unknown" rather
        // than breaking import/run -- but log it loudly, since a silent -1
        // here otherwise looks indistinguishable from every later step
        // succeeding.
        LOG.warn("Could not capture Rascal terminal PID after retrying for " + timeout, last);
        return -1;
    }

    private static void findAndReportDebugPort(Project project, String moduleName, long pid, Set<Integer> before) {
        try {
            LOG.info("Polling for a new listening port on PID " + pid + " (before=" + before + ")");
            // Generous on purpose: the port only appears once ':set debugging
            // true' actually executes, which waits behind however long the
            // import ahead of it in the queued input takes (module loading,
            // parser generation -- observed 6.5s+ for a heavier module).
            Optional<Integer> port = RascalDebugPortFinder.waitForNewListeningPort(pid, before, Duration.ofSeconds(90));
            LOG.info("Port poll result for PID " + pid + ": " + port);
            if (port.isPresent()) {
                int portNumber = port.get();
                boolean attached = RascalDebugAttachConfigurator.attachUsingExistingConfiguration(project, portNumber);
                LOG.info("Auto-attach using existing DAP configuration: " + attached);
                if (attached) {
                    notify(project, "Attaching Rascal debugger on port " + portNumber,
                        "Updated and launched your existing LSP4IJ DAP configuration for " + moduleName + ".",
                        NotificationType.INFORMATION);
                } else {
                    String portStr = String.valueOf(portNumber);
                    CopyPasteManager.getInstance().setContents(new StringSelection(portStr));
                    notify(project, "Rascal debug port: " + portStr + " (copied to clipboard)",
                        "No existing LSP4IJ DAP configuration found -- create one, then paste this port in.",
                        NotificationType.INFORMATION);
                }
            } else {
                notify(project, "Could not find the Rascal debug port for " + moduleName,
                    "PID " + pid + " never showed a new listening socket after ':set debugging true'.",
                    NotificationType.WARNING);
            }
        } catch (Exception e) {
            LOG.warn("Debug port lookup/auto-attach failed for PID " + pid, e);
            notify(project, "Rascal debug port lookup failed for " + moduleName,
                e.getClass().getSimpleName() + ": " + e.getMessage() + " (see idea.log)",
                NotificationType.ERROR);
        }
    }

    private static void notify(Project project, String title, String content, NotificationType type) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Rascal")
            .createNotification(title, content, type)
            .notify(project);
    }

    private static void runInTerminal(ShellTerminalWidget widget, String command) {
        try {
            widget.executeCommand(command);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** @return false if interrupted (caller should abandon the rest of its sequence). */
    private static boolean sleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
