# Numeric binding diagnostics

`-Dmetallum.validation.numericBindings=true` enables process-lifetime fact
counters for the numeric compatibility API. The default is off; the startup
gate is immutable. Validation writes `numeric-binding-diagnostics.json` with
the source SHA, trial ID, status, diagnostic scope and counters.

| Counter | Actual observation |
| --- | --- |
| `numericStorageBufferCalls` | Entry into `MetalRenderPass.bindStorageBuffer(int, slice)`, including invalid calls that subsequently throw |
| `numericUniformCalls` | Entry into `MetalRenderPass.setUniform(int, Object)`, including invalid calls |
| `resourceScanSteps` | Resource elements visited by numeric pipeline lookup or storage-buffer dirty-mask scanning |
| `descriptorNameParseCalls` | Calls to parse a storage descriptor name in the numeric storage-buffer dirty-mask scan |
| `numericToNameDispatches` | Numeric uniform index successfully resolved, before its existing name-based dispatch, including null removal |

These are call counts, not elapsed CPU costs or measured-window metrics. The
snapshot sums independent adders and does not claim a transactionally atomic
multi-counter view. It includes warmup. No production reset is performed.
The parser counter covers only the named numeric storage-buffer path, not
all descriptor parsing in the renderer. Resource scan counts do not identify
a pipeline generation. The observations neither change dispatch behavior nor
justify enabling a numeric binding optimization without equivalence tests
and paired trials.
