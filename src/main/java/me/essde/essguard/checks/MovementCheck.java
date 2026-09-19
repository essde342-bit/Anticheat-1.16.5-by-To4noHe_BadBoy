package me.essde.essguard.checks;

import me.essde.essguard.EssGuard;
import me.essde.essguard.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * High-confidence, server-side movement checks for Paper 1.16.5.
 *
 * This intentionally does NOT try to recreate the entire Minecraft physics engine.
 * Bukkit/Paper does not expose incoming movement packets or the exact client state.
 * Instead it combines several conservative signals over time and aggressively
 * resets evidence around lag, teleports, velocity and unusual collision media.
 */
public final class MovementCheck {
    private final EssGuard plugin;

    public MovementCheck(EssGuard plugin) {
        this.plugin = plugin;
    }

    public void sample(Player p, int serverTick, long nowNs, boolean serverLag) {
        if (!p.isOnline() || p.isDead()) return;
        if (p.getPing() > plugin.getConfig().getInt("safety.max-ping-ms-for-movement", 450)) {
            PlayerData highPingData = plugin.getData(p);
            resetSoft(highPingData, p.getLocation());
            return;
        }
        if (!plugin.getConfig().getBoolean("checks.speed.enabled", true)
                && !plugin.getConfig().getBoolean("checks.fly.enabled", true)
                && !plugin.getConfig().getBoolean("checks.jesus.enabled", true)) return;
        PlayerData pd = plugin.getData(p);
        Location current = p.getLocation();

        if (!pd.isInitialized()) {
            pd.initialize(current, nowNs, serverTick);
            return;
        }

        Location previous = pd.getLastLocation();
        if (previous == null || previous.getWorld() != current.getWorld()) {
            pd.initialize(current, nowNs, serverTick);
            return;
        }

        long elapsedMs = (nowNs - pd.getLastSampleNs()) / 1_000_000L;
        pd.setLastSampleNs(nowNs);
        pd.setSampleTick(serverTick);

        if (serverLag || elapsedMs > plugin.getConfig().getLong("safety.max-sample-gap-ms", 130L)) {
            resetSoft(pd, current);
            return;
        }

        if (plugin.isInGrace(p) || isGlobalExempt(p)) {
            resetSoft(pd, current);
            return;
        }

        double dx = current.getX() - previous.getX();
        double dy = current.getY() - previous.getY();
        double dz = current.getZ() - previous.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        boolean inWater = isWaterContact(p, current);
        boolean onGround = hasConservativeGroundSupport(p, current);
        pd.recordSample(horizontal, dy, onGround, inWater);

        if (onGround && !pd.isLastOnGround()) {
            pd.setGroundTransitions(pd.getGroundTransitions() + 1);
            pd.setJumpTicks(0);
            pd.setSinceGroundTicks(0);
        } else if (!onGround) {
            pd.setSinceGroundTicks(pd.getSinceGroundTicks() + 1);
        }

        if (!onGround) pd.setAirborneTicks(pd.getAirborneTicks() + 1);
        else pd.setAirborneTicks(0);

        if (plugin.getConfig().getBoolean("checks.speed.enabled", true)) {
            speed(p, pd, previous, current, horizontal, onGround);
        } else {
            clearSpeed(pd);
        }
        if (plugin.getConfig().getBoolean("checks.fly.enabled", true)) {
            fly(p, pd, previous, current, dy, horizontal, onGround, inWater);
        } else {
            clearFly(pd);
        }
        if (plugin.getConfig().getBoolean("checks.jesus.enabled", true)) {
            jesus(p, pd, previous, current, dy, horizontal, onGround, inWater);
        } else {
            clearJesus(pd);
        }

        pd.setLastDx(dx);
        pd.setLastDy(dy);
        pd.setLastDz(dz);
        pd.setLastHorizontal(horizontal);
        pd.setLastY(current.getY());
        pd.setLastVerticalVelocity(dy);
        pd.setLastOnGround(onGround);
        pd.setLastInWater(inWater);
        pd.setLastSwimming(p.isSwimming());
        pd.setPreviousLocation(previous);
        pd.setLastLocation(current);
    }

    private void speed(Player p, PlayerData pd, Location from, Location to, double horizontal, boolean onGround) {
        if (speedExempt(p, from, to)) {
            clearSpeed(pd);
            return;
        }

        // Speed check is deliberately strongest on normal ground.
        // Airborne movement is affected by jumping physics and is therefore used only
        // as supporting evidence, never as a one-tick verdict.
        if (!onGround && pd.getSinceGroundTicks() > 12) {
            pd.setSpeedWindowTicks(0);
            pd.setSpeedWindowDistance(0D);
            pd.setSpeedWindowAllowedDistance(0D);
            pd.setSpeedOverspeedTicks(0);
            pd.setSevereSpeedTicks(0);
            return;
        }

        double allowed = vanillaHorizontalPerTick(p, from.getBlock(), to.getBlock(), onGround);
        pd.setSpeedWindowTicks(pd.getSpeedWindowTicks() + 1);
        pd.setSpeedWindowDistance(pd.getSpeedWindowDistance() + horizontal);
        pd.setSpeedWindowAllowedDistance(pd.getSpeedWindowAllowedDistance() + allowed);

        double ratio = horizontal / Math.max(0.001D, allowed);
        double severeRatio = plugin.getConfig().getDouble("checks.speed.severe-ratio", 1.38D);
        double mildRatio = plugin.getConfig().getDouble("checks.speed.mild-ratio", 1.16D);

        if (ratio >= severeRatio && horizontal >= plugin.getConfig().getDouble("checks.speed.min-severe-step", 0.33D)) {
            pd.setSevereSpeedTicks(pd.getSevereSpeedTicks() + 1);
        } else {
            pd.setSevereSpeedTicks(Math.max(0, pd.getSevereSpeedTicks() - 1));
        }

        if (ratio >= mildRatio) {
            pd.setSpeedOverspeedTicks(pd.getSpeedOverspeedTicks() + 1);
        } else {
            pd.setSpeedOverspeedTicks(Math.max(0, pd.getSpeedOverspeedTicks() - 1));
        }

        int window = plugin.getConfig().getInt("checks.speed.window-ticks", 10);
        if (pd.getSpeedWindowTicks() >= window) {
            double observed = pd.getSpeedWindowDistance() / window;
            double limit = pd.getSpeedWindowAllowedDistance() / window;
            double ratioWindow = observed / Math.max(0.001D, limit);
            boolean sustained = ratioWindow >= plugin.getConfig().getDouble("checks.speed.window-ratio", 1.18D)
                    && pd.getSpeedOverspeedTicks() >= plugin.getConfig().getInt("checks.speed.min-mild-samples", 6);
            boolean strong = ratioWindow >= plugin.getConfig().getDouble("checks.speed.strong-window-ratio", 1.30D)
                    && pd.getSevereSpeedTicks() >= plugin.getConfig().getInt("checks.speed.min-severe-samples", 3);

            if (strong || sustained) {
                double points = strong ? 1.8D : 0.9D;
                plugin.getViolations().flag(p, "Speed", points,
                        String.format("window=%.3fx limit=%.3f severe=%d/%d", ratioWindow, limit, pd.getSevereSpeedTicks(), window));
            } else {
                plugin.getViolations().reward(p, "Speed", 0.50D);
            }

            clearSpeed(pd);
        }
    }

    private void fly(Player p, PlayerData pd, Location from, Location to, double dy, double horizontal,
                     boolean onGround, boolean inWater) {
        if (flightExempt(p, from, to) || inWater) {
            clearFly(pd);
            return;
        }

        if (onGround) {
            clearFly(pd);
            return;
        }

        int air = pd.getAirborneTicks();
        boolean lowVerticalMotion = Math.abs(dy) <= plugin.getConfig().getDouble("checks.fly.hover-dy", 0.017D);
        boolean horizontalMotion = horizontal >= plugin.getConfig().getDouble("checks.fly.hover-min-horizontal", 0.075D);

        if (lowVerticalMotion && horizontalMotion) {
            pd.setHoverTicks(pd.getHoverTicks() + 1);
        } else {
            pd.setHoverTicks(Math.max(0, pd.getHoverTicks() - 1));
        }

        // Conservative vertical envelope. We only accumulate when the observed
        // motion is repeatedly incompatible with the expected gravity direction.
        double expected = pd.getLastDy() * 0.98D - 0.08D;
        double residual = Math.abs(dy - expected);
        boolean residualLarge = residual >= plugin.getConfig().getDouble("checks.fly.physics-residual", 0.16D);
        if (air >= 4 && residualLarge) {
            pd.setSuspiciousVerticalTicks(pd.getSuspiciousVerticalTicks() + 1);
        } else {
            pd.setSuspiciousVerticalTicks(Math.max(0, pd.getSuspiciousVerticalTicks() - 1));
        }

        boolean impossibleRise = dy > plugin.getConfig().getDouble("checks.fly.max-natural-rise-step", 0.48D)
                && pd.getLastDy() > -0.1D
                && !hasJumpBoost(p)
                && air <= 6;
        if (impossibleRise) {
            pd.setImpossibleRiseTicks(pd.getImpossibleRiseTicks() + 1);
        } else {
            pd.setImpossibleRiseTicks(Math.max(0, pd.getImpossibleRiseTicks() - 1));
        }

        int hoverNeed = plugin.getConfig().getInt("checks.fly.hover-ticks", 14);
        int residualNeed = plugin.getConfig().getInt("checks.fly.residual-ticks", 6);
        int riseNeed = plugin.getConfig().getInt("checks.fly.rise-ticks", 3);

        boolean hover = pd.getHoverTicks() >= hoverNeed && air >= hoverNeed;
        boolean physics = pd.getSuspiciousVerticalTicks() >= residualNeed && air >= residualNeed + 2;
        boolean rise = pd.getImpossibleRiseTicks() >= riseNeed;

        // Two-signal rule for normal Fly. Extreme repeated rises may stand alone.
        boolean highConfidence = (hover && physics) || (hover && rise) || (physics && rise);
        boolean extreme = rise && pd.getImpossibleRiseTicks() >= riseNeed + 2;

        if (highConfidence || extreme) {
            double points = highConfidence ? 1.7D : 1.2D;
            plugin.getViolations().flag(p, "Fly", points,
                    String.format("air=%d hover=%d residual=%d rise=%d dy=%.3f", air,
                            pd.getHoverTicks(), pd.getSuspiciousVerticalTicks(), pd.getImpossibleRiseTicks(), dy));
            pd.setHoverTicks(Math.max(0, pd.getHoverTicks() - 5));
            pd.setSuspiciousVerticalTicks(Math.max(0, pd.getSuspiciousVerticalTicks() - 2));
            pd.setImpossibleRiseTicks(Math.max(0, pd.getImpossibleRiseTicks() - 1));
        } else {
            plugin.getViolations().reward(p, "Fly", 0.35D);
        }
    }

    private void jesus(Player p, PlayerData pd, Location from, Location to, double dy, double horizontal,
                       boolean onGround, boolean inWater) {
        if (jesusExempt(p, from, to)) {
            clearJesus(pd);
            return;
        }

        if (inWater || p.isSwimming()) {
            clearJesus(pd);
            return;
        }

        Block below = to.clone().subtract(0, 0.08D, 0).getBlock();
        Block feet = to.getBlock();
        boolean waterBelow = isWaterLike(below.getType());
        boolean feetPassable = feet.isPassable();
        boolean moving = horizontal >= plugin.getConfig().getDouble("checks.jesus.min-horizontal", 0.075D);
        boolean stableY = Math.abs(dy) <= plugin.getConfig().getDouble("checks.jesus.max-dy", 0.025D);
        boolean nearSurface = nearWaterSurface(to);
        boolean noSolidSupport = !hasConservativeGroundSupport(p, to);

        if (waterBelow && feetPassable && moving && stableY && nearSurface && noSolidSupport) {
            pd.setJesusCandidateTicks(pd.getJesusCandidateTicks() + 1);
        } else {
            pd.setJesusCandidateTicks(Math.max(0, pd.getJesusCandidateTicks() - 2));
        }

        if (waterBelow && stableY && Math.abs(horizontal) > 0.04D) {
            pd.setJesusStableTicks(pd.getJesusStableTicks() + 1);
        } else {
            pd.setJesusStableTicks(Math.max(0, pd.getJesusStableTicks() - 1));
        }

        int need = plugin.getConfig().getInt("checks.jesus.surface-ticks", 14);
        boolean candidate = pd.getJesusCandidateTicks() >= need;
        boolean corroborated = pd.getJesusStableTicks() >= Math.max(need - 2, 8);
        if (candidate && corroborated) {
            plugin.getViolations().flag(p, "Jesus", 1.5D,
                    String.format("surface=%d stable=%d h=%.3f", pd.getJesusCandidateTicks(), pd.getJesusStableTicks(), horizontal));
            pd.setJesusCandidateTicks(0);
            pd.setJesusStableTicks(0);
        } else {
            plugin.getViolations().reward(p, "Jesus", 0.25D);
        }
    }

    private double vanillaHorizontalPerTick(Player p, Block from, Block to, boolean onGround) {
        // A deliberately generous envelope around vanilla 1.16 movement.
        // The exact client acceleration/friction path depends on input and collision.
        double base = p.isSprinting() ? 0.2873D : 0.215D;

        PotionEffect speed = p.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null) base *= 1D + 0.20D * (speed.getAmplifier() + 1);

        PotionEffect slow = p.getPotionEffect(PotionEffectType.SLOW);
        if (slow != null) base *= Math.max(0.05D, 1D - 0.15D * (slow.getAmplifier() + 1));

        if (p.isSneaking()) base *= 0.30D;
        if (p.isBlocking()) base *= 0.45D;
        if (isSoulSand(from) || isSoulSand(to)) base *= 0.40D;

        if (!onGround) base *= 1.12D;
        return base + plugin.getConfig().getDouble("checks.speed.base-buffer", 0.045D);
    }

    private boolean speedExempt(Player p, Location from, Location to) {
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return true;
        if (p.isFlying() || p.getAllowFlight() || p.isGliding() || p.isInsideVehicle()) return true;
        if (p.isSwimming() || p.isInWater() || p.isRiptiding()) return true;
        if (recentExternalMotion(p)) return true;
        if (isClimbable(from.getBlock()) || isClimbable(to.getBlock())) return true;
        if (isSpecialSurface(from, to)) return true;
        if (isNearWeb(p, to)) return true;
        return hasSpeedAffectingEnchantment(p);
    }

    private boolean flightExempt(Player p, Location from, Location to) {
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return true;
        if (p.isFlying() || p.getAllowFlight() || p.isGliding() || p.isInsideVehicle()) return true;
        if (p.isInWater() || p.isSwimming() || p.isRiptiding()) return true;
        if (p.hasPotionEffect(PotionEffectType.LEVITATION)) return true;
        if (recentExternalMotion(p)) return true;
        if (isClimbable(from.getBlock()) || isClimbable(to.getBlock())) return true;
        if (isSpecialSurface(from, to)) return true;
        return isNearWeb(p, to);
    }

    private boolean jesusExempt(Player p, Location from, Location to) {
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return true;
        if (p.isFlying() || p.getAllowFlight() || p.isInsideVehicle()) return true;
        if (p.isGliding() || p.isRiptiding()) return true;
        if (recentExternalMotion(p)) return true;
        if (isSpecialSurface(from, to)) return true;
        return isNearWeb(p, to);
    }

    private boolean isGlobalExempt(Player p) {
        return p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR;
    }

    private boolean recentExternalMotion(Player p) {
        PlayerData pd = plugin.getData(p);
        long now = System.currentTimeMillis();
        return now - pd.getLastVelocityMs() < plugin.getConfig().getLong("safety.velocity-grace-ms", 950L)
                || now - pd.getLastDamageMs() < plugin.getConfig().getLong("safety.damage-grace-ms", 600L);
    }

    private boolean hasConservativeGroundSupport(Player p, Location location) {
        // Prefer server-side AABB geometry. The Player#isOnGround flag is client-fed
        // in 1.16.5 and is therefore only a fallback when a real block exists below.
        org.bukkit.util.BoundingBox playerBox = p.getBoundingBox();
        double minY = playerBox.getMinY();
        int baseX = location.getBlockX();
        int baseY = (int) Math.floor(minY - 0.08D);
        int baseZ = location.getBlockZ();

        boolean geometricSupport = false;
        for (int x = baseX - 1; x <= baseX + 1; x++) {
            for (int z = baseZ - 1; z <= baseZ + 1; z++) {
                Block block = location.getWorld().getBlockAt(x, baseY, z);
                if (isValidSupport(block, playerBox, minY)) {
                    geometricSupport = true;
                }
            }
        }
        if (geometricSupport) return true;

        if (!p.isOnGround()) return false;
        for (int x = baseX - 1; x <= baseX + 1; x++) {
            for (int z = baseZ - 1; z <= baseZ + 1; z++) {
                Block block = location.getWorld().getBlockAt(x, baseY, z);
                Material m = block.getType();
                if (!m.isAir() && !isWaterLike(m) && !block.isPassable()) return true;
            }
        }
        return false;
    }

    private boolean isValidSupport(Block block, org.bukkit.util.BoundingBox playerBox, double playerMinY) {
        Material m = block.getType();
        if (isWaterLike(m) || m == Material.COBWEB || m == Material.LADDER || m == Material.VINE
                || m == Material.SCAFFOLDING || m.isAir()) return false;

        org.bukkit.util.BoundingBox blockBox = block.getBoundingBox();
        if (blockBox.getVolume() <= 0.000001D) return false;

        double verticalGap = playerMinY - blockBox.getMaxY();
        if (verticalGap < -0.04D || verticalGap > 0.12D) return false;

        boolean xOverlap = playerBox.getMaxX() > blockBox.getMinX() + 0.02D
                && playerBox.getMinX() < blockBox.getMaxX() - 0.02D;
        boolean zOverlap = playerBox.getMaxZ() > blockBox.getMinZ() + 0.02D
                && playerBox.getMinZ() < blockBox.getMaxZ() - 0.02D;
        return xOverlap && zOverlap;
    }

    private boolean isSpecialSurface(Location from, Location to) {
        Material a = from.getBlock().getType();
        Material b = to.getBlock().getType();
        Material belowA = from.clone().subtract(0, 1, 0).getBlock().getType();
        Material belowB = to.clone().subtract(0, 1, 0).getBlock().getType();
        return isIce(a) || isIce(b) || isIce(belowA) || isIce(belowB)
                || belowA == Material.SLIME_BLOCK || belowB == Material.SLIME_BLOCK
                || belowA == Material.HONEY_BLOCK || belowB == Material.HONEY_BLOCK;
    }

    private boolean hasSpeedAffectingEnchantment(Player p) {
        if (p.getInventory().getBoots() == null) return false;
        return p.getInventory().getBoots().containsEnchantment(Enchantment.SOUL_SPEED);
    }

    private boolean isNearWeb(Player p, Location to) {
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (to.clone().add(x, y, z).getBlock().getType() == Material.COBWEB) return true;
                }
            }
        }
        return false;
    }

    private boolean isClimbable(Block b) {
        Material m = b.getType();
        return m == Material.LADDER || m == Material.VINE || m == Material.SCAFFOLDING;
    }

    private boolean isIce(Material m) {
        return m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE;
    }

    private boolean isSoulSand(Block b) {
        return b.getType() == Material.SOUL_SAND;
    }

    private boolean isWaterLike(Material m) {
        return m == Material.WATER || m == Material.BUBBLE_COLUMN;
    }

    private boolean isWaterContact(Player p, Location location) {
        if (p.isInWater() || p.isSwimming()) return true;
        Material feet = location.getBlock().getType();
        Material head = location.clone().add(0, 1.5D, 0).getBlock().getType();
        Material below = location.clone().subtract(0, 0.20D, 0).getBlock().getType();
        return isWaterLike(feet) || isWaterLike(head) || isWaterLike(below) && p.getVelocity().getY() < 0.1D;
    }

    private boolean nearWaterSurface(Location location) {
        Material below = location.clone().subtract(0, 0.05D, 0).getBlock().getType();
        Material current = location.getBlock().getType();
        return isWaterLike(below) && !isWaterLike(current);
    }

    private boolean hasJumpBoost(Player p) {
        return p.hasPotionEffect(PotionEffectType.JUMP);
    }

    private void clearSpeed(PlayerData pd) {
        pd.setSpeedWindowTicks(0);
        pd.setSpeedOverspeedTicks(0);
        pd.setSpeedWindowDistance(0D);
        pd.setSpeedWindowAllowedDistance(0D);
        pd.setSevereSpeedTicks(0);
    }

    private void clearFly(PlayerData pd) {
        pd.setAirborneTicks(0);
        pd.setHoverTicks(0);
        pd.setSuspiciousVerticalTicks(0);
        pd.setImpossibleRiseTicks(0);
    }

    private void clearJesus(PlayerData pd) {
        pd.setWaterSurfaceTicks(0);
        pd.setJesusCandidateTicks(0);
        pd.setJesusStableTicks(0);
    }

    private void resetSoft(PlayerData pd, Location current) {
        pd.resetMovementEvidence();
        pd.setLastLocation(current);
        pd.setPreviousLocation(current);
    }
}
