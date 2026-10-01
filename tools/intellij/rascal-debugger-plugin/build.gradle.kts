/*
 * Copyright (c) 2026, Periteleios
 * All rights reserved. This file is licensed under the BSD 2-Clause
 * License -- see the LICENSE file in this directory.
 */

plugins {
    id("java")
    id("org.jetbrains.intellij.platform")
}

group = "com.periteleios"
version = "0.1.7"

dependencies {
    intellijPlatform {
        intellijIdea("2026.2.2")
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledPlugin("org.jetbrains.plugins.textmate")
        plugin("com.redhat.devtools.lsp4ij", "0.21.0")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.periteleios.rascal-terminal"
        name = "Rascal Debugger"
        version = project.version.toString()
        description = "Adds Rascal debugging support to IntelliJ via LSP4IJ: 'Import'/'Run in new Rascal terminal' CodeLenses that open a real Rascal REPL (and, for 'Run', turn on the interpreter's debugger), plus a 'Rascal Debugger' DAP server that fixes a per-file breakpoint sync bug in LSP4IJ 0.21.0's stock DAP breakpoint handler. Works with any Maven-based Rascal project."
        ideaVersion {
            sinceBuild = "242"
        }
    }
}

// A plain sourceCompatibility/targetCompatibility string assignment on
// JavaCompile is silently ineffective here -- confirmed live: it still
// produced class files at major version 69 (Java 25) instead of 61 (Java
// 17), which is why the built plugin failed to load on an IDE whose own
// boot JDK was older (e.g. 21: UnsupportedClassVersionError). `--release`
// is the flag that actually gets honored; switching to a JDK 17 *toolchain*
// instead (rather than just adding this flag) doesn't work either --
// IntelliJ Platform 2026.2.2's own jars (e.g. util.jar) are themselves
// class version 69, and an actual JDK 17 javac binary can't parse those
// while reading the compile classpath ("bad class file ... has wrong
// version 69.0, should be 61.0"). Keeping the ambient (newer) javac and
// only passing --release avoids that, since a newer compiler can always
// read older-or-equal class files.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}
