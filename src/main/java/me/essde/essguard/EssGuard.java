package me.essde.essguard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityVelocityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class EssGuard extends JavaPlugin implements Listener {
    private final Map<UUID, State> states = new HashMap<UUID, State>();
    private long lagGraceUntil;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                if (Bukkit.getServer().getTPS().length > 0 && Bukkit.getServer().getTPS()[0] < 18.0) {
                    lagGraceUntil = now + getConfig().getLong("safety.server-lag-ms", 150L);
                }
                for (State s : states.values()) s.decay();
            }
        }, 20L, 20L);
        getLogger().info("EssGuard 0.3.1 enabled (observation-only, Paper 1.16.5).");
    }

    @Override public void onDisable() { states.clear(); }

    private State state(Player p) {
        State s = states.get(p.getUniqueId());
        if (s == null) { s = new State(); states.put(p.getUniqueId(), s); }
        return s;
    }

    private boolean bypass(Player p) {
        return p.hasPermission("essguard.bypass")
                || p.getGameMode() == GameMode.CREATIVE
                || p.getGameMode() == GameMode.SPECTATOR
                || p.isFlying() || p.getAllowFlight() || p.isInsideVehicle()
                || p.isGliding() || p.isSwimming() || p.isRiptiding() || p.isInWater()
                || System.currentTimeMillis() < lagGraceUntil
                || System.currentTimeMillis() < state(p).graceUntil;
    }

    private void grace(Player p, long ms) {
        State s = state(p);
        s.graceUntil = Math.max(s.graceUntil, System.currentTimeMillis() + ms);
        s.resetMovementEvidence();
    }

    @EventHandler public void join(PlayerJoinEvent e) {
        State s = state(e.getPlayer());
        s.lastLocation = e.getPlayer().getLocation().clone();
        s.graceUntil = System.currentTimeMillis() + getConfig().getLong("safety.join-grace-ms", 2500L);
    }

    @EventHandler public void quit(PlayerQuitEvent e) { states.remove(e.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void velocity(EntityVelocityEvent e) {
        if (e.getEntity() instanceof Player) {
            Player p = (Player)e.getEntity();
            state(p).lastVelocityAt = System.currentTimeMillis();
            grace(p, getConfig().getLong("safety.velocity-grace-ms", 950L));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void teleport(PlayerTeleportEvent e) {
        grace(e.getPlayer(), getConfig().getLong("safety.teleport-grace-ms", 1400L));
        State s = state(e.getPlayer());
        s.lastLocation = e.getTo() == null ? e.getPlayer().getLocation().clone() : e.getTo().clone();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        if (e.getTo() == null) return;
        Player p = e.getPlayer();
        State s = state(p);
        Location from = e.getFrom();
        Location to = e.getTo();
        if (s.lastLocation == null) { s.lastLocation = to.clone(); return; }

        long now = System.currentTimeMillis();
        long gap = now - s.lastMoveAt;
        s.lastMoveAt = now;

        if (gap > 130L) { grace(p, 250L); s.lastLocation = to.clone(); return; }
        if (bypass(p)) { s.lastLocation = to.clone(); return; }

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double dy = to.getY() - from.getY();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        boolean supported = hasSupport(p, to);
        boolean special = specialSurface(from, to);
        boolean recentMotion = now - s.lastVelocityAt < getConfig().getLong("safety.velocity-grace-ms", 950L);

        if (!recentMotion && !special) {
            checkSpeed(p, s, horizontal, supported);
            checkFly(p, s, horizontal, dy, supported);
            checkJesus(p, s, horizontal, dy, supported, to);
        } else s.resetMovementEvidence();

        s.lastLocation = to.clone();
        s.lastHorizontal = horizontal;
        s.lastDy = dy;
        s.lastY = to.getY();
        s.lastSupported = supported;
    }

    private void checkSpeed(Player p, State s, double horizontal, boolean supported) {
        double limit = p.isSprinting() ? 0.33D : 0.25D;
        PotionEffect speed = p.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null) limit *= 1.0D + 0.20D * (speed.getAmplifier() + 1);
        PotionEffect slow = p.getPotionEffect(PotionEffectType.SLOW);
        if (slow != null) limit *= Math.max(0.20D, 1.0D - 0.15D * (slow.getAmplifier() + 1));
        if (p.isSneaking()) limit *= 0.45D;
        if (!supported) limit *= 1.18D;
        limit += 0.06D;

        double ratio = horizontal / Math.max(limit, 0.05D);
        if (ratio > 1.28D) {
            s.speedSevere++;
            s.speedEvidence += Math.min(2.0D, ratio - 1.0D);
        } else if (ratio > 1.10D) {
            s.speedEvidence += 0.35D;
            s.speedSevere = Math.max(0, s.speedSevere - 1);
        } else {
            s.speedEvidence = Math.max(0D, s.speedEvidence - 0.30D);
            s.speedSevere = Math.max(0, s.speedSevere - 1);
        }

        if (s.speedEvidence >= 6.0D && s.speedSevere >= 3) {
            flag(p, "Speed", 1.0D, String.format("ratio=%.2f evidence=%.1f", ratio, s.speedEvidence));
            s.speedEvidence = 0D;
            s.speedSevere = 0;
        }
    }

    private void checkFly(Player p, State s, double horizontal, double dy, boolean supported) {
        if (!supported) s.airTicks++; else s.airTicks = 0;
        boolean hover = !supported && horizontal > 0.07D && Math.abs(dy) < 0.018D;
        boolean impossibleRise = !supported && dy > 0.48D;
        if (hover) s.hoverTicks++; else s.hoverTicks = Math.max(0, s.hoverTicks - 1);
        if (impossibleRise) s.riseTicks++; else s.riseTicks = Math.max(0, s.riseTicks - 1);

        if (s.hoverTicks >= 14) {
            flag(p, "Fly", 1.4D, "stable-air-hover");
            s.hoverTicks = 0;
        } else if (s.riseTicks >= 3) {
            flag(p, "Fly", 1.7D, String.format("rise=%.3f", dy));
            s.riseTicks = 0;
        }
    }

    private void checkJesus(Player p, State s, double horizontal, double dy, boolean supported, Location to) {
        Material below = to.clone().subtract(0.0D, 0.08D, 0.0D).getBlock().getType();
        boolean waterBelow = below == Material.WATER || below == Material.BUBBLE_COLUMN;
        boolean standingOnWater = waterBelow && !isWaterLike(to.getBlock().getType())
                && !supported && horizontal > 0.075D && Math.abs(dy) < 0.025D;
        if (standingOnWater) s.jesusTicks++; else s.jesusTicks = Math.max(0, s.jesusTicks - 2);
        if (s.jesusTicks >= 14) {
            flag(p, "Jesus", 1.5D, "water-surface");
            s.jesusTicks = 0;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void combat(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player) || !(e.getEntity() instanceof Player)) return;
        Player attacker = (Player)e.getDamager();
        Player target = (Player)e.getEntity();
        if (bypass(attacker)) return;

        State s = state(attacker);
        double distance = distanceToBox(attacker.getEyeLocation(), target.getBoundingBox());
        if (distance > 3.65D) { s.reachSevere++; s.reachEvidence += 1.5D; }
        else if (distance > 3.18D) s.reachEvidence += 0.4D;
        else { s.reachEvidence = Math.max(0D, s.reachEvidence - 0.25D); s.reachSevere = Math.max(0, s.reachSevere - 1); }

        if (s.reachEvidence >= 5.0D && s.reachSevere >= 2) {
            flag(attacker, "Hitbox", 1.5D, String.format("distance=%.2f", distance));
            s.reachEvidence = 0D;
            s.reachSevere = 0;
        }
    }

    private double distanceToBox(Location eye, BoundingBox b) {
        double x = clamp(eye.getX(), b.getMinX(), b.getMaxX());
        double y = clamp(eye.getY(), b.getMinY(), b.getMaxY());
        double z = clamp(eye.getZ(), b.getMinZ(), b.getMaxZ());
        double dx = eye.getX() - x, dy = eye.getY() - y, dz = eye.getZ() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }

    private boolean hasSupport(Player p, Location loc) {
        BoundingBox box = p.getBoundingBox();
        double minY = box.getMinY();
        int y = (int)Math.floor(minY - 0.08D);
        for (int x = loc.getBlockX() - 1; x <= loc.getBlockX() + 1; x++) {
            for (int z = loc.getBlockZ() - 1; z <= loc.getBlockZ() + 1; z++) {
                Block b = loc.getWorld().getBlockAt(x, y, z);
                Material m = b.getType();
                if (m.isAir() || isWaterLike(m) || m == Material.COBWEB || m == Material.LADDER || m == Material.VINE) continue;
                BoundingBox bb = b.getBoundingBox();
                if (bb.getVolume() <= 0.000001D) continue;
                if (minY - bb.getMaxY() < -0.04D || minY - bb.getMaxY() > 0.12D) continue;
                if (box.overlaps(bb)) return true;
            }
        }
        return p.isOnGround();
    }

    private boolean specialSurface(Location from, Location to) {
        Material[] ms = {
            from.getBlock().getType(), to.getBlock().getType(),
            from.clone().subtract(0, 1, 0).getBlock().getType(),
            to.clone().subtract(0, 1, 0).getBlock().getType()
        };
        for (Material m : ms) {
            if (m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE
                    || m == Material.SLIME_BLOCK || m == Material.HONEY_BLOCK || m == Material.SOUL_SAND) return true;
        }
        return false;
    }

    private boolean isWaterLike(Material m) { return m == Material.WATER || m == Material.BUBBLE_COLUMN; }

    private void flag(Player p, String check, double amount, String info) {
        if (!getConfig().getBoolean("alerts.enabled", true)) return;
        State s = state(p);
        long now = System.currentTimeMillis();
        s.vl = Math.min(getConfig().getDouble("alerts.max-vl", 100.0D), s.vl + amount);
        s.streak++;
        if (s.vl < getConfig().getDouble("alerts.min-vl", 6.0D)) return;
        if (s.streak < getConfig().getInt("alerts.min-streak", 3)) return;
        long cooldown = getConfig().getLong("alerts.cooldown-ms", 3000L);
        if (now - s.lastAlert < cooldown) return;
        s.lastAlert = now;

        String msg = ChatColor.DARK_AQUA + "[EssGuard] " + ChatColor.WHITE + p.getName()
                + ChatColor.GRAY + " -> " + ChatColor.YELLOW + check
                + ChatColor.GRAY + " (VL " + String.format("%.1f", s.vl) + ") "
                + ChatColor.DARK_GRAY + info;
        Bukkit.getConsoleSender().sendMessage(msg);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("essguard.alerts")) staff.sendMessage(msg);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("essguard")) return false;
        if (!sender.hasPermission("essguard.admin")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage(ChatColor.GREEN + "[EssGuard] Configuration reloaded.");
        } else sender.sendMessage(ChatColor.AQUA + "EssGuard " + ChatColor.GRAY + "- /essguard reload");
        return true;
    }

    private static final class State {
        long graceUntil, lastVelocityAt, lastMoveAt, lastAlert;
        double lastHorizontal, lastDy, lastY, vl, speedEvidence, reachEvidence;
        boolean lastSupported;
        Location lastLocation;
        int streak, speedSevere, airTicks, hoverTicks, riseTicks, jesusTicks, reachSevere;

        void resetMovementEvidence() {
            speedEvidence = 0D; speedSevere = 0; airTicks = 0; hoverTicks = 0; riseTicks = 0; jesusTicks = 0;
        }

        void decay() {
            vl = Math.max(0D, vl - 0.15D);
            if (vl < 1.0D) streak = Math.max(0, streak - 1);
            speedEvidence = Math.max(0D, speedEvidence - 0.20D);
            reachEvidence = Math.max(0D, reachEvidence - 0.25D);
            if (speedEvidence < 1.0D) speedSevere = Math.max(0, speedSevere - 1);
            if (reachEvidence < 1.0D) reachSevere = Math.max(0, reachSevere - 1);
        }
    }
}
