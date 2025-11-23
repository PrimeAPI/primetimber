package de.primeapi.timber;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

public class TreeChopper {

    private static final int MAX_BLOCKS = 2048; // safety cap to avoid runaway recursion.

    public static void init() {
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (level.isClientSide()) return true;
            if (!isLog(state)) return true;
            if (!(player instanceof ServerPlayer sp)) return true;
            // Require keybind active
            if (!TimberKeyHandler.isActive(sp)) return true;
            chopTree(level, pos, player);
            return false;
        });
    }

    private static boolean isLog(BlockState state) {
        return state.is(BlockTags.LOGS);
    }

    private static boolean isLeaf(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.getBlock() instanceof LeavesBlock;
    }

    private static void chopTree(Level level, BlockPos origin, Player player) {
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(origin);
        int broken = 0;

        while (!queue.isEmpty() && broken < MAX_BLOCKS) {
            BlockPos current = queue.poll();
            if (!visited.add(current)) continue;
            BlockState state = level.getBlockState(current);
            if (isLog(state) || isLeaf(state)) {
                for (BlockPos neighbor : getNeighbors(current)) {
                    if (!visited.contains(neighbor)) {
                        BlockState ns = level.getBlockState(neighbor);
                        if (isLog(ns) || isLeaf(ns)) {
                            queue.add(neighbor);
                        }
                    }
                }
                // destroyBlock will drop items if second parameter true
                level.destroyBlock(current, true);
                broken++;
            }
        }

        PrimeTimber.LOGGER.info("Timber broke {} blocks starting at {}", broken, origin);
    }

    private static Iterable<BlockPos> getNeighbors(BlockPos pos) {
        Set<BlockPos> res = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    res.add(pos.offset(dx, dy, dz));
                }
            }
        }
        return res;
    }
}
