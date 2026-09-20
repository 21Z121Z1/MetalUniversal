import Darwin

/// Fixed-width process memory sample returned by `metallum_process_memory_sample`.
///
/// The sampler intentionally has no cached state and does not enumerate Metal
/// resources.  `resident_size` is sampled at the instant of the call; the
/// caller owns the measurement-window max.  `resident_size_peak` is the
/// process-lifetime kernel peak and must not be presented as a window peak.
private enum ProcessMemorySampleLayout {
    static let wordCount = 6
    static let schemaVersion: Int64 = 1
    static let schemaIndex = 0
    static let kernelStatusIndex = 1
    static let returnedWordCountIndex = 2
    static let residentBytesIndex = 3
    static let physicalFootprintBytesIndex = 4
    static let lifetimeResidentPeakBytesIndex = 5
}

/// `phys_footprint` is the first rev1 field in the Xcode 27 task_vm_info
/// layout.  On the supported 64-bit Apple targets, its byte offset is 144 and
/// its eight-byte value ends at byte 152, hence 38 natural_t words.  A kernel
/// returning fewer words may still provide resident_size, but cannot provide
/// the complete fixed sample contract and is rejected with status -2.
private let taskVMInfoMinimumCountForPhysicalFootprint: mach_msg_type_number_t = 38

@inline(__always)
private func initializeProcessMemoryOutput(_ output: UnsafeMutablePointer<Int64>) {
    for index in 0..<ProcessMemorySampleLayout.wordCount {
        output[index] = 0
    }
    output[ProcessMemorySampleLayout.schemaIndex] = ProcessMemorySampleLayout.schemaVersion
}

@inline(__always)
private func checkedInt64(_ value: mach_vm_size_t) -> Int64? {
    let unsigned = UInt64(value)
    guard unsigned <= UInt64(Int64.max) else { return nil }
    return Int64(unsigned)
}

/// Samples the current process using Mach `TASK_VM_INFO`.
///
/// Output layout (six Int64 words):
///
/// 0. schemaVersion (1)
/// 1. kernelStatus (0 on success, Mach status on task_info failure, -2 for
///    an insufficient returned TASK_VM_INFO count, -3 for invalid numeric
///    values or Int64 overflow)
/// 2. returnedWordCount (the kernel's returned TASK_VM_INFO word count)
/// 3. current resident bytes
/// 4. physical footprint bytes
/// 5. process-lifetime resident peak bytes
///
/// A null output pointer or capacity below six returns -1 without writing.
/// This function performs one synchronous query and maintains no native state.
@_cdecl("metallum_process_memory_sample")
public func metallum_process_memory_sample(
    _ output: UnsafeMutablePointer<Int64>?,
    _ capacityWords: Int32
) -> Int32 {
    guard let output, capacityWords >= Int32(ProcessMemorySampleLayout.wordCount) else {
        return -1
    }
    initializeProcessMemoryOutput(output)

    var info = task_vm_info_data_t()
    var returnedCount = mach_msg_type_number_t(
        MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size
    )
    let kernelStatus = withUnsafeMutablePointer(to: &info) { infoPointer in
        infoPointer.withMemoryRebound(
            to: integer_t.self,
            capacity: Int(returnedCount)
        ) { taskInfoPointer in
            task_info(
                mach_task_self_,
                task_flavor_t(TASK_VM_INFO),
                taskInfoPointer,
                &returnedCount
            )
        }
    }

    output[ProcessMemorySampleLayout.kernelStatusIndex] = Int64(kernelStatus)
    output[ProcessMemorySampleLayout.returnedWordCountIndex] = Int64(returnedCount)
    guard kernelStatus == KERN_SUCCESS else {
        return 0
    }
    guard returnedCount >= taskVMInfoMinimumCountForPhysicalFootprint else {
        output[ProcessMemorySampleLayout.kernelStatusIndex] = -2
        return 0
    }

    guard let residentBytes = checkedInt64(info.resident_size),
          let physicalFootprintBytes = checkedInt64(info.phys_footprint),
          let lifetimeResidentPeakBytes = checkedInt64(info.resident_size_peak),
          residentBytes > 0 else {
        output[ProcessMemorySampleLayout.kernelStatusIndex] = -3
        return 0
    }

    output[ProcessMemorySampleLayout.residentBytesIndex] = residentBytes
    output[ProcessMemorySampleLayout.physicalFootprintBytesIndex] = physicalFootprintBytes
    output[ProcessMemorySampleLayout.lifetimeResidentPeakBytesIndex] = lifetimeResidentPeakBytes
    return 1
}
