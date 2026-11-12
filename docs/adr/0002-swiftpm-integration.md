# ADR 0002: SwiftPM integration through the External System API

- Status: accepted
- Date: 2026-11-11

## Context

ADR 0001 gives Swift files a PSI tree. A Swift project, though, is a SwiftPM package: a
`Package.swift` manifest declaring targets, products and dependencies. To work on one, the IDE
has to know that structure (which directories are sources or tests, how targets depend on each
other, where dependency sources live), keep it in sync with the manifest, and build, run and test
it. The Gradle integration is the model: link the build, reload it when the build script
changes, show its tasks in a tool window, run them, and report build errors and test results in
the Build and Run tool windows.

## Decision

SwiftPM is integrated as an IntelliJ **External System** (`ProjectSystemId("SWIFTPM")`), the
platform API the Gradle and Maven integrations are built on. It brings linking, the
"Load SwiftPM project" notification for unlinked packages, auto-reload on manifest changes, the
tool window with tasks, task run configurations, and before-run tasks.

The project model comes from the `swift` CLI of the selected toolchain, never from parsing
`Package.swift` ourselves, since a manifest is a Swift program:

| Command | Gives us |
| --- | --- |
| `swift package describe --type json` | targets with kinds, paths and sources; products |
| `swift package dump-package` | which package each `.product(name:package:)` comes from |
| `swift package show-dependencies --format json` | the resolved dependency graph and checkout paths |

`describe` and `dump-package` are run for the root package and for every resolved dependency.

### Project model mapping

- The root package becomes a module whose content root is the package directory, with `.build`
  excluded. Each target becomes a module with its directory as the content root: a source root
  for regular, executable, macro and plugin targets, a test source root for test targets.
- Target dependencies become module dependencies. A product dependency becomes a dependency on
  the product's target modules (for a local `path:` package) or on a library (for a remote one).
- Packages added with `path:` become modules too, so they stay editable, as in Xcode. Remote
  packages become read-only libraries whose source roots are their target directories under
  `.build/checkouts`.
- Modules are of a dedicated `SWIFT_MODULE` type.

### Execution

- **Toolchain.** The `swift` executable is detected from `PATH`, `xcrun --find swift`, swiftly
  and the standard install locations, and can be overridden in
  *Settings | Languages & Frameworks | Swift*.
- **Build.** *Build Project* and *Build Module* go through a `ProjectTaskRunner` that runs
  `swift build` (with `--target` for a module). Compiler output appears in the Build tool window,
  and each `file:line:col: error|warning: ...` diagnostic is a navigable message.
- **Run.** A *Swift Run* configuration runs `swift run <product>` with arguments, environment,
  working directory and build configuration (`debug`/`release`).
- **Test.** A *Swift Test* configuration runs `swift test` (with `--filter` for a target, class,
  suite or test) and shows the results as a test tree. Swift Testing reports through its JSON
  event stream (`--event-stream-output-path`, version 0). XCTest has no machine-readable live
  output, so its console output is parsed. Test nodes navigate to source.
- **Gutter.** Run markers appear on `XCTestCase` subclasses and their `test…` methods, on
  `@Suite` types and `@Test` functions, and on `@main` types and `main.swift` of executable
  targets. Context configurations (right-click *Run*) are created for the same elements.

## Alternatives considered

- **Parse `Package.swift` with our PSI.** Manifests are arbitrary Swift: conditionals, helper
  functions, environment checks. Only SwiftPM can evaluate them.
- **SourceKit-LSP's build server / BSP.** SourceKit-LSP answers questions about compiler
  arguments, not about the package structure, tasks or tests that the IDE's project model
  needs. It remains the candidate for semantic features (see ADR 0001), separately.
- **A plain run configuration per command, without the External System.** It would rebuild
  linking, reload, the tasks tool window and before-run tasks that the External System
  already provides.

## Consequences

- Loading a package runs its manifest and, through `show-dependencies`, may resolve and fetch
  dependencies, as `swift build` would. This happens only for packages the user links.
- Debugging is not part of this decision. Swift debugging needs LLDB integration (as CLion has),
  which is a separate piece of work.
- The plugin depends on the `com.intellij.modules.externalSystem` platform module and the
  `intellij.platform.smRunner` / `intellij.platform.testRunner` content modules.
