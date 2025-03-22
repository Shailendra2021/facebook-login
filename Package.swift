// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "TlcgroupCapacitorFacebookLogin",
    platforms: [.iOS(.v14)],
    products: [
        .library(
            name: "FacebookLogin",
            targets: ["FacebookLogin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "7.0.0"),
        .package(url: "https://github.com/facebook/facebook-ios-sdk.git", branch: "main")
    ],
    targets: [
        .target(
            name: "FacebookLogin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm"),
                .product(name: "FBSDKCoreKit", package: "facebook-ios-sdk"),
                .product(name: "FBSDKLoginKit", package: "facebook-ios-sdk")
            ],
            path: "ios/Sources/FacebookLoginPlugin"),
        .testTarget(
            name: "FacebookLoginPluginTests",
            dependencies: ["FacebookLoginPlugin"],
            path: "ios/Tests/FacebookLoginPluginTests")
    ]
)
