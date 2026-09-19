package me.essde.essguard;

import org.bukkit.Location;
import org.bukkit.util.Vector;

/**
 * Per-player state used by the observation engine.
 * The data is intentionally server-side only; no client trust is used as proof.
 */
public final class PlayerData {
    private static final int HISTORY = 32;

    private Location lastLocation;
    private Location previousLocation;
    private Vector lastVelocity = new Vector();

    private long lastTeleportMs;
    private long lastVelocityMs;
    private long lastDamageMs;
    private long lastJoinMs;
    private long graceUntilMs;
    private long lastSampleNs;
    private int sampleTick;

    private int airborneTicks;
    private int hoverTicks;
    private int suspiciousVerticalTicks;
    private int waterSurfaceTicks;

    private int speedWindowTicks;
    private int speedOverspeedTicks;
    private double speedWindowDistance;
    private double speedWindowAllowedDistance;
    private int severeSpeedTicks;

    private int jumpTicks;
    private int sinceGroundTicks;
    private int groundTransitions;
    private int stableAirTicks;
    private int verticalAnomalyTicks;
    private int impossibleRiseTicks;

    private int jesusCandidateTicks;
    private int jesusStableTicks;

    private double lastDx;
    private double lastDy;
    private double lastDz;
    private double lastHorizontal;
    private double lastY;
    private double lastVerticalVelocity;
    private boolean lastOnGround;
    private boolean lastInWater;
    private boolean lastSwimming;
    private boolean initialized;

    private final double[] horizontalHistory = new double[HISTORY];
    private final double[] verticalHistory = new double[HISTORY];
    private final boolean[] groundHistory = new boolean[HISTORY];
    private final boolean[] waterHistory = new boolean[HISTORY];
    private int historySize;
    private int historyIndex;

    public void initialize(Location location, long nowNs, int tick) {
        this.lastLocation = location == null ? null : location.clone();
        this.previousLocation = location == null ? null : location.clone();
        this.lastY = location == null ? 0D : location.getY();
        this.lastSampleNs = nowNs;
        this.sampleTick = tick;
        this.lastOnGround = true;
        this.historySize = 0;
        this.historyIndex = 0;
        this.initialized = true;
    }

    public boolean isInitialized() { return initialized; }
    public Location getLastLocation() { return lastLocation; }
    public void setLastLocation(Location value) { this.lastLocation = value == null ? null : value.clone(); }
    public Location getPreviousLocation() { return previousLocation; }
    public void setPreviousLocation(Location value) { this.previousLocation = value == null ? null : value.clone(); }
    public Vector getLastVelocity() { return lastVelocity; }
    public void setLastVelocity(Vector value) { this.lastVelocity = value == null ? new Vector() : value.clone(); }
    public long getLastTeleportMs() { return lastTeleportMs; }
    public void setLastTeleportMs(long value) { this.lastTeleportMs = value; }
    public long getLastVelocityMs() { return lastVelocityMs; }
    public void setLastVelocityMs(long value) { this.lastVelocityMs = value; }
    public long getLastDamageMs() { return lastDamageMs; }
    public void setLastDamageMs(long value) { this.lastDamageMs = value; }
    public long getLastJoinMs() { return lastJoinMs; }
    public void setLastJoinMs(long value) { this.lastJoinMs = value; }
    public long getGraceUntilMs() { return graceUntilMs; }
    public void setGraceUntilMs(long value) { this.graceUntilMs = value; }
    public long getLastSampleNs() { return lastSampleNs; }
    public void setLastSampleNs(long value) { this.lastSampleNs = value; }
    public int getSampleTick() { return sampleTick; }
    public void setSampleTick(int value) { this.sampleTick = value; }

    public int getAirborneTicks() { return airborneTicks; }
    public void setAirborneTicks(int value) { this.airborneTicks = value; }
    public int getHoverTicks() { return hoverTicks; }
    public void setHoverTicks(int value) { this.hoverTicks = value; }
    public int getSuspiciousVerticalTicks() { return suspiciousVerticalTicks; }
    public void setSuspiciousVerticalTicks(int value) { this.suspiciousVerticalTicks = value; }
    public int getWaterSurfaceTicks() { return waterSurfaceTicks; }
    public void setWaterSurfaceTicks(int value) { this.waterSurfaceTicks = value; }

    public int getSpeedWindowTicks() { return speedWindowTicks; }
    public void setSpeedWindowTicks(int value) { this.speedWindowTicks = value; }
    public int getSpeedOverspeedTicks() { return speedOverspeedTicks; }
    public void setSpeedOverspeedTicks(int value) { this.speedOverspeedTicks = value; }
    public double getSpeedWindowDistance() { return speedWindowDistance; }
    public void setSpeedWindowDistance(double value) { this.speedWindowDistance = value; }
    public double getSpeedWindowAllowedDistance() { return speedWindowAllowedDistance; }
    public void setSpeedWindowAllowedDistance(double value) { this.speedWindowAllowedDistance = value; }
    public int getSevereSpeedTicks() { return severeSpeedTicks; }
    public void setSevereSpeedTicks(int value) { this.severeSpeedTicks = value; }

    public int getJumpTicks() { return jumpTicks; }
    public void setJumpTicks(int value) { this.jumpTicks = value; }
    public int getSinceGroundTicks() { return sinceGroundTicks; }
    public void setSinceGroundTicks(int value) { this.sinceGroundTicks = value; }
    public int getGroundTransitions() { return groundTransitions; }
    public void setGroundTransitions(int value) { this.groundTransitions = value; }
    public int getStableAirTicks() { return stableAirTicks; }
    public void setStableAirTicks(int value) { this.stableAirTicks = value; }
    public int getVerticalAnomalyTicks() { return verticalAnomalyTicks; }
    public void setVerticalAnomalyTicks(int value) { this.verticalAnomalyTicks = value; }
    public int getImpossibleRiseTicks() { return impossibleRiseTicks; }
    public void setImpossibleRiseTicks(int value) { this.impossibleRiseTicks = value; }

    public int getJesusCandidateTicks() { return jesusCandidateTicks; }
    public void setJesusCandidateTicks(int value) { this.jesusCandidateTicks = value; }
    public int getJesusStableTicks() { return jesusStableTicks; }
    public void setJesusStableTicks(int value) { this.jesusStableTicks = value; }

    public double getLastDx() { return lastDx; }
    public void setLastDx(double value) { this.lastDx = value; }
    public double getLastDy() { return lastDy; }
    public void setLastDy(double value) { this.lastDy = value; }
    public double getLastDz() { return lastDz; }
    public void setLastDz(double value) { this.lastDz = value; }
    public double getLastHorizontal() { return lastHorizontal; }
    public void setLastHorizontal(double value) { this.lastHorizontal = value; }
    public double getLastY() { return lastY; }
    public void setLastY(double value) { this.lastY = value; }
    public double getLastVerticalVelocity() { return lastVerticalVelocity; }
    public void setLastVerticalVelocity(double value) { this.lastVerticalVelocity = value; }
    public boolean isLastOnGround() { return lastOnGround; }
    public void setLastOnGround(boolean value) { this.lastOnGround = value; }
    public boolean isLastInWater() { return lastInWater; }
    public void setLastInWater(boolean value) { this.lastInWater = value; }
    public boolean isLastSwimming() { return lastSwimming; }
    public void setLastSwimming(boolean value) { this.lastSwimming = value; }

    public void recordSample(double horizontal, double vertical, boolean onGround, boolean inWater) {
        horizontalHistory[historyIndex] = horizontal;
        verticalHistory[historyIndex] = vertical;
        groundHistory[historyIndex] = onGround;
        waterHistory[historyIndex] = inWater;
        historyIndex = (historyIndex + 1) % HISTORY;
        if (historySize < HISTORY) historySize++;
    }

    public int getHistorySize() { return historySize; }

    public double recentHorizontalAverage(int ticks) {
        int count = Math.min(ticks, historySize);
        if (count == 0) return 0D;
        double sum = 0D;
        for (int i = 0; i < count; i++) {
            int idx = (historyIndex - 1 - i + HISTORY) % HISTORY;
            sum += horizontalHistory[idx];
        }
        return sum / count;
    }

    public double recentVerticalAverage(int ticks) {
        int count = Math.min(ticks, historySize);
        if (count == 0) return 0D;
        double sum = 0D;
        for (int i = 0; i < count; i++) {
            int idx = (historyIndex - 1 - i + HISTORY) % HISTORY;
            sum += verticalHistory[idx];
        }
        return sum / count;
    }

    public int countGroundSamples(int ticks) {
        int count = Math.min(ticks, historySize);
        int result = 0;
        for (int i = 0; i < count; i++) {
            int idx = (historyIndex - 1 - i + HISTORY) % HISTORY;
            if (groundHistory[idx]) result++;
        }
        return result;
    }

    public int countWaterSamples(int ticks) {
        int count = Math.min(ticks, historySize);
        int result = 0;
        for (int i = 0; i < count; i++) {
            int idx = (historyIndex - 1 - i + HISTORY) % HISTORY;
            if (waterHistory[idx]) result++;
        }
        return result;
    }

    public void resetMovementEvidence() {
        airborneTicks = 0;
        hoverTicks = 0;
        suspiciousVerticalTicks = 0;
        waterSurfaceTicks = 0;
        speedWindowTicks = 0;
        speedOverspeedTicks = 0;
        speedWindowDistance = 0D;
        speedWindowAllowedDistance = 0D;
        severeSpeedTicks = 0;
        jumpTicks = 0;
        sinceGroundTicks = 0;
        groundTransitions = 0;
        stableAirTicks = 0;
        verticalAnomalyTicks = 0;
        impossibleRiseTicks = 0;
        jesusCandidateTicks = 0;
        jesusStableTicks = 0;
        lastDx = 0D;
        lastDy = 0D;
        lastDz = 0D;
        lastHorizontal = 0D;
        lastVerticalVelocity = 0D;
        historySize = 0;
        historyIndex = 0;
    }
}
