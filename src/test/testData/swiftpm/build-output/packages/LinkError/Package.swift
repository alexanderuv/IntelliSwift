// swift-tools-version:6.0
import PackageDescription

// Compiles, but fails to link: `main.swift` calls a function that no object file defines.
let package = Package(
    name: "LinkError",
    targets: [.executableTarget(name: "linkerror")]
)
