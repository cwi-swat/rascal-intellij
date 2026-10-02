/*
 * Copyright (c) 2026, cwi-swat
 * All rights reserved. This file is licensed under the BSD 2-Clause
 * License -- see the LICENSE file in this directory.
 */
package nl.cwi.swat.rascalterminal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.redhat.devtools.lsp4ij.LanguageServerManager;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor;
import com.redhat.devtools.lsp4ij.ServerStatus;
import com.redhat.devtools.lsp4ij.server.definition.ServerFileNamePatternMapping;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Makes {@code util::LanguageServer::registerLanguage(...)}, run in a
 * "Rascal: ..." terminal, work in IntelliJ -- the counterpart of the VS
 * Code extension's LanguageRegistry.ts + ParameterizedLanguageServer.ts.
 * <p>
 * How it works in rascal-lsp 2.22.4 (verified against its sources): the
 * REPL-side org.rascalmpl.vscode.lsp.parametric.RascalInterface reads the
 * system property {@code rascal.languageRegistryPort} once, and connects
 * to a JSON-RPC server on that loopback port, which the IDE side has to
 * host itself. Without it, every registerLanguage prints
 * "Could not register language: no connection". That server gets
 * {@code rascal/receiveRegisterLanguage} / {@code
 * rascal/receiveUnregisterLanguage} requests and forwards them, as
 * {@code rascal/sendRegisterLanguage} / {@code rascal/sendUnregisterLanguage},
 * to rascal-lsp's parametric language server (see
 * {@link RascalParametricLanguageServerFactory}), which loads the DSL's
 * contributions and serves every file with a registered extension. This
 * class is that JSON-RPC server, one per project (the terminal for project P
 * passes P's port, see RascalTerminalSupport). It also tells LSP4IJ which
 * file extensions now belong to the parametric server.
 * <p>
 * (The separate {@code --remoteIDEServicesPort} RascalShell argument is
 * <em>not</em> involved in language registration: it only reroutes
 * IDEServices calls such as edit/browse/startDebuggingSession.)
 */
@Service(Service.Level.PROJECT)
public final class RascalLanguageRegistry implements Disposable {

    static final String PARAMETRIC_SERVER_ID = "rascal-parametric";

    /** The LSP languageId VS Code also uses for every parametric document. */
    private static final String PARAMETRIC_LANGUAGE_ID = "parametric-rascalmpl";

    private static final Logger LOG = Logger.getInstance(RascalLanguageRegistry.class);

    /**
     * LSP4IJ's file associations are application-wide (one
     * LanguageServersRegistry), so this has to be, too -- otherwise two
     * projects registering the same extension would add it twice.
     */
    private static final Set<String> ASSOCIATED_EXTENSIONS = ConcurrentHashMap.newKeySet();

    private final Project project;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "Rascal language registry");
        t.setDaemon(true);
        return t;
    });
    private final List<Socket> connections = new CopyOnWriteArrayList<>();

    // All guarded by `this`.
    private ServerSocket serverSocket;
    /** Every registration received so far, keyed like VS Code does (mainModule::mainFunction), for replaying after a server restart. */
    private final Map<String, JsonObject> languages = new LinkedHashMap<>();
    /** The parametric server instance {@link #sentToServer} refers to; compared by identity only (it's an lsp4j proxy). */
    private RascalParametricServerApi currentServer;
    /** Which registrations (by object identity) were already sent to {@link #currentServer}. */
    private Map<JsonObject, CompletableFuture<Void>> sentToServer = new IdentityHashMap<>();
    private RascalParametricLanguageClient currentClient;

    public RascalLanguageRegistry(@NotNull Project project) {
        this.project = project;
    }

    static RascalLanguageRegistry getInstance(@NotNull Project project) {
        return project.getService(RascalLanguageRegistry.class);
    }

    /** The loopback port to pass to a REPL as {@code -Drascal.languageRegistryPort}; starts listening on first use. */
    synchronized int port() throws IOException {
        if (serverSocket == null) {
            ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            serverSocket = socket;
            executor.execute(() -> acceptLoop(socket));
            LOG.info("Rascal language registry for " + project.getName() + " listening on port " + socket.getLocalPort());
        }
        return serverSocket.getLocalPort();
    }

    private void acceptLoop(ServerSocket socket) {
        while (!socket.isClosed()) {
            try {
                Socket connection = socket.accept();
                connection.setTcpNoDelay(true);
                connections.add(connection);
                Launcher<NoRemoteMethods> launcher = new Launcher.Builder<NoRemoteMethods>()
                    .setLocalService(new Endpoint())
                    .setRemoteInterface(NoRemoteMethods.class)
                    .setInput(connection.getInputStream())
                    .setOutput(connection.getOutputStream())
                    .setExecutorService(executor)
                    .create();
                // Kept only so dispose() can close it; one per REPL session.
                launcher.startListening();
                LOG.info("Rascal REPL connected to the language registry from " + connection.getRemoteSocketAddress());
            } catch (IOException e) {
                if (!socket.isClosed()) {
                    LOG.warn("Rascal language registry: accepting a REPL connection failed", e);
                }
            }
        }
    }

    /** Must stay public: lsp4j finds the {@code @JsonRequest} methods reflectively. */
    public final class Endpoint {
        @JsonRequest("rascal/receiveRegisterLanguage")
        public CompletableFuture<Void> registerLanguage(JsonObject lang) {
            return register(lang);
        }

        @JsonRequest("rascal/receiveUnregisterLanguage")
        public CompletableFuture<Void> unregisterLanguage(JsonObject lang) {
            return unregister(lang);
        }
    }

    /** The REPL side (RascalInterface) exposes nothing; lsp4j still needs some remote interface. */
    public interface NoRemoteMethods {
    }

    private CompletableFuture<Void> register(JsonObject lang) {
        LOG.info("registerLanguage from REPL: " + lang);
        synchronized (this) {
            languages.put(key(lang), lang);
        }
        CompletableFuture<Void> result = LanguageServerManager.getInstance(project)
            .getLanguageServer(PARAMETRIC_SERVER_ID)
            .thenCompose(item -> {
                if (item == null || !(item.getServer() instanceof RascalParametricServerApi server)) {
                    throw new IllegalStateException("the parametric Rascal language server '" + PARAMETRIC_SERVER_ID + "' could not be started (see idea.log / the LSP console)");
                }
                return sendOnce(server, lang);
            })
            .thenRun(() -> associateAndRefresh(extensions(lang)));
        result.whenComplete((ignored, e) -> {
            if (e != null) {
                LOG.warn("registerLanguage failed for " + lang, e);
                notify("Could not register Rascal language " + string(lang, "name"),
                    e.getClass().getSimpleName() + ": " + e.getMessage() + " (see idea.log)", NotificationType.ERROR);
            } else {
                LOG.info("Registered Rascal language " + string(lang, "name") + " for " + extensions(lang));
            }
        });
        return result;
    }

    private CompletableFuture<Void> unregister(JsonObject lang) {
        LOG.info("unregisterLanguage from REPL: " + lang);
        synchronized (this) {
            // Same semantics as VS Code: with a mainModule/mainFunction only
            // that one contribution goes, otherwise the whole language does.
            if (!string(lang, "mainModule").isEmpty() && !string(lang, "mainFunction").isEmpty()) {
                languages.remove(key(lang));
            } else {
                String name = string(lang, "name");
                languages.values().removeIf(l -> string(l, "name").equals(name));
            }
        }
        // Not running means it has nothing registered either; don't start it just to unregister.
        if (LanguageServerManager.getInstance(project).getServerStatus(PARAMETRIC_SERVER_ID) != ServerStatus.started) {
            return CompletableFuture.completedFuture(null);
        }
        return LanguageServerManager.getInstance(project)
            .getLanguageServer(PARAMETRIC_SERVER_ID)
            .thenCompose(item -> item != null && item.getServer() instanceof RascalParametricServerApi server
                ? server.sendUnregisterLanguage(lang)
                : CompletableFuture.completedFuture(null));
    }

    /** Called by {@link RascalParametricLanguageClient} each time a (new) parametric server instance is up. */
    void serverStarted(RascalParametricServerApi server, RascalParametricLanguageClient client) {
        List<JsonObject> toReplay;
        synchronized (this) {
            currentClient = client;
            toReplay = new ArrayList<>(languages.values());
        }
        if (toReplay.isEmpty()) {
            return;
        }
        LOG.info("Parametric Rascal language server (re)started; re-registering " + toReplay.size() + " language(s)");
        Set<String> extensions = new LinkedHashSet<>();
        List<CompletableFuture<Void>> sends = new ArrayList<>();
        for (JsonObject lang : toReplay) {
            extensions.addAll(extensions(lang));
            sends.add(sendOnce(server, lang));
        }
        CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
            .thenRun(() -> associateAndRefresh(extensions))
            .exceptionally(e -> {
                LOG.warn("Re-registering languages with the restarted parametric server failed", e);
                return null;
            });
    }

    /**
     * Sends a registration to the given server instance at most once: when
     * the first registerLanguage itself causes the server to start, both
     * {@link #register} and the start-up replay in {@link #serverStarted}
     * would otherwise send it, making the server load the DSL twice.
     * Re-running registerLanguage in the REPL produces a new JsonObject,
     * so it is still sent again (and reloads the DSL, as in VS Code).
     */
    private synchronized CompletableFuture<Void> sendOnce(RascalParametricServerApi server, JsonObject lang) {
        if (server != currentServer) {
            currentServer = server;
            sentToServer = new IdentityHashMap<>();
        }
        return sentToServer.computeIfAbsent(lang, server::sendRegisterLanguage);
    }

    /**
     * Maps {@code *.<ext>} to the parametric server in LSP4IJ, then connects
     * any already-open editor with one of those extensions and asks for
     * fresh semantic tokens. Deliberately does <em>not</em> fire LSP4IJ's
     * "mappings changed" event: that restarts the server, wiping the
     * registration just made. rascal-lsp also never sends a
     * semanticTokens/refresh itself, which is why VS Code toggles the
     * document language after registering; the refresh below is the
     * equivalent.
     */
    private void associateAndRefresh(Set<String> extensions) {
        ApplicationManager.getApplication().invokeLater(() -> {
            var registry = LanguageServersRegistry.getInstance();
            var definition = registry.getServerDefinition(PARAMETRIC_SERVER_ID);
            if (definition == null) {
                LOG.warn("No LSP4IJ server definition '" + PARAMETRIC_SERVER_ID + "' -- check plugin.xml");
                return;
            }
            for (String ext : extensions) {
                if (ASSOCIATED_EXTENSIONS.add(ext)) {
                    registry.registerAssociation(definition, new ServerFileNamePatternMapping(
                        List.of("*." + ext), PARAMETRIC_SERVER_ID, PARAMETRIC_LANGUAGE_ID, (file, p) -> true));
                }
            }

            List<CompletableFuture<?>> connects = new ArrayList<>();
            for (VirtualFile file : FileEditorManager.getInstance(project).getOpenFiles()) {
                if (file.getExtension() != null && extensions.contains(file.getExtension())) {
                    // Same call LSP4IJ itself makes when an editor opens: it
                    // starts/matches the servers for the file and sends didOpen.
                    connects.add(ReadAction.compute(() -> {
                        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                        return psiFile == null
                            ? CompletableFuture.completedFuture(null)
                            : LanguageServiceAccessor.getInstance(project).getLanguageServers(psiFile, null, null);
                    }));
                }
            }
            CompletableFuture.allOf(connects.toArray(CompletableFuture[]::new)).thenRun(() -> {
                RascalParametricLanguageClient client;
                synchronized (this) {
                    client = currentClient;
                }
                if (client != null) {
                    client.refreshSemanticTokens();
                }
            });
        }, project.getDisposed());
    }

    private static String key(JsonObject lang) {
        return string(lang, "mainModule") + "::" + string(lang, "mainFunction");
    }

    private static Set<String> extensions(JsonObject lang) {
        Set<String> result = new LinkedHashSet<>();
        JsonElement exts = lang.get("extensions");
        if (exts != null && exts.isJsonArray()) {
            for (JsonElement ext : exts.getAsJsonArray()) {
                String e = ext.getAsString();
                result.add(e.startsWith(".") ? e.substring(1) : e);
            }
        }
        return result;
    }

    private static String string(JsonObject lang, String field) {
        JsonElement value = lang.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private void notify(String title, String content, NotificationType type) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Rascal Terminal")
            .createNotification(title, content, type)
            .notify(project);
    }

    @Override
    public void dispose() {
        synchronized (this) {
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                    // shutting down anyway
                }
            }
        }
        for (Socket connection : connections) {
            try {
                connection.close();
            } catch (IOException ignored) {
                // shutting down anyway
            }
        }
        executor.shutdownNow();
    }
}
