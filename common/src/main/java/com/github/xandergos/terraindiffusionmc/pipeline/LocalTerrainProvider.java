package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.infinitetensor.InfiniteTensor;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;

public final class LocalTerrainProvider {

    private static final Logger LOG = LoggerFactory.getLogger(LocalTerrainProvider.class);

    private static final float NATIVE_RESOLUTION = WorldPipelineModelConfig.nativeResolution();

    private static final FastNoiseLite ELEV_NOISE_COARSE = makeFnl(99999, 1f/24f, 3, 2f, 0.5f);
    private static final FastNoiseLite ELEV_NOISE_FINE   = makeFnl(88888, 1f/6f, 2, 2f, 0.6f);

    private static final FastNoiseLite ELEV_DITHER       = makeFnl(77777, 1f/1.7f, 1, 2f, 0.5f);

    private static final float DITHER_BAND_GRAD =
            Float.parseFloat(System.getProperty("terradiff.ditherBandGrad", "0.125"));

    private static final float DETAIL_FLOOR_BLOCKS =
            Float.parseFloat(System.getProperty("terradiff.ditherFloor", "0.20"));

    private static final float AMP_COARSE =
            Float.parseFloat(System.getProperty("terradiff.ampCoarse", "100"));

    private static final float AMP_FINE =
            Float.parseFloat(System.getProperty("terradiff.ampFine", "70"));

    private static final float BANK_FRINGE_BLOCKS =
            Float.parseFloat(System.getProperty("terradiff.bankFringe", "8.0"));

    private static final float RELAX_MAX_DROP =
            Float.parseFloat(System.getProperty("terradiff.relaxMaxDrop", "99"));

    private static final boolean LEGACY_DITHER = "old".equals(System.getProperty("terradiff.dither"));
    private static final boolean NO_DITHER = "off".equals(System.getProperty("terradiff.dither"));

    private static FastNoiseLite makeFnl(int seed, float freq, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(freq);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }

    public static final class HeightmapData {
        public static final short NO_WATER = Short.MIN_VALUE;

        public final short[][] heightmap;
        public final short[][] biomeIds;

        public final byte[][] snowLayers;
        public final short[][] waterLevel;
        public final byte[][] climateT;
        public final byte[][] climateH;
        public final byte[][] climateE;
        public KarstNetwork karst = KarstNetwork.EMPTY;
        // Lowest surface Y around each column, filled on first use by the density function
        public volatile short[] sideSurface;
        public final int width;
        public final int height;

        public HeightmapData(short[][] heightmap, short[][] biomeIds, byte[][] snowLayers,
                             short[][] waterLevel, byte[][] climateT, byte[][] climateH,
                             byte[][] climateE, int width, int height) {
            this.heightmap  = heightmap;
            this.biomeIds   = biomeIds;
            this.snowLayers = snowLayers;
            this.waterLevel = waterLevel;
            this.climateT   = climateT;
            this.climateH   = climateH;
            this.climateE   = climateE;
            this.width      = width;
            this.height     = height;
        }
    }

    private static record CacheKey(int i1, int j1, int i2, int j2) {}
    private static record CacheEntry(HeightmapData data, AtomicLong lastAccessed) {}

    // Blocks of area to keep cached, so tile size does not decide the size of the working set
    private static final long CACHE_AREA_BLOCKS =
            Long.parseLong(System.getProperty("terradiff.tileCacheArea", "16777216"));
    private static final int MAX_CACHE_SIZE_HEADROOM = 8;
    private static volatile int maxCacheSize;
    private static final Map<CacheKey, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong CACHE_CLOCK = new AtomicLong();
    private static final Map<CacheKey, Future<HeightmapData>> PENDING = new ConcurrentHashMap<>();

    // The main thread is stalled, so the game is visibly frozen until this tile lands
    private static final int PRIORITY_URGENT = 0;
    private static final int PRIORITY_BLOCKING = 5;
    // Explorer water runs only when nothing else is waiting
    private static final int PRIORITY_BACKGROUND = 10;
    // Stops speculative work from growing without bound if the player outruns the generator
    private static final AtomicLong TASK_SEQ = new AtomicLong();

    // Ordered by priority so a caller waiting on a tile is served before speculative work
    private static final ThreadPoolExecutor INFERENCE_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new PriorityBlockingQueue<Runnable>(),
            r -> {
                Thread t = new Thread(r, "terrain-diffusion-inference");
                t.setDaemon(true);
                return t;
            });

    private static final class PriorityTask<T> extends FutureTask<T>
            implements Comparable<PriorityTask<?>> {
        private volatile int priority;
        private final long seq = TASK_SEQ.incrementAndGet();

        PriorityTask(Callable<T> task, int priority) {
            super(task);
            this.priority = priority;
        }

        @Override
        public int compareTo(PriorityTask<?> other) {
            int byPriority = Integer.compare(priority, other.priority);
            return byPriority != 0 ? byPriority : Long.compare(seq, other.seq);
        }
    }

    // Lifts a queued task to a higher priority
    private static void raisePriority(PriorityTask<?> task, int priority) {
        if (task.priority <= priority) return;
        if (INFERENCE_EXECUTOR.getQueue().remove(task)) {
            task.priority = priority;
            INFERENCE_EXECUTOR.execute(task);
        }
    }

    private static int maxCacheSize() {
        int cached = maxCacheSize;
        if (cached != 0) return cached;
        int tile = TerrainDiffusionConfig.tileSize();
        long tiles = CACHE_AREA_BLOCKS / ((long) tile * tile);
        cached = (int) Math.max(64L, Math.min(4096L, tiles));
        maxCacheSize = cached;
        return cached;
    }

    private static volatile LocalTerrainProvider INSTANCE;
    private static volatile long instanceSeed;

    private static LocalTerrainProvider EXPLORER;
    private static volatile long explorerSeed;
    private static volatile String explorerView = "";
    private static volatile int explorerGeneration = -1;
    private static volatile int worldGeneration;

    private final WorldPipeline pipeline;

    private static final Object INIT_LOCK = new Object();

    private LocalTerrainProvider(long seed, PipelineModels models, boolean readOnlyDisk) {
        this.pipeline = new WorldPipeline(seed, models, readOnlyDisk);
    }

    private static PipelineModels loadedModels() {
        PipelineModels.awaitLoad();
        PipelineModels models = PipelineModels.getInstance();
        if (models == null) throw new IllegalStateException("PipelineModels failed to load");
        return models;
    }

    public static synchronized void init(long seed) {
        DeepCaverns.setSeed(seed);
        CaveBiomes.setSeed(seed);
        PipelineModels models = loadedModels();
        if (INSTANCE == null) {
            INSTANCE = new LocalTerrainProvider(seed, models, false);
            instanceSeed = seed;
        } else if (instanceSeed != seed) {
            INSTANCE.pipeline.setSeed(seed);
            instanceSeed = seed;
            CACHE.clear();
            PENDING.clear();
        }
        worldGeneration++;
    }

    public static LocalTerrainProvider getInstance() {
        if (INSTANCE != null) return INSTANCE;

        synchronized(INIT_LOCK) {
            if (INSTANCE != null) return INSTANCE;
            INSTANCE = new LocalTerrainProvider(0L, loadedModels(), false);
            instanceSeed = 0L;
        }

        return INSTANCE;
    }

    public static void clearCache() {
        CACHE.clear();
        PENDING.clear();
    }

    public static long getSeed() {
        return instanceSeed;
    }

    // Changes on every world load, so position-keyed caches know to refill
    public static int generation() {
        return worldGeneration;
    }

    // The explorer's own pipeline, recreated on the world's seed after each world load; inference thread only
    private static LocalTerrainProvider explorer() {
        int generation = worldGeneration;
        if (EXPLORER == null || explorerGeneration != generation) {
            if (EXPLORER != null) {
                EXPLORER.pipeline.close();
            }
            long seed = instanceSeed;
            EXPLORER = new LocalTerrainProvider(seed, loadedModels(), true);
            EXPLORER.pipeline.hydrology.buildSeamNeighbours(false);
            explorerSeed = seed;
            explorerGeneration = generation;
        }
        return EXPLORER;
    }

    public static long explorerSeed() {
        return explorerGeneration == worldGeneration ? explorerSeed : instanceSeed;
    }

    public static void setExplorerSeed(long newSeed) throws Exception {
        submitToInferenceThread(() -> {
            explorer().pipeline.setSeed(newSeed);
            explorerSeed = newSeed;
            return null;
        });
    }

    public static long randomExplorerSeed() throws Exception {
        long newSeed = new Random().nextLong();
        setExplorerSeed(newSeed);
        return newSeed;
    }

    // Frees the explorer's pipeline; the next explorer request rebuilds it on the world's seed
    public static void releaseExplorer() {
        INFERENCE_EXECUTOR.execute(new PriorityTask<Void>(() -> {
            if (EXPLORER != null) {
                EXPLORER.pipeline.close();
                EXPLORER = null;
            }
            explorerGeneration = -1;
            return null;
        }, PRIORITY_BLOCKING));
    }

    public static float[][] explorerData(int i1, int j1, int i2, int j2, boolean withClimate) throws Exception {
        return submitToInferenceThread(() -> explorer().pipeline.get(i1, j1, i2, j2, withClimate));
    }

    public static FloatTensor explorerCoarse(int ci0, int cj0, int ci1, int cj1) throws Exception {
        return submitToInferenceThread(() -> explorer().pipeline.getCoarseSlice(ci0, cj0, ci1, cj1));
    }

    // The explorer view most recently asked for; water for any other view is abandoned
    public static void markExplorerView(String view) {
        explorerView = view;
    }

    // Returns {bed elevation, water surface} for an explorer view, or null if the view changed first
    public static float[][] explorerWater(int i1, int j1, int i2, int j2, String view) throws Exception {
        BooleanSupplier stale = () -> !view.equals(explorerView);
        PriorityTask<float[][]> task = new PriorityTask<>(() -> {
            if (stale.getAsBoolean()) return null;
            try {
                return InfiniteTensor.cancellable(stale, () -> {
                    LocalTerrainProvider provider = explorer();
                    int H = i2 - i1, W = j2 - j1;
                    int scale = WorldScaleManager.getCurrentScale();
                    float blockM = NATIVE_RESOLUTION / scale;
                    RiverHydrology.prepare(provider.pipeline, i1, j1, i2, j2, blockM);
                    float[][] out = provider.pipeline.get(i1, j1, i2, j2, true);
                    return provider.carveWater(out[0], out[0], out[1], i1, j1, H, W, blockM, scale, scale);
                });
            } catch (CancellationException e) {
                return null;
            }
        }, PRIORITY_BACKGROUND);
        INFERENCE_EXECUTOR.execute(task);
        return task.get();
    }

    public static FloatTensor getPipelineCoarse(int ci0, int cj0, int ci1, int cj1) throws Exception {
        return submitToInferenceThread(() -> getInstance().pipeline.getCoarseSlice(ci0, cj0, ci1, cj1));
    }

    private static <T> T submitToInferenceThread(Callable<T> task) throws Exception {
        PriorityTask<T> prioritised = new PriorityTask<>(task, PRIORITY_BLOCKING);
        INFERENCE_EXECUTOR.execute(prioritised);
        return prioritised.get();
    }

    public HeightmapData fetchHeightmap(int i1, int j1, int i2, int j2) {
        CacheKey key = new CacheKey(i1, j1, i2, j2);
        CacheEntry cached = CACHE.get(key);
        if (cached != null) {
            cached.lastAccessed.set(CACHE_CLOCK.incrementAndGet());
            return cached.data;
        }

        return this.genHeightmap(key, i1, j1, i2, j2);
    }

    // A stalled main thread outranks a chunk worker
    private static int waitingPriority() {
        String thread = Thread.currentThread().getName();
        return thread.equals("Server thread") || thread.equals("Render thread")
                ? PRIORITY_URGENT
                : PRIORITY_BLOCKING;
    }

    private HeightmapData genHeightmap(CacheKey key, int i1, int j1, int i2, int j2) {
        PriorityTask<HeightmapData> toRun = enqueue(key, i1, j1, i2, j2, waitingPriority());
        try {
            return toRun.get();
        } catch (Exception e) {
            PENDING.remove(key);
            throw new RuntimeException("Terrain tile failed: " + key, e);
        }
    }

    // Queues a tile for generation if it is not cached or already queued, and returns the task
    @SuppressWarnings("unchecked")
    private PriorityTask<HeightmapData> enqueue(CacheKey key, int i1, int j1, int i2, int j2,
                                                int priority) {
        int scale = WorldScaleManager.getCurrentScale();
        PriorityTask<HeightmapData> task = new PriorityTask<>(() -> {
            long computedWindowCountBefore = pipeline.getTotalComputedWindowCount();
            HeightmapData data = scale <= 1
                    ? handle1x(i1, j1, i2, j2)
                    : handleUpsampled(i1, j1, i2, j2, scale);
            data.karst = RiverHydrology.karstAt(pipeline, i1 / (float) scale, j1 / (float) scale,
                    NATIVE_RESOLUTION / scale);
            long newlyComputedWindowCount =
                    pipeline.getTotalComputedWindowCount() - computedWindowCountBefore;
            if (newlyComputedWindowCount > 0) {
                LOG.info(
                        "Terrain Diffusion ({}) finished generating region {}x{} ({} newly computed windows)",
                        OnnxModel.getResolvedInferenceProvider(), j2 - j1, i2 - i1,
                        newlyComputedWindowCount);
            }
            CACHE.put(key, new CacheEntry(data, new AtomicLong(CACHE_CLOCK.incrementAndGet())));
            evictLruTo(maxCacheSize());
            PENDING.remove(key);
            return data;
        }, priority);

        Future<HeightmapData> existing = PENDING.putIfAbsent(key, task);
        if (existing != null) {
            PriorityTask<HeightmapData> queued = (PriorityTask<HeightmapData>) existing;
            raisePriority(queued, priority);
            return queued;
        }

        INFERENCE_EXECUTOR.execute(task);
        return task;
    }

    private static void evictLruTo(int maxSize) {
        int headroomHalf = MAX_CACHE_SIZE_HEADROOM / 2;
        if (CACHE.size() > maxSize + headroomHalf) {
            CACHE.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().lastAccessed.get()))
                .limit(MAX_CACHE_SIZE_HEADROOM)
                .map(Map.Entry::getKey)
                .forEach(CACHE::remove);
        }
    }

    private HeightmapData handle1x(int i1, int j1, int i2, int j2) {
        int H = i2 - i1, W = j2 - j1;

        float[] elevPadded = pipeline.get(i1 - 1, j1 - 1, i2 + 1, j2 + 1, false)[0];
        float[][] out = pipeline.get(i1, j1, i2, j2, true);
        float[] elevFlat = out[0];
        float[] climate  = out[1];

        float[][] waterOut = carveWater(elevFlat, elevFlat, climate, i1, j1, H, W,
                NATIVE_RESOLUTION, 1, 1);

        byte[] snowFlat = new byte[H * W];
        short[] biomeFlat = BiomeClassifier.classify(elevFlat, climate, i1, j1, elevPadded, H, W,
                NATIVE_RESOLUTION, snowFlat, riverMask(waterOut[1]));
        return buildHeightmapData(waterOut[0], climate, biomeFlat, snowFlat, waterOut[1], H, W, NATIVE_RESOLUTION);
    }

    private HeightmapData handleUpsampled(int i1, int j1, int i2, int j2, int scale) {
        int H = i2 - i1, W = j2 - j1;
        float pixelSizeM = NATIVE_RESOLUTION / scale;

        int i1n = Math.floorDiv(i1, scale);
        int j1n = Math.floorDiv(j1, scale);
        int i2n = -Math.floorDiv(-i2, scale);
        int j2n = -Math.floorDiv(-j2, scale);

        int i1p = i1n - 2, j1p = j1n - 2;
        int i2p = i2n + 2, j2p = j2n + 2;
        int nH = i2p - i1p, nW = j2p - j1p;

        float[][] out = pipeline.get(i1p, j1p, i2p, j2p, true);
        float[] elevNativeFlat    = out[0];
        float[] climateNativeFlat = out[1];

        float[][] elevNative2D = to2D(elevNativeFlat, nH, nW);
        float[][] elevUp = LaplacianUtils.bilinearResize(elevNative2D, nH * scale, nW * scale);

        int padUp   = 2 * scale;
        int offsetI = i1 - i1n * scale;
        int offsetJ = j1 - j1n * scale;
        int cropI1  = padUp + offsetI;
        int cropJ1  = padUp + offsetJ;

        float[] elevSmooth = cropFlat(elevUp, cropI1, cropJ1, H, W, nH * scale, nW * scale);
        float[] elevPadded = cropFlat(elevUp, cropI1 - 1, cropJ1 - 1, H+2, W+2, nH * scale, nW * scale);

        float[] climate = upsampleClimate(climateNativeFlat, nH, nW, cropI1, cropJ1, H, W, scale, nH * scale, nW * scale);

        float[] elevOut = addElevationNoise(elevSmooth, elevPadded, i1, j1, H, W, pixelSizeM);

        float[][] waterOut = carveWater(elevOut, elevSmooth, climate, i1, j1, H, W,
                pixelSizeM, scale, 1);

        byte[] snowFlat = new byte[H * W];
        short[] biomeFlat = BiomeClassifier.classify(elevSmooth, climate, i1, j1, elevPadded, H, W,
                pixelSizeM, snowFlat, riverMask(waterOut[1]));
        return buildHeightmapData(waterOut[0], climate, biomeFlat, snowFlat, waterOut[1], H, W, pixelSizeM);
    }

    public static float[] addElevationNoise(float[] elevSmooth, float[] elevPadded,
                                       int i1, int j1, int H, int W, float pixelSizeM) {
        float[] slopeGradient = sobelGradient(elevPadded, H + 2, W + 2, H, W);
        float[] elevOut = elevSmooth.clone();
        float normFactor = 40f * pixelSizeM / NATIVE_RESOLUTION;
        float ampC = AMP_COARSE * pixelSizeM / NATIVE_RESOLUTION;
        float ampF = AMP_FINE * pixelSizeM / NATIVE_RESOLUTION;

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float e = elevSmooth[idx];

                float grad = slopeGradient[idx];
                float sf = Math.min(1f, grad / normFactor);
                sf = sf * sf * (float) Math.sqrt(sf);

                float bandRisk = Math.min(1f, grad / (pixelSizeM * DITHER_BAND_GRAD));
                float floorM = DETAIL_FLOOR_BLOCKS * pixelSizeM * bandRisk;
                float offSea = Math.min(1f, Math.abs(e) / (2f * floorM));

                float nx = j1 + c, ny = i1 + r;

                float fineAmp = ampF * sf;
                float ditherAmp = Math.max(0f, floorM - fineAmp) * offSea;

                if (LEGACY_DITHER) {
                    fineAmp = Math.max(fineAmp, floorM * offSea);
                    ditherAmp = 0f;
                } else if (NO_DITHER) {
                    ditherAmp = 0f;
                }
                elevOut[idx] = e
                        + ELEV_DITHER.GetNoise(nx, ny) * ditherAmp
                        + ELEV_NOISE_COARSE.GetNoise(nx, ny) * ampC * sf
                        + ELEV_NOISE_FINE.GetNoise(nx, ny)   * fineAmp;
            }
        }
        return elevOut;
    }

    private static float[] sobelGradient(float[] padded, int pH, int pW, int H, int W) {
        final float[] SOBEL_X = {-1,0,1, -2,0,2, -1,0,1};
        final float[] SOBEL_Y = {-1,-2,-1, 0,0,0, 1,2,1};
        float[] result = new float[H * W];
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float dx = 0, dy = 0;
                for (int k = 0; k < 9; k++) {
                    float v = padded[(r + k/3) * pW + (c + k%3)];
                    dx += v * SOBEL_X[k];
                    dy += v * SOBEL_Y[k];
                }
                dx /= 8f; dy /= 8f;
                result[r * W + c] = (float) Math.sqrt(dx * dx + dy * dy);
            }
        }
        return result;
    }

    private static float[] upsampleClimate(float[] climNative, int nH, int nW,
                                            int cropI1, int cropJ1, int H, int W,
                                            int scale, int upH, int upW) {
        if (climNative == null) return null;
        int channels = climNative.length / (nH * nW);
        float[] result = new float[channels * H * W];
        for (int ch = 0; ch < channels; ch++) {
            float[][] chNative = new float[nH][nW];
            for (int r = 0; r < nH; r++)
                System.arraycopy(climNative, ch * nH * nW + r * nW, chNative[r], 0, nW);
            float[][] chUp = LaplacianUtils.bilinearResize(chNative, upH, upW);
            for (int r = 0; r < H; r++)
                for (int c = 0; c < W; c++)
                    result[ch * H * W + r * W + c] = chUp[cropI1 + r][cropJ1 + c];
        }
        return result;
    }

    private static float[] cropFlat(float[][] src, int r0, int c0, int H, int W, int srcH, int srcW) {
        float[] out = new float[H * W];
        for (int r = 0; r < H; r++) {
            int sr = Math.max(0, Math.min(srcH - 1, r0 + r));
            for (int c = 0; c < W; c++)
                out[r * W + c] = src[sr][Math.max(0, Math.min(srcW - 1, c0 + c))];
        }
        return out;
    }

    private static float[][] to2D(float[] flat, int H, int W) {
        float[][] a = new float[H][W];
        for (int r = 0; r < H; r++) System.arraycopy(flat, r * W, a[r], 0, W);
        return a;
    }

    private float[][] carveWater(float[] elevOut, float[] elevSmooth, float[] climate, int i1, int j1, int H, int W,
                                 float metersPerBlock, int scale, int coordScale) {
        float[] carved = new float[H * W];
        float[] water = new float[H * W];

        boolean[] source = new boolean[H * W];
        boolean[] outlet = new boolean[H * W];
        boolean[] fallMask = new boolean[H * W];
        WaterNetwork.Sample sample = new WaterNetwork.Sample();

        int coordScaleToNative = coordScale == 1 ? scale : 1;

        for (int r = 0; r < H; r++) {
            InfiniteTensor.throwIfCancelled();
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;

                float nativeI = (i1 + r) / (float) coordScaleToNative;
                float nativeJ = (j1 + c) / (float) coordScaleToNative;
                float[] ch = RiverHydrology.sampleChannel(pipeline, nativeI, nativeJ, metersPerBlock);

                float distBlocks = ch == null ? -1f : ch[0] * scale;
                float magnitude = ch == null ? 0f : ch[1];
                float channelWater = ch == null ? 0f : ch[2];
                float deltaWeight = ch == null ? 0f : ch[3];

                float channelWaterSmooth = ch == null ? 0f : ch[4];

                float channelWidth = ch == null ? 0f : ch[5];
                float channelDepth = ch == null ? 0f : ch[6];
                float fallBlocks = ch == null ? 0f : ch[7];
                float[] lk = RiverHydrology.sampleLake(pipeline, nativeI, nativeJ, metersPerBlock);
                float lakeSurface = lk == null ? WaterNetwork.NO_WATER : lk[0];
                float lakeDeep = lk == null ? 0f : lk[1];
                float precip = climate == null ? 0f : Math.max(0f, climate[2 * H * W + idx]);

                WaterNetwork.sample((j1 + c) * coordScale, (i1 + r) * coordScale,
                        elevOut[idx], elevSmooth[idx], precip, distBlocks, magnitude,
                        channelWater, channelWaterSmooth, channelWidth, channelDepth, fallBlocks,
                        lakeSurface, lakeDeep, deltaWeight, metersPerBlock, scale, sample);

                carved[idx] = sample.bedElevM;
                water[idx] = sample.waterSurfaceM;

                fallMask[idx] = fallBlocks >= 1.5f;
                source[idx] = (distBlocks >= 0f && distBlocks < 3f)
                        || lakeSurface > WaterNetwork.NO_WATER
                        || elevOut[idx] < 0f;
                outlet[idx] = lakeSurface > WaterNetwork.NO_WATER || elevOut[idx] < 0f;
            }
        }
        healPlugs(water, carved, H, W, metersPerBlock);
        pruneStrandedWater(water, source, H, W);

        for (int k = 0; k < 4; k++) {
            relaxWaterSteps(water, carved, fallMask, H, W, metersPerBlock);
            breachDams(water, H, W, metersPerBlock);
        }
        dropUndrainable(water, outlet, H, W, metersPerBlock);
        restoreDryCarve(carved, elevOut, water, H, W);
        return new float[][]{carved, water};
    }

    private static final float PLUG_HEAL_BLOCKS =
            Float.parseFloat(System.getProperty("terradiff.plugHeal", "2"));

    private static final int PLUG_HEAL_PASSES =
            Integer.parseInt(System.getProperty("terradiff.plugHealPasses", "2"));

    private static final int PLUG_REACH =
            Integer.parseInt(System.getProperty("terradiff.plugReach", "2"));

    private static boolean wet(float[] water, int i) {
        return water[i] > WaterNetwork.NO_WATER;
    }

    private static float firstWater(float[] water, int r, int c, int dr, int dc,
                                    int H, int W, int reach) {
        for (int k = 1; k <= reach; k++) {
            int a = r + dr * k, b = c + dc * k;
            if (a < 0 || b < 0 || a >= H || b >= W) {
                return WaterNetwork.NO_WATER;
            }
            int j = a * W + b;
            if (wet(water, j)) {
                return water[j];
            }
        }
        return WaterNetwork.NO_WATER;
    }

    private static void healPlugs(float[] water, float[] carved, int H, int W, float blockM) {
        float cap = PLUG_HEAL_BLOCKS * blockM;
        if (cap <= 0f) {
            return;
        }
        int healed = 0;
        for (int pass = 0; pass < PLUG_HEAL_PASSES; pass++) {
            float[] next = null;
            for (int r = 1; r < H - 1; r++) {
                for (int c = 1; c < W - 1; c++) {
                    int i = r * W + c;
                    if (wet(water, i)) {
                        continue;
                    }
                    float best = WaterNetwork.NO_WATER;
                    for (int ax = 0; ax < 4; ax++) {
                        int dr = ax == 0 ? -1 : ax == 1 ? 0 : ax == 2 ? -1 : -1;
                        int dc = ax == 0 ? 0 : ax == 1 ? -1 : ax == 2 ? -1 : 1;
                        float a = firstWater(water, r, c, dr, dc, H, W, PLUG_REACH);
                        float b = firstWater(water, r, c, -dr, -dc, H, W, PLUG_REACH);
                        if (a > WaterNetwork.NO_WATER && b > WaterNetwork.NO_WATER) {
                            float lower = Math.min(a, b);
                            if (lower > best) {
                                best = lower;
                            }
                        }
                    }
                    if (best <= WaterNetwork.NO_WATER || carved[i] - best > cap) {
                        continue;
                    }
                    if (next == null) {
                        next = water.clone();
                    }
                    carved[i] = Math.min(carved[i], best - 0.5f * blockM);
                    next[i] = best;
                    healed++;
                }
            }
            if (next == null) {
                break;
            }
            System.arraycopy(next, 0, water, 0, water.length);
        }
        if (healed > 0 && Boolean.getBoolean("terradiff.diag")) {
            System.err.printf("diag healed %d plug columns%n", healed);
        }
    }

    private static final int BREACH_PASSES =
            Integer.parseInt(System.getProperty("terradiff.breachPasses", "250"));

    private static void breachDams(float[] water, int H, int W, float blockM) {
        int n = H * W;
        int[] lab = new int[n];
        int[] st = new int[n];
        int[] open = new int[n];
        boolean[] drains = new boolean[n];
        int breached = 0;
        for (int pass = 0; pass < BREACH_PASSES; pass++) {
            java.util.Arrays.fill(lab, 0);
            java.util.Arrays.fill(drains, false);
            int top = 0;
            int bodies = 0;
            for (int s = 0; s < n; s++) {
                if (lab[s] != 0 || water[s] <= WaterNetwork.NO_WATER) {
                    continue;
                }
                bodies++;
                int sp = 0;
                st[sp++] = s;
                lab[s] = bodies;
                int loAt = s;
                while (sp > 0) {
                    int i = st[--sp];
                    if (water[i] < water[loAt]) {
                        loAt = i;
                    }
                    int r = i / W, c = i % W;
                    if (r > 0 && water[i - W] > WaterNetwork.NO_WATER && lab[i - W] == 0) { lab[i - W] = bodies; st[sp++] = i - W; }
                    if (r < H - 1 && water[i + W] > WaterNetwork.NO_WATER && lab[i + W] == 0) { lab[i + W] = bodies; st[sp++] = i + W; }
                    if (c > 0 && water[i - 1] > WaterNetwork.NO_WATER && lab[i - 1] == 0) { lab[i - 1] = bodies; st[sp++] = i - 1; }
                    if (c < W - 1 && water[i + 1] > WaterNetwork.NO_WATER && lab[i + 1] == 0) { lab[i + 1] = bodies; st[sp++] = i + 1; }
                }
                drains[loAt] = true;
                open[top++] = loAt;
            }
            while (top > 0) {
                int i = open[--top];
                int r = i / W, c = i % W;
                float from = water[i] - 0.001f;
                if (r > 0) top = drainInto(water, drains, open, top, i - W, from);
                if (r < H - 1) top = drainInto(water, drains, open, top, i + W, from);
                if (c > 0) top = drainInto(water, drains, open, top, i - 1, from);
                if (c < W - 1) top = drainInto(water, drains, open, top, i + 1, from);
            }

            boolean cut = false;
            for (int i = 0; i < n; i++) {
                if (water[i] <= WaterNetwork.NO_WATER || drains[i]) {
                    continue;
                }
                int r = i / W, c = i % W;
                float above = water[i] + 0.001f;
                float spill = Float.MAX_VALUE;
                if (r > 0) spill = spillOver(water, i - W, above, spill);
                if (r < H - 1) spill = spillOver(water, i + W, above, spill);
                if (c > 0) spill = spillOver(water, i - 1, above, spill);
                if (c < W - 1) spill = spillOver(water, i + 1, above, spill);
                if (spill != Float.MAX_VALUE) {
                    water[i] = spill;
                    breached++;
                    cut = true;
                }
            }
            if (!cut) {
                break;
            }
        }
        if (Boolean.getBoolean("terradiff.diag")) {
            System.err.printf("diag breach: cut %d damming columns%n", breached);
        }
    }

    private static int drainInto(float[] water, boolean[] drains, int[] open, int top, int j, float from) {
        if (water[j] <= WaterNetwork.NO_WATER || drains[j] || !(water[j] >= from)) {
            return top;
        }
        drains[j] = true;
        open[top++] = j;
        return top;
    }

    private static float spillOver(float[] water, int j, float above, float spill) {
        float v = water[j];
        return v > WaterNetwork.NO_WATER && v > above && v < spill ? v : spill;
    }

    private static void relaxWaterSteps(float[] water, float[] bed, boolean[] fall,
                                        int H, int W, float blockM) {
        int n = H * W;

        float[] rank = water.clone();
        int wet = 0;
        for (int i = 0; i < n; i++) {
            if (water[i] > WaterNetwork.NO_WATER) {
                wet++;
            }
        }

        long[] keyed = new long[wet];
        for (int i = 0, k = 0; i < n; i++) {
            if (water[i] > WaterNetwork.NO_WATER) {
                int bits = Float.floatToIntBits(rank[i]);
                bits ^= (bits >> 31) & 0x7FFFFFFF;
                keyed[k++] = (((long) bits) << 32) | (i & 0xFFFFFFFFL);
            }
        }
        Arrays.sort(keyed);

        int lowered = 0, dried = 0, floored = 0;
        for (int pass = 0; pass < 12; pass++) {
            boolean changed = false;
            for (int oi = 0; oi < wet; oi++) {
                int i = (int) (keyed[oi] & 0xFFFFFFFFL);
                if (water[i] <= WaterNetwork.NO_WATER || fall[i]) {
                    continue;
                }
                int r = i / W, c = i % W;
                float cap = Float.MAX_VALUE, floor = -Float.MAX_VALUE;
                for (int k = 0; k < 4; k++) {
                    int j = switch (k) {
                        case 0 -> r > 0 ? i - W : -1;
                        case 1 -> r < H - 1 ? i + W : -1;
                        case 2 -> c > 0 ? i - 1 : -1;
                        default -> c < W - 1 ? i + 1 : -1;
                    };
                    if (j < 0 || water[j] <= WaterNetwork.NO_WATER || fall[j]) {
                        continue;
                    }

                    if (rank[j] > rank[i] + 1.0e-4f) {
                        continue;
                    }
                    if (rank[i] - rank[j] > RELAX_MAX_DROP * blockM) {
                        continue;
                    }
                    cap = Math.min(cap, water[j] + blockM);
                    if (rank[j] < rank[i]) {
                        floor = Math.max(floor, water[j]);
                    }
                }
                if (cap == Float.MAX_VALUE) {
                    continue;
                }
                float target = Math.max(floor, Math.min(water[i], cap));
                if (target < water[i]) {
                    if (floor > cap) {
                        floored++;
                    }
                    water[i] = target;
                    lowered++;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        for (int i = 0; i < n; i++) {
            if (water[i] > WaterNetwork.NO_WATER && water[i] <= bed[i]) {
                water[i] = WaterNetwork.NO_WATER;
                dried++;
            }
        }

        int banked = 0;
        float bankCap = BANK_FRINGE_BLOCKS * blockM;
        for (int pass = 0; pass < 8; pass++) {
            boolean moved = false;
            for (int i = 0; i < n; i++) {
                if (water[i] <= WaterNetwork.NO_WATER) {
                    continue;
                }
                int r = i / W, c = i % W;
                for (int k = 0; k < 4; k++) {
                    int j = k == 0 ? (r > 0 ? i - W : -1)
                          : k == 1 ? (r < H - 1 ? i + W : -1)
                          : k == 2 ? (c > 0 ? i - 1 : -1)
                                   : (c < W - 1 ? i + 1 : -1);
                    if (j < 0 || water[j] <= WaterNetwork.NO_WATER) {
                        continue;
                    }
                    if (water[i] - water[j] < 2f * blockM - 0.001f) {
                        continue;
                    }
                    if (water[i] - bed[i] > bankCap) {
                        continue;
                    }
                    water[i] = WaterNetwork.NO_WATER;
                    banked++;
                    moved = true;
                    break;
                }
            }
            if (!moved) {
                break;
            }
        }
        if (Boolean.getBoolean("terradiff.diag")) {
            System.err.printf("diag bank: dried %d fringe columns between mismatched waters%n", banked);
        }
        if (Boolean.getBoolean("terradiff.diag")) {

            int rises = 0;
            float worst = 0f;
            for (int i = 0; i < n; i++) {
                if (water[i] <= WaterNetwork.NO_WATER) {
                    continue;
                }
                int r = i / W, c = i % W;
                for (int k = 0; k < 4; k++) {
                    int j = switch (k) {
                        case 0 -> r > 0 ? i - W : -1;
                        case 1 -> r < H - 1 ? i + W : -1;
                        case 2 -> c > 0 ? i - 1 : -1;
                        default -> c < W - 1 ? i + 1 : -1;
                    };
                    if (j < 0 || water[j] <= WaterNetwork.NO_WATER || rank[j] >= rank[i]) {
                        continue;
                    }
                    if (water[j] - water[i] > 1.0e-3f) {
                        rises++;
                        worst = Math.max(worst, water[j] - water[i]);
                    }
                }
            }
            System.err.printf("diag relax: lowered=%d floored=%d dried=%d rasterRises=%d worst=%.2fm%n",
                    lowered, floored, dried, rises, worst);
        }
    }

    private static final boolean DRAIN_GATE =
            !"false".equals(System.getProperty("terradiff.drainGate"));

    // Blocks of valley kept around water that survived
    private static final int DRY_CARVE_REACH =
            Integer.parseInt(System.getProperty("terradiff.dryCarveReach", "16"));

    private static final int DRY_CARVE_RAMP = 4;

    // Puts back ground that was excavated toward water no longer there
    private static void restoreDryCarve(float[] carved, float[] elev, float[] water, int H, int W) {
        if (!DRAIN_GATE || DRY_CARVE_REACH <= 0) return;
        int n = H * W;
        int[] dist = new int[n];
        int[] queue = new int[n];
        int tail = 0;
        int limit = DRY_CARVE_REACH + DRY_CARVE_RAMP;

        for (int i = 0; i < n; i++) {
            if (water[i] > WaterNetwork.NO_WATER) {
                dist[i] = 0;
                queue[tail++] = i;
            } else {
                dist[i] = Integer.MAX_VALUE;
            }
        }
        for (int head = 0; head < tail; head++) {
            int i = queue[head];
            if (dist[i] >= limit) continue;
            int r = i / W, c = i % W;
            for (int k = 0; k < 4; k++) {
                int j = switch (k) {
                    case 0 -> r > 0 ? i - W : -1;
                    case 1 -> r < H - 1 ? i + W : -1;
                    case 2 -> c > 0 ? i - 1 : -1;
                    default -> c < W - 1 ? i + 1 : -1;
                };
                if (j < 0 || dist[j] != Integer.MAX_VALUE) continue;
                dist[j] = dist[i] + 1;
                queue[tail++] = j;
            }
        }

        for (int i = 0; i < n; i++) {
            if (carved[i] >= elev[i]) continue;
            int d = dist[i];
            if (d <= DRY_CARVE_REACH) continue;
            float f = d >= limit ? 1f : (d - DRY_CARVE_REACH) / (float) DRY_CARVE_RAMP;
            carved[i] += (elev[i] - carved[i]) * f;
        }
    }

    // Removes water with no downhill path out
    private static void dropUndrainable(float[] water, boolean[] outlet, int H, int W, float blockM) {
        if (!DRAIN_GATE) return;
        int n = H * W;
        boolean[] drains = new boolean[n];
        int[] queue = new int[n];
        int tail = 0;

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int i = r * W + c;
                if (water[i] <= WaterNetwork.NO_WATER) {
                    continue;
                }
                boolean rim = r == 0 || c == 0 || r == H - 1 || c == W - 1;
                if (rim || outlet[i]) {
                    drains[i] = true;
                    queue[tail++] = i;
                }
            }
        }

        float tol = 0.05f * blockM;
        for (int head = 0; head < tail; head++) {
            int i = queue[head];
            int r = i / W, c = i % W;
            for (int k = 0; k < 4; k++) {
                int j = switch (k) {
                    case 0 -> r > 0 ? i - W : -1;
                    case 1 -> r < H - 1 ? i + W : -1;
                    case 2 -> c > 0 ? i - 1 : -1;
                    default -> c < W - 1 ? i + 1 : -1;
                };
                if (j < 0 || drains[j] || water[j] <= WaterNetwork.NO_WATER) {
                    continue;
                }
                if (water[j] >= water[i] - tol) {
                    drains[j] = true;
                    queue[tail++] = j;
                }
            }
        }

        for (int i = 0; i < n; i++) {
            if (water[i] > WaterNetwork.NO_WATER && !drains[i]) {
                water[i] = WaterNetwork.NO_WATER;
            }
        }
    }

    private static void pruneStrandedWater(float[] water, boolean[] source, int H, int W) {
        boolean[] keep = new boolean[H * W];
        int[] queue = new int[H * W];
        int head = 0, tail = 0;

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int i = r * W + c;
                if (water[i] <= WaterNetwork.NO_WATER) {
                    continue;
                }
                boolean edge = r == 0 || c == 0 || r == H - 1 || c == W - 1;
                if (source[i] || edge) {
                    keep[i] = true;
                    queue[tail++] = i;
                }
            }
        }
        while (head < tail) {
            int i = queue[head++];
            int r = i / W, c = i % W;
            if (r > 0) tail = visit(water, keep, queue, tail, i - W);
            if (r < H - 1) tail = visit(water, keep, queue, tail, i + W);
            if (c > 0) tail = visit(water, keep, queue, tail, i - 1);
            if (c < W - 1) tail = visit(water, keep, queue, tail, i + 1);
        }
        for (int i = 0; i < H * W; i++) {
            if (water[i] > WaterNetwork.NO_WATER && !keep[i]) {
                water[i] = WaterNetwork.NO_WATER;
            }
        }
    }

    private static int visit(float[] water, boolean[] keep, int[] queue, int tail, int j) {
        if (water[j] > WaterNetwork.NO_WATER && !keep[j]) {
            keep[j] = true;
            queue[tail++] = j;
        }
        return tail;
    }

    private static boolean[] riverMask(float[] waterFlat) {
        boolean[] mask = new boolean[waterFlat.length];
        for (int i = 0; i < waterFlat.length; i++) {
            mask[i] = waterFlat[i] > WaterNetwork.NO_WATER;
        }
        return mask;
    }

    private static HeightmapData buildHeightmapData(float[] elevFlat, float[] climate,
                                                     short[] biomeFlat,
                                                     byte[] snowFlat, float[] waterFlat,
                                                     int H, int W, float metersPerBlock) {
        short[][] heightmap = new short[H][W];
        short[][] biomeIds  = new short[H][W];
        byte[][] snowLayers = new byte[H][W];
        short[][] waterLevel = new short[H][W];
        byte[][] climateT = new byte[H][W];
        byte[][] climateH = new byte[H][W];
        byte[][] climateE = new byte[H][W];
        for (int r = 0; r < H; r++)
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float e = elevFlat[idx];
                heightmap[r][c]  = (short) Math.max(-32768, Math.min(32767, (int) Math.floor(e)));
                biomeIds[r][c]   = biomeFlat[idx];
                snowLayers[r][c] = snowFlat[idx];

                float w = waterFlat == null ? WaterNetwork.NO_WATER : waterFlat[idx];
                waterLevel[r][c] = w <= WaterNetwork.NO_WATER
                        ? HeightmapData.NO_WATER
                        : (short) Math.max(-32767, Math.min(32767, (int) Math.floor(w)));
            }
        fillClimateParams(elevFlat, climate, climateT, climateH, climateE, H, W, metersPerBlock);
        return new HeightmapData(heightmap, biomeIds, snowLayers, waterLevel,
                climateT, climateH, climateE, W, H);
    }

    private static final int RELIEF_WINDOW =
            Integer.parseInt(System.getProperty("terradiff.reliefWindow", "10"));

    private static final float RELIEF_REF =
            Float.parseFloat(System.getProperty("terradiff.reliefRef", "25"));

    // Projects the model's climate output onto vanilla's [-1, 1] climate axes
    private static void fillClimateParams(float[] elevFlat, float[] climate,
                                          byte[][] outT, byte[][] outH, byte[][] outE,
                                          int H, int W, float metersPerBlock) {
        int win = Math.max(1, RELIEF_WINDOW);
        float mpb = Math.max(0.001f, metersPerBlock);
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float tempC  = climate == null ? 12f : climate[idx];
                float precip = climate == null ? 800f : Math.max(1f, climate[2 * H * W + idx]);

                float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
                for (int dr = -win; dr <= win; dr += win) {
                    int rr = Math.max(0, Math.min(H - 1, r + dr));
                    for (int dc = -win; dc <= win; dc += win) {
                        int cc = Math.max(0, Math.min(W - 1, c + dc));
                        float v = elevFlat[rr * W + cc];
                        if (v < lo) lo = v;
                        if (v > hi) hi = v;
                    }
                }

                outT[r][c] = packUnit((tempC - 10.4f) / 17.1f);
                outH[r][c] = packUnit((float) ((Math.log10(precip) - 2.85) / 0.55));
                outE[r][c] = packUnit(1f - 2f * ((hi - lo) / mpb) / RELIEF_REF);
            }
        }
    }

    private static byte packUnit(float v) {
        float c = v < -1f ? -1f : v > 1f ? 1f : v;
        return (byte) Math.round(c * 127f);
    }

    public static HeightmapData peekHeightmap(int i1, int j1, int i2, int j2) {
        CacheEntry cached = CACHE.get(new CacheKey(i1, j1, i2, j2));
        if (cached == null) {
            return null;
        }
        cached.lastAccessed.set(CACHE_CLOCK.incrementAndGet());
        return cached.data;
    }
}
