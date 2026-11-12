// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "swift-syntax-oracle",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "swift-syntax-oracle", targets: ["swift-syntax-oracle"]),
    ],
    dependencies: [
        .package(url: "https://github.com/swiftlang/swift-syntax.git", "600.0.0"..<"700.0.0"),
    ],
    targets: [
        .target(
            name: "OracleCore",
            dependencies: [
                .product(name: "SwiftDiagnostics", package: "swift-syntax"),
                .product(name: "SwiftSyntax", package: "swift-syntax"),
                .product(name: "SwiftParser", package: "swift-syntax"),
                .product(name: "SwiftParserDiagnostics", package: "swift-syntax"),
            ]
        ),
        .executableTarget(
            name: "swift-syntax-oracle",
            dependencies: ["OracleCore"]
        ),
        .testTarget(
            name: "OracleCoreTests",
            dependencies: [
                "OracleCore",
                .product(name: "SwiftParser", package: "swift-syntax"),
            ]
        ),
    ]
)
