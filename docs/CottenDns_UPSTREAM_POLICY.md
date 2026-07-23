# CottenDns Upstream Policy

WhiteDNS treats CottenDNS as a black-box upstream engine. It is not vendored
into this repository: `make CottenDns` and CI both check out the upstream
`WhiteDNS/CottenDns` repository at the commit pinned in
`.engine/COTTENDNS_ENGINE_SHA` and build it from its own source tree into
`.engine/CottenDns` (gitignored). See
[ANDROID_ENGINE_INTEGRATION.md](https://github.com/WhiteDNS/CottenDns/blob/main/docs/ANDROID_ENGINE_INTEGRATION.md)
in the engine repository for the full CI contract.

The Android app must integrate with CottenDns only through stable runtime boundaries:

- The packaged CottenDns executable.
- CLI flags passed to that executable.
- Generated TOML configuration files.
- Generated resolver files.
- stdout/stderr telemetry emitted by the process.
- Process exit codes.
- Version and capability detection from the executable.
- Files written by CottenDns under the WhiteDNS-owned working directory.

## Disallowed Coupling

WhiteDNS app features must not depend on:

- CottenDns internal Go packages.
- Private CottenDns source layout.
- Unexported Go symbols or package structure.
- Parsing upstream Go source files for app behavior.
- Editing upstream code to satisfy Android app feature needs.
- Runtime assumptions that require knowing CottenDns internals beyond documented executable behavior.

Build tooling may know where the upstream client command lives so it can compile the executable. App runtime code must not.

## Allowed Upstream Changes

Bumping the pinned commit is the only way engine code changes reach this repo:

- Update `.engine/COTTENDNS_ENGINE_SHA` to a reviewed commit from
  `WhiteDNS/CottenDns`.
- Do not hand-edit engine source anywhere in this repository; there is none to
  edit. If a feature needs an engine change, make it upstream in CottenDNS and
  bump the pin here.

## Review Checklist

For any pull request touching CottenDns integration:

- Confirm app code interacts through the executable boundary only.
- Confirm generated TOML and resolver files are owned by WhiteDNS and live under app-controlled runtime directories.
- Confirm telemetry parsing is tolerant of unknown or changed output.
- Confirm optional behavior is gated by executable version/capability detection when needed.
- Confirm no Android app code imports, parses, or depends on CottenDns Go internals.
- Confirm any change to `.engine/COTTENDNS_ENGINE_SHA` points at a real, reviewed commit and is called out in the PR description.
