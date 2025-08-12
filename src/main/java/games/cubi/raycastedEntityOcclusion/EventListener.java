package games.cubi.raycastedEntityOcclusion;

import games.cubi.raycastedEntityOcclusion.Packets.PacketProcessor;
import games.cubi.raycastedEntityOcclusion.Snapshot.ChunkSnapshotManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.UUID;

import static games.cubi.raycastedEntityOcclusion.UpdateChecker.checkForUpdates;

public class EventListener implements Listener {
    private final ChunkSnapshotManager manager;
    private final ConfigManager config;
    private PacketProcessor packetProcessor;
    private final RaycastedEntityOcclusion plugin;

    public EventListener(RaycastedEntityOcclusion plugin, ChunkSnapshotManager mgr, ConfigManager cfg) {
        this.manager = mgr;
        this.config = cfg;
        this.plugin = plugin;
        //load packet processor after 2 ticks in a bukkit runnable to ensure the plugin is fully loaded
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (config.packetEventsPresent) {
                packetProcessor = plugin.getPacketProcessor();
            } else {
                packetProcessor = null;
            }
        }, 2L);
    }

    // Snapshot events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) {
        manager.onChunkLoad(e.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent e) {
        manager.onChunkUnload(e.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        manager.onBlockChange(e.getBlock().getLocation(), e.getBlock().getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        manager.onBlockChange(e.getBlock().getLocation(), Material.AIR);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        manager.onBlockChange(e.getBlock().getLocation(), Material.AIR);
    }
    // These events do not cover all cases, but I can't be bothered to figure out a better solution rn. Frequent snapshot refreshes is the solution. If anyone has a solution please let me know.


    @EventHandler
    public void onPlayerDisconnect(PlayerQuitEvent e) {
        if (config.packetEventsPresent && packetProcessor != null) {
            UUID player = e.getPlayer().getUniqueId();
            packetProcessor.sendPlayerInfoRemovePacket(player);
        }
    }

    @EventHandler
    public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        if (config.checkForUpdates && event.getPlayer().hasPermission("raycastedentityocclusions.updatecheck")) {
            Player sender = event.getPlayer();
            checkForUpdates(plugin, sender);
        }
    }
}
