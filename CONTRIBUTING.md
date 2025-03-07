# Contributing

Before submitting pull request features, please discuss them with us first by opening an issue or a discussion.
We welcome new/junior/starting developers. Feel free to join to our [Discord channel](https://discord.gg/metalbear) for help and guidance.

If you would like to start working on an issue, please comment on the issue on GitHub, so that we can assign you to that
issue.

## Building the IntelliJ plugin

First, make sure you have JDK 17 installed. 

Then [build the mirrord binaries](https://github.com/metalbear-co/mirrord/blob/main/CONTRIBUTING.md#build-and-run-mirrord) if not yet built. Then:

```bash
cd mirrord-intellij
```

### On macOS

```bash
cp <path to mirrord repo>/target/universal-apple-darwin/debug/libmirrord_layer.dylib .
touch libmirrord_layer.so
mkdir -p bin/macos
cp <path to mirrord repo>/target/universal-apple-darwin/debug/mirrord bin/macos/
```

### On Linux x86-64

```bash
cp <path to mirrord repo>/target/debug/libmirrord_layer.so .
touch libmirrord_layer.dylib
mkdir -p bin/linux/x86-64
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

You can control which IDE is opened with a `PLATFORMTYPE` environment variable, as listed [here](https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html#intellij-extension-type). For example, set `PLATFORMTYPE=IU` for IntelliJ IDEA Ultimate.


## Adding E2E tests

We use the [intellij-ui-test-robot](https://github.com/JetBrains/intellij-ui-test-robot) to automate UI tests for the extension.

- If you have made a change to the UI, to add new tests, run `./gradlew runIdeForUITests` and open a test project,
go to [localhost:8082](http://localhost:8082) and choose the elements you want to click on by their Xpath.

- To make sure the test doesn't flake in the CI, check if all the elements related to UI component have loaded by using
 a `waitFor` fixture or if a change depends on files to be indexed, use the `dumbAware` fixture.

- As a rule of thumb try to encapsulate functionality in fixtures in the `utils` folder.

- To run the tests locally from scratch run, `./gradlew test` which download the latest stable pycharm IDE. 


## Converting screen recordings to gifs

On our plugin page (generated from README.md), we have a gif showing the usage of the plugin.
On MacOS, this is how I was able to convert the screen recording to a gif that's not weird:

```
ffmpeg -i path-to-screen-recording.mov -r 16 -lavfi '[0]split[a][b];[a]palettegen[p];[b][p]paletteuse' usage.gif && gifsicle -O3 usage.gif -o usage.gif
```

Alternatively, you can use [Image Magick](https://imagemagick.org/) to create higher quality (but
much larger in terms of filesize) gifs from a screen recording, using `-layers Optimize` to slightly
reduce the file size:

```
magick path-to-screen-recording.mov -layers Optimize usage.gif
```
