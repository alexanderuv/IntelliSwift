// swift-tools-version:6.0
import PackageDescription

// Declares a target without sources: SwiftPM warns about it (twice) and the build still succeeds.
let package = Package(
    name: "MissingTarget",
    targets: [.target(name: "Nowhere")]
)
