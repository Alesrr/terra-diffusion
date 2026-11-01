package com.github.xandergos.terraindiffusionmc.checks;

import com.github.xandergos.terraindiffusionmc.world.WorldScaleSettingsState;
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** One owned world, one village and bounded FULL chunks, followed by a save/reload check. */
@EventBusSubscriber(modid = "terra_diffusion_compat")
public final class WorldSmokeCheck {
    private static final ChunkPos ORIGIN = new ChunkPos(375, 782);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final TicketType<ChunkPos> TICKET = TicketType.create("td_village_smoke", Comparator.comparingLong(ChunkPos::toLong));
    private static CompletableFuture<List<CompletableFuture<ChunkResult<ChunkAccess>>>> submitted;
    private static List<CompletableFuture<ChunkResult<ChunkAccess>>> pending;
    private static StructureStart start;
    private static boolean active, full;
    private static int minX, maxX, minZ, maxZ;
    private static long began;

    static void prepare(ServerLevel level) {
        var expectedType = level.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE)
                .get(ResourceLocation.parse("terrain-diffusion-mc:terrain_diffusion_scale_4"));
        require(level.getHeight() == expectedType.height() && level.getMinBuildHeight() == expectedType.minY(),
                "world uses the registered scale-4 dimension height");
        var server = level.getServer();
        var data = server.getWorldData().overworldData();
        data.setSpawn(new BlockPos(ORIGIN.getMiddleBlockX(), 100, ORIGIN.getMiddleBlockZ()), 0);
        data.setInitialized(true);
        level.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, server);
        active = true;
    }

    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        if (!active) return;
        began = System.nanoTime();
        request(event.getServer().overworld(), ORIGIN.x, ORIGIN.z, ORIGIN.x, ORIGIN.z, ChunkStatus.STRUCTURE_STARTS);
    }

    private static void request(ServerLevel level, int x0, int z0, int x1, int z1, ChunkStatus status) {
        int distance = ChunkLevel.byStatus(ChunkStatus.FULL)-ChunkLevel.byStatus(status);
        for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
            var pos = new ChunkPos(x,z);
            level.getChunkSource().addRegionTicket(TICKET, pos, distance, pos);
        }
        pending = null;
        submitted = CompletableFuture.supplyAsync(() -> {
            var result = new ArrayList<CompletableFuture<ChunkResult<ChunkAccess>>>();
            for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++)
                result.add(level.getChunkSource().getChunkFuture(x,z,status,true));
            return result;
        });
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (!active || submitted == null || !submitted.isDone()) return;
        var server = event.getServer();
        var level = server.overworld();
        try {
            if (pending == null) pending = submitted.join();
            if (pending.stream().anyMatch(f -> !f.isDone())) return;
            if (!full) {
                var chunk = pending.getFirst().join().orElseThrow(() -> new IllegalStateException("Start chunk request failed"));
                var structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                        .get(ResourceLocation.parse("minecraft:village_plains"));
                start = chunk.getStartForStructure(structure);
                require(start != null && start.isValid(), "expected real village start");
                var box = start.getBoundingBox();
                minX = Math.floorDiv(box.minX(),16)-1; maxX = Math.floorDiv(box.maxX(),16)+1;
                minZ = Math.floorDiv(box.minZ(),16)-1; maxZ = Math.floorDiv(box.maxZ(),16)+1;
                require(maxX-minX+1 <= 24 && maxZ-minZ+1 <= 24
                        && (maxX-minX+1)*(maxZ-minZ+1) <= 400, "bounded village footprint");
                full = true;
                System.out.println("WORLD SMOKE: generating "+(maxX-minX+1)*(maxZ-minZ+1)+" FULL chunks for "+start.getPieces().size()+" native pieces");
                request(level, minX,minZ,maxX,maxZ,ChunkStatus.FULL);
                return;
            }
            var counts = new TreeMap<String,Integer>();
            int beds=0, bells=0;
            for (var future : pending) {
                var chunk = future.join().orElseThrow(() -> new IllegalStateException("FULL chunk request failed"));
                require(chunk.getPersistedStatus() == ChunkStatus.FULL, "real FULL chunk");
                for (int z=chunk.getPos().getMinBlockZ();z<=chunk.getPos().getMaxBlockZ();z++)
                    for (int x=chunk.getPos().getMinBlockX();x<=chunk.getPos().getMaxBlockX();x++) {
                        int top=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,x&15,z&15);
                        for (int y=Math.max(level.getMinBuildHeight(),top-32);y<=top;y++) {
                            var state=chunk.getBlockState(new BlockPos(x,y,z));
                            if (state.getBlock() instanceof BedBlock) beds++;
                            if (state.is(Blocks.BELL)) bells++;
                            if (state.getBlock() instanceof BedBlock || state.is(Blocks.BELL) || state.is(Blocks.DIRT_PATH))
                                counts.merge(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),1,Integer::sum);
                        }
                    }
            }
            require(beds>0 && bells>0, "village beds and bell actually placed");
            var policy=level.getDataStorage().get(WorldScaleSettingsState.TYPE,"terrain_diffusion_world_settings");
            require(policy != null && policy.scaleStructureSpacing(), "saved world policy");
            var villages=level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET).get(ResourceLocation.parse("minecraft:villages"));
            var original=(RandomSpreadStructurePlacement)villages.placement();
            var adjusted=level.getChunkSource().getGeneratorState().possibleStructureSets().stream()
                    .filter(h -> h.value().structures().equals(villages.structures())).findFirst().orElseThrow();
            var placement=(RandomSpreadStructurePlacement)adjusted.value().placement();
            require(original.spacing()==34 && placement.spacing()==136, "per-generator scaling without registry mutation");
            require(placement.getPotentialStructureChunk(level.getSeed(),ORIGIN.x,ORIGIN.z).equals(ORIGIN), "start lies on new grid");
            require(!original.getPotentialStructureChunk(level.getSeed(),ORIGIN.x,ORIGIN.z).equals(ORIGIN), "start distinguishes old grid");
            var villageHolder = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                    .getHolder(ResourceLocation.parse("minecraft:village_plains")).orElseThrow();
            var located = level.getChunkSource().getGenerator().findNearestMapStructure(level, HolderSet.direct(villageHolder),
                    new BlockPos(ORIGIN.getMiddleBlockX(),100,ORIGIN.getMiddleBlockZ()), 1, false);
            require(located != null && new ChunkPos(located.getFirst()).equals(ORIGIN), "native locate uses the same scaled grid");
            JsonObject result=new JsonObject();
            result.addProperty("seed",level.getSeed()); result.addProperty("scale",policy.getScale());
            result.addProperty("chunks",pending.size());result.addProperty("pieces",start.getPieces().size());
            result.addProperty("startChunkX",ORIGIN.x);result.addProperty("startChunkZ",ORIGIN.z);
            result.addProperty("nativeLocatePassed",true);
            result.addProperty("minY",level.getMinBuildHeight());result.addProperty("height",level.getHeight());
            result.add("placedVillageBlocks",GSON.toJsonTree(counts));
            result.addProperty("elapsedSeconds",(System.nanoTime()-began)/1e9);
            var phase=System.getProperty("terradiff.structureTest.smokePhase");
            Path baseline=Path.of("world-smoke-generate.json");
            if (phase.equals("reload")) {
                var previous=JsonParser.parseString(Files.readString(baseline)).getAsJsonObject();
                require(previous.get("placedVillageBlocks").equals(result.get("placedVillageBlocks")),"blocks persist exactly after reload");
                require(previous.get("pieces").equals(result.get("pieces")),"saved structure layout persists");
            }
            server.saveEverything(true,true,true);
            Files.writeString(Path.of("world-smoke-"+phase+".json"),GSON.toJson(result));
            System.out.println("WORLD SMOKE PASSED "+phase+": "+pending.size()+" FULL chunks, "+beds+" bed blocks, "+bells+" bells");
            active=false; server.halt(false);
        } catch (Throwable failure) {
            active=false;failure.printStackTrace();
            try { Files.writeString(Path.of("world-smoke.failure.txt"),failure.toString()); } catch(Exception ignored) {}
            server.halt(false);
        }
    }

    private static void require(boolean condition,String message) {
        if (!condition) throw new AssertionError(message);
    }
}
