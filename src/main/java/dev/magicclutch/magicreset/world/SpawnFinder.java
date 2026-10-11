package dev.magicclutch.magicreset.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Finds a dry, solid spawn position near the world spawn so nobody starts in an ocean or a lava lake.
 * Chunks are loaded asynchronously one after another to keep the main thread free.
 */
public final class SpawnFinder {

    private static final Set<Material> UNSAFE_GROUND = Set.of(
        Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
        Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH, Material.POINTED_DRIPSTONE,
        Material.FIRE, Material.SOUL_FIRE, Material.WITHER_ROSE, Material.BEDROCK
    );
    private static final int[][] SAMPLE_OFFSETS = {{8, 8}, {4, 4}, {12, 12}, {4, 12}, {12, 4}};

    private SpawnFinder() {
    }

    /**
     * @param radius maximum search distance in blocks
     * @return a safe location, or the vanilla spawn if nothing safe was found within the radius
     */
    public static CompletableFuture<Location> find(World world, int radius) {
        Location origin = world.getSpawnLocation();
        int originChunkX = origin.getBlockX() >> 4;
        int originChunkZ = origin.getBlockZ() >> 4;
        List<int[]> chunks = spiral(originChunkX, originChunkZ, Math.max(0, radius >> 4));

        CompletableFuture<Location> result = new CompletableFuture<>();
        tryChunk(world, chunks, 0, origin, result);
        return result;
    }

    private static void tryChunk(World world, List<int[]> chunks, int index, Location origin, CompletableFuture<Location> result) {
        if (index >= chunks.size()) {
            result.complete(centered(origin));
            return;
        }
        int[] chunk = chunks.get(index);
        world.getChunkAtAsync(chunk[0], chunk[1], true, true).whenComplete((loaded, error) -> {
            if (error != null || loaded == null) {
                tryChunk(world, chunks, index + 1, origin, result);
                return;
            }
            // first, the column at the vanilla spawn itself
            if (index == 0) {
                Location atOrigin = check(world, origin.getBlockX(), origin.getBlockZ());
                if (atOrigin != null) {
                    result.complete(atOrigin);
                    return;
                }
            }
            for (int[] offset : SAMPLE_OFFSETS) {
                Location candidate = check(world, (chunk[0] << 4) + offset[0], (chunk[1] << 4) + offset[1]);
                if (candidate != null) {
                    result.complete(candidate);
                    return;
                }
            }
            tryChunk(world, chunks, index + 1, origin, result);
        });
    }

    private static Location check(World world, int x, int z) {
        if (!world.getWorldBorder().isInside(new Location(world, x + 0.5, 0, z + 0.5))) {
            return null;
        }
        Block ground = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        Material type = ground.getType();
        if (ground.isLiquid() || !type.isSolid() || UNSAFE_GROUND.contains(type) || ground.getY() <= world.getMinHeight()) {
            return null;
        }
        Block feet = ground.getRelative(0, 1, 0);
        Block head = ground.getRelative(0, 2, 0);
        if (!isClear(feet) || !isClear(head)) {
            return null;
        }
        return new Location(world, x + 0.5, ground.getY() + 1, z + 0.5);
    }

    private static boolean isClear(Block block) {
        return block.isPassable() && !block.isLiquid() && block.getType() != Material.POWDER_SNOW && block.getType() != Material.FIRE;
    }

    private static Location centered(Location origin) {
        return new Location(origin.getWorld(), origin.getBlockX() + 0.5, origin.getY(), origin.getBlockZ() + 0.5, origin.getYaw(), origin.getPitch());
    }

    /** Chunk coordinates ordered by ring distance from the origin. */
    private static List<int[]> spiral(int centerX, int centerZ, int radius) {
        List<int[]> result = new ArrayList<>();
        result.add(new int[]{centerX, centerZ});
        for (int ring = 1; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                        result.add(new int[]{centerX + dx, centerZ + dz});
                    }
                }
            }
        }
        return result;
    }

    /** Generates the chunks around {@code center} so players do not arrive in unloaded terrain. */
    public static CompletableFuture<Void> preload(Location center, int radius) {
        World world = center.getWorld();
        int cx = center.getBlockX() >> 4;
        int cz = center.getBlockZ() >> 4;
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                futures.add(world.getChunkAtAsync(cx + dx, cz + dz, true, false));
            }
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).exceptionally(error -> null);
    }
}
