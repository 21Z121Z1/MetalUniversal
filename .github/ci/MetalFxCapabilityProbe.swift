import Foundation
import Metal
import MetalFX

@main
enum MetalFxCapabilityProbe {
    static func main() throws {
        guard let device = MTLCreateSystemDefaultDevice() else {
            throw NSError(domain: "MetalFxCapabilityProbe", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "No Metal device"])
        }
        var spatial = false
        var temporal = false
        var interpolation = false
        var interpolationApiAvailable = false
        var temporalMetal4 = false
        var interpolationMetal4 = false
        if #available(macOS 13.0, *) {
            spatial = MTLFXSpatialScalerDescriptor.supportsDevice(device)
            temporal = MTLFXTemporalScalerDescriptor.supportsDevice(device)
        }
        if #available(macOS 26.0, *) {
            interpolationApiAvailable = true
            interpolation = MTLFXFrameInterpolatorDescriptor.supportsDevice(device)
            temporalMetal4 = MTLFXTemporalScalerDescriptor.supportsMetal4FX(device)
            interpolationMetal4 = MTLFXFrameInterpolatorDescriptor.supportsMetal4FX(device)
        }
        let result: [String: Any] = [
            "sourceCommit": ProcessInfo.processInfo.environment["METALLUM_SOURCE_SHA"] ?? "unknown",
            "device": device.name,
            "registryId": String(device.registryID),
            "os": ProcessInfo.processInfo.operatingSystemVersionString,
            "spatialSupported": spatial,
            "temporalSupported": temporal,
            "frameInterpolationSupported": interpolation,
            "frameInterpolationApiAvailable": interpolationApiAvailable,
            "temporalMetal4Supported": temporalMetal4,
            "frameInterpolationMetal4Supported": interpolationMetal4,
            "temporalExecution": temporal ? "pending" : "blocked: supportsDevice=false",
            "frameInterpolationExecution": interpolation ? "pending"
                : interpolationApiAvailable ? "blocked: supportsDevice=false" : "blocked: requires macOS 26",
            "qualityValidated": false,
            "presentationValidated": false
        ]
        let path = CommandLine.arguments.dropFirst().first ?? "build/agent-evidence/metalfx-capabilities.json"
        let url = URL(fileURLWithPath: path)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let data = try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys])
        try data.write(to: url, options: .atomic)
        print(String(decoding: data, as: UTF8.self))
    }
}
