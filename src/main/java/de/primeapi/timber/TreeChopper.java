package de.primeapi.timber;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;

import java.util.List;

public class TreeChopper {
    private static final int MAX_BLOCKS = 2048; // safety cap

    public static void init() {
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (level.isClientSide()) return true;
            if (!(player instanceof ServerPlayer sp)) return true;
            if (!TimberKeyHandler.isActive(sp)) return true;
            if (!isLogOrStem(state)) return true;
            if (!isAxe(sp.getMainHandItem())) return true;

            List<BlockPos> blocks = TreeAnalyzer.analyze(level, pos, state, MAX_BLOCKS);
            if (blocks.isEmpty()) return true; // abort - treat as normal break
            int totalBlocks = blocks.size();
            if (totalBlocks > MAX_BLOCKS) return true; // safety

            ItemStack tool = sp.getMainHandItem();
            if (tool.isEmpty() || !tool.isDamageableItem()) return true;

            // Count only logs/stems for durability cost
            int logCost = 0;
            for (BlockPos bp : blocks) {
                BlockState bs = level.getBlockState(bp);
                if (isLogOrStem(bs)) logCost++;
            }

            int remaining = tool.getMaxDamage() - tool.getDamageValue();
            if (remaining < logCost) {
                sp.playSound(SoundEvents.ANVIL_LAND, 1.0f, 0.8f);
                return true; // not enough durability for logs alone
            }

            RemovalScheduler.schedule(sp, level, blocks, tool);
            return false; // cancel vanilla
        });
    }

    private static boolean isLogOrStem(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.CRIMSON_STEMS) || state.is(BlockTags.WARPED_STEMS);
    }

    private static boolean isAxe(ItemStack stack) {
        return stack.getItem() instanceof net.minecraft.world.item.AxeItem;
    }

    // Durability application helper invoked by scheduler per block
    static void applyDurability(ItemStack stack, ServerPlayer player, BlockState state) {
        if (!isLogOrStem(state)) return; // only logs consume durability now
        if (stack.isEmpty() || !stack.isDamageableItem()) return;
        stack.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
    }
}
