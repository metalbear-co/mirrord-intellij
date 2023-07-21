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


## Adding E2E tests

We use the [intellij-ui-test-robot](https://github.com/JetBrains/intellij-ui-test-robot) to automate UI tests for the extension.

- If you have made a change to the UI, to add new tests, run `./gradlew runIdeForUITests` and open a test project,
go to [localhost:8082](http://localhost:8082) and choose the elements you want to click on by their Xpath.

- To make sure the test doesn't flake in the CI, check if all the elements related to UI component have loaded by using
 a `waitFor` fixture or if a change depends on files to be indexed, use the `dumbAware` fixture.

- As a rule of thumb try to encapsulate functionality in fixtures in the `utils` folder.

- To verify things work as expected locally, 



