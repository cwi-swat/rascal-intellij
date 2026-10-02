/*
 * Copyright (c) 2026, cwi-swat
 * All rights reserved. This file is licensed under the BSD 2-Clause
 * License -- see the LICENSE file in this directory.
 */
package nl.cwi.swat.rascalterminal;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.commands.LSPCommand;
import com.redhat.devtools.lsp4ij.commands.LSPCommandAction;

/**
 * Handles the "rascalmpl.importModule" LSP command -- the "Import in new
 * Rascal terminal" CodeLens above every Rascal module declaration. The
 * command's sole argument is the module's qualified name as written in
 * source (see org.rascalmpl.vscode.lsp.rascal.RascalLanguageServices
 * #locateCodeLenses).
 */
public class ImportModuleAction extends LSPCommandAction {
    @Override
    protected void commandPerformed(LSPCommand command, AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        String moduleName = command.getArgumentAt(0, String.class);
        if (moduleName == null) {
            return;
        }
        RascalTerminalSupport.importThenMaybeDebug(project, moduleName, false);
    }
}
