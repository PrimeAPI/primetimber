package de.primeapi.timber;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;

import java.util.*;

public class TreeChopper {

    private static final int MAX_BLOCKS = 2048; // safety cap

    public static void init() {
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (level.isClientSide()) return true;
            if (!isLog(state)) return true;
            if (!(player instanceof ServerPlayer sp)) return true;
            if (!TimberKeyHandler.isActive(sp)) return true;

            // Gather blocks first (do not modify world yet)
            List<BlockPos> blocks = collectTreeBlocks(level, pos);
            if (blocks.isEmpty()) return true;
            int totalBlocks = blocks.size();
            if (totalBlocks > MAX_BLOCKS) {
                // Too large, abort to avoid performance issues
                return true;
            }

            ItemStack tool = sp.getMainHandItem();
            if (tool.isEmpty() || !tool.isDamageableItem()) {
                // Must have a damageable tool to timber
                return true; // allow normal log break
            }

            int remaining = tool.getMaxDamage() - tool.getDamageValue();
            // Worst-case durability cost equals number of blocks (Unbreaking may reduce actual cost)
            if (remaining < totalBlocks) {
                // Not enough durability for whole tree; play error sound and allow vanilla
                sp.playSound(SoundEvents.ANVIL_LAND, 1.0f, 0.8f);
                return true;
            }

            // Schedule animated removal
            RemovalScheduler.schedule(sp, level, blocks, tool);
            return false; // cancel vanilla break
        });
    }

    private static boolean isLog(BlockState state) {
        return state.is(BlockTags.LOGS);
    }

    private static boolean isLeaf(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.getBlock() instanceof LeavesBlock;
    }

    private static List<BlockPos> collectTreeBlocks(Level level, BlockPos origin) {
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        List<BlockPos> result = new ArrayList<>();
        queue.add(origin);
        while (!queue.isEmpty() && result.size() < MAX_BLOCKS) {
            BlockPos current = queue.poll();
            if (!visited.add(current)) continue;
            BlockState state = level.getBlockState(current);
            if (isLog(state) || isLeaf(state)) {
                result.add(current);
                for (BlockPos neighbor : getNeighbors(current)) {
                    if (!visited.contains(neighbor)) {
                        BlockState ns = level.getBlockState(neighbor);
                        if (isLog(ns) || isLeaf(ns)) queue.add(neighbor);
                    }
                }
            }
        }
        return result;
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

    // Durability application helper invoked by scheduler per block
    static void applyDurability(ItemStack stack, ServerPlayer player) {
        if (stack.isEmpty() || !stack.isDamageableItem()) return;
        stack.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
    }
}
