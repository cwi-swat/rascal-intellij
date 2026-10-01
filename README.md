
# Developing | updating | building the plugin
 
This project is a `Gradle IntelliJ Platform plugin` project.<br>
&nbsp;&nbsp;IntelliJ does not hot-reload plugins from disk.<br>
&nbsp;&nbsp;Whenever you change its source, you need to:

* rebuild the zip using the `gradle` build tool
* create a new [release on our github repo](https://github.com/Periteleios/rascal-intellij-debugger-releases)
* reinstall it in IntelliJ
  - Settings > Plugins > gear icon > Install Plugin from Disk..., then restart when prompted.

This build needs a JDK **25+** (platform 2026.2.2's own jars are class v69; the plugin's own bytecode still targets 17).

### Option 1)
**use Gradle tool to manage building the plugin build cycle**
- `Gradle tool window` > `gear icon` > `Gradle Settings` > `"Gradle JVM"` dropdown. 
- Pick or download any `JDK 25+` there


### Option 2)
**use command line**

1. Check what JDKs you already have
   - the exact path varies by OS and by how the JDK was installed, <br>so check more than one place:
    
    ```bash
    ls ~/.jdks/                              # JetBrains-managed downloads (all platforms)
    ls /Library/Java/JavaVirtualMachines/    # macOS  (Homebrew, vendor installers, some IntelliJ downloads)
    /usr/libexec/java_home -V                # macOS  (JDKs registered with the system)
    ```

2. If nothing `25+` shows up, download one via 
   - `File` > `Project Structure` > `SDKs` > `+` > `Download JDK` 
   - **Amazon Corretto 26** pick that if it's offered
   - downloads should land under `~/.jdks/` by default <br>
     to pin/confirm, update IntelliJ's VM options (`Help` > `Edit Custom VM Options...`, or edit `idea.vmoptions` directly):
     ```
     -Djdk.downloader.home=~/.jdks
     ```
     - Mac: `~/Library/Application Support/JetBrains/IntelliJIdea2026.2/idea.vmoptions`
     - Linux: `~/.config/JetBrains/IntelliJIdea2026.2/idea.vmoptions`
   <br><br>
3. Set `JAVA_HOME` to the path IntelliJ actually installed it at (shown in the SDK's "JDK home path" field). <br>
   Confirm it with `ls ~/.jdks/` <br>
   on **Mac**, the actual JDK (`bin/java`) sits nested under `<folder>/Contents/Home/` <br>
   not at the folder's top level (Linux's tarball is flat, so `bin/java` is right at the top there). <br>
   <br><br>

---

###### Useful Gradle Tasks

**Clean**

```bash
cd tools/intellij/rascal-debugger-plugin
export JAVA_HOME=~/.jdks/corretto-26.0.2.1
[ -d "$JAVA_HOME/Contents/Home" ] && export JAVA_HOME="$JAVA_HOME/Contents/Home"
./gradlew clean
```

**Build**

```bash
cd tools/intellij/rascal-debugger-plugin
export JAVA_HOME=~/.jdks/corretto-26.0.2.1
[ -d "$JAVA_HOME/Contents/Home" ] && export JAVA_HOME="$JAVA_HOME/Contents/Home"
./gradlew buildPlugin
```

Output: `build/distributions/rascal-debugger-<version>.zip` (version from
`build.gradle.kts`).

###### Other Gradle tasks

**List Gradle tasks**
```bash
cd tools/intellij/rascal-debugger-plugin
export JAVA_HOME=~/.jdks/corretto-26.0.2.1
[ -d "$JAVA_HOME/Contents/Home" ] && export JAVA_HOME="$JAVA_HOME/Contents/Home"
./gradlew tasks
```

**Launch a sandbox IDE with the plugin pre-installed** <br>-- a disposable
IntelliJ instance, the fast loop for iterating without reinstalling into
your real IDE each time (see "Building/updating the plugin jar" above for
why a real install still needs a rebuild + "Install Plugin from Disk").
This is also the best way to verify the auto-configuration in "Quick
start" above actually works with zero prior setup, since the sandbox
starts with none of its own -- see this project's own commit history for
the exact log line (`RascalProjectActivity - Auto-created the "Rascal
Attach" DAP configuration for ...`) that proves the auto-creation path
actually ran, not just that a configuration happened to already exist.

```bash
cd tools/intellij/rascal-debugger-plugin
export JAVA_HOME=~/.jdks/corretto-26.0.2.1
[ -d "$JAVA_HOME/Contents/Home" ] && export JAVA_HOME="$JAVA_HOME/Contents/Home"
./gradlew runIde
```

**Reset that sandbox instance**<br> -- wipes its own state (config/plugins/
system dirs under `.intellijPlatform/sandbox/`) if `runIde` ever gets into
a broken state. Separate from `clean`, which doesn't touch it.

```bash
cd tools/intellij/rascal-debugger-plugin
export JAVA_HOME=~/.jdks/corretto-26.0.2.1
[ -d "$JAVA_HOME/Contents/Home" ] && export JAVA_HOME="$JAVA_HOME/Contents/Home"
./gradlew cleanSandbox
```

**Verify IDE-version compatibility**<br> -- runs the IntelliJ Plugin Verifier
against the `sinceBuild`/`untilBuild` range declared in `build.gradle.kts`.
Catches "this won't load on IDE version X" problems before shipping a
release.

```bash
cd tools/intellij/rascal-debugger-plugin
export JAVA_HOME=~/.jdks/corretto-26.0.2.1
[ -d "$JAVA_HOME/Contents/Home" ] && export JAVA_HOME="$JAVA_HOME/Contents/Home"
./gradlew verifyPlugin
```

**Sanity-check the Gradle project setup**<br> -- JDK version, `plugin.xml`
fields, target platform compatibility. Worth running after editing
`build.gradle.kts`.

```bash
cd tools/intellij/rascal-debugger-plugin
export JAVA_HOME=~/.jdks/corretto-26.0.2.1
[ -d "$JAVA_HOME/Contents/Home" ] && export JAVA_HOME="$JAVA_HOME/Contents/Home"
./gradlew verifyPluginProjectConfiguration
```


---

# Installing and Running the plugin

#### Prerequisite: 
- project SDK of Java 11+ `File > Project Structure > Project > SDK`
- build the project once so `target/classes` exists and the Maven jars are in your local `~/.m2`
repository:

    ```bash
    ./build.sh
    ```

#### Install the plugin:
- `Settings/Preferences > Plugins > gear icon (⚙) > Manage Plugin Repositories... > + >` <br>
   
    ```
    https://raw.githubusercontent.com/Periteleios/rascal-intellij-debugger-releases/main/updatePlugins.xml
    ```

- Go to the **Marketplace** tab and search "Rascal Debugger"
- Install it, restart when prompted.


#### Open the test file provided `src/main/rascal/Sanity.rsc`
- Inspect [Sanity.rsc](src/main/rascal/Sanity.rsc) for syntax highlighting,diagnostics/hover/CodeLenses and <br>
  breakpoints/stepping. Should work against whichever Maven-based Rascal project is currently open.

> Note (**Mac only**): `RascalLanguageServerFactory` doesn't inherit the Project SDK above.
> If you started the debugger and `.rsc` files fail to load and you see the message
> `JAVA_HOME environment variable is not defined correctly`, do set
> `export JAVA_HOME=~/.jdks/<your-11+-JDK>` to `~/.zshenv`
> Restart IntelliJ 

### What gets auto-configured, and how

Three plugin classes, each registered via an IntelliJ or LSP4IJ extension
point are worth knowing about if something doesn't work, and you want to
know where to look in `idea.log` (`Help > Show Log in Files/Finder`):

- **`RascalTextMateBundleProvider`** registers the syntax-highlighting
  grammar (`rascal-textmate-bundle/`, shipped as a plugin resource,
  extracted once to a real directory on first use -- TextMate bundles need
  an actual filesystem path, not a jar resource).
  <br><br>
- **`RascalLanguageServerFactory`** registers the `.rsc` Language Server,
  computing its classpath from whichever project is currently open (the
  same computation "Import"/"Run in new Rascal terminal" already used).
  <br><br>
- **`RascalProjectActivity`** auto-creates the "Rascal Attach" DAP
  Run/Debug configuration on project open, including the exact
  `*.rsc -> rascal` Mappings-tab entry that's easy to miss by hand and,
  when missed, causes a completely silent breakpoint failure (no error, no
  gutter dot). Idempotent: if a DAP configuration already exists (of any
  name), it leaves it alone rather than creating a duplicate.
  <br><br>
- **`RascalLanguageRegistry`** makes `util::LanguageServer::registerLanguage(...)`
  work from a "Rascal: ..." terminal (as in VS Code). Each terminal gets
  `-Drascal.languageRegistryPort=<port>`; registrations arriving there are
  forwarded to the "Rascal DSL Language Server (parametric)" (also
  registered automatically, by `RascalParametricLanguageServerFactory`),
  and each registered extension (e.g. `*.hql`) is mapped to it at runtime --
  see Settings > Languages & Frameworks > Language Servers > Mappings.
  Registrations live only as long as the IDE session: re-run the REPL's
  `registerLanguage` calls after a restart. If IntelliJ's own SQL support
  also claims a DSL's extension, move that pattern to the Text file type
  (Settings > Editor > File Types) so only the DSL's grammar applies.

