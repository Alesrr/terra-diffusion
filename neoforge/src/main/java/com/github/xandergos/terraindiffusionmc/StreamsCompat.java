package com.github.xandergos.terraindiffusionmc;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.pipeline.RiverHydrology;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.mojang.serialization.Codec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public final class StreamsCompat {

    private static final Logger LOG = LoggerFactory.getLogger(StreamsCompat.class);
    private static final String MOD_ID = "streamsreflowing";
    private static final int LAYER = 1536;
    private static final int SPEED_AT = 256;
    private static final int LO_AT = 512;
    private static final int HI_AT = 1024;
    private static final int STREAMS_RIVER_SPAN = 12;
    private static final byte[] NONE = new byte[0];
    private static final String[] OVERRIDDEN = {"generateStreams", "generateLakes", "flowVanillaRivers"};

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, TerrainDiffusionMc.FML_MOD_ID);

    private static final Supplier<AttachmentType<byte[]>> FLOW = ATTACHMENTS.register("river_flow",
            () -> AttachmentType.<byte[]>builder(() -> NONE)
                    .serialize(Codec.BYTE_BUFFER.xmap(StreamsCompat::bytesOf, ByteBuffer::wrap))
                    .build());

    private static final ExecutorService BACKFILL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "terra-diffusion-streams-flow");
        t.setDaemon(true);
        return t;
    });

    // Namespace of the flow attachment in worlds saved before the mod id was renamed
    private static final String LEGACY_NAMESPACE = "terra_diffusion_compat";

    private static Method storeGrid;
    private static Method peekGrid;
    private static volatile MethodHandle physicsSpeed;
    private static Constructor<?> payload;
    private static Field[] overridden;
    private static boolean[] userValues;
    private static boolean terrainWorld;

    private StreamsCompat() {
    }

    static void init(IEventBus modBus) {
        if (!ModList.get().isLoaded(MOD_ID)) {
            return;
        }
        try {
            ClassLoader loader = StreamsCompat.class.getClassLoader();
            Class<?> grid = Class.forName("dev.streamsreflowing.flow.StreamFlowGrid", false, loader);
            storeGrid = grid.getDeclaredMethod("cache", long.class, byte[].class, boolean.class);
            storeGrid.setAccessible(true);
            peekGrid = grid.getMethod("peek", int.class, int.class);
            physicsSpeed = MethodHandles.publicLookup().findStatic(grid, "speedForPhysics",
                    MethodType.methodType(double.class, BlockGetter.class, int.class, int.class, int.class));
            Class<?> net = Class.forName("dev.streamsreflowing.neoforge.net.StreamFlowNet$FlowGridPayload", false, loader);
            payload = net.getDeclaredConstructor(int.class, int.class, byte[].class);
            net.getField("TYPE");
            Class<?> settings = Class.forName("dev.streamsreflowing.config.StreamsSettings", false, loader);
            overridden = new Field[OVERRIDDEN.length];
            for (int k = 0; k < OVERRIDDEN.length; k++) {
                overridden[k] = settings.getDeclaredField(OVERRIDDEN[k]);
                overridden[k].setAccessible(true);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("Streams Reflowing is installed but its flow API was not recognised; Terra Diffusion rivers will have no current there", e);
            return;
        }
        ATTACHMENTS.register(modBus);
        modBus.addListener(StreamsCompat::aliasLegacyAttachment);
        LocalTerrainProvider.enableFlowField();
        NeoForge.EVENT_BUS.addListener(StreamsCompat::onLevelLoad);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, StreamsCompat::onServerStarting);
        NeoForge.EVENT_BUS.addListener(StreamsCompat::onServerStopped);
        NeoForge.EVENT_BUS.addListener(StreamsCompat::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(StreamsCompat::onChunkSent);
        LOG.info("Streams Reflowing found: Terra Diffusion rivers will carry its current");
    }

    private static void aliasLegacyAttachment(RegisterEvent event) {
        if (event.getRegistryKey().equals(NeoForgeRegistries.Keys.ATTACHMENT_TYPES)) {
            event.getRegistry().addAlias(ResourceLocation.fromNamespaceAndPath(LEGACY_NAMESPACE, "river_flow"),
                    ResourceLocation.fromNamespaceAndPath(TerrainDiffusionMc.FML_MOD_ID, "river_flow"));
        }
    }

    // Mobs in lake current slower than any river are pushed by that speed
    public static double lakeMobScale(FluidType type, Entity entity) {
        MethodHandle speed = physicsSpeed;
        if (!terrainWorld || speed == null || type != NeoForgeMod.WATER_TYPE.value()
                || !(entity instanceof LivingEntity) || entity instanceof Player
                || !(entity.level() instanceof ServerLevel level)) {
            return 1.0;
        }
        double v;
        try {
            v = (double) speed.invokeExact((BlockGetter) level, entity.getBlockX(), entity.getBlockY(), entity.getBlockZ());
        } catch (Throwable t) {
            physicsSpeed = null;
            LOG.warn("Streams Reflowing's current speed lookup failed; lake current no longer slows mobs", t);
            return 1.0;
        }
        return v > 0.0 && v < RiverHydrology.RIVER_MIN_SPEED ? v : 1.0;
    }

    private static void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        terrainWorld = level.getChunkSource().getGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource;
        if (!terrainWorld) {
            return;
        }
        try {
            if (userValues == null) {
                userValues = new boolean[overridden.length];
                for (int k = 0; k < overridden.length; k++) {
                    userValues[k] = overridden[k].getBoolean(null);
                }
            }
        } catch (ReflectiveOperationException e) {
            LOG.warn("Could not read Streams Reflowing's settings", e);
        }
        turnOffStreamsRivers();
    }

    private static void onServerStarting(ServerStartingEvent event) {
        if (terrainWorld) {
            turnOffStreamsRivers();
        }
    }

    private static void turnOffStreamsRivers() {
        try {
            for (Field f : overridden) {
                if (f.getBoolean(null)) {
                    f.setBoolean(null, false);
                }
            }
        } catch (ReflectiveOperationException e) {
            LOG.warn("Could not turn off Streams Reflowing's own river generation for this world", e);
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        terrainWorld = false;
        if (userValues == null) {
            return;
        }
        try {
            for (int k = 0; k < overridden.length; k++) {
                overridden[k].setBoolean(null, userValues[k]);
            }
        } catch (ReflectiveOperationException e) {
            LOG.warn("Could not restore Streams Reflowing's settings", e);
        }
        userValues = null;
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (!terrainWorld || !(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD
                || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        if (chunk.hasData(FLOW)) {
            publish(chunk.getData(FLOW), chunk.getPos(), level, null);
            return;
        }
        fill(chunk, level);
    }

    private static void onChunkSent(ChunkWatchEvent.Sent event) {
        if (!terrainWorld || event.getLevel().dimension() != Level.OVERWORLD) {
            return;
        }
        turnOffStreamsRivers();
        LevelChunk chunk = event.getChunk();
        if (chunk.hasData(FLOW)) {
            publish(chunk.getData(FLOW), chunk.getPos(), event.getLevel(), event.getPlayer());
        }
    }

    private static void fill(LevelChunk chunk, ServerLevel level) {
        ChunkPos pos = chunk.getPos();
        int tileSize = TerrainDiffusionConfig.tileSize();
        int startX = Math.floorDiv(pos.getMinBlockX(), tileSize) * tileSize;
        int startZ = Math.floorDiv(pos.getMinBlockZ(), tileSize) * tileSize;
        HeightmapData tile = LocalTerrainProvider.peekHeightmap(startZ, startX, startZ + tileSize, startX + tileSize);
        if (tile != null) {
            store(chunk, level, gridFor(tile, pos, startX, startZ));
            return;
        }
        BACKFILL.execute(() -> {
            HeightmapData data = LocalTerrainProvider.getInstance()
                    .fetchHeightmap(startZ, startX, startZ + tileSize, startX + tileSize);
            byte[] grid = gridFor(data, pos, startX, startZ);
            level.getServer().execute(() -> store(chunk, level, grid));
        });
    }

    private static void store(ChunkAccess chunk, ServerLevel level, byte[] grid) {
        chunk.setData(FLOW, grid);
        chunk.setUnsaved(true);
        publish(grid, chunk.getPos(), level, null);
    }

    private static void publish(byte[] grid, ChunkPos pos, ServerLevel level, ServerPlayer only) {
        if (grid.length == 0) {
            return;
        }
        try {
            if (peekGrid.invoke(null, pos.x, pos.z) == null) {
                long key = (long) pos.x << 32 ^ (long) pos.z & 0xFFFFFFFFL;
                storeGrid.invoke(null, key, grid, true);
            }
            CustomPacketPayload packet = (CustomPacketPayload) payload.newInstance(pos.x, pos.z, grid);
            if (only != null) {
                send(only, packet);
                return;
            }
            for (ServerPlayer watcher : level.getChunkSource().chunkMap.getPlayers(pos, false)) {
                send(watcher, packet);
            }
        } catch (ReflectiveOperationException e) {
            LOG.warn("Could not hand chunk {} to Streams Reflowing", pos, e);
        }
    }

    private static void send(ServerPlayer player, CustomPacketPayload packet) {
        if (player.connection.hasChannel(packet.type())) {
            PacketDistributor.sendToPlayer(player, packet);
        }
    }

    private static byte[] gridFor(HeightmapData tile, ChunkPos pos, int startX, int startZ) {
        if (tile == null || tile.flowAngle == null) {
            return NONE;
        }
        byte[] grid = new byte[LAYER];
        Arrays.fill(grid, 0, SPEED_AT, (byte) -1);
        boolean any = false;
        for (int lz = 0; lz < 16; lz++) {
            int r = pos.getMinBlockZ() + lz - startZ;
            for (int lx = 0; lx < 16; lx++) {
                int c = pos.getMinBlockX() + lx - startX;
                byte angle = tile.flowAngle[r][c];
                short level = tile.waterLevel[r][c];
                if (angle == -1 || level == HeightmapData.NO_WATER) {
                    continue;
                }
                int ground = HeightConverter.convertToMinecraftHeight(tile.heightmap[r][c]);
                int top = HeightConverter.convertToMinecraftHeight(level);
                if (top <= ground) {
                    continue;
                }
                int flow = tile.waterFlow == null ? 0 : tile.waterFlow[r][c] & 0xff;
                int hi = flow >= 16 ? top + flow - 17 : flow >= 1 ? top : top - 1;
                int lo = hi - ground == STREAMS_RIVER_SPAN ? ground - 1 : ground;
                int i = lx + lz * 16;
                grid[i] = angle;
                grid[SPEED_AT + i] = tile.flowSpeed[r][c];
                grid[LO_AT + 2 * i] = (byte) (lo >> 8);
                grid[LO_AT + 2 * i + 1] = (byte) lo;
                grid[HI_AT + 2 * i] = (byte) (hi >> 8);
                grid[HI_AT + 2 * i + 1] = (byte) hi;
                any = true;
            }
        }
        return any ? grid : NONE;
    }

    private static byte[] bytesOf(ByteBuffer buffer) {
        byte[] out = new byte[buffer.remaining()];
        buffer.duplicate().get(out);
        return out;
    }
}
