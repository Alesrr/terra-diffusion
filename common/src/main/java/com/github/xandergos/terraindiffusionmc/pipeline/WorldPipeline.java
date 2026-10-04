package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.infinitetensor.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.nio.file.Files;
import java.nio.file.Path;
import com.github.xandergos.terraindiffusionmc.platform.PlatformPaths;

// Java port of terrain_diffusion/inference/world_pipeline.py WorldPipeline
public final class WorldPipeline implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(WorldPipeline.class);

    static final int LATENT_COMPRESSION = WorldPipelineModelConfig.latentCompression();
    static final float SIGMA_DATA = EDMScheduler.SIGMA_DATA;

    static final int COARSE_TILE_SIZE   = 64;
    static final int COARSE_TILE_STRIDE = 48;
    static final int LATENT_TILE_SIZE   = 64;
    static final int LATENT_TILE_STRIDE = 32;
    static final int DECODER_TILE_SIZE  = 256;
    static final int DECODER_TILE_STRIDE = 192;

    static final float[] MODEL_MEANS = WorldPipelineModelConfig.coarseMeans();
    static final float[] MODEL_STDS = WorldPipelineModelConfig.coarseStds();

    static final float[] COND_MEANS = {14.99f, 11.65f, 15.87f, 619.26f, 833.12f, 69.40f, 0.66f};
    static final float[] COND_STDS  = {21.72f, 21.78f, 10.40f, 452.29f, 738.09f, 34.59f, 0.47f};

    static final float LOWFREQ_MEAN  = -31.4f;
    static final float LOWFREQ_STD   = 38.6f;
    static final float RESIDUAL_MEAN = WorldPipelineModelConfig.residualMean();
    static final float RESIDUAL_STD  = WorldPipelineModelConfig.residualStd();

    static final float[] COND_SNR  = WorldPipelineModelConfig.conditioningSnr();
    static final int COARSE_POOLING = WorldPipelineModelConfig.coarsePooling();
    static final float[] COND_VALS;  // log(COND_SNR / 8)
    static {
        if (COARSE_POOLING != 1) {
            throw new IllegalStateException("coarse_pooling=" + COARSE_POOLING + " is not supported in the Java pipeline yet");
        }
        COND_VALS = new float[COND_SNR.length];
        for (int i = 0; i < COND_SNR.length; i++) COND_VALS[i] = (float) Math.log(COND_SNR[i] / 8.0);
    }

    // mp_concat scales for 6 tensors of sizes [16, 16, 4, 16, 5, 1] → 58 total
    static final int[] COND_DIMS = {16, 16, 4, 16, 5, 1};
    static final float[] MP_CONCAT_SCALES;
    static final float[] HISTOGRAM_RAW;
    static {
        int sumN = 0; for (int n : COND_DIMS) sumN += n;
        int k = COND_DIMS.length;
        float C = (float) Math.sqrt((double) sumN * k);
        MP_CONCAT_SCALES = new float[k];
        for (int i = 0; i < k; i++) MP_CONCAT_SCALES[i] = C / (float) Math.sqrt(COND_DIMS[i]) / k;
        float[] configuredHistogramRaw = WorldPipelineModelConfig.histogramRaw();
        HISTOGRAM_RAW = configuredHistogramRaw != null ? configuredHistogramRaw : new float[]{0f, 0f, 0f, 0f, 0f};
    }

    private final OnnxModel coarseModel;
    private final OnnxModel baseModel;
    private final OnnxModel decoderModel;
    private final boolean ownModels;
    private volatile SyntheticMapFactory syntheticMapFactory;
    private volatile long seed;

    private final MemoryTileStore tileStore;
    private final long cacheLimitBytes =
            TerrainDiffusionConfig.tileCacheMb() * 1024L * 1024L;

    final RiverHydrology.RegionCache hydrology = new RiverHydrology.RegionCache();
    private final boolean readOnlyDisk;

    final InfiniteTensor coarse;
    final InfiniteTensor latents;
    final InfiniteTensor residual;

    // Uses models shared from PipelineModels
    public WorldPipeline(long seed, PipelineModels models) {
        this(seed, models, false);
    }

    // Uses shared models; readOnlyDisk opens the disk cache for reading only
    public WorldPipeline(long seed, PipelineModels models, boolean readOnlyDisk) {
        this.seed = seed & 0xFFFFFFFFFFFFFFFFL;
        this.readOnlyDisk = readOnlyDisk;
        this.coarseModel = models.getCoarseModel();
        this.baseModel = models.getBaseModel();
        this.decoderModel = models.getDecoderModel();
        this.ownModels = false;
        this.syntheticMapFactory = new SyntheticMapFactory(this.seed);
        this.tileStore = new MemoryTileStore();
        this.coarse = buildCoarseStage();
        this.latents = buildLatentStage();
        this.residual = buildDecoderStage();
        attachDiskCache();
    }

    // Loads and owns its own models
    public WorldPipeline(long seed) {
        this.seed = seed & 0xFFFFFFFFFFFFFFFFL;
        this.readOnlyDisk = false;
        ModelAssetManager.ensureAssetsReady();
        this.coarseModel = new OnnxModel(ModelAssetManager.resolveAssetPath("coarse_model.onnx"), "coarse");
        this.baseModel = new OnnxModel(ModelAssetManager.resolveAssetPath("base_model.onnx"), "base");
        this.decoderModel = new OnnxModel(ModelAssetManager.resolveAssetPath("decoder_model.onnx"), "decoder");
        this.ownModels = true;
        this.syntheticMapFactory = new SyntheticMapFactory(this.seed);
        this.tileStore = new MemoryTileStore();
        this.coarse = buildCoarseStage();
        this.latents = buildLatentStage();
        this.residual = buildDecoderStage();
        attachDiskCache();
    }

    // Lightweight seed change (Python change_seed): update seed and synthetic map, clear tile caches
    public void setSeed(long newSeed) {
        long s = newSeed & 0xFFFFFFFFFFFFFFFFL;
        if (s == this.seed) return;
        this.seed = s;
        this.syntheticMapFactory = new SyntheticMapFactory(s);
        tileStore.clearAllCaches();
        hydrology.clear();
        attachDiskCache();
    }

    private InfiniteTensor buildCoarseStage() {
        int S = COARSE_TILE_SIZE, ST = COARSE_TILE_STRIDE;
        float[] ww = linearWeightWindow(S);
        TensorWindow outWin = new TensorWindow(new int[]{7, S, S}, new int[]{7, ST, ST});
        return tileStore.getOrCreate("base_coarse_map", new Integer[]{7, null, null},
                (wi, args) -> coarseTile(wi, ww), outWin,
                new InfiniteTensor[]{}, new TensorWindow[]{}, cacheLimitBytes);
    }

    private FloatTensor coarseTile(int[] wi, float[] ww) {
        int S = COARSE_TILE_SIZE, ST = COARSE_TILE_STRIDE;
        int i = wi[1], j = wi[2];
        int i1 = i * ST, j1 = j * ST;

        // Synthetic map conditioning: channels [elev_sqrt, temp, tempStd, precip, precipStd]
        float[][][] syn = syntheticMapFactory.sample(j1, i1, j1 + S, i1 + S);

        // Modify temp channel (index 1): where <= 20, scale toward 20
        for (int r = 0; r < S; r++)
            for (int c = 0; c < S; c++) {
                float v = syn[1][r][c];
                if (v <= 20f) syn[1][r][c] = (v - 20f) * 1.25f + 20f;
            }

        // Normalize with MODEL_MEANS/STDS indices [0,2,3,4,5]
        int[] meanIdx = {0, 2, 3, 4, 5};
        float[] condImg = new float[5 * S * S];
        for (int ch = 0; ch < 5; ch++) {
            float mean = MODEL_MEANS[meanIdx[ch]], std = MODEL_STDS[meanIdx[ch]];
            for (int px = 0; px < S * S; px++)
                condImg[ch * S * S + px] = (syn[ch][px / S][px % S] - mean) / std;
        }

        // Conditioning noise: Gaussian noise (5, S, S)
        float[] condNoise = flatten3D(GaussianNoisePatch.generate(seed, i1, j1, S, S, 5, S, S));

        // cond_img_mixed = cos(t_cond) * normalized + sin(t_cond) * noise
        float[] condMixed = new float[5 * S * S];
        for (int ch = 0; ch < 5; ch++) {
            float cosT = (float) Math.cos(Math.atan(COND_SNR[ch]));
            float sinT = (float) Math.sin(Math.atan(COND_SNR[ch]));
            for (int px = 0; px < S * S; px++) {
                condMixed[ch * S * S + px] = cosT * condImg[ch * S * S + px]
                        + sinT * condNoise[ch * S * S + px];
            }
        }

        // Initial sample: (6, S, S) noise * sigma_max
        EDMScheduler sched = new EDMScheduler(20);
        float[] sample = flatten3D(GaussianNoisePatch.generate(seed + 1, i1, j1, S, S, 6, S, S));
        for (int k = 0; k < sample.length; k++) sample[k] *= sched.sigmas[0];

        // 20-step DPM-Solver++
        float[][] condInputs = new float[5][1];
        long[][] condShapes  = new long[5][1];
        for (int ci = 0; ci < 5; ci++) { condInputs[ci] = new float[]{COND_VALS[ci]}; condShapes[ci] = new long[]{1}; }

        LOG.debug("Coarse model called for chunk ({}, {}) tile pixels [{}, {}]-[{}, {}] (20 steps)", i, j, i1, j1, i1 + S, j1 + S);
        for (int step = 0; step < 20; step++) {
            float sigma  = sched.sigmas[step];
            float cnoise = EDMScheduler.trigflowPreconditionNoise(sigma);
            float[] scaledIn = EDMScheduler.preconditionInputs(sample, sigma);

            float[] xIn = new float[11 * S * S];
            System.arraycopy(scaledIn, 0, xIn, 0, 6 * S * S);
            System.arraycopy(condMixed, 0, xIn, 6 * S * S, 5 * S * S);

            float[] modelOut = coarseModel.runModel(
                    xIn, new long[]{1, 11, S, S}, new float[]{cnoise}, condInputs, condShapes);
            sample = sched.step(modelOut, sample);
        }

        // Denormalize: sample / sigma_data → raw, then * STDS + MEANS
        float[] out = new float[6 * S * S];
        for (int ch = 0; ch < 6; ch++)
            for (int px = 0; px < S * S; px++)
                out[ch * S * S + px] = (sample[ch * S * S + px] / SIGMA_DATA) * MODEL_STDS[ch] + MODEL_MEANS[ch];

        // ch1 = ch0 - ch1 (convert to p5)
        for (int px = 0; px < S * S; px++)
            out[S * S + px] = out[px] - out[S * S + px];

        // Output: (7, S, S) = [6 channels * weight | weight]
        FloatTensor result = new FloatTensor(new int[]{7, S, S});
        for (int ch = 0; ch < 6; ch++)
            for (int px = 0; px < S * S; px++)
                result.data[ch * S * S + px] = out[ch * S * S + px] * ww[px];
        System.arraycopy(ww, 0, result.data, 6 * S * S, S * S);
        return result;
    }

    private InfiniteTensor buildLatentStage() {
        int S = LATENT_TILE_SIZE, ST = LATENT_TILE_STRIDE;
        TensorWindow outWin = new TensorWindow(new int[]{6, S, S}, new int[]{6, ST, ST});
        TensorWindow coarseWin = new TensorWindow(new int[]{7, 4, 4}, new int[]{7, 1, 1}, new int[]{0, -1, -1});
        float[] ww = linearWeightWindow(S);
        float tInit = (float) Math.atan(EDMScheduler.SIGMA_MAX / SIGMA_DATA);

        InfiniteTensor initLatent = tileStore.getOrCreateBatched(
                "init_latent_map", new Integer[]{6, null, null},
                (wis, args) -> latentBatch(wis, null, args.get(0), tInit, 5819, ww),
                outWin, new InfiniteTensor[]{coarse}, new TensorWindow[]{coarseWin},
                cacheLimitBytes, 4);

        float interT = (float) Math.atan(0.35f / SIGMA_DATA);
        return tileStore.getOrCreateBatched(
                "step_latent_map_0", new Integer[]{6, null, null},
                (wis, args) -> latentBatch(wis, args.get(0), args.get(1), interT, 5820, ww),
                outWin, new InfiniteTensor[]{initLatent, coarse}, new TensorWindow[]{outWin, coarseWin},
                cacheLimitBytes, 4);
    }

    private List<FloatTensor> latentBatch(List<int[]> wis, List<FloatTensor> prevSamples,
                                           List<FloatTensor> coarseSlices, float t,
                                           int seedOffset, float[] ww) {
        int S = LATENT_TILE_SIZE, ST = LATENT_TILE_STRIDE;
        int batch = wis.size();
        float cosT = (float) Math.cos(t), sinT = (float) Math.sin(t);

        // Intermediate storage: xT per batch element (needed for output step)
        float[][] xTArr = new float[batch][5 * S * S];

        float[] modelInBatch   = new float[batch * 5 * S * S];
        float[] condInputBatch = new float[batch * 58];

        for (int b = 0; b < batch; b++) {
            int[] ctx = wis.get(b);
            int i1 = ctx[1] * ST, j1 = ctx[2] * ST;

            // Build conditioning from coarse slice (7, 4, 4)
            float[] cond58 = buildLatentConditioning(coarseSlices.get(b));
            System.arraycopy(cond58, 0, condInputBatch, b * 58, 58);

            // Build sample (unnormalized prev output or zeros)
            float[] sample = new float[5 * S * S];
            if (prevSamples != null) {
                FloatTensor ps = prevSamples.get(b);
                for (int ch = 0; ch < 5; ch++)
                    for (int px = 0; px < S * S; px++) {
                        float w = ps.data[5 * S * S + px];
                        sample[ch * S * S + px] = (w > 1e-6f) ? ps.data[ch * S * S + px] / w * SIGMA_DATA : 0f;
                    }
            }

            float[] noise = flatten3D(GaussianNoisePatch.generate(seed + seedOffset, i1, j1, S, S, 5, S, S));
            float[] xT = new float[5 * S * S];
            for (int k = 0; k < 5 * S * S; k++) {
                float z = noise[k] * SIGMA_DATA;
                xT[k] = cosT * sample[k] + sinT * z;
            }
            xTArr[b] = xT;

            // model_in = xT / sigma_data
            for (int k = 0; k < 5 * S * S; k++)
                modelInBatch[b * 5 * S * S + k] = xT[k] / SIGMA_DATA;
        }

        String chunkList = wis.stream().map(w -> "(" + w[1] + "," + w[2] + ")").collect(Collectors.joining(", "));
        LOG.debug("Base model called for {} chunks: {}", batch, chunkList);

        float[] noiseLabels = new float[batch];
        for (int b = 0; b < batch; b++) noiseLabels[b] = t;

        float[] predBatch = baseModel.runModel(
                modelInBatch, new long[]{batch, 5, S, S},
                noiseLabels, new float[][]{condInputBatch}, new long[][]{{batch, 58}});

        List<FloatTensor> results = new ArrayList<>(batch);
        for (int b = 0; b < batch; b++) {
            float[] xT = xTArr[b];
            float[] newSample = new float[5 * S * S];
            for (int k = 0; k < 5 * S * S; k++) {
                float pred = -predBatch[b * 5 * S * S + k];  // base model output is negated
                newSample[k] = (cosT * xT[k] - sinT * SIGMA_DATA * pred) / SIGMA_DATA;
            }

            FloatTensor out = new FloatTensor(new int[]{6, S, S});
            for (int ch = 0; ch < 5; ch++)
                for (int px = 0; px < S * S; px++)
                    out.data[ch * S * S + px] = newSample[ch * S * S + px] * ww[px];
            System.arraycopy(ww, 0, out.data, 5 * S * S, S * S);
            results.add(out);
        }
        return results;
    }

    // Build 58-dim conditioning vector from a (7,4,4) coarse tile slice
    private float[] buildLatentConditioning(FloatTensor coarseSlice) {
        int N = 4 * 4;
        // Unnormalize: cond[:-1] / cond[-1] for each pixel
        float[] condFlat = new float[6 * N];
        for (int ch = 0; ch < 6; ch++)
            for (int px = 0; px < N; px++) {
                float w = coarseSlice.data[6 * N + px];
                condFlat[ch * N + px] = (w > 1e-6f) ? coarseSlice.data[ch * N + px] / w : 0f;
            }

        // Append mask channel (all ones = (1 - mean) / std normalized)
        float[] condImg7 = new float[7 * N];
        System.arraycopy(condFlat, 0, condImg7, 0, 6 * N);
        float maskNorm = (1.0f - COND_MEANS[6]) / COND_STDS[6];
        for (int px = 0; px < N; px++) condImg7[6 * N + px] = maskNorm;

        // Normalize all 7 channels
        for (int ch = 0; ch < 6; ch++)
            for (int px = 0; px < N; px++) {
                float v = (condFlat[ch * N + px] - COND_MEANS[ch]) / COND_STDS[ch];
                condImg7[ch * N + px] = Float.isNaN(v) ? 0f : v;
            }

        // Extract components
        float[] meansCrop    = new float[16]; System.arraycopy(condImg7, 0, meansCrop, 0, 16);
        float[] p5Crop       = new float[16]; System.arraycopy(condImg7, 16, p5Crop, 0, 16);
        float[] maskCrop     = new float[16]; System.arraycopy(condImg7, 6 * 16, maskCrop, 0, 16);
        float[] climateMeans = new float[4];
        for (int ch = 0; ch < 4; ch++) {
            float sum = 0;
            for (int r = 1; r < 3; r++) for (int c = 1; c < 3; c++)
                sum += condImg7[(2 + ch) * 16 + r * 4 + c];
            climateMeans[ch] = sum / 4f;
            if (Float.isNaN(climateMeans[ch])) climateMeans[ch] = 0f;
        }

        float noiseLevelNorm = (0f - 0.5f) * (float) Math.sqrt(12.0);
        float[] histRaw = HISTOGRAM_RAW;

        // mp_concat
        float[] out = new float[58];
        int off = 0;
        off = appendScaled(out, off, meansCrop, MP_CONCAT_SCALES[0]);
        off = appendScaled(out, off, p5Crop, MP_CONCAT_SCALES[1]);
        off = appendScaled(out, off, climateMeans, MP_CONCAT_SCALES[2]);
        off = appendScaled(out, off, maskCrop, MP_CONCAT_SCALES[3]);
        off = appendScaled(out, off, histRaw, MP_CONCAT_SCALES[4]);
        out[off] = noiseLevelNorm * MP_CONCAT_SCALES[5];
        return out;
    }

    private InfiniteTensor buildDecoderStage() {
        int S = DECODER_TILE_SIZE, ST = DECODER_TILE_STRIDE, lc = LATENT_COMPRESSION;
        TensorWindow outWin  = new TensorWindow(new int[]{2, S, S}, new int[]{2, ST, ST});
        TensorWindow inpWin  = new TensorWindow(new int[]{6, S/lc, S/lc}, new int[]{6, ST/lc, ST/lc});
        float[] ww = linearWeightWindow(S);
        float t = (float) Math.atan(EDMScheduler.SIGMA_MAX / SIGMA_DATA);

        return tileStore.getOrCreate("init_residual_map", new Integer[]{2, null, null},
                (wi, args) -> decoderTile(wi, args.get(0), t, ww),
                outWin, new InfiniteTensor[]{latents}, new TensorWindow[]{inpWin}, cacheLimitBytes);
    }

    private FloatTensor decoderTile(int[] wi, FloatTensor latentSlice, float t, float[] ww) {
        int S = DECODER_TILE_SIZE, ST = DECODER_TILE_STRIDE, lc = LATENT_COMPRESSION;
        int Slc = S / lc;
        int i1 = wi[1] * ST, j1 = wi[2] * ST;
        float cosT = (float) Math.cos(t), sinT = (float) Math.sin(t);

        // Unnormalize latents channels 0..3 (4 channels)
        float[] latFlat = new float[4 * Slc * Slc];
        for (int ch = 0; ch < 4; ch++)
            for (int px = 0; px < Slc * Slc; px++) {
                float w = latentSlice.data[5 * Slc * Slc + px];
                latFlat[ch * Slc * Slc + px] = (w > 1e-6f) ? latentSlice.data[ch * Slc * Slc + px] / w : 0f;
            }

        // Nearest-neighbor upsample (4, Slc, Slc) → (4, S, S)
        float[] upsampled = nearestUpsample(latFlat, 4, Slc, Slc, S, S);

        // One flow-matching step (sample starts at zero)
        float[] noise = flatten3D(GaussianNoisePatch.generate(seed + 5819, i1, j1, S, S, 1, S, S));
        float[] xT = new float[S * S];
        for (int k = 0; k < S * S; k++) xT[k] = sinT * noise[k] * SIGMA_DATA;  // sample=0

        // model_in = concat([xT/sigma_data (1,S,S), upsampled (4,S,S)]) → (5,S,S)
        float[] modelIn = new float[5 * S * S];
        for (int k = 0; k < S * S; k++) modelIn[k] = xT[k] / SIGMA_DATA;
        System.arraycopy(upsampled, 0, modelIn, S * S, 4 * S * S);

        LOG.debug("Decoder model called for chunk ({}, {}) tile pixels [{}, {}]-[{}, {}]", wi[1], wi[2], i1, j1, i1 + S, j1 + S);
        float[] rawPred = decoderModel.runModel(modelIn, new long[]{1, 5, S, S}, new float[]{t}, null, null);

        float[] newSample = new float[S * S];
        for (int k = 0; k < S * S; k++) {
            float pred = -rawPred[k];  // decoder model output is negated
            newSample[k] = (cosT * xT[k] - sinT * SIGMA_DATA * pred) / SIGMA_DATA;
        }
        FloatTensor result = new FloatTensor(new int[]{2, S, S});
        for (int px = 0; px < S * S; px++) result.data[px] = newSample[px] * ww[px];
        System.arraycopy(ww, 0, result.data, S * S, S * S);
        return result;
    }

    // Returns the current world seed
    public long getSeed() {
        return seed;
    }

    // Returns the total count of newly computed tensor windows since startup
    public long getTotalComputedWindowCount() {
        return tileStore.getTotalComputedWindowCount();
    }

    // Returns a coarse tensor slice with shape [7, ci1-ci0, cj1-cj0]
    public FloatTensor getCoarseSlice(int ci0, int cj0, int ci1, int cj1) {
        return coarse.getSlice(new int[]{0, ci0, cj0}, new int[]{7, ci1, cj1});
    }

    // Returns the latent stage's low-frequency elevation channel over a latent-pixel box,
    public float[] getLowfreqSlice(int li0, int lj0, int li1, int lj1) {
        int h = li1 - li0, w = lj1 - lj0;
        int px = h * w;
        FloatTensor slice = latents.getSlice(new int[]{0, li0, lj0}, new int[]{6, li1, lj1});
        float[] out = new float[px];
        for (int k = 0; k < px; k++) {
            float weight = slice.data[5 * px + k];
            float v = (weight > 1.0e-6f) ? slice.data[4 * px + k] / weight : 0f;
            out[k] = v * LOWFREQ_STD + LOWFREQ_MEAN;
        }
        return out;
    }

    // Get elevation and climate for a bounding box
    public float[][] get(int i1, int j1, int i2, int j2, boolean withClimate) {
        float[] elevFlat = computeElev(i1, j1, i2, j2);
        int H = i2 - i1, W = j2 - j1;
        float[] climate = withClimate ? computeClimate(i1, j1, i2, j2, elevFlat, H, W) : null;
        return new float[][]{elevFlat, climate};
    }

    private float[] computeElev(int i1, int j1, int i2, int j2) {
        int lc = LATENT_COMPRESSION;
        float sigma = 5.0f;
        int ks = ((int) (sigma * 2) / 2) * 2 + 1;
        int padLr = ks / 2 + 1;
        int padHr = padLr * lc;

        // Align padding to lc-pixel grid
        int pi1 = Math.floorDiv(i1 - padHr, lc) * lc;
        int pj1 = Math.floorDiv(j1 - padHr, lc) * lc;
        int pi2 = -Math.floorDiv(-(i2 + padHr), lc) * lc;
        int pj2 = -Math.floorDiv(-(j2 + padHr), lc) * lc;
        int pH = pi2 - pi1, pW = pj2 - pj1;

        // Residual slice (2, pH, pW)
        FloatTensor resSlice = residual.getSlice(new int[]{0, pi1, pj1}, new int[]{2, pi2, pj2});
        float[][] residualP = new float[pH][pW];
        for (int r = 0; r < pH; r++)
            for (int c = 0; c < pW; c++) {
                float w = resSlice.data[pH * pW + r * pW + c];
                float v = (w > 1e-6f) ? resSlice.data[r * pW + c] / w : 0f;
                residualP[r][c] = v * RESIDUAL_STD + RESIDUAL_MEAN;
            }

        // Latent slice (6, lH, lW)
        int lH = pH / lc, lW = pW / lc;
        FloatTensor latSlice = latents.getSlice(
                new int[]{0, pi1 / lc, pj1 / lc}, new int[]{6, pi2 / lc, pj2 / lc});
        float[][] lowfreqP = new float[lH][lW];
        for (int r = 0; r < lH; r++)
            for (int c = 0; c < lW; c++) {
                float w = latSlice.data[5 * lH * lW + r * lW + c];
                float v = (w > 1e-6f) ? latSlice.data[4 * lH * lW + r * lW + c] / w : 0f;
                lowfreqP[r][c] = v * LOWFREQ_STD + LOWFREQ_MEAN;
            }

        float[][] newLowres = LaplacianUtils.laplacianDenoise(residualP, lowfreqP, sigma);
        float[][] elevP = LaplacianUtils.laplacianDecode(residualP, newLowres);

        int oi = i1 - pi1, oj = j1 - pj1, H = i2 - i1, W = j2 - j1;
        float[] flat = new float[H * W];
        for (int r = 0; r < H; r++)
            for (int c = 0; c < W; c++) {
                float es = elevP[oi + r][oj + c];
                flat[r * W + c] = (float) (Math.signum(es) * es * es);
            }
        return flat;
    }

    private float[] computeClimate(int i1, int j1, int i2, int j2,
                                    float[] elevFlat, int H, int W) {
        int lc = LATENT_COMPRESSION;
        int S = 32 * lc;  // native pixels per coarse pixel in stride sense

        int ci1 = Math.floorDiv(i1, S);
        int cj1 = Math.floorDiv(j1, S);
        int ci2 = -Math.floorDiv(-i2, S);
        int cj2 = -Math.floorDiv(-j2, S);

        int win = 15, pad = (win - 1) / 2 + 1;

        FloatTensor coarseSlice = coarse.getSlice(
                new int[]{0, ci1 - pad, cj1 - pad}, new int[]{7, ci2 + pad, cj2 + pad});
        int cH = ci2 + pad - (ci1 - pad);
        int cW = cj2 + pad - (cj1 - pad);

        // Unnormalize all 6 coarse channels
        float[][] coarseMap = new float[6][cH * cW];
        for (int ch = 0; ch < 6; ch++)
            for (int px = 0; px < cH * cW; px++) {
                float w = coarseSlice.data[6 * cH * cW + px];
                coarseMap[ch][px] = (w > 1e-6f) ? coarseSlice.data[ch * cH * cW + px] / w : 0f;
            }

        // Coarse elevation (undo sqrt): max(0, v)^2 — ocean pixels clamp to 0, matching Python
        float[] coarseElev = new float[cH * cW];
        for (int px = 0; px < cH * cW; px++) {
            float v = Math.max(0f, coarseMap[0][px]);
            coarseElev[px] = v * v;
        }

        // Windowed lapse-rate regression
        float[][][] lbt = LaplacianUtils.localBaselineTemperature(
                to2D(coarseMap[2], cH, cW), to2D(coarseElev, cH, cW), win, 0.02f);
        int lH = lbt[0].length, lW = lbt[0][0].length;

        // Central coarse (crop pad pixels from each side)
        int cenPad = win / 2;
        int cenH = cH - 2 * cenPad, cenW = cW - 2 * cenPad;
        float[][][] centralCoarse = new float[6][cenH][cenW];
        for (int ch = 0; ch < 6; ch++) {
            float[][] full = to2D(coarseMap[ch], cH, cW);
            centralCoarse[ch] = cropArray(full, cenPad, cenPad, cenH, cenW);
        }

        // Bilinear upsample to native resolution
        float[] climate = new float[6 * H * W];
        for (int r = 0; r < H; r++) {
            // fractional index into lbt/centralCoarse arrays (matches Python's u = (ii+0.5)/S - ci1 + 0.5)
            float gridY    = (i1 + r + 0.5f) / S - ci1 + 0.5f;
            float cenGridY = gridY;
            for (int c = 0; c < W; c++) {
                float gridX    = (j1 + c + 0.5f) / S - cj1 + 0.5f;
                float cenGridX = gridX;

                float tBase = bilinearSample2D(lbt[0], lH, lW, gridY, gridX);
                float beta  = bilinearSample2D(lbt[1], lH, lW, gridY, gridX);
                float tempReal = tBase + beta * Math.max(0f, elevFlat[r * W + c]);

                climate[r * W + c]             = tempReal;
                climate[H * W + r * W + c]     = bilinearSample2D(centralCoarse[3], cenH, cenW, cenGridY, cenGridX);
                climate[2 * H * W + r * W + c] = bilinearSample2D(centralCoarse[4], cenH, cenW, cenGridY, cenGridX);
                climate[3 * H * W + r * W + c] = bilinearSample2D(centralCoarse[5], cenH, cenW, cenGridY, cenGridX);
                climate[4 * H * W + r * W + c] = beta;
                climate[5 * H * W + r * W + c] = bilinearSample2D(centralCoarse[2], cenH, cenW, cenGridY, cenGridX);
            }
        }
        return climate;
    }

    static float[] linearWeightWindow(int size) {
        float[] w = new float[size * size];
        float mid = (size - 1) / 2.0f, eps = 1e-3f;
        for (int r = 0; r < size; r++) {
            float wy = 1f - (1f - eps) * Math.min(1f, Math.abs(r - mid) / mid);
            for (int c = 0; c < size; c++) {
                float wx = 1f - (1f - eps) * Math.min(1f, Math.abs(c - mid) / mid);
                w[r * size + c] = wy * wx;
            }
        }
        return w;
    }

    static float[] flatten3D(float[][][] arr) {
        int C = arr.length, H = arr[0].length, W = arr[0][0].length;
        float[] out = new float[C * H * W];
        for (int c = 0; c < C; c++)
            for (int r = 0; r < H; r++)
                System.arraycopy(arr[c][r], 0, out, c * H * W + r * W, W);
        return out;
    }

    static int appendScaled(float[] out, int off, float[] arr, float scale) {
        for (float v : arr) out[off++] = v * scale;
        return off;
    }

    static float[] nearestUpsample(float[] src, int C, int sH, int sW, int dH, int dW) {
        float[] dst = new float[C * dH * dW];
        for (int c = 0; c < C; c++)
            for (int r = 0; r < dH; r++) {
                int sr = r * sH / dH;
                for (int col = 0; col < dW; col++)
                    dst[c * dH * dW + r * dW + col] = src[c * sH * sW + sr * sW + col * sW / dW];
            }
        return dst;
    }

    static float[][] to2D(float[] flat, int H, int W) {
        float[][] a = new float[H][W];
        for (int r = 0; r < H; r++) System.arraycopy(flat, r * W, a[r], 0, W);
        return a;
    }

    static float[][] cropArray(float[][] src, int r0, int c0, int H, int W) {
        float[][] out = new float[H][W];
        for (int r = 0; r < H; r++) System.arraycopy(src[r + r0], c0, out[r], 0, W);
        return out;
    }

    static float bilinearSample2D(float[][] src, int H, int W, float gy, float gx) {
        float y = Math.max(0f, Math.min(H - 1f, gy));
        float x = Math.max(0f, Math.min(W - 1f, gx));
        int y0 = (int) y, y1 = Math.min(H - 1, y0 + 1);
        int x0 = (int) x, x1 = Math.min(W - 1, x0 + 1);
        float wy = y - y0, wx = x - x0;
        return (1-wy)*(1-wx)*src[y0][x0] + (1-wy)*wx*src[y0][x1]
             + wy*(1-wx)*src[y1][x0] + wy*wx*src[y1][x1];
    }

    private static final boolean DISK_CACHE_ENABLED =
            !"false".equals(System.getProperty("terradiff.diskCache"));

    private static volatile Path cacheRootOverride;

    // Set from the world-load hook so the cache lives beside the save it belongs to
    public static void setCacheRoot(Path worldDir) {
        cacheRootOverride = worldDir;
    }

    private DiskTileCache diskCache;

    // Points the tile store at the on-disk tier for the current seed
    private void attachDiskCache() {
        if (diskCache != null) {
            diskCache.close();
            diskCache = null;
        }
        tileStore.setDiskCache(null);
        if (!DISK_CACHE_ENABLED) {
            return;
        }
        try {
            Path base = cacheRootOverride != null ? cacheRootOverride : PlatformPaths.gameDir();
            Path root = base.resolve("terradiff-cache").resolve(Long.toUnsignedString(seed));
            diskCache = readOnlyDisk
                    ? DiskTileCache.openReadOnly(root, pipelineFingerprint())
                    : DiskTileCache.open(root, pipelineFingerprint());
            tileStore.setDiskCache(diskCache);
        } catch (RuntimeException e) {
            LOG.warn("Terrain cache not attached: {}", e.toString());
        }
    }

    private static String pipelineFingerprint() {
        StringBuilder sb = new StringBuilder();
        sb.append("v1")
          .append(";latentCompression=").append(LATENT_COMPRESSION)
          .append(";coarseTile=").append(COARSE_TILE_SIZE)
          .append(";coarseStride=").append(COARSE_TILE_STRIDE)
          .append(";coarsePooling=").append(COARSE_POOLING)
          .append(";means=").append(java.util.Arrays.hashCode(MODEL_MEANS))
          .append(";stds=").append(java.util.Arrays.hashCode(MODEL_STDS));
        for (String model : new String[]{"coarse_model.onnx", "base_model.onnx", "decoder_model.onnx"}) {
            sb.append(';').append(model).append('=');
            try {
                Path p = ModelAssetManager.resolveAssetPath(model);
                sb.append(Files.size(p)).append('@').append(Files.getLastModifiedTime(p).toMillis());
            } catch (Exception e) {
                sb.append("unknown");
            }
        }
        return sb.toString();
    }

    @Override
    public void close() {
        if (diskCache != null) {
            diskCache.close();
            diskCache = null;
        }
        if (ownModels) {
            coarseModel.close();
            baseModel.close();
            decoderModel.close();
        }
    }
}
