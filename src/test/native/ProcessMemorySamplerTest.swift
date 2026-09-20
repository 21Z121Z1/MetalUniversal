import Darwin
import Foundation

@_silgen_name("metallum_process_memory_sample")
private func processMemorySample(
    _ output: UnsafeMutablePointer<Int64>?,
    _ capacityWords: Int32
) -> Int32

private func check(_ condition: @autoclosure () -> Bool, _ message: String) {
    guard condition() else {
        fputs("FAIL: \(message)\n", stderr)
        exit(1)
    }
}

@main
struct ProcessMemorySamplerTest {
    static func main() {
        check(processMemorySample(nil, 6) == -1, "null output is rejected without a write")

        let shortSentinel = Int64(0x1122334455667788)
        var shortOutput = Array(repeating: shortSentinel, count: 6)
        let shortResult = shortOutput.withUnsafeMutableBufferPointer { buffer in
            processMemorySample(buffer.baseAddress, 5)
        }
        check(shortResult == -1, "capacity five is rejected")
        check(shortOutput.allSatisfy { $0 == shortSentinel },
              "capacity-five rejection leaves every sentinel untouched")

        var output = Array(repeating: Int64.min, count: 6)
        let result = output.withUnsafeMutableBufferPointer { buffer in
            processMemorySample(buffer.baseAddress, 6)
        }
        check(result == 1, "capacity six returns a real TASK_VM_INFO sample")
        check(output[0] == 1, "schema version is one")
        check(output[1] == 0, "kernel status is successful")
        check(output[2] >= 38, "returned count covers phys_footprint")
        check(output[3] > 0, "current resident bytes are positive")
        check(output[4] >= 0, "physical footprint is nonnegative")
        check(output[5] >= output[3], "lifetime resident peak covers current resident bytes")

        let tailSentinel = Int64(0x2233445566778899)
        var extendedOutput = Array(repeating: Int64.min, count: 6) + [tailSentinel]
        let extendedResult = extendedOutput.withUnsafeMutableBufferPointer { buffer in
            processMemorySample(buffer.baseAddress, 7)
        }
        check(extendedResult == 1, "capacity seven preserves the same sample result")
        check(extendedOutput[6] == tailSentinel, "word seven is outside the ABI and untouched")

        print("ProcessMemorySamplerTest: PASS")
    }
}
