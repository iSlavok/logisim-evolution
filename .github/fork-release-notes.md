> [!IMPORTANT]
> **Unofficial build.** This release is made by [@iSlavok](https://github.com/iSlavok) from a fork of
> [Logisim-evolution](https://github.com/logisim-evolution/logisim-evolution). It is not affiliated with
> or endorsed by the Logisim-evolution developers. Please report problems with it
> [in this fork](https://github.com/iSlavok/logisim-evolution/issues), not upstream.

## What is in this build

- Upstream `main` at commit `46295a9e2` (Logisim-evolution 5.1.0-dev). This is everything merged
  upstream after the 5.0.0 release, including the fix for the infinite repaint loop with mismatched
  bus widths (logisim-evolution/logisim-evolution#3121). It is not an upstream release.
- The simulation tree fix from logisim-evolution/logisim-evolution#3153: designs with nested
  subcircuits no longer get slower and slower to edit (logisim-evolution/logisim-evolution#3152).
- The dark theme fix from logisim-evolution/logisim-evolution#3155: the subcircuit clock symbol, Classic
  and HolyCross subcircuit outlines and icons, the Test Vector table, the timing diagram and the zoom icons
  are no longer drawn in black (logisim-evolution/logisim-evolution#3154).

The full list of changes is in [CHANGES.md](https://github.com/iSlavok/logisim-evolution/blob/v5.1.0-fork2/CHANGES.md).

## Downloads

| Platform | File |
|---|---|
| Windows x64 | `*-amd64.msi` installer, or `*-windows-amd64.zip` portable |
| Windows ARM | `*-aarch64.msi` installer (if present), or `*-windows-aarch64.zip` portable |
| macOS Apple Silicon | `*-aarch64.dmg` |
| macOS Intel | `*-x86_64.dmg` |
| Linux x64 | `*_amd64.deb`, `*.x86_64.rpm` |
| Linux ARM | `*_arm64.deb`, `*.aarch64.rpm` |
| Any OS with Java 21+ | `*-all.jar` (`java -jar logisim-evolution-5.1.0fork2-all.jar`) |

The macOS packages are not signed or notarized, like the upstream ones. On first launch, right-click
the app and choose **Open**.

## License and source code

Logisim-evolution is free software under the
[GNU General Public License v3](https://github.com/iSlavok/logisim-evolution/blob/v5.1.0-fork2/LICENSE.md).
This build is a modified version. The complete corresponding source code is the
[`v5.1.0-fork2`](https://github.com/iSlavok/logisim-evolution/tree/v5.1.0-fork2) tag of this repository,
and is also attached as `*-src.jar`.
