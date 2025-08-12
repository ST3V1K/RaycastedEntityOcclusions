package games.cubi.raycastedEntityOcclusion.Raycast;

import games.cubi.raycastedEntityOcclusion.Snapshot.ChunkSnapshotManager;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.util.Vector;

public class RaycastUtil {

    private static final Particle.DustOptions RED = new Particle.DustOptions(org.bukkit.Color.RED, 1f);
    private static final Particle.DustOptions GREEN = new Particle.DustOptions(org.bukkit.Color.GREEN, 1f);

    public static boolean raycast(Location start, Location end, int maxOccluding, boolean debug, ChunkSnapshotManager snap) {
        double totalDistanceSqr = start.distanceSquared(end);
        Location curr = start.clone();
        Vector dir = end.toVector().subtract(start.toVector()).normalize();
        while (curr.distanceSquared(start) < totalDistanceSqr) {
            curr.add(dir);
            if (snap.isOccluding(curr)) {
                if (debug) {
                    start.getWorld().spawnParticle(Particle.DUST, curr, 1, RED);
                }
                if (--maxOccluding < 1) {
                    return false;
                }
            } else if (debug) {
                start.getWorld().spawnParticle(Particle.DUST, curr, 1, GREEN);
            }
        }
        return true;
    }
}