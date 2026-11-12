// swift-tools-version:6.0
import PackageDescription

// Compile errors, a warning and notes (one in the same file, one in another module), to capture
// Swift diagnostics as printed by `swift build`.
let package = Package(
    name: "Broken",
    targets: [
        .target(name: "Broken"),
        .executableTarget(name: "brokenmain", dependencies: ["Broken"]),
    ]
)
