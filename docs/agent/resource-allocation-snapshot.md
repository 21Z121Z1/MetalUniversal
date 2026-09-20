# Module-owned Metal allocation snapshot

Set `METALLUM_RESOURCE_ALLOCATION_TRACE=1` **before starting the client process**
to collect this diagnostic. It is off by default and cannot be enabled halfway
through a run. The existing fullscreen baseline writes `rendererOwnedAllocations`
after its final GPU drain, with the exact measurement window and end-frame identity.
The copy is outside measured frame intervals. Factory registration still has an
observer cost; this profile requires on/off paired trials before performance use.

The registry observes buffers, textures and indirect command buffers created by
this native module's device factories. It holds weak object references, assigns
monotonic resource IDs independent of recycled addresses, and reads live objects'
[`MTLResource.allocatedSize`](https://developer.apple.com/documentation/metal/mtlresource/allocatedsize).
Its copy briefly retains each queried object, but keeps no resource ownership
between calls. The registry lock serializes registration and copying; it does not
freeze resource destruction on other threads. This is an enumeration observation
after drain, not an atomic physical-memory census.

Texture and buffer-backed texture views share their parent's storage and do not
add allocation rows. Java-pooled and deferred-release objects remain observable
until native ownership actually releases them. Factory coverage is checked by
`verify_resource_allocation_coverage.py`; introducing untracked device factories
or heaps fails this check. Heap-backed/view resources unexpectedly returned by an
owned-resource factory invalidate the diagnostic instead of guessing shared size.

This scope excludes pipeline objects, opaque MetalFX allocations, system drawables,
driver overhead and allocations created outside this module. It is **not** device
allocation total, physical residency, a window maximum, or DRAM traffic. In
particular, it does not fill `resident_render_resource_bytes` in the canonical
performance metrics. Full resource-residency/peak acceptance remains open.

## ABI and independent integrity

`metallum_resource_allocations_copy(Int64 *rows, Int32 capacityRows,
Int64 *metadata, Int32 metadataCapacityWords) -> Int32` returns the copied row
count or -1 for invalid/insufficient output. The Java bridge supplies all 65,536
row slots in one call, avoiding a sizing/copy race. No output pointers are retained.

Eight metadata words are schema version (1), enabled, capacity (65,536), dropped
rows, invalid events, lifetime created resources, total allocated bytes and live
row count. Eight words per row are resource ID, kind (0 buffer / 1 texture / 2 ICB),
allocated bytes, storage mode, memoryless flag, and three reserved zero words.
Rows are sorted by resource ID. Dead weak entries can be reclaimed without
resetting IDs; overflow/drop/error counts remain visible and reject acceptance.
Java validates the ABI shape; the independent Python checker is the complete
diagnostic integrity gate. `createdResources` is a process-lifetime counter,
not the number of creations during the measurement window.

```bash
python3 scripts/agent/verify_resource_allocations.py \
  build/agent-runs/<run>/native-fullscreen-baseline.json \
  --output build/agent-runs/<run>/resource-allocations.json
```

The independent checker recomputes the row sum and validates strict integer types,
capacity, identities, window binding, memoryless constraints and error counters.
Success means diagnostic integrity only. Missing opt-in data remains unavailable;
malformed present data rejects the trial. Unknown ownership is never filled using
the device's aggregate allocation count.
