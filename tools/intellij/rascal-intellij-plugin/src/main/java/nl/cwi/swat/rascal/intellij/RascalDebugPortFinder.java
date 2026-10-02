/*
 * Copyright (c) 2026, cwi-swat
 * All rights reserved. This file is licensed under the BSD 2-Clause
 * License -- see the LICENSE file in this directory.
 */
package nl.cwi.swat.rascal.intellij;

import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.EnvironmentUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the TCP port org.rascalmpl.dap.DebugSocketServer bound for a given
 * process. DebugSocketServer picks the port itself via `new ServerSocket(0)`
 * (an OS-assigned ephemeral port) and, per the decompiled bytecode of
 * org.rascalmpl.ideservices.IDEServices, never reports it anywhere a bare
 * org.rascalmpl.shell.RascalShell process can surface it
 * (registerDebugServerPort/startDebuggingSession are no-op default methods
 * unless the caller supplies its own IDEServices, which RascalShell
 * doesn't). So the only way to learn the port is to look at the OS's own
 * view of that process's sockets directly -- on Linux this reads it
 * straight out of /proc (no subprocess needed); macOS has no /proc, so
 * there {@code lsof} is used instead (present by default). Any other OS
 * isn't supported -- see {@link #listeningPorts}.
 */
final class RascalDebugPortFinder {

    private static final Logger LOG = Logger.getInstance(RascalDebugPortFinder.class);
    private static final AtomicBoolean UNSUPPORTED_OS_LOGGED = new AtomicBoolean();
    private static final Pattern MAC_LSOF_LISTEN_PORT = Pattern.compile(":(\\d+)\\s*\\(LISTEN\\)\\s*$");

    private RascalDebugPortFinder() {
    }

    /**
     * Snapshot of ports the process currently has LISTENing, before triggering
     * debug mode. Always mutable (even when empty) -- waitForNewListeningPort
     * calls removeAll() on results of this method, which throws
     * UnsupportedOperationException against an immutable Set.of() (confirmed
     * live: this was actually happening on every call before a debug port
     * existed yet, i.e. essentially always, since we snapshot immediately
     * after the terminal launches).
     */
    static Set<Integer> listeningPorts(long pid) {
        if (SystemInfo.isLinux) {
            return listeningPortsLinux(pid);
        }
        if (SystemInfo.isMac) {
            return listeningPortsMac(pid);
        }
        if (UNSUPPORTED_OS_LOGGED.compareAndSet(false, true)) {
            LOG.warn("Automatic Rascal debug port discovery isn't implemented for "
                + SystemInfo.OS_NAME + " (only Linux and macOS are supported) -- "
                + "find the port manually and paste it into LSP4IJ's Attach config.");
        }
        return new HashSet<>();
    }

    private static Set<Integer> listeningPortsLinux(long pid) {
        Set<Integer> ports = new HashSet<>();
        Set<Long> inodes = socketInodes(pid);
        if (inodes.isEmpty()) {
            return ports;
        }
        for (String procNetFile : List.of("/proc/net/tcp", "/proc/net/tcp6")) {
            readListeningPorts(procNetFile, inodes, ports);
        }
        return ports;
    }

    /**
     * macOS has no /proc, so this shells out to {@code lsof} instead (no shell
     * involved -- args are passed directly to ProcessBuilder, not through
     * `sh -c`). {@code -P -n} disable port/host name resolution so the
     * output is always numeric, and {@code -a -iTCP -sTCP:LISTEN} restrict
     * the listing to just this PID's listening TCP sockets, e.g.:
     * {@code java  12345 user  123u  IPv6 0x...  0t0  TCP *:54321 (LISTEN)}
     * <p>
     * Resolves {@code lsof}'s absolute path the same way {@link
     * RascalTerminalSupport#computeDependencyClasspath} resolves {@code mvn}
     * -- a bare command name is looked up against the *launching* IDE
     * process's own PATH, not whatever this ProcessBuilder's environment is
     * configured with, so a GUI-launched IDE on macOS could fail to find
     * even a system binary like {@code lsof} the same way it failed to find
     * {@code mvn}, silently leaving port discovery empty for the entire
     * polling window instead of erroring out immediately.
     */
    private static Set<Integer> listeningPortsMac(long pid) {
        Set<Integer> ports = new HashSet<>();
        try {
            File lsofExecutable = PathEnvironmentVariableUtil.findInPath("lsof");
            String lsofCommand = lsofExecutable != null ? lsofExecutable.getAbsolutePath() : "lsof";
            ProcessBuilder builder = new ProcessBuilder(
                lsofCommand, "-a", "-p", String.valueOf(pid), "-iTCP", "-sTCP:LISTEN", "-P", "-n"
            ).redirectErrorStream(true);
            builder.environment().putAll(EnvironmentUtil.getEnvironmentMap());
            Process process = builder.start();
            List<String> lines;
            try (var reader = process.inputReader()) {
                lines = reader.lines().toList();
            }
            process.waitFor();
            for (String line : lines) {
                Matcher matcher = MAC_LSOF_LISTEN_PORT.matcher(line);
                if (matcher.find()) {
                    try {
                        ports.add(Integer.parseInt(matcher.group(1)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to run lsof for PID " + pid, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ports;
    }

    /** Polls until a LISTEN port not present in {@code before} appears, or the timeout elapses. */
    static Optional<Integer> waitForNewListeningPort(long pid, Set<Integer> before, Duration timeout) {
        // Linux polling is just a couple of file reads, cheap at 200ms; the
        // macOS path spawns an `lsof` subprocess per tick, so it polls less
        // often (90s of timeout is ~450 spawns at 200ms vs. ~180 at 500ms).
        long pollIntervalMs = SystemInfo.isMac ? 500 : 200;
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Set<Integer> now = listeningPorts(pid);
            now.removeAll(before);
            if (!now.isEmpty()) {
                return Optional.of(now.iterator().next());
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static void readListeningPorts(String procNetFile, Set<Long> inodesOfInterest, Set<Integer> out) {
        Path path = Path.of(procNetFile);
        if (!Files.isReadable(path)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(path);
            // Format (whitespace-separated, header on line 0):
            // sl local_address rem_address st tx_queue:rx_queue tr:tm->when retrnsmt uid timeout inode ...
            for (int i = 1; i < lines.size(); i++) {
                String[] fields = lines.get(i).trim().split("\\s+");
                if (fields.length < 10) {
                    continue;
                }
                String state = fields[3];
                if (!"0A".equalsIgnoreCase(state)) { // TCP_LISTEN
                    continue;
                }
                long inode;
                try {
                    inode = Long.parseLong(fields[9]);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (!inodesOfInterest.contains(inode)) {
                    continue;
                }
                String localAddress = fields[1];
                int colon = localAddress.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                out.add(Integer.parseInt(localAddress.substring(colon + 1), 16));
            }
        } catch (IOException ignored) {
        }
    }

    private static Set<Long> socketInodes(long pid) {
        Path fdDir = Path.of("/proc/" + pid + "/fd");
        Set<Long> inodes = new HashSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(fdDir)) {
            for (Path fd : entries) {
                try {
                    String target = Files.readSymbolicLink(fd).toString();
                    if (target.startsWith("socket:[") && target.endsWith("]")) {
                        inodes.add(Long.parseLong(target.substring(8, target.length() - 1)));
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return inodes;
    }
}
