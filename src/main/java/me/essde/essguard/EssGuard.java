package me.essde.essguard;

import me.essde.essguard.checks.CombatCheck;
import me.essde.essguard.checks.MovementCheck;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityVelocityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class EssGuard extends JavaPlugin implements Listener {
    private final Map<UUID, PlayerData> data = new HashMap<UUID, PlayerData>();
    private ViolationManager violations;
    private MovementCheck movement;
    private CombatCheck combat;
    private int serverTick;
    private long lastTickNs;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        violations = new ViolationManager(this);
        movement = new MovementCheck(this);
        combat = new CombatCheck(this);

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(combat, this);

        for (Player player : Bukkit.getOnlinePlayers()) {
            getData(player).setLastJoinMs(System.currentTimeMillis());
            addGrace(player, getConfig().getLong("safety.join-grace-ms", 2500L));
        }

        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                long nowNs = System.nanoTime();
                long gapNs = lastTickNs == 0L ? 50_000_000L : nowNs - lastTickNs;
                lastTickNs = nowNs;
                boolean serverLag = gapNs > getConfig().getLong("safety.server-lag-ms", 150L) * 1_000_000L;
                serverTick++;
                for (Player player : Bukkit.getOnlinePlayers()) {
                    movement.sample(player, serverTick, nowNs, serverLag);
                }
            }
        }, 1L, 1L);

        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                violations.decayAll();
            }
        }, 20L, 20L);

        getLogger().info("EssGuard 0.3.0 enabled (observation-only, Paper 1.16.5).");
    }

    public PlayerData getData(Player player) {
        PlayerData existing = data.get(player.getUniqueId());
        if (existing == null) {
            existing = new PlayerData();
            data.put(player.getUniqueId(), existing);
        }
        return existing;
    }

    public ViolationManager getViolations() { return violations; }

    public boolean isInGrace(Player player) {
        PlayerData pd = getData(player);
        long now = System.currentTimeMillis();
        return now < Math.max(pd.getGraceUntilMs(),
                pd.getLastTeleportMs() + getConfig().getLong("safety.teleport-grace-ms", 1400L));
    }

    public void addGrace(Player player, long ms) {
        PlayerData pd = getData(player);
        pd.setGraceUntilMs(Math.max(pd.getGraceUntilMs(), System.currentTimeMillis() + ms));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerData pd = getData(event.getPlayer());
        long now = System.currentTimeMillis();
        pd.setLastTeleportMs(now);
        pd.setGraceUntilMs(Math.max(pd.getGraceUntilMs(), now + getConfig().getLong("safety.teleport-grace-ms", 1400L)));
        pd.resetMovementEvidence();
        pd.setPreviousLocation(event.getTo());
        pd.setLastLocation(event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(EntityVelocityEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            PlayerData pd = getData(player);
            pd.setLastVelocityMs(System.currentTimeMillis());
            pd.setLastVelocity(event.getVelocity());
            addGrace(player, getConfig().getLong("safety.velocity-grace-ms", 950L));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            getData((Player) event.getDamager()).setLastDamageMs(System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        PlayerData pd = getData(event.getPlayer());
        pd.setLastJoinMs(System.currentTimeMillis());
        addGrace(event.getPlayer(), getConfig().getLong("safety.join-grace-ms", 2500L));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        data.remove(event.getPlayer().getUniqueId());
        violations.remove(event.getPlayer());
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
            return true;
        }
        sender.sendMessage(ChatColor.AQUA + "EssGuard " + ChatColor.GRAY + "commands: /essguard reload");
        return true;
    }
}
