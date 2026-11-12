// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "Greeter",
    platforms: [.macOS(.v13)],
    products: [
        .library(name: "Greeter", targets: ["Greeter"]),
        .executable(name: "greet", targets: ["greet"]),
        .plugin(name: "Lint", targets: ["Lint"]),
    ],
    dependencies: [
        .package(path: "../Utils"),
    ],
    targets: [
        .target(name: "CHelpers"),
        .target(name: "Greeter", dependencies: ["CHelpers", .product(name: "Utils", package: "Utils")]),
        .executableTarget(name: "greet", dependencies: ["Greeter"]),
        .testTarget(name: "GreeterTests", dependencies: ["Greeter"]),
        .plugin(name: "Lint", capability: .command(intent: .custom(verb: "lint", description: "Lint the package"))),
    ]
)
