/*
 * Copyright (c) 2026, cwi-swat
 * All rights reserved. This file is licensed under the BSD 2-Clause
 * License -- see the LICENSE file in this directory.
 */
package nl.cwi.swat.rascal.intellij;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.commands.LSPCommand;
import com.redhat.devtools.lsp4ij.commands.LSPCommandAction;

/**
 * Handles the "rascalmpl.runMain" LSP command -- the "Run in new Rascal
 * terminal" CodeLens above a module's top-level main() function. Like
 * importModule, its sole argument is the enclosing module's qualified name
 * (not the function itself -- see org.rascalmpl.vscode.lsp.rascal
 * .RascalLanguageServices#locateCodeLenses). Beyond importing the module,
 * this also turns on the interpreter's debugger (":set debugging true",
 * the same meta-command org.rascalmpl.repl.rascal.RascalInterpreterREPL
 * itself recognizes), matching what the real VS Code extension does before
 * leaving you to type main(...) yourself with whatever arguments you want.
 */
public class RunMainAction extends LSPCommandAction {
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
        RascalTerminalSupport.importThenMaybeDebug(project, moduleName, true);
    }
}
