// swift-tools-version:6.0
import PackageDescription

// The manifest itself does not compile.
let package = Package(
    name: "BadManifest",
    targets: [
        .target(name: "BadManifest", dependencies: [undefinedDependency]),
    ]
)
