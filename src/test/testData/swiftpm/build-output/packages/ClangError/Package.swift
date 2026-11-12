// swift-tools-version:6.0
import PackageDescription

// A C target with a compile error and a warning, to capture clang diagnostics as printed by `swift build`.
let package = Package(
    name: "ClangError",
    targets: [
        .target(name: "CLib", cSettings: [.unsafeFlags(["-Wall"])]),
    ]
)
