/// Renders reports for `paths` concurrently and hands each line to `emit` strictly in input order.
///
/// At most `jobs` files are parsed at once. Finished reports wait in a reorder buffer until all
/// earlier ones have been emitted; scheduling pauses once that buffer would exceed `8 * jobs`
/// entries, so memory stays bounded even when one huge file holds up the stream.
///
/// `emit` runs in the caller's isolation domain (`isolation`), so it may touch non-`Sendable`
/// state owned by the caller, such as an output buffer.
public func renderReports(
    for paths: [String],
    jobs: Int,
    isolation: isolated (any Actor)? = #isolation,
    emit: (_ line: [UInt8]) throws -> Void
) async throws {
    let jobs = max(1, jobs)
    let reorderLimit = jobs * 8

    try await withThrowingTaskGroup(of: (Int, [UInt8]).self) { group in
        var nextToSchedule = 0
        var nextToEmit = 0
        var inFlight = 0
        var finished: [Int: [UInt8]] = [:]

        func scheduleMore() {
            while inFlight < jobs, nextToSchedule < paths.count,
                  nextToSchedule - nextToEmit < reorderLimit {
                let index = nextToSchedule
                let path = paths[index]
                group.addTask { (index, FileReport.render(path: path)) }
                nextToSchedule += 1
                inFlight += 1
            }
        }

        scheduleMore()
        while let (index, line) = try await group.next() {
            inFlight -= 1
            finished[index] = line
            while let ready = finished.removeValue(forKey: nextToEmit) {
                try emit(ready)
                nextToEmit += 1
            }
            scheduleMore()
        }
    }
}
