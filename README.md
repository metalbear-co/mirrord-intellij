# mirrord for JetBrains IntelliJ

[![Community Slack](https://img.shields.io/badge/Join-e5f7f7?logo=slack&label=Community%20Slack)](https://metalbear.com/slack)
![License](https://img.shields.io/badge/license-MIT-green)
![GitHub release (latest SemVer)](https://img.shields.io/github/v/release/metalbear-co/mirrord-intellij)
[![X Follow](https://img.shields.io/twitter/follow/metalbear?style=social)](https://x.com/metalbear)
[![JetBrains Marketplace](https://img.shields.io/badge/JetBrains%20Plugin%20Page-e6005c)](https://plugins.jetbrains.com/plugin/19772-mirrord)

This repository is for the IntelliJ plugin.
mirrord's main repository can be found [here](https://github.com/metalbear-co/mirrord).

<p align="center">
  <img src="https://raw.githubusercontent.com/metalbear-co/mirrord-intellij/main/src/main/resources/META-INF/usage.gif"
  alt="A gif showing mirrord being used to mirror traffic from a kubernetes cluster in the IntelliJ UI">
</p>

<!-- Plugin description -->

mirrord lets developers and AI coding agents [run local processes inside a live Kubernetes cluster](https://metalbear.com/mirrord).
Your code stays on your machine, but mirrord routes its traffic, files, and environment through a target pod in the cluster.
Use it to read live cluster context while writing code (real env vars, real service responses, real queue contents),
and to run the code against those same services and data once it's written.
You get the feedback of a deploy in seconds, without the deploy, and without disrupting the cluster for anyone else.

This plugin brings that workflow to JetBrains IntelliJ. mirrord also ships as a VS Code extension and a CLI tool.
Read more in [the docs](https://metalbear.com/mirrord/docs/overview/introduction/), or
[sign up to our newsletter](https://metalbear.com/newsletter) to hear about new features.
Both the [core mirrord repository](https://github.com/metalbear-co/mirrord) and
[this plugin's code](https://github.com/metalbear-co/mirrord-intellij) can be found on GitHub.

## How To Use

- Click the mirrord icon in the Navigation Toolbar
- Start debugging your project
- Choose a pod to impersonate or choose to run in the "targetless" mode
- The debugged process will be plugged into the selected pod or into the cluster environment by mirrord

## Configuring mirrord for IntelliJ

mirrord allows for rich configuration of the environment it provides.
The schema for it is documented [here](https://metalbear.com/mirrord/docs/reference/configuration/).
The extension supports autocomplete for `json` files, but you can also use `toml` or `yaml` format.

_Quick start: the easiest way to start configuring mirrord is to choose_ "Settings" _from the dropdown menu,
which will open a new `mirrord.json`._

<!-- Plugin description end -->

## Installation

- From the IDE:
  
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "mirrord-intellij-plugin"</kbd> >
  <kbd>Install Plugin</kbd>
  
- Manually:

  Download [the latest release](https://github.com/metalbear-co/mirrord-intellij/releases) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

## Screenshots

<p align="center">
  <img src="https://raw.githubusercontent.com/metalbear-co/mirrord-intellij/main/src/main/resources/META-INF/enable_mirrord.gif"
  width="50%" alt="A gif showing mirrord being enabled via a click in the IntelliJ UI">
</p>

> Enable mirrord

<p align="center">
  <img src="https://raw.githubusercontent.com/metalbear-co/mirrord-intellij/main/src/main/resources/META-INF/target_selection_dialog.png"
  width="50%" alt="A screenshot of mirrord's target selection dialog in the IntelliJ UI">
</p>

> Target selection dialog

<p align="center">
  <img src="https://raw.githubusercontent.com/metalbear-co/mirrord-intellij/main/src/main/resources/META-INF/settings_from_dropdown.png"
  width="50%" alt="A screenshot of mirrord's dropdown menu in the IntelliJ UI, with 'Settings' highlighted">
</p>

> Settings option in the dropdown menu

## Helpful Links

- [Official documentation for this extension](https://metalbear.com/mirrord/docs/using-mirrord/intellij-plugin/)
- [Official language-specific guides for debugging](https://metalbear.com/mirrord/docs/guides)
- [Frequently Asked Questions](https://metalbear.com/mirrord/docs/faq/general)

## Contributions, feature requests, issues and support

- Feel free to join to our [Slack](https://metalbear.com/slack) if you need help using mirrord,
or if you encounter an issue while using the extension.
- Check our open issues for [the IntelliJ extension](https://github.com/metalbear-co/mirrord-intellij/issues)
and [mirrord's core code](https://github.com/metalbear-co/mirrord/issues), and 👍 react to any that you would like to see addressed.
- Before submitting a pull request for new features, please take a look at [mirrord's contributing guide](https://github.com/metalbear-co/mirrord/blob/main/CONTRIBUTING.md).

## License

[MIT](./LICENSE)

---

Plugin based on the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
