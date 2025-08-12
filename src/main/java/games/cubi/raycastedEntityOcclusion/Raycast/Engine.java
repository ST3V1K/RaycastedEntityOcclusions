package games.cubi.raycastedEntityOcclusion.Raycast;


import games.cubi.raycastedEntityOcclusion.BypassPermissionManager;
import games.cubi.raycastedEntityOcclusion.ConfigManager;
import games.cubi.raycastedEntityOcclusion.Logger;
import games.cubi.raycastedEntityOcclusion.RaycastedEntityOcclusion;
import games.cubi.raycastedEntityOcclusion.Snapshot.ChunkSnapshotManager;
import games.cubi.raycastedEntityOcclusion.util.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Engine {

    private static final Particle.DustOptions BLUE = new Particle.DustOptions(Color.BLUE, 1f);

    public static ConcurrentHashMap<BlockPos, Set<Player>> canSeeTileEntity = new ConcurrentHashMap<>();
    public static Set<Chunk> syncRecheck = ConcurrentHashMap.newKeySet();

    private final static BlockData STONE = Material.STONE.createBlockData();
    private final static BlockData DEEPSLATE = Material.DEEPSLATE.createBlockData();

    private static class RayJob {
        final Player player;
        final Entity target;
        final Location start, predictedStart, end;
        final boolean visible;

        RayJob(Player p, Entity e, boolean seen, Location s, Location pred, Location t) {
            player = p;
            target = e;
            visible = seen;
            start = s;
            predictedStart = pred;
            end = t;
        }
    }

    private static class RayResult {
        final Player player;
        final Entity target;
        final boolean wasVisible, nowVisible;

        RayResult(Player p, Entity e, boolean was, boolean now) {
            player = p;
            target = e;
            wasVisible = was;
            nowVisible = now;
        }
    }

    private static class TileResult {
        final Player player;
        final Location loc;
        final boolean visible;

        TileResult(Player p, Location location, boolean v) {
            player = p;
            loc = location;
            visible = v;
        }
    }

    public static void runEngine(ConfigManager cfg, ChunkSnapshotManager snapMgr, MovementTracker tracker, BypassPermissionManager permissionManager, RaycastedEntityOcclusion plugin) {
        // ----- PHASE 1: SYNC GATHER -----

        if (!syncRecheck.isEmpty()) {
            if (cfg.debugMode) {
                Logger.warning(syncRecheck.size() + " chunks failed to snapshot asynchronously, rechecking them now.");
            }
            for (Chunk c : syncRecheck) {
                if (c.isLoaded()) {
                    plugin.getChunkSnapshotManager().snapshotChunk(c);
                    syncRecheck.remove(c);
                    if (cfg.debugMode) {
                        Logger.info("Successfully rechecked chunk " + c.getX() + ", " + c.getZ() + " in world " + c.getWorld().getName());
                    }
                }
            }
        }

        List<RayJob> jobs = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (permissionManager.hasBypass(p)) continue;
            Location eye = p.getEyeLocation();
            Location predEye = null;
            if (cfg.engineMode == 2) {
                // getPredictedLocation returns null if insufficient data or too slow
                predEye = tracker.getPredictedLocation(p);
            }

            for (Entity e : p.getNearbyEntities(cfg.searchRadius, cfg.searchRadius, cfg.searchRadius)) {
                if (e == p) continue;
                // Cull-players logic
                boolean seen = p.canSee(e);
                if (e instanceof Player pl && (!cfg.cullPlayers || (cfg.onlyCullSneakingPlayers && !pl.isSneaking()))) {
                    if (!seen) {
                        p.showEntity(plugin, e);
                    }
                    continue;
                }

                Location target = e.getLocation().add(0, e.getHeight() / 2, 0);
                double distSqr = eye.distanceSquared(target);
                if (distSqr <= cfg.alwaysShowRadius * cfg.alwaysShowRadius) {
                    if (!seen) {
                        p.showEntity(plugin, e);
                    }
                } else if (cfg.raycastRadius > 0 && distSqr > cfg.raycastRadius * cfg.raycastRadius) {
                    if (!seen) {
                        p.hideEntity(plugin, e);
                    }
                } else if (!seen || plugin.tick % cfg.recheckInterval == 0) {
                    // schedule for async raycast (with or without predEye)
                    jobs.add(new RayJob(p, e, seen, eye, predEye, target));
                }
            }
        }

        // ----- PHASE 2: ASYNC RAYCASTS -----
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<RayResult> results = new ArrayList<>(jobs.size());
            for (RayJob job : jobs) {
                // if the player is not in the same world as the target, skip
                if (!job.player.getWorld().equals(job.target.getWorld())) {
                    continue;
                }
                // first cast from real eye
                boolean vis = RaycastUtil.raycast(job.start, job.end, cfg.maxOccludingCount, cfg.debugMode, snapMgr);

                // if that fails, and we have a predEye, cast again from predicted
                if (!vis && job.predictedStart != null) {
                    if (cfg.debugMode) {
                        job.predictedStart.getWorld().spawnParticle(Particle.DUST, job.predictedStart, 1, BLUE);
                    }
                    vis = RaycastUtil.raycast(job.predictedStart, job.end, cfg.maxOccludingCount, cfg.debugMode, snapMgr);
                }

                results.add(new RayResult(job.player, job.target, job.visible, vis));
            }

            // ----- PHASE 3: SYNC APPLY -----
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (RayResult r : results) {
                    Player p = r.player;
                    Entity ent = r.target;
                    if (p == null || ent == null) {
                        continue;
                    }

                    if (r.nowVisible) {
                        if (!r.wasVisible) {
                            p.showEntity(plugin, ent);
                        }
                    } else {
                        if (r.wasVisible) {
                            p.hideEntity(plugin, ent);
                        }
                    }
                }
            });
        });

    }

    private static final ConcurrentLinkedQueue<TileResult> results = new ConcurrentLinkedQueue<>();

    public static void runTileEngine(ConfigManager cfg, ChunkSnapshotManager snapMgr, MovementTracker tracker, BypassPermissionManager permissionManager, RaycastedEntityOcclusion plugin) {
        if (!cfg.checkTileEntities) {
            return;
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (permissionManager.hasBypass(p)) continue;
            World world = p.getWorld();
            //get player's chunk location
            int chunkX = p.getLocation().getBlockX() >> 4;
            int chunkZ = p.getLocation().getBlockZ() >> 4;

            //async run with vars passed in
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                int chunksRadius = (cfg.searchRadius + 15) / 16;
                HashSet<BlockPos> tileEntities = new HashSet<>();
                for (int x = (-chunksRadius / 2) + chunkX; x <= chunksRadius + chunkX; x++) {
                    for (int z = (-chunksRadius / 2) + chunkZ; z <= chunksRadius + chunkZ; z++) {
                        tileEntities.addAll(snapMgr.getTileEntitiesInChunk(world, x, z));
                    }
                }
                for (BlockPos pos : tileEntities) {
                    Set<Player> seen = canSeeTileEntity.get(pos);
                    boolean sees = seen != null && seen.contains(p);
                    if (sees) {
                        if (cfg.tileEntityRecheckInterval == 0) continue;
                        if (plugin.tick % (cfg.tileEntityRecheckInterval * 20) != 0) continue;
                    }

                    Location loc = pos.toLocation(world);

                    double distSquared = loc.distanceSquared(p.getLocation());
                    if (sees && distSquared > cfg.searchRadius * cfg.searchRadius) {
                        results.add(new TileResult(p, loc, false));
                        continue;
                    }
                    if (!sees && distSquared < cfg.alwaysShowRadius * cfg.alwaysShowRadius) {
                        results.add(new TileResult(p, loc, true));
                        continue;
                    }

                    boolean visible = RaycastUtil.raycast(p.getEyeLocation(), loc, cfg.maxOccludingCount, cfg.debugMode, snapMgr);
                    if (cfg.engineMode == 2 && !visible) {
                        Location predEye = tracker.getPredictedLocation(p);
                        if (predEye != null) {
                            visible = RaycastUtil.raycast(predEye, loc, cfg.maxOccludingCount, cfg.debugMode, snapMgr);
                        }
                    }

//                    if (sees == visible) {
//                        continue;
//                    }

                    if (visible) {
                        canSeeTileEntity.computeIfAbsent(pos, k -> ConcurrentHashMap.newKeySet()).add(p);
                    } else {
                        Set<Player> seenPlayers = canSeeTileEntity.get(pos);
                        if (seenPlayers != null) {
                            seenPlayers.remove(p);
                            if (seenPlayers.isEmpty()) {
                                canSeeTileEntity.remove(pos);
                            }
                        }
                    }
                    results.add(new TileResult(p, loc, visible));
                }
            });
        }
        for (TileResult r : results) {
            Player p = r.player;
            Location loc = r.loc;
            boolean visible = r.visible;

            syncToggleTileEntity(p, loc, visible, permissionManager, plugin);
            results.remove(r);
        }
    }

    public static void hideTileEntity(Player p, Location location, BypassPermissionManager permissionManager, RaycastedEntityOcclusion plugin) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (permissionManager.hasBypass(p)) return;
            if (location.getBlockY() < 0) p.sendBlockChange(location, DEEPSLATE);
            else p.sendBlockChange(location, STONE);
        });
    }

    public static void showTileEntity(Player p, Location location, RaycastedEntityOcclusion plugin) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Block block = location.getBlock();
            BlockState data = block.getState();
            if (data instanceof TileState tileData) {
                p.sendBlockChange(location, block.getBlockData());
                p.sendBlockUpdate(location, tileData);
            } else if (plugin.getConfigManager().debugMode) {
                Logger.warning("Attempting to show a block which isn't a tile entity at " + block.getX() + ", " + block.getY() + ", " + block.getZ());
            }
        });
    }

    public static void syncToggleTileEntity(Player p, Location loc, boolean show, BypassPermissionManager permissionManager, RaycastedEntityOcclusion plugin) {
        if (show) {
            showTileEntity(p, loc, plugin);
        } else {
            hideTileEntity(p, loc, permissionManager, plugin);
        }
    }
}
