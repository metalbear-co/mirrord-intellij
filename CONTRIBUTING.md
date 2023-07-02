# Contributing

Before submitting pull request features, please discuss them with us first by opening an issue or a discussion.
We welcome new/junior/starting developers. Feel free to join to our [Discord channel](https://discord.gg/metalbear) for help and guidance.

If you would like to start working on an issue, please comment on the issue on GitHub, so that we can assign you to that
issue.

## Building the IntelliJ plugin

First, [build the mirrord binaries](https://github.com/metalbear-co/mirrord/blob/main/CONTRIBUTING.md#build-and-run-mirrord) if not yet built. Then:

```bash
cd intellij-ext
```

### On macOS

```bash
cp <path to mirrord repo>/target/universal-apple-darwin/debug/libmirrord_layer.dylib .
touch libmirrord_layer.so
cp <path to mirrord repo>/target/universal-apple-darwin/debug/mirrord bin/macos/
```

### On Linux x86-64

```bash
cp <path to mirrord repo>/target/debug/libmirrord_layer.so .
touch libmirrord_layer.dylib
cp <path to mirrord repo>/target/debug/mirrord bin/linux/x86-64/mirrord
```

### In order to "cross build"
Just include all the binaries in the `mirrord-intellij` directory:
```text
libmirrord_layer.dylib
libmirrord_layer.so
bin/macos/mirrord
bin/linux/x86-64/mirrord
bin/linux/arm64/mirrord
```

Then build the plugin:
```bash
./gradlew buildPlugin
```

## Debugging the IntelliJ plugin

To debug the IntelliJ plugin, first [build the plugin](#building-the-intellij-plugin).

Now open the plugin's code in IntelliJ IDEA. Create a new Gradle run configuration with a `runIde` task.
Running this configuration in debug will open a new IDE window.
You can set breakpoints in the plugin's code in the first window, and use the plugin in the second window to reach the breakpoints.

You can control which IDE is opened with a `PLATFORMTYPE` environment variable. For example, set `PLATFORMTYPE=IU` for IntelliJ IDEA Ultimate.