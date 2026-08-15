import CoreGraphics
import Foundation
import ImageIO

struct ScreenshotStats: Codable {
    let width: Int
    let height: Int
    let sampledPixels: Int
    let meanLuma: Double
    let lumaStdDev: Double
    let nearBlackRatio: Double
    let redDominantRatio: Double
    let quantizedColorBuckets: Int
}

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data(("SCREENSHOT_EVIDENCE_FAIL: \(message)\n").utf8))
    exit(1)
}

guard CommandLine.arguments.count == 3 else {
    fail("usage: verify_screenshot <input.png> <stats.json>")
}

let input = URL(fileURLWithPath: CommandLine.arguments[1])
let output = URL(fileURLWithPath: CommandLine.arguments[2])

guard let source = CGImageSourceCreateWithURL(input as CFURL, nil),
      let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
    fail("could not decode \(input.path)")
}

let width = image.width
let height = image.height
guard width >= 320 && height >= 180 else {
    fail("unexpectedly small framebuffer capture \(width)x\(height)")
}

let bytesPerPixel = 4
let bytesPerRow = width * bytesPerPixel
var pixels = [UInt8](repeating: 0, count: height * bytesPerRow)
let colorSpace = CGColorSpaceCreateDeviceRGB()
let bitmapInfo = CGBitmapInfo.byteOrder32Big.union(
    CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue)
)

guard let context = CGContext(
    data: &pixels,
    width: width,
    height: height,
    bitsPerComponent: 8,
    bytesPerRow: bytesPerRow,
    space: colorSpace,
    bitmapInfo: bitmapInfo.rawValue
) else {
    fail("could not create RGBA8 analysis context")
}

context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))

let stride = max(1, min(width, height) / 240)
var count = 0
var sum = 0.0
var sumSquares = 0.0
var black = 0
var redDominant = 0
var buckets = Set<Int>()

for y in Swift.stride(from: 0, to: height, by: stride) {
    for x in Swift.stride(from: 0, to: width, by: stride) {
        let offset = y * bytesPerRow + x * bytesPerPixel
        let r = Int(pixels[offset])
        let g = Int(pixels[offset + 1])
        let b = Int(pixels[offset + 2])
        let luma = 0.2126 * Double(r) + 0.7152 * Double(g) + 0.0722 * Double(b)

        count += 1
        sum += luma
        sumSquares += luma * luma

        if r < 8 && g < 8 && b < 8 {
            black += 1
        }
        if r > 170 && r > g * 2 && r > b * 2 && g < 90 && b < 90 {
            redDominant += 1
        }

        let qr = r >> 5
        let qg = g >> 5
        let qb = b >> 5
        buckets.insert((qr << 6) | (qg << 3) | qb)
    }
}

guard count > 0 else {
    fail("no pixels sampled")
}

let mean = sum / Double(count)
let variance = max(0.0, sumSquares / Double(count) - mean * mean)
let stdDev = sqrt(variance)
let blackRatio = Double(black) / Double(count)
let redRatio = Double(redDominant) / Double(count)

let stats = ScreenshotStats(
    width: width,
    height: height,
    sampledPixels: count,
    meanLuma: mean,
    lumaStdDev: stdDev,
    nearBlackRatio: blackRatio,
    redDominantRatio: redRatio,
    quantizedColorBuckets: buckets.count
)

try FileManager.default.createDirectory(
    at: output.deletingLastPathComponent(),
    withIntermediateDirectories: true
)
let encoder = JSONEncoder()
encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
try encoder.encode(stats).write(to: output)

print("SCREENSHOT_EVIDENCE width=\(width) height=\(height) sampled=\(count)")
print(String(format: "SCREENSHOT_EVIDENCE mean_luma=%.3f stddev=%.3f black_ratio=%.5f red_ratio=%.5f colors=%d",
             mean, stdDev, blackRatio, redRatio, buckets.count))

guard blackRatio < 0.98 else {
    fail("capture is effectively black (near-black ratio \(blackRatio))")
}
guard redRatio < 0.95 else {
    fail("capture is effectively a uniform red/error frame (red ratio \(redRatio))")
}
guard stdDev >= 2.0 else {
    fail("capture has insufficient luminance variation (stddev \(stdDev))")
}
guard buckets.count >= 8 else {
    fail("capture has insufficient color variation (\(buckets.count) quantized buckets)")
}

print("SCREENSHOT_EVIDENCE_PASS")
