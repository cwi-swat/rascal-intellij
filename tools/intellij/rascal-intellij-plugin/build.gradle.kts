/*
 * Copyright (c) 2026, cwi-swat
 * All rights reserved. This file is licensed under the BSD 2-Clause
 * License -- see the LICENSE file in this directory.
 */

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

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
    // Plugin signing (via ./sign.sh, which runs `signPlugin`), see README.md.
    // The private key and pass phrase come only from the environment -- never
    // commit them.
    signing {
        // The certificate is public, so it may default to its usual location --
        // that lets `verifyPluginSignature` run straight from IntelliJ's Gradle
        // view. The private key and its pass phrase stay environment-only
        // (set by sign.sh), so signing itself still needs ./sign.sh.
        certificateChainFile = providers.environmentVariable("CERTIFICATE_CHAIN_FILE")
            .orElse(System.getProperty("user.home") + "/.rascal-intellij-signing/chain.crt")
            .map { file(it) }
        privateKeyFile = providers.environmentVariable("PRIVATE_KEY_FILE").map { file(it) }
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    // `./gradlew verifyPlugin`: runs the IntelliJ Plugin Verifier (the same
    // check the Marketplace runs on every upload) against one release of each
    // IDE version in the sinceBuild..untilBuild range.
    pluginVerification {
        ides {
            recommended()
        }
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

// `./gradlew showPluginSignature`: prints the certificate embedded in the signed
// zip (subject, issuer, validity, SHA-256 fingerprint) -- JetBrains' tooling
// only offers sign/verify, nothing to *show* who signed a zip. The signature
// block stores the certificate in DER form, so this scans for a parseable
// X.509 certificate. Compare the fingerprint with
// `openssl x509 -in ~/.rascal-intellij-signing/chain.crt -noout -fingerprint -sha256`.
tasks.register("showPluginSignature") {
    group = "intellij platform"
    description = "Prints the certificate embedded in the signed plugin zip."
    val signedZip = layout.buildDirectory.file("distributions/rascal-intellij-${project.version}-signed.zip")
    doLast {
        val zipFile = signedZip.get().asFile
        if (!zipFile.isFile) {
            throw GradleException("No signed zip at $zipFile -- run ./sign.sh first.")
        }
        val bytes = zipFile.readBytes()
        val factory = CertificateFactory.getInstance("X.509")
        var found = 0
        var i = 0
        while (i < bytes.size - 4) {
            // DER SEQUENCE with a two-byte length: 0x30 0x82 <len hi> <len lo>
            if (bytes[i] == 0x30.toByte() && bytes[i + 1] == 0x82.toByte()) {
                val length = ((bytes[i + 2].toInt() and 0xff) shl 8) + (bytes[i + 3].toInt() and 0xff) + 4
                if (i + length <= bytes.size) {
                    val cert = try {
                        factory.generateCertificate(ByteArrayInputStream(bytes, i, length)) as X509Certificate
                    } catch (e: Exception) {
                        null
                    }
                    if (cert != null) {
                        found++
                        val fingerprint = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                            .joinToString(":") { "%02X".format(it) }
                        println("Signed zip:  ${zipFile.name}")
                        println("Subject:     ${cert.subjectX500Principal.name}")
                        println("Issuer:      ${cert.issuerX500Principal.name}" +
                            if (cert.subjectX500Principal == cert.issuerX500Principal) "  (self-signed)" else "")
                        println("Valid:       ${cert.notBefore} .. ${cert.notAfter}")
                        println("SHA-256:     $fingerprint")
                        i += length
                        continue
                    }
                }
            }
            i++
        }
        if (found == 0) {
            throw GradleException("No certificate found in $zipFile -- is it signed?")
        }
    }
}
