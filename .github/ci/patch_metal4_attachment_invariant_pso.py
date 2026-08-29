from pathlib import Path

p = Path('src/main/java/com/metallum/client/metal/render/MetalCompiledRenderPipeline.java')
text = p.read_text()

old = '''            for (DepthStencilFormats formats : eagerFormats) {
                MemorySegment pipeline = createPipeline(
                        device,
                        info,
                        this.vertexFunction,
                        this.fragmentFunction,
                        vertexDescriptor,
                        this.colorFormats,
                        formats.depthFormat(),
                        formats.stencilFormat()
                );
                if (!MetalNativeBridge.isNullHandle(pipeline)) {
                    states.put(this.signatureFor(formats.depthFormat(), formats.stencilFormat()), pipeline);
                }
            }
'''
new = '''            for (DepthStencilFormats formats : eagerFormats) {
                PipelineSignature signature = this.signatureFor(
                        formats.depthFormat(), formats.stencilFormat()
                );
                // MTL4RenderPipelineDescriptor has no depth/stencil attachment
                // format fields: those formats are supplied by the render pass.
                // Once signatureFor canonicalizes Metal 4 attachment state, do
                // not compile the same native PSO twice during eager startup.
                if (states.containsKey(signature)) {
                    continue;
                }
                MemorySegment pipeline = createPipeline(
                        device,
                        info,
                        this.vertexFunction,
                        this.fragmentFunction,
                        vertexDescriptor,
                        this.colorFormats,
                        formats.depthFormat(),
                        formats.stencilFormat()
                );
                if (!MetalNativeBridge.isNullHandle(pipeline)) {
                    states.put(signature, pipeline);
                }
            }
'''
if text.count(old) != 1:
    raise SystemExit('eager loop anchor mismatch')
text = text.replace(old, new, 1)

old = '''        if (this.lazyVariants) {
            for (DepthStencilFormats formats : supportedDepthStencilFormats()) {
'''
new = '''        if (this.lazyVariants && !device.metal4MainRendererEnabled()) {
            for (DepthStencilFormats formats : supportedDepthStencilFormats()) {
'''
if text.count(old) != 1:
    raise SystemExit('lazy variants anchor mismatch')
text = text.replace(old, new, 1)

old = '''    private PipelineSignature signatureFor(final MTLPixelFormat depthFormat, final MTLPixelFormat stencilFormat) {
        return new PipelineSignature(this.colorFormatsView, depthFormat, stencilFormat, 1);
    }
'''
new = '''    private PipelineSignature signatureFor(final MTLPixelFormat depthFormat, final MTLPixelFormat stencilFormat) {
        if (this.device.metal4MainRendererEnabled()) {
            // Metal 4 deliberately removes depth/stencil attachment formats from
            // the pipeline descriptor. They are render-pass state, so carrying
            // them in the Java PSO cache key would manufacture duplicate native
            // pipelines that compile identical Metal 4 descriptors.
            return new PipelineSignature(
                    this.colorFormatsView,
                    MTLPixelFormat.Invalid,
                    MTLPixelFormat.Invalid,
                    1
            );
        }
        return new PipelineSignature(this.colorFormatsView, depthFormat, stencilFormat, 1);
    }
'''
if text.count(old) != 1:
    raise SystemExit('signature anchor mismatch')
text = text.replace(old, new, 1)

p.write_text(text)
print('Metal 4 attachment-invariant PSO patch applied')
