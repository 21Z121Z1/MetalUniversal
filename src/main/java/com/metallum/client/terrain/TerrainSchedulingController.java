package com.metallum.client.terrain;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * Deterministic policy layer for Sodium's existing terrain scheduler.
 *
 * <p>The controller never owns a queue or a render section. It only supplies a
 * per-frame duration budget and an optional forward-priority decision at the
 * mixin boundary, so disabled and warmup frames retain Sodium's arguments.</p>
 */
public final class TerrainSchedulingController {
    public static final String ADAPTIVE_PROPERTY = "metallum.opt.terrainAdaptiveScheduling";
    public static final String CSV_PROPERTY = "metallum.opt.terrainSchedulingCsv";
    public static final String TELEMETRY_PROPERTY = "metallum.opt.terrainSchedulingTelemetry";
    public static final long TARGET_FRAME_NANOS = 16_666_667L;

    private static final int DEFAULT_WARMUP_FRAMES = 30;
    private static final int FORWARD_BOOST_FRAMES = 12;
    private static final double MEANINGFUL_TURN_DOT = 0.94;
    private static final double FORWARD_PRIORITY_DOT = 0.62;
    private static final double FORWARD_EXTRA_DISTANCE = 24.0;
    private static final int PRESSURE_RISE_SAMPLES = 2;
    private static final int PRESSURE_RECOVERY_SAMPLES = 8;
    private static final int BACKLOG_CONSTRAINED = 48;
    private static final int BACKLOG_SEVERE = 128;

    private static final TerrainSchedulingController RUNTIME = createRuntime();

    private final boolean enabled;
    private final int warmupFrames;
    private final TerrainSchedulingTelemetry telemetry;

    private long frameIndex;
    private long cpuFrameStartNanos;
    private long lastCpuFrameNanos;
    private long terrainStartNanos;
    private long buildStartNanos;
    private long uploadStartNanos;
    private long terrainNanos;
    private long buildSubmitNanos;
    private long uploadNanos;
    private int submittedTasks;
    private int uploadResults;

    private boolean hasForward;
    private double cameraX;
    private double cameraY;
    private double cameraZ;
    private double forwardX;
    private double forwardY;
    private double forwardZ;
    private int forwardBoostFrames;
    private boolean turnDetected;

    private FrameInputs inputs = FrameInputs.neutral();
    private FrameDecision decision = FrameDecision.defaults();
    private FrameSnapshot lastSnapshot = FrameSnapshot.empty();
    private int pressureLevel;
    private int pressureRiseSamples;
    private int pressureRecoverySamples;
    private long adaptiveFrameCounted = Long.MIN_VALUE;
    private long pressureFrameCounted = Long.MIN_VALUE;

    private long frames;
    private long adaptiveFrames;
    private long turnCount;
    private long forwardBoostFrameCount;
    private long pressureFrames;
    private long buildStageCount;
    private long uploadStageCount;
    private long totalSubmittedTasks;
    private long totalUploadResults;
    private long totalTerrainNanos;
    private long totalBuildSubmitNanos;
    private long totalUploadNanos;

    public TerrainSchedulingController(final boolean enabled, final int warmupFrames) {
        this(enabled, warmupFrames, null);
    }

    public TerrainSchedulingController(
            final boolean enabled,
            final int warmupFrames,
            final TerrainSchedulingTelemetry telemetry
    ) {
        if (warmupFrames < 0) {
            throw new IllegalArgumentException("warmupFrames must be non-negative");
        }
        this.enabled = enabled;
        this.warmupFrames = warmupFrames;
        this.telemetry = telemetry;
        if (telemetry != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(telemetry::close, "metallum-terrain-telemetry-close"));
        }
    }

    public static TerrainSchedulingController runtime() {
        return RUNTIME;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean observesFrames() {
        return enabled || telemetry != null;
    }

    public synchronized void beginCpuFrame(final long nowNanos) {
        if (!observesFrames()) {
            return;
        }
        cpuFrameStartNanos = nowNanos;
    }

    public synchronized void endCpuFrame(final long nowNanos) {
        if (!observesFrames() || cpuFrameStartNanos <= 0L || nowNanos <= cpuFrameStartNanos) {
            return;
        }
        lastCpuFrameNanos = clampNanos(nowNanos - cpuFrameStartNanos);
    }

    public synchronized long latestCpuFrameNanos() {
        return lastCpuFrameNanos;
    }

    /** Returns the index that the next observed frame will receive. */
    public synchronized long nextFrameIndex() {
        return frameIndex + 1L;
    }

    public synchronized void beginFrame(
            final long nowNanos,
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final double forwardX,
            final double forwardY,
            final double forwardZ,
            final FrameInputs initialInputs
    ) {
        if (!observesFrames()) {
            return;
        }
        frameIndex++;
        frames++;
        terrainStartNanos = nowNanos;
        buildStartNanos = 0L;
        uploadStartNanos = 0L;
        terrainNanos = 0L;
        buildSubmitNanos = 0L;
        uploadNanos = 0L;
        submittedTasks = 0;
        uploadResults = 0;
        turnDetected = false;
        this.cameraX = cameraX;
        this.cameraY = cameraY;
        this.cameraZ = cameraZ;
        inputs = initialInputs == null ? FrameInputs.neutral() : initialInputs;

        double length = Math.sqrt(forwardX * forwardX + forwardY * forwardY + forwardZ * forwardZ);
        if (!(length > 0.0) || !Double.isFinite(length)) {
            decision = computeDecision();
            return;
        }
        double normalizedX = forwardX / length;
        double normalizedY = forwardY / length;
        double normalizedZ = forwardZ / length;
        if (hasForward) {
            double dot = clampUnit(
                    this.forwardX * normalizedX
                            + this.forwardY * normalizedY
                            + this.forwardZ * normalizedZ
            );
            if (dot < MEANINGFUL_TURN_DOT) {
                forwardBoostFrames = FORWARD_BOOST_FRAMES;
                turnDetected = true;
                turnCount++;
            } else if (forwardBoostFrames > 0) {
                forwardBoostFrames--;
            }
        }
        hasForward = true;
        this.forwardX = normalizedX;
        this.forwardY = normalizedY;
        this.forwardZ = normalizedZ;
        decision = computeDecision();
        if (decision.forwardBoost()) {
            forwardBoostFrameCount++;
        }
    }

    public synchronized void updateBacklog(
            final int backlogJobs,
            final int busyThreads,
            final int totalThreads
    ) {
        if (!observesFrames()) {
            return;
        }
        inputs = inputs.withQueue(backlogJobs, busyThreads, totalThreads);
        updatePressure(rawPressure(inputs));
        decision = computeDecision();
        if (decision.pressureLevel() > 0 && pressureFrameCounted != frameIndex) {
            pressureFrames++;
            pressureFrameCounted = frameIndex;
        }
    }

    public synchronized long overrideBuildBudget(final long sodiumBudget) {
        return decision.adaptive() ? decision.buildBudgetNanos() : sodiumBudget;
    }

    public synchronized long overrideUploadBudget(final long sodiumBudget) {
        return decision.adaptive() ? decision.uploadBudgetNanos() : sodiumBudget;
    }

    public synchronized void beginBuildStage(final long nowNanos) {
        if (observesFrames()) {
            buildStartNanos = nowNanos;
        }
    }

    public synchronized void endBuildStage(final long nowNanos, final int submittedTasks) {
        if (!observesFrames() || buildStartNanos <= 0L || nowNanos <= buildStartNanos) {
            return;
        }
        buildSubmitNanos = clampNanos(nowNanos - buildStartNanos);
        this.submittedTasks = Math.max(0, submittedTasks);
        buildStageCount++;
        totalBuildSubmitNanos = saturatedAdd(totalBuildSubmitNanos, buildSubmitNanos);
        totalSubmittedTasks = saturatedAdd(totalSubmittedTasks, this.submittedTasks);
    }

    public synchronized void beginUploadStage(final long nowNanos) {
        if (observesFrames()) {
            uploadStartNanos = nowNanos;
        }
    }

    public synchronized void endUploadStage(final long nowNanos) {
        if (!observesFrames() || uploadStartNanos <= 0L || nowNanos <= uploadStartNanos) {
            return;
        }
        uploadNanos = clampNanos(nowNanos - uploadStartNanos);
        uploadStageCount++;
        totalUploadNanos = saturatedAdd(totalUploadNanos, uploadNanos);
    }

    public synchronized void recordUploadResults(final int count) {
        if (observesFrames()) {
            uploadResults = Math.max(0, count);
            totalUploadResults = saturatedAdd(totalUploadResults, uploadResults);
        }
    }

    public synchronized void endFrame(final long nowNanos) {
        if (!observesFrames() || terrainStartNanos <= 0L) {
            return;
        }
        terrainNanos = nowNanos > terrainStartNanos ? clampNanos(nowNanos - terrainStartNanos) : 0L;
        totalTerrainNanos = saturatedAdd(totalTerrainNanos, terrainNanos);
        lastSnapshot = new FrameSnapshot(
                frameIndex,
                decision,
                inputs,
                inputs.presentationPacing(),
                terrainNanos,
                buildSubmitNanos,
                uploadNanos,
                submittedTasks,
                uploadResults,
                turnDetected
        );
        if (telemetry != null) {
            telemetry.append(lastSnapshot);
        }
    }

    public synchronized boolean shouldBoostForward(
            final double sectionCenterX,
            final double sectionCenterY,
            final double sectionCenterZ,
            final float originalDistanceSquared,
            final int renderDistanceChunks
    ) {
        if (!decision.adaptive() || !decision.forwardBoost() || renderDistanceChunks <= 0) {
            return false;
        }
        double dx = sectionCenterX - cameraX;
        double dy = sectionCenterY - cameraY;
        double dz = sectionCenterZ - cameraZ;
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        double original = Math.max(0.0, originalDistanceSquared);
        if (!(distanceSquared > original)) {
            return false;
        }
        double renderDistance = renderDistanceChunks * 16.0 + 16.0;
        double maximumDistanceSquared = renderDistance * renderDistance;
        double forwardWindowSquared = original + FORWARD_EXTRA_DISTANCE * FORWARD_EXTRA_DISTANCE;
        if (distanceSquared > Math.min(maximumDistanceSquared, forwardWindowSquared)) {
            return false;
        }
        double length = Math.sqrt(distanceSquared);
        if (!(length > 0.0)) {
            return false;
        }
        double dot = (dx * forwardX + dy * forwardY + dz * forwardZ) / length;
        return dot >= FORWARD_PRIORITY_DOT;
    }

    public synchronized FrameDecision decision() {
        return decision;
    }

    public synchronized FrameSnapshot lastSnapshot() {
        return lastSnapshot;
    }

    public synchronized Counters counters() {
        return new Counters(
                frames,
                adaptiveFrames,
                turnCount,
                forwardBoostFrameCount,
                pressureFrames,
                buildStageCount,
                uploadStageCount,
                totalSubmittedTasks,
                totalUploadResults,
                totalTerrainNanos,
                totalBuildSubmitNanos,
                totalUploadNanos
        );
    }

    private FrameDecision computeDecision() {
        boolean adaptive = enabled && frameIndex > warmupFrames;
        if (!adaptive) {
            return new FrameDecision(frameIndex, false, 0L, 0L, pressureLevel, false, turnDetected);
        }
        if (adaptiveFrameCounted != frameIndex) {
            adaptiveFrames++;
            adaptiveFrameCounted = frameIndex;
        }
        long frameNanos = inputs.frameDurationNanos() > 0L
                ? inputs.frameDurationNanos()
                : TARGET_FRAME_NANOS;
        long buildBudget = clampNanos(Math.round(frameNanos * 0.10), 1_500_000L, 8_000_000L);
        long uploadBudget = clampNanos(Math.round(frameNanos * 0.08), 2_000_000L, 8_000_000L);
        double multiplier = switch (pressureLevel) {
            case 2 -> 0.50;
            case 1 -> 0.75;
            default -> 1.0;
        };
        buildBudget = clampNanos(Math.round(buildBudget * multiplier), 750_000L, 8_000_000L);
        uploadBudget = clampNanos(Math.round(uploadBudget * multiplier), 1_000_000L, 8_000_000L);
        return new FrameDecision(
                frameIndex,
                true,
                buildBudget,
                uploadBudget,
                pressureLevel,
                forwardBoostFrames > 0,
                turnDetected
        );
    }

    private int rawPressure(final FrameInputs value) {
        int pressure = 0;
        if (value.backlogJobs() >= BACKLOG_SEVERE
                || above(value.cpuFrameNanos(), TARGET_FRAME_NANOS, 1.60)
                || above(value.gpuFrameNanos(), TARGET_FRAME_NANOS, 1.60)
                || value.thermalState() >= 3
                || value.memoryPressure() >= 0.92) {
            pressure = 2;
        } else if (value.backlogJobs() >= BACKLOG_CONSTRAINED
                || above(value.cpuFrameNanos(), TARGET_FRAME_NANOS, 1.20)
                || above(value.gpuFrameNanos(), TARGET_FRAME_NANOS, 1.20)
                || value.thermalState() >= 2
                || value.memoryPressure() >= 0.80) {
            pressure = 1;
        }
        return pressure;
    }

    private void updatePressure(final int rawPressure) {
        if (rawPressure > pressureLevel) {
            pressureRiseSamples++;
            pressureRecoverySamples = 0;
            if (pressureRiseSamples >= PRESSURE_RISE_SAMPLES) {
                pressureLevel = rawPressure;
                pressureRiseSamples = 0;
            }
        } else if (rawPressure < pressureLevel) {
            pressureRecoverySamples++;
            pressureRiseSamples = 0;
            if (pressureRecoverySamples >= PRESSURE_RECOVERY_SAMPLES) {
                pressureLevel = rawPressure;
                pressureRecoverySamples = 0;
            }
        } else {
            pressureRiseSamples = 0;
            pressureRecoverySamples = 0;
        }
    }

    private static boolean above(final long value, final long target, final double ratio) {
        return value > 0L && value > Math.round(target * ratio);
    }

    private static long clampNanos(final long value, final long minimum, final long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long clampNanos(final long value) {
        return Math.max(0L, value);
    }

    private static double clampUnit(final double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }

    private static long saturatedAdd(final long left, final long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + Math.max(0L, right);
    }

    private static TerrainSchedulingController createRuntime() {
        boolean enabled = Boolean.parseBoolean(System.getProperty(ADAPTIVE_PROPERTY, "false"));
        boolean telemetryEnabled = Boolean.parseBoolean(System.getProperty(TELEMETRY_PROPERTY, "false"));
        String configuredPath = System.getProperty(CSV_PROPERTY, "").trim();
        TerrainSchedulingTelemetry telemetry = null;
        if (telemetryEnabled || !configuredPath.isEmpty()) {
            Path path = configuredPath.isEmpty()
                    ? FabricLoader.getInstance().getGameDir().resolve("metallum-terrain-scheduling.csv")
                    : Path.of(configuredPath);
            telemetry = new TerrainSchedulingTelemetry(path);
        }
        return new TerrainSchedulingController(enabled, DEFAULT_WARMUP_FRAMES, telemetry);
    }

    public record FrameInputs(
            long frameDurationNanos,
            long cpuFrameNanos,
            long gpuFrameNanos,
            int backlogJobs,
            int busyThreads,
            int totalThreads,
            int thermalState,
            double memoryPressure,
            PresentationPacingSnapshot presentationPacing
    ) {
        public FrameInputs(
                final long frameDurationNanos,
                final long cpuFrameNanos,
                final long gpuFrameNanos,
                final int backlogJobs,
                final int busyThreads,
                final int totalThreads,
                final int thermalState,
                final double memoryPressure
        ) {
            this(
                    frameDurationNanos,
                    cpuFrameNanos,
                    gpuFrameNanos,
                    backlogJobs,
                    busyThreads,
                    totalThreads,
                    thermalState,
                    memoryPressure,
                    PresentationPacingSnapshot.neutral()
            );
        }

        public FrameInputs {
            frameDurationNanos = Math.max(0L, frameDurationNanos);
            cpuFrameNanos = Math.max(0L, cpuFrameNanos);
            gpuFrameNanos = gpuFrameNanos <= 0L ? -1L : gpuFrameNanos;
            backlogJobs = Math.max(0, backlogJobs);
            busyThreads = Math.max(0, busyThreads);
            totalThreads = Math.max(0, totalThreads);
            thermalState = thermalState < 0 ? -1 : Math.min(3, thermalState);
            memoryPressure = Double.isFinite(memoryPressure)
                    ? Math.max(0.0, Math.min(1.0, memoryPressure))
                    : 0.0;
            presentationPacing = presentationPacing == null
                    ? PresentationPacingSnapshot.neutral()
                    : presentationPacing;
        }

        public static FrameInputs neutral() {
            return new FrameInputs(
                    TARGET_FRAME_NANOS,
                    0L,
                    -1L,
                    0,
                    0,
                    0,
                    -1,
                    0.0,
                    PresentationPacingSnapshot.neutral()
            );
        }

        private FrameInputs withQueue(final int backlog, final int busy, final int total) {
            return new FrameInputs(
                    frameDurationNanos,
                    cpuFrameNanos,
                    gpuFrameNanos,
                    backlog,
                    busy,
                    total,
                    thermalState,
                    memoryPressure,
                    presentationPacing
            );
        }
    }

    public record FrameDecision(
            long frameIndex,
            boolean adaptive,
            long buildBudgetNanos,
            long uploadBudgetNanos,
            int pressureLevel,
            boolean forwardBoost,
            boolean turnDetected
    ) {
        private static FrameDecision defaults() {
            return new FrameDecision(0L, false, 0L, 0L, 0, false, false);
        }

        public boolean usesSodiumDefaults() {
            return !adaptive;
        }
    }

    public record FrameSnapshot(
            long frameIndex,
            FrameDecision decision,
            FrameInputs inputs,
            PresentationPacingSnapshot presentationPacing,
            long terrainNanos,
            long buildSubmitNanos,
            long uploadNanos,
            int submittedTasks,
            int uploadResults,
            boolean turnDetected
    ) {
        private static FrameSnapshot empty() {
            return new FrameSnapshot(
                    0L,
                    FrameDecision.defaults(),
                    FrameInputs.neutral(),
                    PresentationPacingSnapshot.neutral(),
                    0L,
                    0L,
                    0L,
                    0,
                    0,
                    false
            );
        }

        String toCsv() {
            return String.join(",",
                    Long.toString(frameIndex),
                    Boolean.toString(decision.adaptive()),
                    Integer.toString(decision.pressureLevel()),
                    Long.toString(decision.buildBudgetNanos()),
                    Long.toString(decision.uploadBudgetNanos()),
                    Integer.toString(inputs.backlogJobs()),
                    Integer.toString(inputs.busyThreads()),
                    Integer.toString(inputs.totalThreads()),
                    Integer.toString(inputs.thermalState()),
                    TerrainSchedulingTelemetry.csvDouble(inputs.memoryPressure()),
                    Long.toString(inputs.cpuFrameNanos()),
                    Long.toString(inputs.gpuFrameNanos()),
                    Long.toString(terrainNanos),
                    Long.toString(buildSubmitNanos),
                    Long.toString(uploadNanos),
                    Integer.toString(submittedTasks),
                    Integer.toString(uploadResults),
                    Boolean.toString(decision.forwardBoost()),
                    Boolean.toString(turnDetected),
                    Long.toString(presentationPacing.targetPresentInterval().value()),
                    Boolean.toString(presentationPacing.targetPresentInterval().measured()),
                    csvValue(presentationPacing.targetPresentInterval().provenance()),
                    csvValue(presentationPacing.targetPresentInterval().fallbackReason()),
                    Long.toString(presentationPacing.measuredPresentInterval().value()),
                    Boolean.toString(presentationPacing.measuredPresentInterval().available()),
                    csvValue(presentationPacing.measuredPresentInterval().provenance()),
                    csvValue(presentationPacing.measuredPresentInterval().fallbackReason()),
                    Long.toString(presentationPacing.drawableWait().value()),
                    Boolean.toString(presentationPacing.drawableWait().available()),
                    Long.toString(presentationPacing.framesInFlight().value()),
                    Boolean.toString(presentationPacing.framesInFlight().available()),
                    csvValue(presentationPacing.provenance()),
                    csvValue(presentationPacing.fallbackReason())
            );
        }

        private static String csvValue(final String value) {
            if (value == null) {
                return "";
            }
            return value.replace(',', ';').replace('\n', ' ').replace('\r', ' ');
        }
    }

    public record Counters(
            long frames,
            long adaptiveFrames,
            long turns,
            long forwardBoostFrames,
            long pressureFrames,
            long buildStageCount,
            long uploadStageCount,
            long submittedTasks,
            long uploadResults,
            long terrainNanos,
            long buildSubmitNanos,
            long uploadNanos
    ) {
    }
}
