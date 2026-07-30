import Foundation
import Metal
import QuartzCore

@_silgen_name("metallum_create_system_default_device")
private func createSystemDefaultDevice() -> UnsafeMutableRawPointer?

@_silgen_name("metallum_set_metal_hud")
private func setMetalHud(_ layer: CAMetalLayer, _ enabled: Int32)

@_silgen_name("metallum_metal_hud_status")
private func metalHudStatus(_ layer: CAMetalLayer) -> Int32

private func requireHudSelectors() {
    let instanceSelector = NSSelectorFromString("instance")
    guard let hudClass = NSClassFromString("_CADeveloperHUDProperties") as? NSObject.Type,
          hudClass.responds(to: instanceSelector),
          let properties = hudClass.perform(instanceSelector)?.takeUnretainedValue() as? NSObject else {
        fatalError("Metal HUD properties singleton is unavailable")
    }
    if #available(macOS 26.0, *) {
        for name in [
            "addMetric:name:unit:nameColor:valueColor:visualType:options:",
            "updateLabelMetric:label:",
            "getMetric:",
            "removeMetric:",
            "metalFXFrameInterpolatorEncodingEnd:",
            "metalFXFrameInterpolatorDisable"
        ] {
            guard properties.responds(to: NSSelectorFromString(name)) else {
                fatalError("Metal HUD properties does not respond to \(name)")
            }
        }
    }
}

@main
private struct MetalHudRuntimeTest {
    static func main() {
        guard #available(macOS 13.0, *) else {
            print("Metal HUD runtime toggle validation skipped: macOS 13 is required")
            return
        }
        guard let devicePointer = createSystemDefaultDevice() else {
            fatalError("No Metal device")
        }
        guard ProcessInfo.processInfo.environment["MTLFX_HUD_ENABLED"] == "1" else {
            fatalError("MetalFX HUD was not enabled before effect construction")
        }
        let deviceObject = Unmanaged<AnyObject>.fromOpaque(devicePointer).takeRetainedValue()
        guard let device = deviceObject as? MTLDevice else {
            fatalError("Native device export returned a non-MTLDevice object")
        }

        let layer = CAMetalLayer()
        layer.device = device
        setMetalHud(layer, 1)
        guard layer.developerHUDProperties?["mode"] as? String == "default" else {
            fatalError("Metal HUD layer request was not enabled")
        }
        guard metalHudStatus(layer) & 0b011 == 0b011 else {
            fatalError("Metal HUD did not report a primed and requested layer")
        }
        requireHudSelectors()

        setMetalHud(layer, 0)
        guard layer.developerHUDProperties?.isEmpty == true else {
            fatalError("Metal HUD layer request was not cleared")
        }
        guard metalHudStatus(layer) & 0b011 == 0b001 else {
            fatalError("Metal HUD did not report disabled")
        }
        setMetalHud(layer, 1)
        guard layer.developerHUDProperties?["mode"] as? String == "default" else {
            fatalError("Metal HUD layer request was not restored")
        }
        guard metalHudStatus(layer) & 0b111 == 0b111 else {
            fatalError("Metal HUD and MetalFX metrics did not report enabled after re-enabling")
        }
        setMetalHud(layer, 0)

        print("Metal HUD startup/layer request and MetalFX selector validation passed")
    }
}
