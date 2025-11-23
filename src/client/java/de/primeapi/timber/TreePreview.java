package de.primeapi.timber;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.item.AxeItem;

import java.util.*;

/** Client-side preview overlay for timber. */
public class TreePreview {
    private static List<BlockPos> previewBlocks = Collections.emptyList();
    private static boolean canChop = false;
    private static int tickCounter = 0; // retained but no longer used for action bar

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(TreePreview::updatePreview);
    }

    public static List<BlockPos> getPreviewBlocks() { return previewBlocks; }
    public static boolean canChopAll() { return canChop; }

    private static void updatePreview(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) { previewBlocks = Collections.emptyList(); return; }
        // Use keybind state (timber key) - fallback to shift if key not integrated client-side
        boolean keyActive = PrimeTimberClient.isTimberKeyDown();
        if (!keyActive) { previewBlocks = Collections.emptyList(); return; }
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof AxeItem)) { previewBlocks = Collections.emptyList(); return; }
        HitResult hit = client.hitResult;
        if (!(hit instanceof BlockHitResult bhr)) { previewBlocks = Collections.emptyList(); return; }
        BlockPos origin = bhr.getBlockPos();
        Level level = player.level();
        BlockState originState = level.getBlockState(origin);
        if (!originState.is(BlockTags.LOGS)) { previewBlocks = Collections.emptyList(); return; }
        previewBlocks = collect(level, origin);
        int remaining = stack.getMaxDamage() - stack.getDamageValue();
        canChop = remaining >= previewBlocks.size();

        tickCounter++;
        if (tickCounter % 10 == 0 && client.player != null && !previewBlocks.isEmpty()) { // every 10 ticks
            // String msg = (canChop ? "§a" : "§c") + "Timber: " + previewBlocks.size() + " blocks" + (canChop ? " (OK)" : " (Insufficient Durability)" );
            // client.player.displayClientMessage(net.minecraft.network.chat.Component.literal(msg), true);
        }
        if (!previewBlocks.isEmpty()) {
            // Compute bounding box of preview
            int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
            for (BlockPos p : previewBlocks) {
                int x=p.getX(), y=p.getY(), z=p.getZ();
                if (x<minX) minX=x; if (y<minY) minY=y; if (z<minZ) minZ=z;
                if (x>maxX) maxX=x; if (y>maxY) maxY=y; if (z>maxZ) maxZ=z;
            }
            maxX += 1; maxY += 1; maxZ += 1; // include upper faces
            // Decide particle color
            int color = canChop ? 0x00FF00 : 0xFF0000; // green or red
            float scale = 1.0f;
            net.minecraft.core.particles.DustParticleOptions dust = new net.minecraft.core.particles.DustParticleOptions(color, scale);
            // Limit total edge points for performance
            int maxPointsPerEdge = 64; // adaptively sample long edges
            java.util.function.BiConsumer<BlockPos, BlockPos> edge = (start, end) -> {
                int dx = end.getX() - start.getX();
                int dy = end.getY() - start.getY();
                int dz = end.getZ() - start.getZ();
                int length = Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz));
                int step = Math.max(1, length / maxPointsPerEdge);
                for (int i=0;i<=length;i+=step) {
                    double x = start.getX() + (dx==0?0:(dx>0?i:-i));
                    double y = start.getY() + (dy==0?0:(dy>0?i:-i));
                    double z = start.getZ() + (dz==0?0:(dz>0?i:-i));
                    client.level.addParticle(dust, x, y, z, 0,0,0);
                }
            };
            // 12 edges (using inclusive coords)
            BlockPos A = new BlockPos(minX,minY,minZ);
            BlockPos B = new BlockPos(maxX,minY,minZ);
            BlockPos C = new BlockPos(minX,maxY,minZ);
            BlockPos D = new BlockPos(minX,minY,maxZ);
            BlockPos E = new BlockPos(maxX,maxY,maxZ);
            BlockPos F = new BlockPos(minX,maxY,maxZ);
            BlockPos G = new BlockPos(maxX,minY,maxZ);
            BlockPos H = new BlockPos(maxX,maxY,minZ);
            edge.accept(A,B); edge.accept(A,C); edge.accept(A,D);
            edge.accept(E,F); edge.accept(E,G); edge.accept(E,H);
            edge.accept(C,F); edge.accept(C,H); edge.accept(D,F); edge.accept(D,G); edge.accept(B,G); edge.accept(B,H);
        }
    }

    private static List<BlockPos> collect(Level level, BlockPos origin) {
        int max = 1024;
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        List<BlockPos> result = new ArrayList<>();
        queue.add(origin);
        while (!queue.isEmpty() && result.size() < max) {
            BlockPos current = queue.poll();
            if (!visited.add(current)) continue;
            BlockState state = level.getBlockState(current);
            if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.getBlock() instanceof LeavesBlock) {
                result.add(current);
                for (BlockPos n : neighbors(current)) {
                    if (!visited.contains(n)) {
                        BlockState ns = level.getBlockState(n);
                        if (ns.is(BlockTags.LOGS) || ns.is(BlockTags.LEAVES) || ns.getBlock() instanceof LeavesBlock) queue.add(n);
                    }
                }
            }
        }
        return result;
    }

    private static Iterable<BlockPos> neighbors(BlockPos pos) {
        Set<BlockPos> set = new HashSet<>();
        for (int dx=-1;dx<=1;dx++) for (int dy=-1;dy<=1;dy++) for (int dz=-1;dz<=1;dz++) {
            if (dx==0&&dy==0&&dz==0) continue;
            set.add(pos.offset(dx,dy,dz));
        }
        return set;
    }
}
