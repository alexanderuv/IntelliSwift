import PackagePlugin

@main
struct Lint: CommandPlugin {
    func performCommand(context: PluginContext, arguments: [String]) async throws {
        print("Linting \(context.package.displayName)")
    }
}
