package games.cubi.raycastedEntityOcclusion.util;

import org.bukkit.Location;
import org.bukkit.World;

public record BlockPos(int x, int y, int z) {
    public static BlockPos fromLocation(Location loc) {
        return new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public Location toLocation(World world) {
        return new Location(world, x + 0.5, y + 0.5, z + 0.5);
    }
}