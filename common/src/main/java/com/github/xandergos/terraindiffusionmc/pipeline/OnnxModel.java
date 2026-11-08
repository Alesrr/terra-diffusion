package com.github.xandergos.terraindiffusionmc.pipeline;

import ai.onnxruntime.*;
import ai.onnxruntime.providers.CoreMLFlags;
import ai.onnxruntime.providers.OrtCUDAProviderOptions;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

// Thin wrapper around ONNX Runtime with aggressive VRAM optimization
public final class OnnxModel implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OnnxModel.class);
    private static final String OPTIMIZED_MODELS_DIR_NAME = "onnx-cache";

    private static volatile String resolvedInferenceProvider = null;
    private static final AtomicBoolean providerLoggedOnce = new AtomicBoolean(false);
    private static final AtomicBoolean coremlWarnLoggedOnce = new AtomicBoolean(false);
    private static final AtomicBoolean cudaWarnLoggedOnce = new AtomicBoolean(false);
    private static final AtomicBoolean dmlWarnLoggedOnce = new AtomicBoolean(false);
    private static final AtomicBoolean noGpuWarnLoggedOnce = new AtomicBoolean(false);

    // GPU slot: when offload_models=true, only one session is alive at a time
    private static final Object GPU_SLOT_LOCK = new Object();
    private static OnnxModel gpuSlotHolder = null;
    private static OrtSession activeGpuSession = null;

    private static final String HALF_PRECISION_TAG = "fp16v1";

    private final OrtEnvironment env;
    private final byte[] optimizedModelBytes;
    private final String name;
    private final long spatialSize;
    private final long[] fixedBatches;
    private final Map<List<Long>, OrtSession> fixedSessions = new HashMap<>();
    private final boolean halfPrecision;
    private OrtSession cpuSession;    // non-null in CPU-only mode
    private OrtSession gpuSession;    // non-null when offload_models=false

    private static final class OptimizedModelLoadResult {
        private final byte[] modelBytes;
        private final Path optimizedModelPath;
        private final boolean loadedFromCache;

        private OptimizedModelLoadResult(byte[] modelBytes, Path optimizedModelPath, boolean loadedFromCache) {
            this.modelBytes = modelBytes;
            this.optimizedModelPath = optimizedModelPath;
            this.loadedFromCache = loadedFromCache;
        }
    }

    public OnnxModel(Path modelFilePath, String name) {
        this(modelFilePath, name, 0);
    }

    public OnnxModel(Path modelFilePath, String name, long spatialSize, long... fixedBatches) {
        this.name = name;
        this.spatialSize = spatialSize;
        this.fixedBatches = fixedBatches.clone();
        Arrays.sort(this.fixedBatches);
        try {
            long start = System.currentTimeMillis();
            this.env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
            byte[] sourceModelBytes = Files.readAllBytes(modelFilePath);
            byte[] halfPrecisionModelBytes = loadHalfPrecisionModel(sourceModelBytes, start);
            if (halfPrecisionModelBytes != null) {
                this.optimizedModelBytes = halfPrecisionModelBytes;
                this.halfPrecision = true;
                return;
            }
            this.halfPrecision = false;
            OptimizedModelLoadResult initialOptimizedModelLoadResult = optimizeModelAtRuntime(sourceModelBytes, false);
            byte[] loadedModelBytes;
            try {
                initializeModelSession(initialOptimizedModelLoadResult.modelBytes, start);
                loadedModelBytes = initialOptimizedModelLoadResult.modelBytes;
            } catch (Exception initialLoadException) {
                if (!initialOptimizedModelLoadResult.loadedFromCache) {
                    throw initialLoadException;
                }
                closeLoadedSessions();
                LOG.warn("Cached optimized ONNX model '{}' failed to load. Rebuilding cache: {}",
                        name, initialLoadException.getMessage());
                deleteOptimizedCacheFile(initialOptimizedModelLoadResult.optimizedModelPath);
                OptimizedModelLoadResult rebuiltOptimizedModelLoadResult = optimizeModelAtRuntime(sourceModelBytes, true);
                initializeModelSession(rebuiltOptimizedModelLoadResult.modelBytes, start);
                loadedModelBytes = rebuiltOptimizedModelLoadResult.modelBytes;
            }
            this.optimizedModelBytes = loadedModelBytes;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load ONNX model: " + modelFilePath, e);
        }
    }

    // Optimizes model bytes and caches the optimized file in the config directory
    private OptimizedModelLoadResult optimizeModelAtRuntime(byte[] sourceModelBytes, boolean forceRebuildFromSource) {
        Path optimizedModelPath = resolveOptimizedModelPath(sourceModelBytes);
        try {
            if (!forceRebuildFromSource && Files.exists(optimizedModelPath)) {
                byte[] cachedOptimizedModelBytes = Files.readAllBytes(optimizedModelPath);
                return new OptimizedModelLoadResult(cachedOptimizedModelBytes, optimizedModelPath, true);
            }

            Files.createDirectories(optimizedModelPath.getParent());
            Path temporaryOptimizedModelPath = optimizedModelPath.resolveSibling(optimizedModelPath.getFileName() + ".tmp");
            Files.deleteIfExists(temporaryOptimizedModelPath);
            OrtSession.SessionOptions optimizationOptions = new OrtSession.SessionOptions();
            optimizationOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT);
            optimizationOptions.setOptimizedModelFilePath(temporaryOptimizedModelPath.toAbsolutePath().toString());
            try (OrtSession ignored = env.createSession(sourceModelBytes, optimizationOptions)) {
                // Session creation materializes the optimized model on disk
            }
            byte[] optimizedModelBytesFromDisk = Files.readAllBytes(temporaryOptimizedModelPath);
            Files.move(
                    temporaryOptimizedModelPath,
                    optimizedModelPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
            LOG.info("Optimized ONNX model '{}' at runtime ({} KB -> {} KB)",
                    name, sourceModelBytes.length / 1024, optimizedModelBytesFromDisk.length / 1024);
            return new OptimizedModelLoadResult(optimizedModelBytesFromDisk, optimizedModelPath, false);
        } catch (Exception optimizationException) {
            LOG.warn("Runtime ONNX optimization failed for '{}', using source model bytes: {}",
                    name, optimizationException.getMessage());
            return new OptimizedModelLoadResult(sourceModelBytes, optimizedModelPath, false);
        }
    }

    private byte[] loadHalfPrecisionModel(byte[] sourceModelBytes, long startMillis) {
        if (!halfPrecisionAvailable()) {
            return null;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                byte[] modelBytes = halfPrecisionModelBytes(sourceModelBytes, attempt > 0);
                long firstBatch = fixedBatches.length > 0 ? fixedBatches[0] : 1;
                fixedSession(modelBytes, firstBatch, spatialSize, spatialSize);
                if (TerrainDiffusionConfig.offloadModels()) {
                    closeFixedSessions();
                } else {
                    for (long batch : fixedBatches) {
                        fixedSession(modelBytes, batch, spatialSize, spatialSize);
                    }
                }
                setResolvedProviderOnce("DirectML");
                LOG.info("ONNX model '{}' loaded on GPU in half precision ({} KB) in {} ms",
                        name, modelBytes.length / 1024, System.currentTimeMillis() - startMillis);
                return modelBytes;
            } catch (Throwable t) {
                closeFixedSessions();
                LOG.warn("Half precision {} for '{}': {}",
                        attempt == 0 ? "failed, rebuilding" : "unavailable, using full precision", name, t.toString());
            }
        }
        return null;
    }

    private boolean halfPrecisionAvailable() {
        if ("cpu".equals(TerrainDiffusionConfig.inferenceDevice())) {
            return false;
        }
        EnumSet<OrtProvider> providers = OrtEnvironment.getAvailableProviders();
        return providers.contains(OrtProvider.DIRECT_ML) && !providers.contains(OrtProvider.CUDA);
    }

    private byte[] halfPrecisionModelBytes(byte[] sourceModelBytes, boolean rebuild) throws Exception {
        String sourceModelHashPrefix = sha256Hex(sourceModelBytes).substring(0, 16);
        Path cachedPath = ModelAssetManager.resolveAssetPath(OPTIMIZED_MODELS_DIR_NAME).resolve(
                name + "-" + HALF_PRECISION_TAG + "-" + resolveOnnxRuntimeVersionTag() + "-" + sourceModelHashPrefix + ".onnx");
        if (rebuild) {
            Files.deleteIfExists(cachedPath);
        } else if (Files.exists(cachedPath)) {
            return Files.readAllBytes(cachedPath);
        }
        Files.createDirectories(cachedPath.getParent());
        Path basicPath = cachedPath.resolveSibling(cachedPath.getFileName() + ".basic.tmp");
        Path temporaryPath = cachedPath.resolveSibling(cachedPath.getFileName() + ".tmp");
        try {
            try (OrtSession.SessionOptions basicOptions = new OrtSession.SessionOptions()) {
                basicOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
                basicOptions.setOptimizedModelFilePath(basicPath.toAbsolutePath().toString());
                try (OrtSession ignored = env.createSession(sourceModelBytes, basicOptions)) {
                }
            }
            byte[] basicModelBytes = Files.readAllBytes(basicPath);
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temporaryPath), 1 << 20)) {
                HalfPrecisionModel.convert(basicModelBytes, out);
            }
            Files.move(temporaryPath, cachedPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(basicPath);
            Files.deleteIfExists(temporaryPath);
        }
        LOG.info("Converted ONNX model '{}' to half precision", name);
        return Files.readAllBytes(cachedPath);
    }

    private OrtSession fixedSession(byte[] modelBytes, long batch, long height, long width) throws OrtException {
        List<Long> key = List.of(batch, height, width);
        synchronized (fixedSessions) {
            OrtSession session = fixedSessions.get(key);
            if (session != null) {
                return session;
            }
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                options.setMemoryPatternOptimization(false);
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
                options.setSymbolicDimensionValue("batch", batch);
                if (spatialSize > 0) {
                    options.setSymbolicDimensionValue("height", height);
                    options.setSymbolicDimensionValue("width", width);
                }
                options.addDirectML(0);
                session = env.createSession(modelBytes, options);
            }
            fixedSessions.put(key, session);
            return session;
        }
    }

    private void closeFixedSessions() {
        synchronized (fixedSessions) {
            for (OrtSession session : fixedSessions.values()) {
                try { session.close(); } catch (OrtException ignored) {}
            }
            fixedSessions.clear();
        }
    }

    // Returns the resolved inference provider name, or "unknown" if not yet determined
    public static String getResolvedInferenceProvider() {
        String provider = resolvedInferenceProvider;
        return provider != null ? provider : "unknown";
    }

    private static void setResolvedProviderOnce(String provider) {
        if (resolvedInferenceProvider == null) {
            resolvedInferenceProvider = provider;
        }
        if (providerLoggedOnce.compareAndSet(false, true)) {
            LOG.info("Terrain diffusion inference: {}", provider);
        }
    }

    // Loads model sessions for the active inference device configuration
    private void initializeModelSession(byte[] modelBytes, long startMillis) throws OrtException {
        if ("cpu".equals(TerrainDiffusionConfig.inferenceDevice())) {
            OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions();
            sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            this.cpuSession = env.createSession(modelBytes, sessionOptions);
            this.gpuSession = null;
            setResolvedProviderOnce("CPU");
            LOG.info("ONNX model '{}' loaded on CPU ({} KB) in {} ms",
                    name, modelBytes.length / 1024, System.currentTimeMillis() - startMillis);
            return;
        }
        if (!TerrainDiffusionConfig.offloadModels()) {
            OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions();
            sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            addGpuProvider(sessionOptions);
            if ("CoreML".equals(resolvedInferenceProvider)) {
                throw new OrtException(
                        "inference.offload_models=false is not supported with CoreML. " +
                        "Set inference.offload_models=true in terrain-diffusion-mc.properties.");
            }
            this.gpuSession = env.createSession(modelBytes, sessionOptions);
            this.cpuSession = null;
            LOG.info("ONNX model '{}' loaded on GPU ({} KB) in {} ms",
                    name, modelBytes.length / 1024, System.currentTimeMillis() - startMillis);
            return;
        }
        this.cpuSession = null;
        this.gpuSession = null;
        LOG.info("ONNX model '{}' bytes cached in CPU RAM ({} KB) in {} ms",
                name, modelBytes.length / 1024, System.currentTimeMillis() - startMillis);
    }

    private void closeLoadedSessions() {
        if (cpuSession != null) {
            try { cpuSession.close(); } catch (OrtException ignored) {}
            cpuSession = null;
        }
        if (gpuSession != null) {
            try { gpuSession.close(); } catch (OrtException ignored) {}
            gpuSession = null;
        }
    }

    private void deleteOptimizedCacheFile(Path optimizedModelPath) {
        try {
            Files.deleteIfExists(optimizedModelPath);
        } catch (Exception deleteException) {
            LOG.warn("Failed to delete optimized cache '{}' for '{}': {}",
                    optimizedModelPath, name, deleteException.getMessage());
        }
    }

    // Resolves a deterministic cache file path for an optimized model
    private Path resolveOptimizedModelPath(byte[] sourceModelBytes) {
        String sourceModelHashPrefix = sha256Hex(sourceModelBytes).substring(0, 16);
        String runtimeVersionTag = resolveOnnxRuntimeVersionTag();
        String optimizedFileName = name + "-" + runtimeVersionTag + "-" + sourceModelHashPrefix + ".onnx";
        return ModelAssetManager.resolveAssetPath(OPTIMIZED_MODELS_DIR_NAME)
                .resolve(optimizedFileName);
    }

    // Returns the ONNX Runtime version used as part of the optimization cache key
    private static String resolveOnnxRuntimeVersionTag() {
        Package onnxRuntimePackage = OrtEnvironment.class.getPackage();
        String implementationVersion = onnxRuntimePackage == null ? null : onnxRuntimePackage.getImplementationVersion();
        return implementationVersion == null ? "unknown" : implementationVersion;
    }

    // Computes a lowercase SHA-256 hex string for deterministic cache naming
    private static String sha256Hex(byte[] inputBytes) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] digestBytes = messageDigest.digest(inputBytes);
            StringBuilder hexBuilder = new StringBuilder(digestBytes.length * 2);
            for (byte digestByte : digestBytes) {
                hexBuilder.append(String.format("%02x", digestByte));
            }
            return hexBuilder.toString();
        } catch (NoSuchAlgorithmException noSuchAlgorithmException) {
            throw new IllegalStateException("Missing SHA-256 algorithm", noSuchAlgorithmException);
        }
    }

    // Run the model with a flat float array for each named input
    public float[] run(Object[][] inputs) {
        if (halfPrecision) {
            if (!TerrainDiffusionConfig.offloadModels()) {
                return runFixed(inputs);
            }
            synchronized (GPU_SLOT_LOCK) {
                claimFixedSlot();
                return runFixed(inputs);
            }
        }
        if (cpuSession != null) {
            return runWithSession(cpuSession, inputs);
        }
        if (gpuSession != null) {
            return runWithSession(gpuSession, inputs);
        }
        synchronized (GPU_SLOT_LOCK) {
            claimGpuSlot();
            return runWithSession(activeGpuSession, inputs);
        }
    }

    // Convenience: run with x, noise_labels, and optional cond tensors
    public float[] runModel(float[] x, long[] xShape,
                            float[] noiseLabels,
                            float[][] condInputs, long[][] condShapes) {
        int nCond = condInputs == null ? 0 : condInputs.length;
        Object[][] inputs = new Object[2 + nCond][3];
        inputs[0] = new Object[]{"x", x, xShape};
        inputs[1] = new Object[]{"noise_labels", noiseLabels, new long[]{noiseLabels.length}};
        for (int i = 0; i < nCond; i++)
            inputs[2 + i] = new Object[]{"cond_" + i, condInputs[i], condShapes[i]};
        return run(inputs);
    }

    private static final int GPU_SESSION_ATTEMPTS = 4;
    private static final long GPU_RETRY_BASE_MS = 250L;

    private float[] runFixed(Object[][] inputs) {
        long[] shape = (long[]) inputs[0][2];
        long batch = shape[0];
        long target = batch;
        for (long fixedBatch : fixedBatches) {
            if (fixedBatch >= batch) {
                target = fixedBatch;
                break;
            }
        }
        boolean spatial = spatialSize > 0 && shape.length == 4;
        OrtSession session;
        try {
            session = fixedSession(optimizedModelBytes, target, spatial ? shape[2] : 0, spatial ? shape[3] : 0);
        } catch (OrtException e) {
            throw new RuntimeException("Failed to create GPU session for: " + name, e);
        }
        if (target == batch) {
            return runWithSession(session, inputs);
        }
        float[] out = runWithSession(session, padBatch(inputs, batch, target));
        return Arrays.copyOf(out, (int) (out.length / target * batch));
    }

    private static Object[][] padBatch(Object[][] inputs, long batch, long target) {
        Object[][] padded = new Object[inputs.length][];
        for (int k = 0; k < inputs.length; k++) {
            float[] values = (float[]) inputs[k][1];
            long[] shape = (long[]) inputs[k][2];
            if (shape.length == 0 || shape[0] != batch) {
                padded[k] = inputs[k];
                continue;
            }
            int item = (int) (values.length / batch);
            float[] grown = Arrays.copyOf(values, (int) (item * target));
            for (int row = (int) batch; row < target; row++) {
                System.arraycopy(values, (int) ((batch - 1) * item), grown, row * item, item);
            }
            long[] grownShape = shape.clone();
            grownShape[0] = target;
            padded[k] = new Object[]{inputs[k][0], grown, grownShape};
        }
        return padded;
    }

    private void claimFixedSlot() {
        if (gpuSlotHolder == this) return;
        if (activeGpuSession != null) {
            try { activeGpuSession.close(); } catch (OrtException ignored) {}
            activeGpuSession = null;
        }
        if (gpuSlotHolder != null) {
            gpuSlotHolder.closeFixedSessions();
        }
        gpuSlotHolder = this;
    }

    // Takes the GPU slot from the model holding it and opens this model's session
    private void claimGpuSlot() {
        if (gpuSlotHolder == this) return;

        if (gpuSlotHolder != null) {
            gpuSlotHolder.closeFixedSessions();
        }
        if (activeGpuSession != null) {
            LOG.debug("Evicting '{}' from GPU, loading '{}'",
                    gpuSlotHolder != null ? gpuSlotHolder.name : "?", name);
            try { activeGpuSession.close(); } catch (OrtException ignored) {}
            activeGpuSession = null;
            gpuSlotHolder = null;
        }

        OrtException lastFailure = null;

        for (int attempt = 1; attempt <= GPU_SESSION_ATTEMPTS; attempt++) {
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            try {
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                addGpuProvider(opts);
            } catch (OrtException e) {
                opts.close();
                throw new RuntimeException("No GPU provider available for: " + name, e);
            }

            try {
                activeGpuSession = env.createSession(optimizedModelBytes, opts);
                gpuSlotHolder = this;
                if (attempt > 1) {
                    LOG.info("GPU session for '{}' succeeded on attempt {}", name, attempt);
                }
                LOG.debug("GPU session ready for '{}'", name);
                return;
            } catch (OrtException e) {
                lastFailure = e;
                opts.close();
                if (attempt < GPU_SESSION_ATTEMPTS) {
                    LOG.warn("GPU session for '{}' unavailable (attempt {}/{}), retrying in {} ms: {}",
                            name, attempt, GPU_SESSION_ATTEMPTS, GPU_RETRY_BASE_MS * attempt,
                            e.getMessage());
                    try {
                        Thread.sleep(GPU_RETRY_BASE_MS * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        throw new RuntimeException("Failed to create GPU session for: " + name
                + " after " + GPU_SESSION_ATTEMPTS + " attempts", lastFailure);
    }

    private static void addGpuProvider(OrtSession.SessionOptions opts) throws OrtException {
        boolean gpuRequired = "gpu".equals(TerrainDiffusionConfig.inferenceDevice());
        boolean added = false;

        try {
            OrtCUDAProviderOptions cudaOpts = new OrtCUDAProviderOptions(0);
            // Grows the BFC arena by exactly what is needed
            cudaOpts.add("arena_extend_strategy", "kSameAsRequested");
            // Heuristic: fast startup, no exhaustive benchmarking, workspace-efficient
            cudaOpts.add("cudnn_conv_algo_search", "HEURISTIC");
            cudaOpts.add("do_copy_in_default_stream", "1");
            opts.addCUDA(cudaOpts);
            cudaOpts.close();
            added = true;
            setResolvedProviderOnce("CUDA");
        } catch (Throwable t) {
            if (cudaWarnLoggedOnce.compareAndSet(false, true)) {
                LOG.warn("CUDA not available: {} - {}. This is expected if you are not using a CUDA build.",
                        t.getClass().getSimpleName(), t.getMessage());
            }
        }

        if (!added) {
            try {
                opts.addDirectML(0);
                added = true;
                setResolvedProviderOnce("DirectML");
            } catch (Throwable t) {
                if (dmlWarnLoggedOnce.compareAndSet(false, true)) {
                    LOG.warn("DirectML not available: {} - {}. This is expected if you are not using a DirectML build.",
                            t.getClass().getSimpleName(), t.getMessage());
                }
            }
        }

        if (!added) {
            try {
                // ENABLE_ON_SUBGRAPH: allow CoreML to handle partial graphs with CPU fallback
                opts.addCoreML(EnumSet.of(CoreMLFlags.ENABLE_ON_SUBGRAPH));
                added = true;
                setResolvedProviderOnce("CoreML");
            } catch (Throwable t) {
                if (coremlWarnLoggedOnce.compareAndSet(false, true)) {
                    LOG.warn("CoreML not available: {} - {}. This is expected on non-macOS platforms.",
                            t.getClass().getSimpleName(), t.getMessage());
                }
            }
        }
        if (gpuRequired && !added) {
            throw new OrtException(
                    "inference.device=gpu but no GPU provider (CUDA, DirectML, CoreML) is available. " +
                    "Use the appropriate build for your platform or set inference.device=cpu.");
        }
        if (!added) {
            setResolvedProviderOnce("CPU");
            if (noGpuWarnLoggedOnce.compareAndSet(false, true)) {
                LOG.warn("No GPU provider loaded. Check drivers and that the mod jar is the GPU build.");
            }
        }
    }

    private static float[] runWithSession(OrtSession session, Object[][] inputs) {
        Map<String, OnnxTensor> feed = new LinkedHashMap<>();
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try {
            for (Object[] inp : inputs) {
                feed.put((String) inp[0],
                        OnnxTensor.createTensor(env, FloatBuffer.wrap((float[]) inp[1]), (long[]) inp[2]));
            }
            try (OrtSession.Result result = session.run(feed)) {
                OnnxTensor output = (OnnxTensor) result.get(0);
                FloatBuffer buf = output.getFloatBuffer();
                float[] out = new float[buf.remaining()];
                buf.get(out);
                return out;
            }
        } catch (OrtException e) {
            throw new RuntimeException("ONNX inference failed", e);
        } finally {
            for (OnnxTensor t : feed.values()) t.close();
        }
    }

    @Override
    public void close() {
        synchronized (GPU_SLOT_LOCK) {
            if (gpuSlotHolder == this) {
                if (activeGpuSession != null) {
                    try { activeGpuSession.close(); } catch (OrtException ignored) {}
                    activeGpuSession = null;
                }
                gpuSlotHolder = null;
            }
        }
        closeFixedSessions();
        if (cpuSession != null) {
            try { cpuSession.close(); } catch (OrtException ignored) {}
            cpuSession = null;
        }
        if (gpuSession != null) {
            try { gpuSession.close(); } catch (OrtException ignored) {}
            gpuSession = null;
        }
    }
}
