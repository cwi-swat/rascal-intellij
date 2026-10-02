/*
 * Copyright (c) 2026, cwi-swat
 * All rights reserved. This file is licensed under the BSD 2-Clause
 * License -- see the LICENSE file in this directory.
 */

plugins {
    id("java")
    id("org.jetbrains.intellij.platform")
}

group = "nl.cwi.swat"
version = "1.0.0"

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
        id = "nl.cwi.swat.rascal-intellij"
        name = "Rascal"
        version = project.version.toString()
        description = """
            Rascal language support for IntelliJ, built on LSP4IJ and the official rascal-lsp
            language server. Works with any Maven-based Rascal project, with no manual setup:
            <ul>
              <li>Editing .rsc files: syntax highlighting, diagnostics, hover, completion,
              Go to Definition (including into the standard library) and CodeLenses.</li>
              <li>"Import" / "Run in new Rascal terminal" open a real Rascal REPL; "Run" also
              turns on the debugger and attaches automatically, with breakpoints and stepping.</li>
              <li>DSLs: <code>util::LanguageServer::registerLanguage(...)</code> in a Rascal
              terminal gives the DSL's files syntax highlighting and parse-error markers, as in
              VS Code.</li>
              <li>Clickable Rascal source locations in terminal output and the LSP console.</li>
            </ul>
            Requires the project's rascal and rascal-lsp Maven dependencies (they are not bundled).
        """.trimIndent()
        ideaVersion {
            sinceBuild = "242"
        }
    }
    // Plugin signing (`./gradlew signPlugin`; publishPlugin signs too), see
    // README.md. Key and certificate come only from the environment -- never
    // commit them.
    signing {
        certificateChainFile = providers.environmentVariable("CERTIFICATE_CHAIN_FILE").map { file(it) }
        privateKeyFile = providers.environmentVariable("PRIVATE_KEY_FILE").map { file(it) }
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    // JetBrains Marketplace upload (`./gradlew publishPlugin`), see README.md.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
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
