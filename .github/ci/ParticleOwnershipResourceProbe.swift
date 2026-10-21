import Foundation
import Metal

// Independent native allocation measurement only. Does not add or call a production ABI.
enum ProbeFault: Error { case injected }
let output = CommandLine.arguments.dropFirst().first ?? "build/agent-evidence/pixel-ownership/resource-costs.json"
guard let device = MTLCreateSystemDefaultDevice() else { fatalError("No Metal device") }
func desc(_ width: Int, _ height: Int, _ owner: Bool) -> MTLTextureDescriptor {
    let d = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: owner ? .rgba32Uint : .depth32Float,
                                                    width: width, height: height, mipmapped: false)
    d.storageMode = .private
    d.hazardTrackingMode = .untracked
    d.usage = [.renderTarget, .shaderRead]
    return d
}
func aligned(_ size: Int, _ alignment: Int) -> Int {
    precondition(alignment > 0)
    return ((size + alignment - 1) / alignment) * alignment
}
var resolutions: [[String: Any]] = []
for (width, height) in [(64,48),(854,480),(1920,1080),(2560,1440),(3840,2160)] {
    let before = device.currentAllocatedSize
    var entries: [[String: Any]] = []
    var requiredHeap = 0
    var held: [MTLTexture] = []
    let during: Int = autoreleasepool {
        for slot in 0..<2 {
            for owner in [true,false] {
                let d = desc(width,height,owner)
                let requirement = device.heapTextureSizeAndAlign(descriptor: d)
                requiredHeap = aligned(requiredHeap, requirement.align) + requirement.size
                guard let texture = device.makeTexture(descriptor: d) else {
                    fatalError("Actual matching texture allocation failed at \(width)x\(height)")
                }
                held.append(texture)
                entries.append(["slot":slot,"format":owner ? "RGBA32_UINT":"D32_FLOAT",
                                "logicalBytes":width*height*(owner ? 16:4),
                                "actualAllocatedSize":texture.allocatedSize,
                                "heapRequirementSize":requirement.size,"heapRequirementAlignment":requirement.align])
            }
        }
        let peak = device.currentAllocatedSize
        held.removeAll()
        return peak
    }
    let after = device.currentAllocatedSize
    precondition(held.isEmpty && entries.count == 4)
    resolutions.append(["width":width,"height":height,"historyTextures":entries,
                        "pairLogicalBytes":width*height*40,"individualAllocatedBytes":entries.reduce(0) { $0 + ($1["actualAllocatedSize"] as! Int) },
                        "heapBasedPlanningReservationBytes":requiredHeap,
                        "deviceAllocatedBefore":before,"deviceAllocatedDuring":during,"deviceAllocatedAfterScope":after,
                        "scopeReleasedAllFourTextureReferences":true])
}
let failureBefore = device.currentAllocatedSize
var createdBeforeFailure = 0
do {
    try autoreleasepool {
        var partial: [MTLTexture] = []
        defer { partial.removeAll() }
        for i in 0..<4 {
            if i == 2 { throw ProbeFault.injected }
            guard let texture = device.makeTexture(descriptor: desc(64,48,i%2==0)) else { fatalError("Unexpected allocation failure") }
            partial.append(texture); createdBeforeFailure += 1
        }
    }
    fatalError("Injected failure was not executed")
} catch ProbeFault.injected {} catch { fatalError("Unexpected probe failure: \(error)") }
precondition(createdBeforeFailure == 2)
let report: [String: Any] = [
    "sourceCommit":ProcessInfo.processInfo.environment["METALLUM_SOURCE_SHA"] ?? "local",
    "device":device.name,"prototypeOnly":true,"resolutions":resolutions,
    "logicalHistoryBudgetBytesPerPixel":40,
    "failureRollback":["createdTextures":createdBeforeFailure,"scopeReleasedReferences":true,
                       "deviceAllocatedBefore":failureBefore,"deviceAllocatedAfter":device.currentAllocatedSize,
                       "injection":"application exception after two actual allocations; not forced GPU/Metal OOM"],
    "boundary":"Matching formats/usages measured in an independent native allocation probe. allocatedSize and driver heap requirements are separate from logical texel bytes; heap reservation is a planning measurement, not a universal resident-memory/FPS guarantee. No source rendering is replaced by this probe."
]
let url = URL(fileURLWithPath: output)
try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
let data = try JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys])
try data.write(to: url)
print("PARTICLE_OWNERSHIP_RESOURCE_COSTS " + String(decoding:data,as:UTF8.self))
