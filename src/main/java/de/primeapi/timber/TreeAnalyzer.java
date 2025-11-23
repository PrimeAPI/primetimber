package de.primeapi.timber;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Advanced tree analysis selecting only the intended tree using trunk base and natural leaves with multi-source BFS partition. */
public final class TreeAnalyzer {
    private static final int MAX_HEIGHT = 40;
    private static final int BASE_H_RADIUS = 10;
    private static final int BASE_MAX_LEAF_DEPTH = 16;
    private static final int JUNGLE_H_RADIUS = 14;
    private static final int JUNGLE_MAX_LEAF_DEPTH = 28;
    private static final int UPWARD_LEAF_SCAN = 24; // how far upward to search for leaves if none near base
    private static final int MAX_VISIT = 4096;
    private static final int MIN_NATURAL_LEAVES = 5;
    private static final int BASE_LOG_SPREAD = 6; // max horizontal spread from trunk center for non-jungle
    private static final int JUNGLE_LOG_SPREAD = 9; // jungle allows wider branching

    private static final Set<String> SOIL_SUFFIXES = Set.of(
            "dirt","grass_block","podzol","rooted_dirt","mud","muddy_mangrove_roots","mycelium","crimson_nylium","warped_nylium"
    );

    private TreeAnalyzer() {}

    public static List<BlockPos> analyze(Level level, BlockPos startPos, BlockState startState, int hardCap) {
        if (!isLogOrStem(startState)) return List.of();
        String familyKey = familyKey(startState);
        boolean isJungle = familyKey.equals("jungle");
        int hRadius = isJungle ? JUNGLE_H_RADIUS : BASE_H_RADIUS;
        int maxLeafDepth = isJungle ? JUNGLE_MAX_LEAF_DEPTH : BASE_MAX_LEAF_DEPTH;
        BlockPos base = findTrunkBase(level, startPos, startState);
        if (base == null) return List.of();
        if (!validSoil(level.getBlockState(base.below()))) return List.of();
        if (!hasNaturalLeavesNearby(level, startPos) && !scanUpwardForLeaves(level, base, startState)) return List.of();
        Set<BlockPos> mainCluster = discoverTrunkCluster(level, base, startState);
        BlockPos mainCenter = averagePos(mainCluster);
        int treeHeightApprox = estimateHeight(level, mainCluster, startState);
        if (treeHeightApprox > 16 && !isJungle) maxLeafDepth += 4; // allow larger crowns for tall normal trees
        if (treeHeightApprox > 22) maxLeafDepth += 4; // further extension for very tall (mega spruce etc.)
        List<TrunkSource> sources = findAllTrunkSources(level, mainCenter, startState, mainCluster, hRadius, familyKey);
        if (sources.size() > 18) return List.of(); // safety cap
        PartitionResult partition = multiSourcePartition(level, sources, hardCap, startState, hRadius, maxLeafDepth, familyKey, isJungle);
        if (partition == null) return List.of();
        Set<BlockPos> myBlocks = new HashSet<>();
        for (Map.Entry<BlockPos, VisitInfo> e : partition.map.entrySet()) {
            VisitInfo info = e.getValue();
            if (info.treeId == 0 && !info.contested) {
                BlockState s = level.getBlockState(e.getKey());
                // Enforce leaf family match
                if (s.is(BlockTags.LEAVES)) {
                    if (leafFamilyKey(s).equals(familyKey)) myBlocks.add(e.getKey());
                } else {
                    myBlocks.add(e.getKey());
                }
            }
        }
        myBlocks.addAll(mainCluster); // ensure trunk cluster included
        if (myBlocks.size() > hardCap) return List.of();
        return new ArrayList<>(myBlocks);
    }

    private static int estimateHeight(Level level, Set<BlockPos> cluster, BlockState family) {
        int maxY = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        for (BlockPos p : cluster) { int y=p.getY(); if (y>maxY) maxY=y; if (y<minY) minY=y; }
        // Attempt to climb upward from highest trunk to find last connected log
        BlockPos seed = null;
        for (BlockPos p : cluster) if (p.getY()==maxY) { seed = p; break; }
        if (seed != null) {
            BlockPos cur = seed;
            for (int i=0;i<MAX_HEIGHT;i++) {
                BlockPos up = cur.above();
                BlockState us = level.getBlockState(up);
                if (isSameFamily(family, us)) { maxY = up.getY(); cur = up; } else break;
            }
        }
        return maxY - minY;
    }

    private static boolean isLogOrStem(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.CRIMSON_STEMS) || state.is(BlockTags.WARPED_STEMS);
    }

    private static boolean sameFamily(BlockState baseFamily, BlockState other) {
        if (!isLogOrStem(other)) return false;
        return isSameFamily(baseFamily, other);
    }

    private static BlockPos findTrunkBase(Level level, BlockPos pos, BlockState familyState) {
        BlockPos current = pos;
        for (int i=0;i<MAX_HEIGHT;i++) {
            BlockPos down = current.below();
            BlockState ds = level.getBlockState(down);
            if (isSameFamily(familyState, ds)) current = down; else break;
        }
        return current;
    }

    private static boolean isSameFamily(BlockState a, BlockState b) {
        // Strict matching by family key (overworld logs) else falls back to stems tags
        String fa = familyKey(a);
        String fb = familyKey(b);
        if (!fa.isEmpty() && fa.equals(fb)) return true;
        return (a.is(BlockTags.CRIMSON_STEMS) && b.is(BlockTags.CRIMSON_STEMS)) || (a.is(BlockTags.WARPED_STEMS) && b.is(BlockTags.WARPED_STEMS));
    }

    private static String familyKey(BlockState state) {
        String id = state.getBlock().getDescriptionId(); // block.minecraft.oak_log
        int lastDot = id.lastIndexOf('.');
        String suffix = lastDot>=0 ? id.substring(lastDot+1) : id;
        suffix = suffix.replaceFirst("^stripped_", "");
        if (suffix.endsWith("_log")) return suffix.substring(0, suffix.length()-4);
        if (suffix.endsWith("_wood")) return suffix.substring(0, suffix.length()-5);
        if (suffix.endsWith("_stem")) return suffix.substring(0, suffix.length()-5);
        if (suffix.endsWith("_hyphae")) return suffix.substring(0, suffix.length()-7);
        return ""; // unknown
    }

    private static String leafFamilyKey(BlockState state) {
        String id = state.getBlock().getDescriptionId(); // block.minecraft.oak_leaves
        int lastDot = id.lastIndexOf('.');
        String suffix = lastDot>=0? id.substring(lastDot+1) : id;
        suffix = suffix.replaceFirst("^stripped_", "");
        if (suffix.endsWith("_leaves")) return suffix.substring(0, suffix.length()-7);
        return "";
    }

    private static boolean isLeafCandidate(BlockState s, String trunkFamily) {
        if (!s.is(BlockTags.LEAVES)) return false;
        String lf = leafFamilyKey(s);
        if (!lf.equals(trunkFamily)) return false; // only same family leaves
        if (s.hasProperty(LeavesBlock.PERSISTENT) && s.hasProperty(LeavesBlock.DISTANCE)) {
            Boolean persistent = s.getValue(LeavesBlock.PERSISTENT);
            Integer dist = s.getValue(LeavesBlock.DISTANCE);
            return !persistent && dist != null && dist <= 6;
        }
        return true; // fallback treat as natural if properties missing
    }

    private static boolean scanUpwardForLeaves(Level level, BlockPos base, BlockState family) {
        String fk = familyKey(family);
        int found = 0;
        for (int dy=0; dy<=UPWARD_LEAF_SCAN && found < MIN_NATURAL_LEAVES; dy++) {
            BlockPos layer = base.above(dy);
            int radius = 4;
            for (int dx=-radius; dx<=radius && found < MIN_NATURAL_LEAVES; dx++) {
                for (int dz=-radius; dz<=radius && found < MIN_NATURAL_LEAVES; dz++) {
                    BlockPos p = layer.offset(dx,0,dz);
                    BlockState s = level.getBlockState(p);
                    if (isLeafCandidate(s, fk)) found++;
                }
            }
        }
        return found >= MIN_NATURAL_LEAVES;
    }

    private static boolean validSoil(BlockState state) {
        String id = state.getBlock().getDescriptionId(); // e.g. block.minecraft.dirt
        int lastDot = id.lastIndexOf('.');
        String suffix = lastDot>=0? id.substring(lastDot+1): id;
        return SOIL_SUFFIXES.contains(suffix);
    }

    private static boolean hasNaturalLeavesNearby(Level level, BlockPos start) {
        BlockState trunkState = level.getBlockState(start);
        String fk = familyKey(trunkState);
        int natural = 0;
        int radius = 3;
        for (int dx=-radius; dx<=radius; dx++) {
            for (int dy=-radius; dy<=radius; dy++) {
                for (int dz=-radius; dz<=radius; dz++) {
                    BlockPos p = start.offset(dx,dy,dz);
                    BlockState s = level.getBlockState(p);
                    if (isLeafCandidate(s, fk)) {
                        natural++;
                        if (natural >= MIN_NATURAL_LEAVES) return true;
                    }
                }
            }
        }
        return false;
    }

    private static Set<BlockPos> discoverTrunkCluster(Level level, BlockPos base, BlockState family) {
        Set<BlockPos> cluster = new HashSet<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        q.add(base);
        while (!q.isEmpty()) {
            BlockPos p = q.poll();
            if (!cluster.add(p)) continue;
            for (int dx=-1; dx<=1; dx++) {
                for (int dz=-1; dz<=1; dz++) {
                    if (dx==0 && dz==0) continue;
                    BlockPos n = p.offset(dx,0,dz);
                    if (!cluster.contains(n) && isSameFamily(family, level.getBlockState(n))) {
                        q.add(n);
                    }
                }
            }
        }
        return cluster;
    }

    private static BlockPos averagePos(Set<BlockPos> positions) {
        long sx=0, sy=0, sz=0; int c=0;
        for (BlockPos p : positions) { sx+=p.getX(); sy+=p.getY(); sz+=p.getZ(); c++; }
        return c==0? BlockPos.ZERO : new BlockPos((int)Math.round((double)sx/c),(int)Math.round((double)sy/c),(int)Math.round((double)sz/c));
    }

    private static List<TrunkSource> findAllTrunkSources(Level level, BlockPos center, BlockState family, Set<BlockPos> mainCluster, int hRadius, String familyKey) {
        List<TrunkSource> sources = new ArrayList<>();
        sources.add(new TrunkSource(0, mainCluster, averagePos(mainCluster)));
        int nextId = 1;
        // broaden vertical scan: allow bases up to 8 above and 4 below center
        for (int dx=-hRadius; dx<=hRadius; dx++) {
            for (int dz=-hRadius; dz<=hRadius; dz++) {
                for (int dy=-4; dy<=8; dy++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    BlockState s = level.getBlockState(p);
                    if (!sameFamily(family, s)) continue;
                    BlockPos below = p.below();
                    if (sameFamily(family, level.getBlockState(below))) continue; // not a base
                    if (!validSoil(level.getBlockState(below))) continue;
                    if (mainCluster.contains(p)) continue;
                    Set<BlockPos> cluster = discoverTrunkCluster(level, p, family);
                    BlockPos cCenter = averagePos(cluster);
                    boolean duplicate = false;
                    for (TrunkSource ts : sources) {
                        if (ts.center.distSqr(cCenter) < 3) { duplicate = true; break; }
                    }
                    if (!duplicate) sources.add(new TrunkSource(nextId++, cluster, cCenter));
                }
            }
        }
        return sources;
    }

    private static PartitionResult multiSourcePartition(Level level, List<TrunkSource> sources, int hardCap, BlockState family, int hRadius, int maxLeafDepth, String familyKey, boolean jungle) {
        Map<BlockPos, VisitInfo> visited = new HashMap<>();
        ArrayDeque<Node> q = new ArrayDeque<>();
        Map<Integer, BlockPos> centers = new HashMap<>();
        for (TrunkSource src : sources) {
            centers.put(src.id, src.center);
            for (BlockPos pos : src.cluster) {
                q.add(new Node(pos, src.id, 0));
                visited.put(pos, new VisitInfo(src.id,0,false));
            }
        }
        int logSpread = jungle ? JUNGLE_LOG_SPREAD : BASE_LOG_SPREAD;
        while (!q.isEmpty() && visited.size() < MAX_VISIT && visited.size() < hardCap) {
            Node node = q.poll();
            BlockState state = level.getBlockState(node.pos);
            boolean isLog = isLogOrStem(state);
            for (BlockPos n : adjacency(node.pos, true)) { // always 26-neighbor for logs/leaves pathing
                if (!inBounds(n, centers.get(0), hRadius)) continue;
                BlockState ns = level.getBlockState(n);
                boolean nIsLog = isLogOrStem(ns) && isSameFamily(family, ns);
                boolean nIsLeaf = isLeafCandidate(ns, familyKey);
                if (!(nIsLog || nIsLeaf)) continue;
                // Limit horizontal spread for logs relative to their own trunk center
                if (nIsLog) {
                    BlockPos center = centers.get(node.treeId);
                    int dx = n.getX() - center.getX();
                    int dz = n.getZ() - center.getZ();
                    if (dx*dx + dz*dz > logSpread*logSpread) continue; // too far from trunk cluster
                }
                int nextDist = node.dist + 1;
                if (nIsLeaf && nextDist > maxLeafDepth) continue;
                VisitInfo existing = visited.get(n);
                if (existing == null) {
                    visited.put(n, new VisitInfo(node.treeId, nextDist, false));
                    q.add(new Node(n, node.treeId, nextDist));
                } else {
                    if (nextDist < existing.dist) {
                        visited.put(n, new VisitInfo(node.treeId, nextDist, false));
                        q.add(new Node(n, node.treeId, nextDist));
                    } else if (nextDist == existing.dist && existing.treeId != node.treeId) {
                        visited.put(n, new VisitInfo(existing.treeId, existing.dist, true));
                    }
                }
            }
        }
        return new PartitionResult(visited);
    }

    private static boolean inBounds(BlockPos p, BlockPos center, int hRadius) {
        int dy = p.getY() - center.getY(); if (dy < -1 || dy > MAX_HEIGHT) return false;
        int dx = p.getX() - center.getX(); int dz = p.getZ() - center.getZ();
        return dx*dx + dz*dz <= hRadius*hRadius;
    }

    private static List<BlockPos> adjacency(BlockPos pos, boolean diag) {
        List<BlockPos> list = new ArrayList<>(26);
        for (int dx=-1; dx<=1; dx++) for (int dy=-1; dy<=1; dy++) for (int dz=-1; dz<=1; dz++) {
            if (dx==0 && dy==0 && dz==0) continue;
            list.add(pos.offset(dx,dy,dz));
        }
        return list;
    }

    // Data classes
    private record TrunkSource(int id, Set<BlockPos> cluster, BlockPos center) {}
    private record Node(BlockPos pos, int treeId, int dist) {}
    private static class VisitInfo { final int treeId; final int dist; final boolean contested; VisitInfo(int t,int d,boolean c){treeId=t;dist=d;contested=c;} }
    private record PartitionResult(Map<BlockPos, VisitInfo> map) {}
}
