<p align="center">
  <img src="images/icon.png" width="20%">
</p>
<h1 align="center">mirrord</h1>

[![Discord](https://img.shields.io/discord/933706914808889356?color=5865F2&label=Discord&logo=discord&logoColor=white)](https://discord.gg/metalbear)
![License](https://img.shields.io/badge/license-MIT-green)
![GitHub release (latest SemVer)](https://img.shields.io/github/v/release/metalbear-co/mirrord-intellij)
[![Twitter Follow](https://img.shields.io/twitter/follow/metalbearco?style=social)](https://twitter.com/metalbearco)

This repository is for the IntelliJ plugin.
mirrord main repository can be found [here](https://github.com/metalbear-co/mirrord).

<!-- Plugin description -->

mirrord lets developers [run local processes in the context of their cloud environment](https://mirrord.dev).
It’s provides the benefits of running your service on a cloud environment (e.g. staging) without actually
going through the hassle of deploying it there, and without disrupting the environment by deploying untested code.
It comes as a Visual Studio Code extension, an IntelliJ plugin and a CLI tool. You can read more about it [here](https://mirrord.dev/docs/overview/introduction/).
mirrord's main repository can be found [here](https://github.com/metalbear-co/mirrord).

## How To Use

- Click the mirrord icon in the Navigation Toolbar
- Start debugging your project
- Choose a pod to impersonate or choose to run in the "targetless" mode
- The debugged process will be plugged into the selected pod or into the cluster environment by mirrord

## Settings

mirrord allows for rich configuration of the environment it provides. The schema for it is documented [here](https://mirrord.dev/docs/reference/configuration/).
This plugin supports configuration specified in `json` files.

mirrord reads its configuration from the following locations:

1. Active config can be set for the whole workspace using the `Select Active Config` button from the dropdown menu.
   If active config is set, mirrord always uses it.
2. If active config is not set, mirrord searches process environment (specified in run configuration) for `MIRRORD_CONFIG_FILE` variable.
3. If no config is specified, mirrord looks for a default project config file in the `.mirrord` directory with a name ending with `mirrord.json`.
   If there is no default config file, mirrord uses default configuration values for everything.
4. If there are many candidates for the default config file, mirrord sorts them alphabetically and uses the first one.

You can use the `Settings` button in the dropdown menu to quickly edit detected configs.

<!-- Plugin description end -->

## Installation

- Using IDE built-in plugin system:
  
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "mirrord-intellij-plugin"</kbd> >
  <kbd>Install Plugin</kbd>
  
- Manually:

  Download the latest release and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

<p align="center">
  <img src="./src/main/resources/META-INF/usage.gif">
</p>

## FAQ

Our FAQ is available [here](https://mirrord.dev/docs/faq/general).
If you have a question that's not on there, feel free to ask in our [Discussions](https://github.com/metalbear-co/mirrord/discussions)
or on [Discord](https://discord.gg/metalbear).

## Contributing

Contributions are very welcome. Start by checking out our [open issues](https://github.com/metalbear-co/mirrord-intellij/issues),
and by going through our [contributing guide](CONTRIBUTING.md).
We're available on [Discord](https://discord.gg/metalbear) for any questions.

## Help and Community

Join our [Discord Server](https://discord.gg/metalbear) for questions, support and fun.

## Code of Conduct

We take our community seriously and we are dedicated to providing a safe and welcoming environment for everyone.
Please take a few minutes to review our [Code of Conduct](./CODE_OF_CONDUCT.md).

## License

[MIT](./LICENSE)

---

Plugin based on the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
