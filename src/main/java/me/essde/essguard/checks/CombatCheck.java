package me.essde.essguard.checks;

import me.essde.essguard.EssGuard;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Observation-only reach/hitbox geometry checks.
 *
 * A server cannot directly observe a modified client hitbox. This therefore
 * checks the server-observable consequences: distance to the server AABB,
 * ray/AABB intersection, facing consistency and latency-aware target movement.
 */
public final class CombatCheck implements Listener {
    private final EssGuard plugin;

    public CombatCheck(EssGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("checks.hitbox.enabled", true)) return;
        if (!(event.getDamager() instanceof Player)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;

        Player attacker = (Player) event.getDamager();
        LivingEntity target = (LivingEntity) event.getEntity();
        if (attacker.isDead() || target.isDead()) return;
        if (attacker.getGameMode() == GameMode.CREATIVE || attacker.getGameMode() == GameMode.SPECTATOR) return;
        if (attacker.isInsideVehicle() || attacker.getAllowFlight()) return;
        if (attacker.getWorld() != target.getWorld()) return;
        if (plugin.isInGrace(attacker)) return;
        if (attacker.hasPermission("essguard.bypass")) return;

        Vector eye = attacker.getEyeLocation().toVector();
        Vector direction = attacker.getEyeLocation().getDirection().normalize();
        if (direction.lengthSquared() < 0.5D) return;

        BoundingBox serverBox = target.getBoundingBox();
        double targetPingMs = target instanceof Player ? ((Player) target).getPing() : 0D;
        double attackerPingMs = attacker.getPing();

        double pingAllowance = Math.min(
                plugin.getConfig().getDouble("checks.hitbox.max-ping-allowance", 0.55D),
                Math.max(0D, attackerPingMs + targetPingMs) *
                        plugin.getConfig().getDouble("checks.hitbox.ping-blocks-per-ms", 0.0009D)
        );

        // Estimate how far the target could legitimately move between the client
        // seeing it and the attack being processed. This is intentionally capped.
        double targetMotionAllowance = 0D;
        if (target.getVelocity() != null) {
            targetMotionAllowance = Math.min(
                    plugin.getConfig().getDouble("checks.hitbox.max-motion-allowance", 0.45D),
                    target.getVelocity().length() * Math.max(1D, (attackerPingMs + targetPingMs) / 50D)
            );
        }

        double totalAllowance = pingAllowance + targetMotionAllowance;
        BoundingBox compensated = serverBox.expand(totalAllowance, totalAllowance * 0.75D, totalAllowance);

        double distanceToBox = distanceToBox(compensated, eye);
        double rayHitDistance = rayBoxDistance(eye, direction, compensated);
        Vector center = target.getLocation().clone().add(0D, target.getHeight() * 0.5D, 0D).toVector();
        double centerDistance = center.distance(eye);
        double facingDot = direction.dot(center.clone().subtract(eye).normalize());

        double baseReach = plugin.getConfig().getDouble("checks.hitbox.base-reach", 3.18D);
        double hardReach = plugin.getConfig().getDouble("checks.hitbox.hard-reach", 3.65D);
        double extremeReach = plugin.getConfig().getDouble("checks.hitbox.extreme-reach", 4.00D);

        double softLimit = baseReach + totalAllowance;
        double hardLimit = hardReach + totalAllowance;
        double extremeLimit = extremeReach + totalAllowance;

        boolean overSoft = distanceToBox > softLimit;
        boolean overHard = distanceToBox > hardLimit;
        boolean overExtreme = distanceToBox > extremeLimit;
        boolean rayMiss = Double.isInfinite(rayHitDistance);
        boolean rayBeyondHard = !rayMiss && rayHitDistance > hardLimit;
        boolean badFacing = facingDot < plugin.getConfig().getDouble("checks.hitbox.min-facing-dot", 0.15D);

        // A single borderline hit is ignored. Strong evidence requires two
        // independent geometry contradictions, or an extreme reach distance.
        boolean corroborated = overHard && (rayMiss || rayBeyondHard) && badFacing;
        boolean twoSignal = overHard && (rayMiss || rayBeyondHard);
        boolean extreme = overExtreme && (rayMiss || rayBeyondHard);

        if (extreme || corroborated) {
            double points = extreme ? 2.2D : 1.5D;
            plugin.getViolations().flag(attacker, "Hitbox", points,
                    String.format("box=%.2f ray=%s limit=%.2f face=%.2f ping=%d/%dms",
                            distanceToBox,
                            rayMiss ? "miss" : String.format("%.2f", rayHitDistance),
                            hardLimit, facingDot, attacker.getPing(), (int) targetPingMs));
        } else if (twoSignal && overSoft) {
            plugin.getViolations().flag(attacker, "Hitbox", 0.7D,
                    String.format("box=%.2f ray=%s soft=%.2f", distanceToBox,
                            rayMiss ? "miss" : String.format("%.2f", rayHitDistance), softLimit));
        } else {
            plugin.getViolations().reward(attacker, "Hitbox", 0.30D);
        }
    }

    private double distanceToBox(BoundingBox box, Vector point) {
        double x = clamp(point.getX(), box.getMinX(), box.getMaxX());
        double y = clamp(point.getY(), box.getMinY(), box.getMaxY());
        double z = clamp(point.getZ(), box.getMinZ(), box.getMaxZ());
        double dx = point.getX() - x;
        double dy = point.getY() - y;
        double dz = point.getZ() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Slab ray/AABB intersection. Returns positive hit distance or infinity. */
    private double rayBoxDistance(Vector origin, Vector dir, BoundingBox box) {
        double tMin = 0D;
        double tMax = Double.POSITIVE_INFINITY;
        double[] o = {origin.getX(), origin.getY(), origin.getZ()};
        double[] d = {dir.getX(), dir.getY(), dir.getZ()};
        double[] min = {box.getMinX(), box.getMinY(), box.getMinZ()};
        double[] max = {box.getMaxX(), box.getMaxY(), box.getMaxZ()};

        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1.0E-9D) {
                if (o[i] < min[i] || o[i] > max[i]) return Double.POSITIVE_INFINITY;
            } else {
                double inv = 1D / d[i];
                double t1 = (min[i] - o[i]) * inv;
                double t2 = (max[i] - o[i]) * inv;
                if (t1 > t2) {
                    double tmp = t1;
                    t1 = t2;
                    t2 = tmp;
                }
                tMin = Math.max(tMin, t1);
                tMax = Math.min(tMax, t2);
                if (tMin > tMax) return Double.POSITIVE_INFINITY;
            }
        }
        return tMin >= 0D ? tMin : tMax;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
