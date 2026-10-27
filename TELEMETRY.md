# Telemetry

mirrord sends usage statistics to MetalBear to help improve the product. Telemetry is enabled by default.
Information sent can include:

- mirrord version, platform, and whether mirrord is used through the CLI or an extension.
- Session duration, feature usage, and error categories.
- A machine identifier and, when using mirrord for Teams, operator identifiers.

See [mirrord's telemetry documentation](https://github.com/metalbear-co/mirrord/blob/main/TELEMETRY.md)
for more information.

## Disabling

To disable telemetry for mirrord sessions started by the IntelliJ plugin, set `telemetry = false`
at the top level of the mirrord configuration used by your project. For example, in `.mirrord/mirrord.toml`:

```toml
telemetry = false
```

Use **Select Active Config** in the mirrord dropdown to select this file if your project has multiple
mirrord configuration files.
