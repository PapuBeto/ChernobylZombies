package com.chernobylzombies.zone;

import com.chernobylzombies.registry.ZombieVariantRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a 3D extruded polygonal spawn zone with cached AABB bounds and cached ear-clipping
 * triangulation for zero-allocation O(1) broad-phase checks and fast client rendering.
 */
public class SpawnZone {

    public static final int DEFAULT_SPAWN_INTERVAL_SECONDS = 15;
    public static final int MIN_SPAWN_INTERVAL_SECONDS = 2;
    public static final int MAX_SPAWN_INTERVAL_SECONDS = 300;
    private static final double EDGE_TOLERANCE_SQ = 0.55D * 0.55D;

    private static final String TAG_ID = "Id";
    private static final String TAG_NAME = "Name";
    private static final String TAG_COLOR = "Color";
    private static final String TAG_DIMENSION = "Dimension";
    private static final String TAG_VERTICES = "Vertices";
    private static final String TAG_MIN_Y = "MinY";
    private static final String TAG_MAX_Y = "MaxY";
    private static final String TAG_LEGACY_BOXES = "Boxes";
    private static final String TAG_LEGACY_POS1 = "Pos1";
    private static final String TAG_LEGACY_POS2 = "Pos2";
    private static final String TAG_MAX_ZOMBIES = "MaxZombies";
    private static final String TAG_SPAWN_RATE_MULTIPLIER = "SpawnRateMultiplier";
    private static final String TAG_TIME_CONDITION = "TimeCondition";
    private static final String TAG_IGNORE_LIGHT_LEVEL = "IgnoreLightLevel";
    private static final String TAG_SPAWN_INTERVAL_SECONDS = "SpawnIntervalSeconds";
    private static final String TAG_WEIGHTS = "Weights";
    private static final String TAG_ENABLED = "Enabled";

    private final UUID id;
    private String name;
    private int color;
    private ResourceKey<Level> dimension;
    private final List<BlockPos> vertices = new ArrayList<>();
    private int minY;
    private int maxY;
    private int maxZombies;
    private float spawnRateMultiplier;
    private ZoneTimeCondition timeCondition;
    private boolean ignoreLightLevel;
    private int spawnIntervalSeconds;
    private Map<Integer, Integer> allowedModelWeights;
    private boolean enabled;

    // Pre-computed geometry caches
    private AABB cachedBoundingBox = new AABB(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
    private List<int[]> cachedTriangles = List.of();
    private BlockPos cachedCenterPos = BlockPos.ZERO;
    private BlockPos cachedLabelAnchorPos = BlockPos.ZERO;

    public SpawnZone(
            UUID id,
            String name,
            int color,
            ResourceKey<Level> dimension,
            List<BlockPos> vertices,
            int minY,
            int maxY,
            int maxZombies,
            float spawnRateMultiplier,
            ZoneTimeCondition timeCondition,
            boolean ignoreLightLevel,
            int spawnIntervalSeconds,
            Map<Integer, Integer> allowedModelWeights,
            boolean enabled
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.name = (name == null || name.isBlank()) ? "Zona" : name.trim();
        this.color = color & 0xFFFFFF;
        this.dimension = dimension != null ? dimension : Level.OVERWORLD;
        this.minY = Math.min(minY, maxY);
        this.maxY = Math.max(minY, maxY);
        setVerticesInternal(vertices);
        rebuildGeometryCache();
        this.maxZombies = Math.max(0, maxZombies);
        this.spawnRateMultiplier = Math.max(0.0F, spawnRateMultiplier);
        this.timeCondition = timeCondition != null ? timeCondition : ZoneTimeCondition.ALWAYS;
        this.ignoreLightLevel = ignoreLightLevel;
        this.spawnIntervalSeconds = clampInterval(spawnIntervalSeconds);
        this.allowedModelWeights = normalizeWeights(allowedModelWeights);
        this.enabled = enabled;
    }

    public static int clampInterval(int seconds) {
        return Math.max(MIN_SPAWN_INTERVAL_SECONDS, Math.min(MAX_SPAWN_INTERVAL_SECONDS, seconds));
    }

    public static Map<Integer, Integer> createDefaultWeights() {
        Map<Integer, Integer> defaults = new LinkedHashMap<>();
        int total = ZombieVariantRegistry.getTotalVariants();
        for (int i = 0; i < total; i++) {
            defaults.put(i, 50);
        }
        return defaults;
    }

    public static Map<Integer, Integer> normalizeWeights(Map<Integer, Integer> rawWeights) {
        Map<Integer, Integer> normalized = new LinkedHashMap<>();
        int total = ZombieVariantRegistry.getTotalVariants();
        for (int i = 0; i < total; i++) {
            int raw = rawWeights != null ? rawWeights.getOrDefault(i, 0) : 0;
            normalized.put(i, Math.max(0, Math.min(100, raw)));
        }
        return normalized;
    }

    public static int computeTotalWeight(Map<Integer, Integer> weights) {
        int sum = 0;
        for (int weight : weights.values()) {
            if (weight > 0) {
                sum += weight;
            }
        }
        return sum;
    }

    public static int pickWeightedModelId(Map<Integer, Integer> weights, RandomSource random) {
        int totalWeight = computeTotalWeight(weights);
        if (totalWeight <= 0) {
            return -1;
        }
        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (Map.Entry<Integer, Integer> entry : weights.entrySet()) {
            int weight = entry.getValue();
            if (weight <= 0) {
                continue;
            }
            cumulative += weight;
            if (roll < cumulative) {
                return entry.getKey();
            }
        }
        return -1;
    }

    public static CompoundTag writeWeightsToNbt(Map<Integer, Integer> weights) {
        CompoundTag weightsTag = new CompoundTag();
        for (Map.Entry<Integer, Integer> entry : weights.entrySet()) {
            weightsTag.putInt(String.valueOf(entry.getKey()), entry.getValue());
        }
        return weightsTag;
    }

    public static Map<Integer, Integer> readWeightsFromNbt(CompoundTag weightsTag) {
        Map<Integer, Integer> weights = new LinkedHashMap<>();
        for (String key : weightsTag.getAllKeys()) {
            try {
                int modelId = Integer.parseInt(key);
                weights.put(modelId, weightsTag.getInt(key));
            } catch (NumberFormatException ignored) {
            }
        }
        return weights;
    }

    public static void writeWeightsToNetwork(FriendlyByteBuf buf, Map<Integer, Integer> weights) {
        buf.writeVarInt(weights.size());
        for (Map.Entry<Integer, Integer> entry : weights.entrySet()) {
            buf.writeVarInt(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
    }

    public static Map<Integer, Integer> readWeightsFromNetwork(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        Map<Integer, Integer> weights = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            weights.put(buf.readVarInt(), buf.readVarInt());
        }
        return weights;
    }

    private void setVerticesInternal(List<BlockPos> newVertices) {
        this.vertices.clear();
        if (newVertices != null) {
            for (BlockPos pos : newVertices) {
                if (pos != null) {
                    this.vertices.add(pos.immutable());
                }
            }
        }
    }

    private void rebuildGeometryCache() {
        if (this.vertices.isEmpty()) {
            this.cachedBoundingBox = new AABB(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
            this.cachedTriangles = List.of();
            this.cachedCenterPos = BlockPos.ZERO;
            this.cachedLabelAnchorPos = BlockPos.ZERO;
            return;
        }

        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        double sumX = 0.0D;
        double sumZ = 0.0D;

        for (BlockPos v : this.vertices) {
            int vx = v.getX();
            int vz = v.getZ();
            minX = Math.min(minX, vx);
            minZ = Math.min(minZ, vz);
            maxX = Math.max(maxX, vx);
            maxZ = Math.max(maxZ, vz);
            sumX += vx;
            sumZ += vz;
        }

        this.cachedBoundingBox = new AABB(minX, this.minY, minZ, maxX + 1.0D, this.maxY + 1.0D, maxZ + 1.0D);
        int cx = (int) Math.round(sumX / this.vertices.size());
        int cz = (int) Math.round(sumZ / this.vertices.size());
        this.cachedCenterPos = new BlockPos(cx, (this.minY + this.maxY) / 2, cz);
        this.cachedLabelAnchorPos = new BlockPos(cx, this.maxY + 1, cz);
        this.cachedTriangles = Collections.unmodifiableList(triangulatePolygonXZ(this.vertices));
    }

    public UUID getId() {
        return this.id;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = (name == null || name.isBlank()) ? "Zona" : name.trim();
    }

    public int getColor() {
        return this.color;
    }

    public void setColor(int color) {
        this.color = color & 0xFFFFFF;
    }

    public ResourceKey<Level> getDimension() {
        return this.dimension;
    }

    public void setDimension(ResourceKey<Level> dimension) {
        this.dimension = dimension != null ? dimension : Level.OVERWORLD;
    }

    public List<BlockPos> getVertices() {
        return Collections.unmodifiableList(this.vertices);
    }

    public void setVertices(List<BlockPos> newVertices) {
        setVerticesInternal(newVertices);
        rebuildGeometryCache();
    }

    public int getMinY() {
        return this.minY;
    }

    public void setMinY(int minY) {
        setYRange(minY, this.maxY);
    }

    public int getMaxY() {
        return this.maxY;
    }

    public void setMaxY(int maxY) {
        setYRange(this.minY, maxY);
    }

    public void setYRange(int y1, int y2) {
        this.minY = Math.min(y1, y2);
        this.maxY = Math.max(y1, y2);
        rebuildGeometryCache();
    }

    public int getMaxZombies() {
        return this.maxZombies;
    }

    public void setMaxZombies(int maxZombies) {
        this.maxZombies = Math.max(0, maxZombies);
    }

    public float getSpawnRateMultiplier() {
        return this.spawnRateMultiplier;
    }

    public void setSpawnRateMultiplier(float spawnRateMultiplier) {
        this.spawnRateMultiplier = Math.max(0.0F, spawnRateMultiplier);
    }

    public ZoneTimeCondition getTimeCondition() {
        return this.timeCondition;
    }

    public void setTimeCondition(ZoneTimeCondition timeCondition) {
        this.timeCondition = timeCondition != null ? timeCondition : ZoneTimeCondition.ALWAYS;
    }

    public boolean isIgnoreLightLevel() {
        return this.ignoreLightLevel;
    }

    public void setIgnoreLightLevel(boolean ignoreLightLevel) {
        this.ignoreLightLevel = ignoreLightLevel;
    }

    public int getSpawnIntervalSeconds() {
        return this.spawnIntervalSeconds;
    }

    public void setSpawnIntervalSeconds(int spawnIntervalSeconds) {
        this.spawnIntervalSeconds = clampInterval(spawnIntervalSeconds);
    }

    public Map<Integer, Integer> getAllowedModelWeights() {
        return Collections.unmodifiableMap(this.allowedModelWeights);
    }

    public void setAllowedModelWeights(Map<Integer, Integer> allowedModelWeights) {
        this.allowedModelWeights = normalizeWeights(allowedModelWeights);
    }

    public int getModelWeight(int modelId) {
        return this.allowedModelWeights.getOrDefault(modelId, 0);
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Checks whether a block position falls inside this zone using O(1) cachedAABB broad-phase
     * followed by 2D Ray-Casting in XZ.
     */
    public boolean contains(BlockPos pos) {
        if (this.vertices.size() < 3) {
            return false;
        }
        double px = pos.getX() + 0.5D;
        double py = pos.getY() + 0.5D;
        double pz = pos.getZ() + 0.5D;
        if (!this.cachedBoundingBox.contains(px, py, pz)) {
            return false;
        }
        return isPointInPolygonXZ(px, pz, this.vertices);
    }

    public boolean contains(ResourceKey<Level> levelDimension, BlockPos pos) {
        return this.dimension.equals(levelDimension) && contains(pos);
    }

    /**
     * Standard 2D Ray-Casting point-in-polygon test in the XZ plane, including boundary edges.
     */
    public static boolean isPointInPolygonXZ(double px, double pz, List<BlockPos> vertices) {
        int n = vertices.size();
        if (n < 3) {
            return false;
        }

        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = vertices.get(i).getX() + 0.5D;
            double zi = vertices.get(i).getZ() + 0.5D;
            double xj = vertices.get(j).getX() + 0.5D;
            double zj = vertices.get(j).getZ() + 0.5D;

            if (pointToSegmentDistSq(px, pz, xi, zi, xj, zj) <= EDGE_TOLERANCE_SQ) {
                return true;
            }

            boolean intersects = ((zi > pz) != (zj > pz))
                    && (px < (xj - xi) * (pz - zi) / (zj - zi) + xi);
            if (intersects) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static double pointToSegmentDistSq(double px, double pz, double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double lenSq = dx * dx + dz * dz;
        if (lenSq <= 1.0E-8D) {
            double rx = px - x1;
            double rz = pz - z1;
            return rx * rx + rz * rz;
        }
        double t = Math.max(0.0D, Math.min(1.0D, ((px - x1) * dx + (pz - z1) * dz) / lenSq));
        double projX = x1 + t * dx;
        double projZ = z1 + t * dz;
        double ex = px - projX;
        double ez = pz - projZ;
        return ex * ex + ez * ez;
    }

    /**
     * Ear-clipping triangulation in XZ plane supporting both convex and concave polygons.
     */
    public static List<int[]> triangulatePolygonXZ(List<BlockPos> vertices) {
        List<int[]> triangles = new ArrayList<>();
        int n = vertices.size();
        if (n < 3) {
            return triangles;
        }

        List<Integer> indices = new ArrayList<>(n);
        double signedArea = 0.0D;
        for (int i = 0; i < n; i++) {
            BlockPos a = vertices.get(i);
            BlockPos b = vertices.get((i + 1) % n);
            signedArea += (double) a.getX() * b.getZ() - (double) b.getX() * a.getZ();
        }
        if (signedArea >= 0.0D) {
            for (int i = 0; i < n; i++) {
                indices.add(i);
            }
        } else {
            for (int i = n - 1; i >= 0; i--) {
                indices.add(i);
            }
        }

        int guard = 0;
        while (indices.size() > 3 && guard < n * n) {
            guard++;
            boolean clipped = false;
            int m = indices.size();
            for (int i = 0; i < m; i++) {
                int iPrev = indices.get((i - 1 + m) % m);
                int iCurr = indices.get(i);
                int iNext = indices.get((i + 1) % m);

                BlockPos a = vertices.get(iPrev);
                BlockPos b = vertices.get(iCurr);
                BlockPos c = vertices.get(iNext);

                if (cross2D(a, b, c) <= 0.0D) {
                    continue;
                }

                boolean containsOther = false;
                for (int k = 0; k < m; k++) {
                    int idx = indices.get(k);
                    if (idx == iPrev || idx == iCurr || idx == iNext) {
                        continue;
                    }
                    if (isPointInTriangle2D(vertices.get(idx), a, b, c)) {
                        containsOther = true;
                        break;
                    }
                }
                if (!containsOther) {
                    triangles.add(new int[]{iPrev, iCurr, iNext});
                    indices.remove(i);
                    clipped = true;
                    break;
                }
            }
            if (!clipped) {
                break;
            }
        }

        if (indices.size() == 3) {
            triangles.add(new int[]{indices.get(0), indices.get(1), indices.get(2)});
        }
        return triangles;
    }

    private static double cross2D(BlockPos a, BlockPos b, BlockPos c) {
        return (double) (b.getX() - a.getX()) * (c.getZ() - a.getZ())
                - (double) (b.getZ() - a.getZ()) * (c.getX() - a.getX());
    }

    private static boolean isPointInTriangle2D(BlockPos p, BlockPos a, BlockPos b, BlockPos c) {
        double c1 = cross2D(a, b, p);
        double c2 = cross2D(b, c, p);
        double c3 = cross2D(c, a, p);
        return c1 >= 0.0D && c2 >= 0.0D && c3 >= 0.0D;
    }

    public AABB getBoundingBox() {
        return this.cachedBoundingBox;
    }

    public List<int[]> getCachedTriangles() {
        return this.cachedTriangles;
    }

    public BlockPos getCenterPos() {
        return this.cachedCenterPos;
    }

    public BlockPos getLabelAnchorPos() {
        return this.cachedLabelAnchorPos;
    }

    public int getTotalWeight() {
        return computeTotalWeight(this.allowedModelWeights);
    }

    public int pickRandomModelId(RandomSource random) {
        return pickWeightedModelId(this.allowedModelWeights, random);
    }

    public SpawnZone copyWithPolygon(List<BlockPos> newVertices, int newMinY, int newMaxY, ResourceKey<Level> newDimension) {
        return new SpawnZone(
                this.id,
                this.name,
                this.color,
                newDimension != null ? newDimension : this.dimension,
                newVertices,
                newMinY,
                newMaxY,
                this.maxZombies,
                this.spawnRateMultiplier,
                this.timeCondition,
                this.ignoreLightLevel,
                this.spawnIntervalSeconds,
                this.allowedModelWeights,
                this.enabled
        );
    }

    public void copyRulesFrom(SpawnZone source) {
        if (source == null) {
            return;
        }
        this.maxZombies = source.maxZombies;
        this.spawnRateMultiplier = source.spawnRateMultiplier;
        this.timeCondition = source.timeCondition;
        this.ignoreLightLevel = source.ignoreLightLevel;
        this.spawnIntervalSeconds = source.spawnIntervalSeconds;
        this.allowedModelWeights = normalizeWeights(source.allowedModelWeights);
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_ID, this.id);
        tag.putString(TAG_NAME, this.name);
        tag.putInt(TAG_COLOR, this.color);
        tag.putString(TAG_DIMENSION, this.dimension.location().toString());
        tag.putInt(TAG_MIN_Y, this.minY);
        tag.putInt(TAG_MAX_Y, this.maxY);
        tag.putInt(TAG_MAX_ZOMBIES, this.maxZombies);
        tag.putFloat(TAG_SPAWN_RATE_MULTIPLIER, this.spawnRateMultiplier);
        tag.putString(TAG_TIME_CONDITION, this.timeCondition.name());
        tag.putBoolean(TAG_IGNORE_LIGHT_LEVEL, this.ignoreLightLevel);
        tag.putInt(TAG_SPAWN_INTERVAL_SECONDS, this.spawnIntervalSeconds);
        tag.putBoolean(TAG_ENABLED, this.enabled);

        long[] packedVertices = new long[this.vertices.size()];
        for (int i = 0; i < this.vertices.size(); i++) {
            packedVertices[i] = this.vertices.get(i).asLong();
        }
        tag.putLongArray(TAG_VERTICES, packedVertices);
        tag.put(TAG_WEIGHTS, writeWeightsToNbt(this.allowedModelWeights));
        return tag;
    }

    public static SpawnZone fromNbt(CompoundTag tag) {
        UUID id = tag.hasUUID(TAG_ID) ? tag.getUUID(TAG_ID) : UUID.randomUUID();
        String name = tag.getString(TAG_NAME);
        int color = tag.contains(TAG_COLOR) ? tag.getInt(TAG_COLOR) : 0x33CC55;
        ResourceLocation dimLoc = ResourceLocation.tryParse(tag.getString(TAG_DIMENSION));
        ResourceKey<Level> dimension = dimLoc != null
                ? ResourceKey.create(Registries.DIMENSION, dimLoc)
                : Level.OVERWORLD;

        List<BlockPos> vertices = new ArrayList<>();
        int minY = tag.contains(TAG_MIN_Y) ? tag.getInt(TAG_MIN_Y) : 0;
        int maxY = tag.contains(TAG_MAX_Y) ? tag.getInt(TAG_MAX_Y) : 15;

        if (tag.contains(TAG_VERTICES)) {
            for (long packed : tag.getLongArray(TAG_VERTICES)) {
                vertices.add(BlockPos.of(packed));
            }
        } else if (tag.contains(TAG_LEGACY_BOXES, Tag.TAG_LIST)) {
            ListTag boxesTag = tag.getList(TAG_LEGACY_BOXES, Tag.TAG_COMPOUND);
            if (!boxesTag.isEmpty()) {
                CompoundTag firstBox = boxesTag.getCompound(0);
                BlockPos min = BlockPos.of(firstBox.getLong("Min"));
                BlockPos max = BlockPos.of(firstBox.getLong("Max"));
                addRectangleVertices(vertices, min, max);
                minY = Math.min(min.getY(), max.getY());
                maxY = Math.max(min.getY(), max.getY());
            }
        } else if (tag.contains(TAG_LEGACY_POS1) && tag.contains(TAG_LEGACY_POS2)) {
            BlockPos p1 = BlockPos.of(tag.getLong(TAG_LEGACY_POS1));
            BlockPos p2 = BlockPos.of(tag.getLong(TAG_LEGACY_POS2));
            addRectangleVertices(vertices, p1, p2);
            minY = Math.min(p1.getY(), p2.getY());
            maxY = Math.max(p1.getY(), p2.getY());
        }

        int maxZombies = tag.contains(TAG_MAX_ZOMBIES) ? tag.getInt(TAG_MAX_ZOMBIES) : 15;
        float rate = tag.contains(TAG_SPAWN_RATE_MULTIPLIER) ? tag.getFloat(TAG_SPAWN_RATE_MULTIPLIER) : 1.0F;
        ZoneTimeCondition timeCondition = ZoneTimeCondition.fromName(tag.getString(TAG_TIME_CONDITION));
        boolean ignoreLight = !tag.contains(TAG_IGNORE_LIGHT_LEVEL) || tag.getBoolean(TAG_IGNORE_LIGHT_LEVEL);
        int interval = tag.contains(TAG_SPAWN_INTERVAL_SECONDS)
                ? tag.getInt(TAG_SPAWN_INTERVAL_SECONDS)
                : DEFAULT_SPAWN_INTERVAL_SECONDS;
        boolean enabled = !tag.contains(TAG_ENABLED) || tag.getBoolean(TAG_ENABLED);
        Map<Integer, Integer> weights = readWeightsFromNbt(tag.getCompound(TAG_WEIGHTS));

        return new SpawnZone(
                id, name, color, dimension, vertices, minY, maxY, maxZombies, rate,
                timeCondition, ignoreLight, interval, weights, enabled
        );
    }

    private static void addRectangleVertices(List<BlockPos> out, BlockPos c1, BlockPos c2) {
        int minX = Math.min(c1.getX(), c2.getX());
        int maxX = Math.max(c1.getX(), c2.getX());
        int minZ = Math.min(c1.getZ(), c2.getZ());
        int maxZ = Math.max(c1.getZ(), c2.getZ());
        int y = Math.min(c1.getY(), c2.getY());
        out.add(new BlockPos(minX, y, minZ));
        out.add(new BlockPos(maxX, y, minZ));
        out.add(new BlockPos(maxX, y, maxZ));
        out.add(new BlockPos(minX, y, maxZ));
    }

    public void toNetwork(FriendlyByteBuf buf) {
        buf.writeUUID(this.id);
        buf.writeUtf(this.name, 128);
        buf.writeInt(this.color);
        buf.writeResourceLocation(this.dimension.location());
        buf.writeVarInt(this.vertices.size());
        for (BlockPos pos : this.vertices) {
            buf.writeBlockPos(pos);
        }
        buf.writeInt(this.minY);
        buf.writeInt(this.maxY);
        buf.writeVarInt(this.maxZombies);
        buf.writeFloat(this.spawnRateMultiplier);
        buf.writeEnum(this.timeCondition);
        buf.writeBoolean(this.ignoreLightLevel);
        buf.writeVarInt(this.spawnIntervalSeconds);
        buf.writeBoolean(this.enabled);
        writeWeightsToNetwork(buf, this.allowedModelWeights);
    }

    public static SpawnZone fromNetwork(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        String name = buf.readUtf(128);
        int color = buf.readInt();
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, buf.readResourceLocation());

        int vertexCount = buf.readVarInt();
        List<BlockPos> vertices = new ArrayList<>(vertexCount);
        for (int i = 0; i < vertexCount; i++) {
            vertices.add(buf.readBlockPos());
        }
        int minY = buf.readInt();
        int maxY = buf.readInt();

        int maxZombies = buf.readVarInt();
        float rate = buf.readFloat();
        ZoneTimeCondition timeCondition = buf.readEnum(ZoneTimeCondition.class);
        boolean ignoreLight = buf.readBoolean();
        int interval = buf.readVarInt();
        boolean enabled = buf.readBoolean();
        Map<Integer, Integer> weights = readWeightsFromNetwork(buf);

        return new SpawnZone(
                id, name, color, dimension, vertices, minY, maxY, maxZombies, rate,
                timeCondition, ignoreLight, interval, weights, enabled
        );
    }
}
