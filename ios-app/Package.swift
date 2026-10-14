// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "QingxinCore",
    platforms: [.iOS(.v17), .macOS(.v13)],
    products: [.library(name: "QingxinCore", targets: ["QingxinCore"])],
    targets: [
        .target(name: "QingxinCore"),
        .testTarget(name: "QingxinCoreTests", dependencies: ["QingxinCore"])
    ]
)
