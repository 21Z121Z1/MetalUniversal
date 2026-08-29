from pathlib import Path


def once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected one match, got {count}')
    return text.replace(old, new, 1)

path = Path('src/main/java/com/metallum/client/metal/render/MetalGpuTexture.java')
text = path.read_text()
text = once(
    text,
    'import java.lang.foreign.MemorySegment;\n',
    'import java.lang.foreign.MemorySegment;\nimport java.util.Objects;\n',
    'objects import'
)
text = once(
    text,
    '    private final MTLPixelFormat mtlPixelFormat;\n',
    '    private final MTLPixelFormat mtlPixelFormat;\n    private final MTLStorageMode storageMode;\n',
    'storage field'
)
old_ctor = '''    MetalGpuTexture(
            final MetalDevice device,
            @GpuTexture.Usage final int usage,
            final String label,
            final GpuFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels,
            final MetalTextureDimension dimension
    ) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.device = device;
        this.allocationIdentity = MetalAllocationIdentity.allocate(label);
        this.mtlPixelFormat = MTLPixelFormat.from(format);

        this.nativeHandle = MetalNativeBridge.metallum_create_texture(
                device.metalDeviceHandle(),
                this.mtlPixelFormat,
                width,
                height,
                depthOrLayers,
                mipLevels,
                dimension.nativeValue,
                (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0 ? 1L : 0L,
                toMtlTextureUsage(usage),
                MTLStorageMode.Private,
                label
        );
        if (MetalNativeBridge.isNullHandle(this.nativeHandle)) {
            throw new IllegalStateException(
                    "Failed to create Metal " + dimension + " texture " + label + " ("
                            + width + 'x' + height + 'x' + depthOrLayers + ", " + format + ')'
            );
        }
    }
'''
new_ctor = '''    MetalGpuTexture(
            final MetalDevice device,
            @GpuTexture.Usage final int usage,
            final String label,
            final GpuFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels,
            final MetalTextureDimension dimension
    ) {
        this(device, usage, label, format, width, height, depthOrLayers, mipLevels, dimension, MTLStorageMode.Private);
    }

    MetalGpuTexture(
            final MetalDevice device,
            @GpuTexture.Usage final int usage,
            final String label,
            final GpuFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels,
            final MetalTextureDimension dimension,
            final MTLStorageMode storageMode
    ) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.device = device;
        this.allocationIdentity = MetalAllocationIdentity.allocate(label);
        this.mtlPixelFormat = MTLPixelFormat.from(format);
        this.storageMode = Objects.requireNonNull(storageMode, "storageMode");
        if (storageMode == MTLStorageMode.Memoryless
                && !memorylessCompatible(usage, dimension, depthOrLayers, mipLevels)) {
            throw new IllegalArgumentException(
                    "Memoryless Metal textures must be single-layer 2D render-only attachments"
            );
        }

        this.nativeHandle = MetalNativeBridge.metallum_create_texture(
                device.metalDeviceHandle(),
                this.mtlPixelFormat,
                width,
                height,
                depthOrLayers,
                mipLevels,
                dimension.nativeValue,
                (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0 ? 1L : 0L,
                toMtlTextureUsage(usage, storageMode),
                storageMode,
                label
        );
        if (MetalNativeBridge.isNullHandle(this.nativeHandle)) {
            throw new IllegalStateException(
                    "Failed to create Metal " + dimension + " texture " + label + " ("
                            + width + 'x' + height + 'x' + depthOrLayers + ", " + format + ", "
                            + storageMode + ')'
            );
        }
    }

    static MetalGpuTexture createMemorylessRenderTarget(
            final MetalDevice device,
            @GpuTexture.Usage final int usage,
            final String label,
            final GpuFormat format,
            final int width,
            final int height
    ) {
        return new MetalGpuTexture(
                device,
                usage,
                label,
                format,
                width,
                height,
                1,
                1,
                MetalTextureDimension.TWO_D,
                MTLStorageMode.Memoryless
        );
    }

    static boolean memorylessCompatible(
            @GpuTexture.Usage final int usage,
            final MetalTextureDimension dimension,
            final int depthOrLayers,
            final int mipLevels
    ) {
        return usage == GpuTexture.USAGE_RENDER_ATTACHMENT
                && dimension == MetalTextureDimension.TWO_D
                && depthOrLayers == 1
                && mipLevels == 1;
    }
'''
text = once(text, old_ctor, new_ctor, 'texture constructor')
text = once(
    text,
    '    MTLPixelFormat mtlPixelFormat() {\n        return this.mtlPixelFormat;\n    }\n',
    '    MTLPixelFormat mtlPixelFormat() {\n        return this.mtlPixelFormat;\n    }\n\n'
    '    MTLStorageMode storageMode() {\n        return this.storageMode;\n    }\n',
    'storage accessor'
)
text = once(
    text,
    '    private long toMtlTextureUsage(@GpuTexture.Usage final int usage) {\n',
    '    private long toMtlTextureUsage(\n            @GpuTexture.Usage final int usage,\n            final MTLStorageMode storageMode\n    ) {\n',
    'usage signature'
)
text = once(
    text,
    '''        if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
            result |= MTLTextureUsage.RenderTarget.value;
            result |= MTLTextureUsage.ShaderRead.value;
''',
    '''        if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
            result |= MTLTextureUsage.RenderTarget.value;
            // Pass-local memoryless attachments never become long-lived shader
            // resources; keep their Metal usage at RenderTarget so the driver
            // can preserve the strongest tile-memory assumptions.
            if (storageMode != MTLStorageMode.Memoryless) {
                result |= MTLTextureUsage.ShaderRead.value;
            }
''',
    'minimal memoryless usage'
)
path.write_text(text)
print('memoryless texture allocation patch applied')
