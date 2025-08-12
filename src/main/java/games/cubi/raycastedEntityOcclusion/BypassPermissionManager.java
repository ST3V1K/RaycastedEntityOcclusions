package games.cubi.raycastedEntityOcclusion;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BypassPermissionManager implements Listener {

    private static final String BYPASS_PERMISSION = "raycastedentityocclusion.bypass";

    private final Map<UUID, Boolean> permissions = new ConcurrentHashMap<>();

    public BypassPermissionManager(RaycastedEntityOcclusion plugin, ConfigManager cfg) {
        new BukkitRunnable() {
            public void run() {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    boolean bypass = player.hasPermission(BYPASS_PERMISSION);
                    permissions.put(player.getUniqueId(), bypass);
                }
            }
        }.runTaskTimerAsynchronously(plugin, cfg.permissionRecheckInterval, cfg.permissionRecheckInterval);
    }

    @EventHandler
    private void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean bypass = player.hasPermission(BYPASS_PERMISSION);
        permissions.put(player.getUniqueId(), bypass);
    }

    @EventHandler
    private void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        permissions.remove(player.getUniqueId());
    }

    @EventHandler
    private void onPlayerKick(PlayerKickEvent event) {
        Player player = event.getPlayer();
        permissions.remove(player.getUniqueId());
    }

    public boolean hasBypass(Player player) {
        return permissions.getOrDefault(player.getUniqueId(), false);
    }
}
