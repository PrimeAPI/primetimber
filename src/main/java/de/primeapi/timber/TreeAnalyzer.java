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
        List<TrunkSource> sources = findAllTrunkSources(level, mainCenter, startState, mainCluster, hRadius, familyKey);
        if (sources.size() > 12) return List.of();
        PartitionResult partition = multiSourcePartition(level, sources, hardCap, startState, hRadius, maxLeafDepth, familyKey);
        if (partition == null) return List.of();
        Set<BlockPos> myBlocks = new HashSet<>();
        for (Map.Entry<BlockPos, VisitInfo> e : partition.map.entrySet()) {
            VisitInfo info = e.getValue();
            if (info.treeId == 0 && !info.contested) myBlocks.add(e.getKey());
        }
        myBlocks.addAll(mainCluster);
        if (myBlocks.size() > hardCap) return List.of();
        return new ArrayList<>(myBlocks);
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

    private static boolean scanUpwardForLeaves(Level level, BlockPos base, BlockState family) {
        int found = 0;
        for (int dy=0; dy<=UPWARD_LEAF_SCAN && found < MIN_NATURAL_LEAVES; dy++) {
            BlockPos layer = base.above(dy);
            int radius = 4;
            for (int dx=-radius; dx<=radius && found < MIN_NATURAL_LEAVES; dx++) {
                for (int dz=-radius; dz<=radius && found < MIN_NATURAL_LEAVES; dz++) {
                    BlockPos p = layer.offset(dx,0,dz);
                    BlockState s = level.getBlockState(p);
                    if (isLeafCandidate(s)) found++;
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
        int natural = 0;
        int radius = 3;
        for (int dx=-radius; dx<=radius; dx++) {
            for (int dy=-radius; dy<=radius; dy++) {
                for (int dz=-radius; dz<=radius; dz++) {
                    BlockPos p = start.offset(dx,dy,dz);
                    BlockState s = level.getBlockState(p);
                    if (isLeafCandidate(s)) {
                        natural++;
                        if (natural >= MIN_NATURAL_LEAVES) return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isLeafCandidate(BlockState s) {
        if (!s.is(BlockTags.LEAVES)) return false;
        // Must be natural: persistent=false distance<=6 if properties exist
        if (s.hasProperty(LeavesBlock.PERSISTENT) && s.hasProperty(LeavesBlock.DISTANCE)) {
            Boolean persistent = s.getValue(LeavesBlock.PERSISTENT);
            Integer dist = s.getValue(LeavesBlock.DISTANCE);
            return !persistent && dist != null && dist <= 6;
        }
        return true; // fallback treat as natural if properties missing
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
        for (int dx=-hRadius; dx<=hRadius; dx++) {
            for (int dz=-hRadius; dz<=hRadius; dz++) {
                for (int dy=0; dy<=6; dy++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    BlockState s = level.getBlockState(p);
                    if (!sameFamily(family, s)) continue;
                    BlockPos below = p.below();
                    if (sameFamily(family, level.getBlockState(below))) continue;
                    if (!validSoil(level.getBlockState(below))) continue;
                    if (mainCluster.contains(p)) continue;
                    Set<BlockPos> cluster = discoverTrunkCluster(level, p, family);
                    BlockPos cCenter = averagePos(cluster);
                    boolean duplicate = false;
                    for (TrunkSource ts : sources) {
                        if (ts.center.distSqr(cCenter) < 2) { duplicate = true; break; }
                    }
                    if (!duplicate) sources.add(new TrunkSource(nextId++, cluster, cCenter));
                }
            }
        }
        return sources;
    }

    private static PartitionResult multiSourcePartition(Level level, List<TrunkSource> sources, int hardCap, BlockState family, int hRadius, int maxLeafDepth, String familyKey) {
        Map<BlockPos, VisitInfo> visited = new HashMap<>();
        ArrayDeque<Node> q = new ArrayDeque<>();
        for (TrunkSource src : sources) {
            for (BlockPos pos : src.cluster) {
                q.add(new Node(pos, src.id, 0));
                visited.put(pos, new VisitInfo(src.id,0,false));
            }
        }
        boolean diagLogs = "jungle".equals(familyKey); // jungle logs can connect diagonally
        BlockPos mainCenter = sources.get(0).center;
        while (!q.isEmpty() && visited.size() < MAX_VISIT && visited.size() < hardCap) {
            Node node = q.poll();
            BlockState state = level.getBlockState(node.pos);
            boolean isLog = isLogOrStem(state);
            for (BlockPos n : adjacency(node.pos, isLog, diagLogs)) {
                if (!inBounds(n, mainCenter, hRadius)) continue;
                BlockState ns = level.getBlockState(n);
                boolean traversable = (isLogOrStem(ns) && isSameFamily(family, ns)) || isLeafCandidate(ns);
                if (!traversable) continue;
                int nextDist = node.dist + 1;
                if (!isLogOrStem(ns) && nextDist > maxLeafDepth) continue;
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

    private static List<BlockPos> adjacency(BlockPos pos, boolean isLog, boolean diagLogs) {
        if (isLog && diagLogs) {
            List<BlockPos> list = new ArrayList<>(26);
            for (int dx=-1; dx<=1; dx++) for (int dy=-1; dy<=1; dy++) for (int dz=-1; dz<=1; dz++) {
                if (dx==0 && dy==0 && dz==0) continue;
                list.add(pos.offset(dx,dy,dz));
            }
            return list;
        }
        List<BlockPos> list = new ArrayList<>(6);
        list.add(pos.above()); list.add(pos.below()); list.add(pos.north()); list.add(pos.south()); list.add(pos.east()); list.add(pos.west());
        return list;
    }

    // Data classes
    private record TrunkSource(int id, Set<BlockPos> cluster, BlockPos center) {}
    private record Node(BlockPos pos, int treeId, int dist) {}
    private static class VisitInfo { final int treeId; final int dist; final boolean contested; VisitInfo(int t,int d,boolean c){treeId=t;dist=d;contested=c;} }
    private record PartitionResult(Map<BlockPos, VisitInfo> map) {}
}
