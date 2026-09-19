package me.essde.essguard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Conservative observation-only evidence accumulator.
 * Flags are evidence, not proof. Alerts require persistent evidence and a cooldown.
 */
public final class ViolationManager {
    private final EssGuard plugin;
    private final Map<UUID, Map<String, Double>> violations = new HashMap<UUID, Map<String, Double>>();
    private final Map<UUID, Map<String, Long>> lastAlert = new HashMap<UUID, Map<String, Long>>();
    private final Map<UUID, Map<String, Integer>> streaks = new HashMap<UUID, Map<String, Integer>>();
    private final Map<UUID, Map<String, Integer>> cleanWindows = new HashMap<UUID, Map<String, Integer>>();

    public ViolationManager(EssGuard plugin) {
        this.plugin = plugin;
    }

    public void flag(Player player, String check, double points, String detail) {
        if (player == null || !player.isOnline()) return;
        if (!plugin.getConfig().getBoolean("alerts.enabled", true)) return;
        if (player.hasPermission("essguard.bypass")) return;

        UUID id = player.getUniqueId();
        Map<String, Double> byCheck = getDoubleMap(violations, id);
        double old = value(byCheck, check);
        double vl = Math.min(plugin.getConfig().getDouble("alerts.max-vl", 100D), old + points);
        byCheck.put(check, vl);

        Map<String, Integer> clean = getIntMap(cleanWindows, id);
        clean.put(check, 0);
        int streak = increment(streaks, id, check);

        double minVl = plugin.getConfig().getDouble("alerts.min-vl", 6.0D);
        int minStreak = plugin.getConfig().getInt("alerts.min-streak", 3);
        if (vl < minVl || streak < minStreak || !canAlert(player, check)) return;

        String safeDetail = detail == null ? "" : detail;
        String message = ChatColor.DARK_AQUA + "[EssGuard] " + ChatColor.GRAY + player.getName()
                + ChatColor.YELLOW + " -> " + ChatColor.RED + check
                + ChatColor.DARK_GRAY + " (VL " + String.format("%.1f", vl) + ") "
                + ChatColor.GRAY + safeDetail;

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("essguard.alerts")) online.sendMessage(message);
        }
        Bukkit.getConsoleSender().sendMessage(ChatColor.stripColor(message));
    }

    public void reward(Player player, String check, double amount) {
        if (player == null) return;
        UUID id = player.getUniqueId();
        Map<String, Double> byCheck = violations.get(id);
        if (byCheck != null && byCheck.containsKey(check)) {
            byCheck.put(check, Math.max(0D, byCheck.get(check) - amount));
        }

        Map<String, Integer> clean = getIntMap(cleanWindows, id);
        int next = Math.min(1000, value(clean, check) + 1);
        clean.put(check, next);
        if (next >= plugin.getConfig().getInt("alerts.clean-windows-to-reset-streak", 4)) {
            clearStreak(player, check);
        }
    }

    public void clearStreak(Player player, String check) {
        if (player == null) return;
        Map<String, Integer> map = streaks.get(player.getUniqueId());
        if (map != null) map.remove(check);
    }

    private int increment(Map<UUID, Map<String, Integer>> outer, UUID id, String check) {
        Map<String, Integer> map = getIntMap(outer, id);
        int next = value(map, check) + 1;
        map.put(check, Math.min(1000, next));
        return next;
    }

    private boolean canAlert(Player player, String check) {
        long cooldown = plugin.getConfig().getLong("alerts.cooldown-ms", 3000L);
        Map<String, Long> map = lastAlert.get(player.getUniqueId());
        if (map == null) {
            map = new HashMap<String, Long>();
            lastAlert.put(player.getUniqueId(), map);
        }
        long now = System.currentTimeMillis();
        Long previous = map.get(check);
        if (previous != null && now - previous < cooldown) return false;
        map.put(check, now);
        return true;
    }

    public void decayAll() {
        double decay = plugin.getConfig().getDouble("alerts.decay-per-second", 0.15D);
        for (Map<String, Double> byCheck : violations.values()) {
            for (Map.Entry<String, Double> entry : byCheck.entrySet()) {
                entry.setValue(Math.max(0D, entry.getValue() - decay));
            }
        }
        for (Map<String, Integer> map : streaks.values()) {
            for (Map.Entry<String, Integer> entry : map.entrySet()) {
                entry.setValue(Math.max(0, entry.getValue() - 1));
            }
        }
        for (Map<String, Integer> map : cleanWindows.values()) {
            for (Map.Entry<String, Integer> entry : map.entrySet()) {
                entry.setValue(Math.max(0, entry.getValue() - 1));
            }
        }
    }

    private double value(Map<String, Double> map, String key) {
        Double v = map.get(key);
        return v == null ? 0D : v;
    }

    private int value(Map<String, Integer> map, String key) {
        Integer v = map.get(key);
        return v == null ? 0 : v;
    }

    private Map<String, Double> getDoubleMap(Map<UUID, Map<String, Double>> outer, UUID id) {
        Map<String, Double> map = outer.get(id);
        if (map == null) {
            map = new HashMap<String, Double>();
            outer.put(id, map);
        }
        return map;
    }

    private Map<String, Integer> getIntMap(Map<UUID, Map<String, Integer>> outer, UUID id) {
        Map<String, Integer> map = outer.get(id);
        if (map == null) {
            map = new HashMap<String, Integer>();
            outer.put(id, map);
        }
        return map;
    }

    public void remove(Player player) {
        UUID id = player.getUniqueId();
        violations.remove(id);
        lastAlert.remove(id);
        streaks.remove(id);
        cleanWindows.remove(id);
    }
}
