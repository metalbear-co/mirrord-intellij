<p align="center">
  <img src="images/icon.png" width="20%">
</p>
<h1 align="center">mirrord</h1>

[![Discord](https://img.shields.io/discord/933706914808889356?color=5865F2&label=Discord&logo=discord&logoColor=white)](https://discord.gg/metalbear)
![License](https://img.shields.io/badge/license-MIT-green)
![GitHub release (latest SemVer)](https://img.shields.io/github/v/release/metalbear-co/mirrord-intellij)
[![Twitter Follow](https://img.shields.io/twitter/follow/metalbearco?style=social)](https://twitter.com/metalbearco)

mirrord lets developers run local processes in the context of their cloud environment.
It’s meant to provide the benefits of running your service on a cloud environment (e.g. staging) without actually
going through the hassle of deploying it there, and without disrupting the environment by deploying untested code.
It comes as a Visual Studio Code extension, an IntelliJ plugin and a CLI tool. You can read more about it [here](https://mirrord.dev/docs/overview/introduction/).

This repository is for the IntelliJ plugin.
mirrord main repository can be found [here](https://github.com/metalbear-co/mirrord).

## Installation

Get the plugin [here](https://plugins.jetbrains.com/plugin/19772-mirrord).

## How To Use

- Click the mirrord icon in the Navigation Toolbar
- Start debugging your project
- Choose a pod to impersonate or choose to run in the "targetless" mode
- The debugged process will be plugged into the selected pod or into the cluster environment by mirrord

<p align="center">
  <img src="./intellij-ext/src/main/resources/META-INF/usage.gif">
</p>

## FAQ

Our FAQ is available [here](https://mirrord.dev/docs/overview/faq/).
If you have a question that's not on there, feel free to ask in our [Discussions](https://github.com/metalbear-co/mirrord/discussions)
or on [Discord](https://discord.gg/metalbear).

## Contributing

Contributions are very welcome. Start by checking out our [open issues](https://github.com/metalbear-co/mirrord-intellij/issues), and by going through our [contributing guide](CONTRIBUTING.md).
We're available on [Discord](https://discord.gg/metalbear) for any questions.

## Help and Community

Join our [Discord Server](https://discord.gg/metalbear) for questions, support and fun.

## Code of Conduct

We take our community seriously and we are dedicated to providing a safe and welcoming environment for everyone.
Please take a few minutes to review our [Code of Conduct](./CODE_OF_CONDUCT.md).

## License

[MIT](./LICENSE)
