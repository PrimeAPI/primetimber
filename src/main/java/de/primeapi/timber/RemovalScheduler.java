package de.primeapi.timber;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * Schedules animated removal of tree blocks over subsequent server ticks.
 */
public class RemovalScheduler {
    private static final Map<UUID, ActiveTask> ACTIVE = new HashMap<>();

    private static class ActiveTask {
        final ServerPlayer player;
        final Level level;
        final List<BlockPos> blocks;
        final ItemStack tool;
        int index;

        ActiveTask(ServerPlayer player, Level level, List<BlockPos> blocks, ItemStack tool) {
            this.player = player;
            this.level = level;
            this.blocks = blocks;
            this.tool = tool;
            this.index = 0;
        }
    }

    public static void schedule(ServerPlayer player, Level level, List<BlockPos> blocks, ItemStack tool) {
        // Prevent overlapping tasks per player
        ACTIVE.put(player.getUUID(), new ActiveTask(player, level, blocks, tool));
    }

    static {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Iterator<Map.Entry<UUID, ActiveTask>> it = ACTIVE.entrySet().iterator();
            while (it.hasNext()) {
                ActiveTask task = it.next().getValue();
                if (task.player.isRemoved() || task.player.level() != task.level) {
                    it.remove();
                    continue;
                }
                // Break a batch per tick for speed but still animated
                int batch = Math.min(20, task.blocks.size() - task.index); // up to 20 blocks per tick
                for (int i = 0; i < batch; i++) {
                    BlockPos pos = task.blocks.get(task.index + i);
                    BlockState state = task.level.getBlockState(pos);
                    if (!state.isAir()) {
                        task.level.destroyBlock(pos, true, task.player);
                        TreeChopper.applyDurability(task.tool, task.player);
                        if (task.tool.isEmpty()) {
                            // Tool broke; play sound and abort remaining
                            task.player.playSound(SoundEvents.ANVIL_BREAK, 1f, 1f);
                            it.remove();
                            PrimeTimber.LOGGER.info("Timber aborted early - tool broke after {} blocks", task.index + i + 1);
                            return;
                        }
                    }
                }
                task.index += batch;
                if (task.index >= task.blocks.size()) {
                    task.player.playSound(SoundEvents.WOOD_BREAK, 0.8f, 1.2f);
                    PrimeTimber.LOGGER.info("Timber finished removing {} blocks", task.blocks.size());
                    it.remove();
                }
            }
        });
    }
}
