package games.cubi.raycastedEntityOcclusion.Snapshot;

import games.cubi.raycastedEntityOcclusion.ConfigManager;
import games.cubi.raycastedEntityOcclusion.Logger;
import games.cubi.raycastedEntityOcclusion.RaycastedEntityOcclusion;
import games.cubi.raycastedEntityOcclusion.util.BlockPos;
import games.cubi.raycastedEntityOcclusion.util.ChunkPos;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkSnapshotManager {
    public static class Data {
        public final ChunkSnapshot snapshot;
        public final ConcurrentHashMap<BlockPos, Boolean> occluding = new ConcurrentHashMap<>();
        public final Set<BlockPos> tileEntities = ConcurrentHashMap.newKeySet();
        public long lastRefresh;

        public Data(ChunkSnapshot snapshot, long time) {
            this.snapshot = snapshot;
            this.lastRefresh = time;
        }
    }

    private static final ConcurrentHashMap<ChunkPos, Data> dataMap = new ConcurrentHashMap<>();
    private final ConfigManager cfg;
    private final RaycastedEntityOcclusion plugin;

    public ChunkSnapshotManager(RaycastedEntityOcclusion plugin) {
        cfg = plugin.getConfigManager();
        this.plugin = plugin;
        //get loaded chunks and add them to dataMap
        for (World w : plugin.getServer().getWorlds()) {
            for (Chunk c : w.getLoadedChunks()) {
                snapshotChunk(c);
            }
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                int chunksRefreshed = 0;
                int chunksToRefreshMaximum = getNumberOfCachedChunks() / 3;
                for (Map.Entry<ChunkPos, Data> e : dataMap.entrySet()) {
                    if (now - e.getValue().lastRefresh >= cfg.snapshotRefreshInterval * 1000L && chunksRefreshed < chunksToRefreshMaximum) {
                        chunksRefreshed++;
                        ChunkPos pos = e.getKey();
                        World w = Bukkit.getWorld(pos.world());

                        if (w == null) {
                            if (cfg.debugMode) {
                                plugin.getLogger().warning("ChunkSnapshotManager: World " + pos.world() + " not found. Please report this on our discord (discord.cubi.games)'");
                            }
                            continue;
                        }

                        snapshotChunk(w.getChunkAt(pos.chunk()));
                    }
                }
                if (cfg.debugMode) {
                    Logger.info("ChunkSnapshotManager: Refreshed " + chunksRefreshed + " chunks out of " + chunksToRefreshMaximum + " maximum.");
                }
            }
        }.runTaskTimerAsynchronously(plugin, cfg.snapshotRefreshInterval * 2L, cfg.snapshotRefreshInterval * 2L /* This runs 10 times per refreshInterval, spreading out the refreshes */);
    }

    public void onChunkLoad(Chunk c) {
        snapshotChunk(c);
    }

    public void onChunkUnload(Chunk c) {
        removeChunkSnapshot(c);
    }

    public void snapshotChunk(Chunk c) {
        if (cfg.debugMode) {
            //Logger.info("ChunkSnapshotManager: Taking snapshot of chunk " + c.getWorld().getName() + ":" + c.getX() + ":" + c.getZ());
        }
        dataMap.put(key(c), takeSnapshot(c, System.currentTimeMillis()));
    }

    public void removeChunkSnapshot(Chunk c) {
        if (cfg.debugMode) {
            Logger.info("ChunkSnapshotManager: Removing snapshot of chunk " + c.getWorld().getName() + ":" + c.getX() + ":" + c.getZ());
        }
        dataMap.remove(key(c));
    }

    // Used by EventListener to update the delta map when a block is placed or broken
    public void onBlockChange(Location loc, Material m) {
        if (cfg.debugMode) {
            Logger.info("ChunkSnapshotManager: Block change at " + loc + " to " + m);
        }
        Data d = dataMap.get(key(loc.getChunk()));
        if (d != null) {
            BlockPos pos = BlockPos.fromLocation(loc);
            boolean occluding = m.isOccluding();
            d.occluding.put(pos, occluding);

            if (cfg.checkTileEntities) {
                // Check if the block is a tile entity
                BlockState data = Bukkit.createBlockData(m).createBlockState();
                if (data instanceof TileState) {
                    if (cfg.debugMode) {
                        Logger.info("ChunkSnapshotManager: Tile entity at " + loc);
                    }
                    d.tileEntities.add(pos);
                } else {
                    d.tileEntities.remove(pos);
                }
            }
        } else if (cfg.debugMode) {
            Logger.error("Data map value empty, ignoring block update!");
        }
    }

    private Data takeSnapshot(Chunk c, long now) {
        ChunkSnapshot snapshot = c.getChunkSnapshot(true, false, false, false);
        Data data = new Data(snapshot, now);
        dataMap.put(key(c), data);

        if (cfg.checkTileEntities) {
            int min = c.getWorld().getMinHeight();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                int chunkX = c.getX() << 4;
                int chunkZ = c.getZ() << 4;

                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int max = snapshot.getHighestBlockYAt(x, z);

                        for (int y = min; y < max; y++) {
                            BlockData blockData = snapshot.getBlockData(x, y, z);
                            if (blockData.createBlockState() instanceof TileState && blockData.getMaterial() != Material.BEACON) {
                                data.tileEntities.add(new BlockPos(x + chunkX, y, z + chunkZ));
                            }
                        }
                    }
                }
            });
        }

        return data;
    }

    public ChunkPos key(Chunk c) {
        return new ChunkPos(c.getWorld().getUID(), c.getChunkKey());
    }

    public ChunkPos key(World world, int x, int z) {
        return new ChunkPos(world.getUID(), Chunk.getChunkKey(x, z));
    }

    public boolean isOccluding(Location loc) {
        Chunk c = loc.getChunk();
        Data d = dataMap.get(key(c));
        if (d == null) {
            //dataMap.put(key(c), takeSnapshot(c, System.currentTimeMillis())); infinite loop
            if (cfg.debugMode) {
                System.err.println("ChunkSnapshotManager: No snapshot for " + c + " Please report this on our discord (discord.cubi.games)'");
            }
            return loc.getBlock().getBlockData().isOccluding();
        }

        BlockPos pos = BlockPos.fromLocation(loc);
        Boolean occluding = d.occluding.get(pos);
        if (occluding != null) {
            return occluding;
        }

        occluding = d.snapshot.getBlockData(loc.getBlockX() & 0xF, loc.getBlockY(), loc.getBlockZ() & 0xF).isOccluding();
        d.occluding.put(pos, occluding);
        return occluding;
    }

    //get TileEntity Locations in chunk
    public Set<BlockPos> getTileEntitiesInChunk(World world, int x, int z) {
        Data d = dataMap.get(key(world, x, z));
        if (d == null) {
            return Collections.emptySet();
        }
        return d.tileEntities;
    }

    public int getNumberOfCachedChunks() {
        return dataMap.size();
        //created to use in a debug command maybe
    }

}
