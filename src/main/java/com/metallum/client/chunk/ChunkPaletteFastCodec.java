package com.metallum.client.chunk;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.LongStream;

/**
 * Direct NBT palette codec for the clean Minecraft 26.3 section shape.
 *
 * <p>Malformed, unknown, or unsupported values decline immediately and the
 * original codec remains authoritative. Verify mode always returns the
 * original codec result after comparing the independently decoded/encoded form.
 */
public final class ChunkPaletteFastCodec {
    private ChunkPaletteFastCodec() {}

    public static <C> Codec<C> wrap(
            Codec<C> vanilla,
            Function<CompoundTag, C> decoder,
            Function<C, Tag> encoder,
            String kind
    ) {
        return new Codec<>() {
            @Override
            public <T> DataResult<Pair<C, T>> decode(DynamicOps<T> ops, T input) {
                if (ops != NbtOps.INSTANCE || !(input instanceof CompoundTag compound)
                        || ChunkPipelineOptions.paletteParseMode() == ChunkPipelineOptions.Mode.OFF) {
                    return vanilla.decode(ops, input);
                }

                C candidate = decoder.apply(compound);
                if (ChunkPipelineOptions.paletteParseMode() == ChunkPipelineOptions.Mode.VERIFY) {
                    DataResult<Pair<C, T>> reference = vanilla.decode(ops, input);
                    if (candidate != null) {
                        Optional<Pair<C, T>> resolved = reference.result();
                        if (resolved.isEmpty() || !sameContainer(candidate, resolved.get().getFirst())) {
                            ChunkPaletteFastPathTelemetry.mismatch();
                            throw new IllegalStateException("Palette decode verification diverged for " + kind);
                        }
                        ChunkPaletteFastPathTelemetry.decodeVerified();
                    } else {
                        ChunkPaletteFastPathTelemetry.decodeFallback();
                    }
                    return reference;
                }

                if (candidate != null) {
                    ChunkPaletteFastPathTelemetry.decodeFast();
                    return DataResult.success(Pair.of(candidate, ops.empty()));
                }

                ChunkPaletteFastPathTelemetry.decodeFallback();
                return vanilla.decode(ops, input);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> DataResult<T> encode(C input, DynamicOps<T> ops, T prefix) {
                if (ops != NbtOps.INSTANCE
                        || prefix != NbtOps.INSTANCE.empty()
                        || ChunkPipelineOptions.paletteSerializeMode() == ChunkPipelineOptions.Mode.OFF) {
                    return vanilla.encode(input, ops, prefix);
                }

                Tag candidate = encoder.apply(input);
                if (ChunkPipelineOptions.paletteSerializeMode() == ChunkPipelineOptions.Mode.VERIFY) {
                    DataResult<T> reference = vanilla.encode(input, ops, prefix);
                    if (candidate != null) {
                        Optional<T> resolved = reference.result();
                        if (resolved.isEmpty()
                                || !(resolved.get() instanceof Tag referenceTag)
                                || !Arrays.equals(serializedTag(candidate), serializedTag(referenceTag))) {
                            ChunkPaletteFastPathTelemetry.mismatch();
                            throw new IllegalStateException("Palette encode verification diverged for " + kind);
                        }
                        ChunkPaletteFastPathTelemetry.encodeVerified();
                    } else {
                        ChunkPaletteFastPathTelemetry.encodeFallback();
                    }
                    return reference;
                }

                if (candidate != null) {
                    ChunkPaletteFastPathTelemetry.encodeFast();
                    return DataResult.success((T) candidate);
                }

                ChunkPaletteFastPathTelemetry.encodeFallback();
                return vanilla.encode(input, ops, prefix);
            }

            @Override
            public String toString() {
                return "MetalUniversalPaletteFastPath[" + vanilla + "]";
            }
        };
    }

    public static PalettedContainer<BlockState> decodeBlockStates(
            CompoundTag tag,
            Strategy<BlockState> strategy
    ) {
        ListTag paletteTag = tag.get("palette") instanceof ListTag list ? list : null;
        if (paletteTag == null) return null;

        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        for (Tag entry : paletteTag) {
            BlockState state = decodeBlockState(entry);
            if (state == null) return null;
            palette.add(state);
        }
        return unpack(tag, strategy, palette);
    }

    public static PalettedContainerRO<Holder<Biome>> decodeBiomes(
            CompoundTag tag,
            Strategy<Holder<Biome>> strategy,
            Registry<Biome> registry
    ) {
        ListTag paletteTag = tag.get("palette") instanceof ListTag list ? list : null;
        if (paletteTag == null) return null;

        List<Holder<Biome>> palette = new ArrayList<>(paletteTag.size());
        for (Tag entry : paletteTag) {
            if (!(entry instanceof StringTag(String name))) return null;
            Identifier id = parseIdentifier(name);
            if (id == null) return null;
            Optional<Holder.Reference<Biome>> holder = registry.get(id);
            if (holder.isEmpty()) return null;
            palette.add(holder.get());
        }
        return unpack(tag, strategy, palette);
    }

    public static Tag encodeBlockStates(
            PalettedContainer<BlockState> container,
            Strategy<BlockState> strategy
    ) {
        PalettedContainerRO.PackedData<BlockState> packed = container.pack(strategy);
        ListTag palette = new ListTag();
        for (BlockState state : packed.paletteEntries()) {
            palette.add(encodeBlockState(state));
        }
        return encodePacked(palette, packed);
    }

    public static Tag encodeBiomes(
            PalettedContainerRO<Holder<Biome>> container,
            Strategy<Holder<Biome>> strategy
    ) {
        PalettedContainerRO.PackedData<Holder<Biome>> packed = container.pack(strategy);
        ListTag palette = new ListTag();
        for (Holder<Biome> holder : packed.paletteEntries()) {
            if (!(holder instanceof Holder.Reference<Biome> reference)) return null;
            palette.add(StringTag.valueOf(reference.key().identifier().toString()));
        }
        return encodePacked(palette, packed);
    }

    private static CompoundTag encodePacked(ListTag palette, PalettedContainerRO.PackedData<?> packed) {
        CompoundTag result = new CompoundTag();
        result.put("palette", palette);
        packed.storage().ifPresent(words -> result.put("data", new LongArrayTag(words.toArray())));
        return result;
    }

    private static Tag encodeBlockState(BlockState state) {
        Block block = state.getBlock();
        String id = BuiltInRegistries.BLOCK.getKey(block).toString();
        if (state == block.defaultBlockState()) {
            return StringTag.valueOf(id);
        }

        CompoundTag result = new CompoundTag();
        result.putString("id", id);
        CompoundTag properties = new CompoundTag();
        for (Property<?> property : block.getStateDefinition().getProperties()) {
            properties.putString(property.getName(), propertyValue(state, property));
        }
        result.put("properties", properties);
        return result;
    }

    private static BlockState decodeBlockState(Tag tag) {
        if (tag instanceof StringTag(String id)) {
            Block block = block(id);
            return block == null ? null : block.defaultBlockState();
        }
        if (!(tag instanceof CompoundTag compound)
                || !(compound.get("id") instanceof StringTag(String id))) {
            return null;
        }

        Block block = block(id);
        if (block == null) return null;
        StateDefinition<Block, BlockState> definition = block.getStateDefinition();
        BlockState state = block.defaultBlockState();
        if (definition.isSingletonState()) return state;

        Tag propertyTag = compound.get("properties");
        if (propertyTag == null) return state;
        if (!(propertyTag instanceof CompoundTag properties)) return null;

        for (Property<?> property : definition.getProperties()) {
            Tag valueTag = properties.get(property.getName());
            if (valueTag instanceof StringTag(String value)) {
                state = applyProperty(state, property, value);
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState applyProperty(
            BlockState state,
            Property<T> property,
            String value
    ) {
        Optional<T> parsed = property.getValue(value);
        return parsed.map(v -> state.setValue(property, v)).orElse(state);
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static Block block(String raw) {
        Identifier id = parseIdentifier(raw);
        if (id == null) return null;
        return BuiltInRegistries.BLOCK.get(id).map(Holder.Reference::value).orElse(null);
    }

    private static Identifier parseIdentifier(String raw) {
        return Identifier.read(raw).result().orElse(null);
    }

    private static <T> PalettedContainer<T> unpack(
            CompoundTag tag,
            Strategy<T> strategy,
            List<T> palette
    ) {
        Tag data = tag.get("data");
        Optional<LongStream> storage;
        if (data == null) {
            storage = Optional.empty();
        } else if (data instanceof LongArrayTag longs) {
            storage = Optional.of(Arrays.stream(longs.getAsLongArray()));
        } else {
            return null;
        }

        return PalettedContainer.unpack(
                strategy,
                new PalettedContainerRO.PackedData<>(palette, storage)
        ).result().orElse(null);
    }

    private static boolean sameContainer(Object left, Object right) {
        if (!(left instanceof PalettedContainerRO<?> a) || !(right instanceof PalettedContainerRO<?> b)) {
            return false;
        }
        return Arrays.equals(networkBytes(a), networkBytes(b));
    }

    private static byte[] networkBytes(PalettedContainerRO<?> container) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            container.write(buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static byte[] serializedTag(Tag tag) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                NbtIo.writeAnyTag(tag, output);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not serialize NBT for palette verification", exception);
        }
    }
}
