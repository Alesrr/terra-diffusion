package com.github.xandergos.terraindiffusionmc;

import com.github.xandergos.terraindiffusionmc.explorer.ExplorerServer;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.platform.PlatformPaths;
import com.github.xandergos.terraindiffusionmc.pipeline.ModelAssetManager;
import com.github.xandergos.terraindiffusionmc.pipeline.PipelineModels;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionDensityFunction;
import com.github.xandergos.terraindiffusionmc.world.DeepOres;
import com.github.xandergos.terraindiffusionmc.world.TfmgCompat;
import com.github.xandergos.terraindiffusionmc.world.TerralithSnow;
import com.github.xandergos.terraindiffusionmc.world.TerralithSurfaceRules;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import com.github.xandergos.terraindiffusionmc.pipeline.WorldPipeline;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.feature.Feature;
import com.mojang.serialization.MapCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

// Loader-neutral lifecycle and command logic for terrain-diffusion-mc
public final class TerrainDiffusionLifecycle {
    public static final String MOD_ID = "terrain-diffusion-mc";
    private static final Logger LOG = LoggerFactory.getLogger(TerrainDiffusionLifecycle.class);
    public static final ResourceLocation TERRAIN_DIFFUSION_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "terrain_diffusion");
    private static boolean initialized;

    private TerrainDiffusionLifecycle() {
    }

    // Loader entrypoints call this once with their platform-provided runtime paths
    public static synchronized void bootstrap(Path configDir, Path gameDir) {
        PlatformPaths.configure(configDir, gameDir);
        initialize();
    }

    // Runs common mod initialization once per loader
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;

        ModelAssetManager.ensureAssetsReady();
        PipelineModels.load();
    }

    // Registers the common biome source codec through the active loader's registry hook
    public static void registerBiomeSourceCodecs(CodecRegistrar<MapCodec<? extends BiomeSource>> registrar) {
        registrar.register(TERRAIN_DIFFUSION_ID, TerrainDiffusionBiomeSource.CODEC);
    }

    // Registers the common density function codec through the active loader's registry hook
    public static void registerDensityFunctionCodecs(CodecRegistrar<MapCodec<? extends DensityFunction>> registrar) {
        registrar.register(TERRAIN_DIFFUSION_ID, TerrainDiffusionDensityFunction.CODEC);
    }

    // Registers the features that stand in for another mod's own, through the active loader's
    public static void registerFeatures(CodecRegistrar<Feature<?>> registrar) {
        registrar.register(ResourceLocation.fromNamespaceAndPath(MOD_ID, "tfmg_oil_deposit"),
                TfmgCompat.OIL_DEPOSIT);
        registrar.register(ResourceLocation.fromNamespaceAndPath(MOD_ID, "tfmg_oil_well"),
                TfmgCompat.OIL_WELL);
        registrar.register(ResourceLocation.fromNamespaceAndPath(MOD_ID, "scarlet_snow_gate"),
                TerralithSnow.SCARLET_SNOW_GATE);
    }

    @FunctionalInterface
    public interface CodecRegistrar<T> {
        void register(ResourceLocation id, T value);
    }

    // Called by each loader when the server is starting
    public static void onServerStarting() {
        LocalTerrainProvider.clearCache();
    }

    // Called by each loader when a server level is loaded
    public static void onWorldLoad(ServerLevel world) {
        if (world.dimension() == Level.OVERWORLD) {
            WorldScaleManager.initializeForWorld(world);
            WorldPipeline.setCacheRoot(world.getServer().getWorldPath(LevelResource.ROOT));
            LocalTerrainProvider.init(world.getSeed());
            TerralithSurfaceRules.apply(world);
            TerralithSnow.apply(world);
            DeepOres.apply(world);
            TfmgCompat.apply(world);
        }
    }

    // Called by each loader when the server is stopping
    public static void onServerStopping() {
        ExplorerServer.stop();
    }

    // Registers common server commands on the loader-provided command dispatcher
    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("td-explore").executes(TerrainDiffusionLifecycle::executeExplore));
    }

    private static int executeExplore(CommandContext<CommandSourceStack> ctx) {
        try {
            int port = ExplorerServer.startIfNotRunning();
            String url = "http://localhost:" + port;
            MutableComponent link = Component.literal(url)
                    .withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                            .withUnderlined(true));
            ctx.getSource().sendSuccess(
                    () -> Component.literal("Terrain Explorer: ").append(link),
                    false);
        } catch (Exception e) {
            LOG.error("Failed to start terrain explorer", e);
            ctx.getSource().sendFailure(Component.literal("Failed to start terrain explorer: " + e.getMessage()));
        }
        return 1;
    }
}
