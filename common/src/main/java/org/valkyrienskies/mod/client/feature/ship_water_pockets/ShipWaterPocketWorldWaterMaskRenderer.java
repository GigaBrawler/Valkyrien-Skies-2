package org.valkyrienskies.mod.client.feature.ship_water_pockets;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4dc;
import org.joml.Vector3d;
import org.joml.primitives.AABBdc;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.LoadedShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.config.VSGameConfig;
import org.valkyrienskies.mod.common.feature.ship_water_pockets.ShipWaterPocketManager;

public final class ShipWaterPocketWorldWaterMaskRenderer {

    private ShipWaterPocketWorldWaterMaskRenderer() {}

    private static final float DEPTH_BIAS_SCALE = 0.9995f;
    private static final double DEPTH_BIAS_ABSOLUTE_ABOVE_WATER = 0.001;
    private static final double DEPTH_BIAS_ABSOLUTE_BELOW_WATER = 0.25;
    private static final double DEPTH_BIAS_MAX_FRACTION_OF_DISTANCE = 0.5;
    private static final int SURFACE_UPDATE_INTERVAL_TICKS = 20;

    private static final int[][] EDGES = {
        {0, 1}, {1, 2}, {2, 3}, {3, 0},
        {4, 5}, {5, 6}, {6, 7}, {7, 4},
        {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };

    private static final class RenderTemps {
        private final Vector3d nShip = new Vector3d();
        private final Vector3d tmp0 = new Vector3d();
        private final Vector3d tmp1 = new Vector3d();
        private final Vector3d tmpCorner = new Vector3d();
        private final Vector3d[] cubeWorld = new Vector3d[] {
            new Vector3d(), new Vector3d(), new Vector3d(), new Vector3d(),
            new Vector3d(), new Vector3d(), new Vector3d(), new Vector3d()
        };
        private final double[] ptsX = new double[12];
        private final double[] ptsZ = new double[12];
        private final double[] angles = new double[12];
        private final int[] order = new int[12];
    }

    private static final ThreadLocal<RenderTemps> RENDER_TEMPS = ThreadLocal.withInitial(RenderTemps::new);

    private static final class CachedSurface {
        private double y;
        private long lastTick;
    }

    private static final Map<Long, CachedSurface> WATER_SURFACE_CACHE = new HashMap<>();
    private static ClientLevel lastLevel = null;

    public static void clear() {
        WATER_SURFACE_CACHE.clear();
        lastLevel = null;
    }

    /**
     * Renders a depth-only water mask which hides the *world* water surface inside ship air pockets.
     *
     * This is a Beyond-Oxygen-style approach: we render triangles into {@link RenderType#waterMask()} before the
     * world translucent layer. This avoids chunk rebuilds and works with vanilla + Sodium/Embeddium renderers.
     */
    public static void render(final PoseStack poseStack, final Camera camera, final ClientLevel level) {
        render(poseStack, camera, level, Minecraft.getInstance().renderBuffers().bufferSource());
    }

    public static void render(final PoseStack poseStack, final Camera camera, final ClientLevel level,
        final MultiBufferSource.BufferSource bufferSource) {
        if (level == null || camera == null) return;

        if (!VSGameConfig.COMMON.WATER_POCKETS.getEnableShipWaterPockets()) return;

        if (lastLevel != level) {
            clear();
            lastLevel = level;
        }

        final Vec3 cameraPos = camera.getPosition();
        final long gameTime = level.getGameTime();

        final VertexConsumer consumer = bufferSource.getBuffer(RenderType.waterMask());
        final var poseMatrix = poseStack.last().pose();

        // Reusable temporaries to avoid allocations.
        final RenderTemps temps = RENDER_TEMPS.get();
        final Vector3d nShip = temps.nShip;
        final Vector3d tmp0 = temps.tmp0;
        final Vector3d tmp1 = temps.tmp1;
        final Vector3d tmpCorner = temps.tmpCorner;
        final Vector3d[] cubeWorld = temps.cubeWorld;
        final double[] ptsX = temps.ptsX;
        final double[] ptsZ = temps.ptsZ;
        final double[] angles = temps.angles;
        final int[] order = temps.order;

        for (final LoadedShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            final long shipId = ship.getId();

            final ShipTransform shipTransform;
            final AABBdc shipWorldAabbDc;
            if (ship instanceof final ClientShip clientShip) {
                shipTransform = clientShip.getRenderTransform();
                shipWorldAabbDc = clientShip.getRenderAABB();
            } else {
                shipTransform = ship.getShipTransform();
                shipWorldAabbDc = ship.getWorldAABB();
            }

            if (shipWorldAabbDc == null) continue;

            // Skip far ships to keep this pass cheap.
            final double closestX = Mth.clamp(cameraPos.x, shipWorldAabbDc.minX(), shipWorldAabbDc.maxX());
            final double closestY = Mth.clamp(cameraPos.y, shipWorldAabbDc.minY(), shipWorldAabbDc.maxY());
            final double closestZ = Mth.clamp(cameraPos.z, shipWorldAabbDc.minZ(), shipWorldAabbDc.maxZ());
            final double dx = closestX - cameraPos.x;
            final double dy = closestY - cameraPos.y;
            final double dz = closestZ - cameraPos.z;
            final double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > 96.0 * 96.0) continue;

            final AABB shipWorldAabb = new AABB(
                shipWorldAabbDc.minX(), shipWorldAabbDc.minY(), shipWorldAabbDc.minZ(),
                shipWorldAabbDc.maxX(), shipWorldAabbDc.maxY(), shipWorldAabbDc.maxZ()
            );

            final Double waterSurfaceY = getTopWaterSurfaceY(level, shipId, gameTime, shipWorldAabb);
            if (waterSurfaceY == null) continue;

            final ShipWaterPocketManager.ClientWaterReachableSnapshot snapshot =
                ShipWaterPocketManager.getClientWaterReachableSnapshot(level, shipId);
            if (snapshot == null) continue;

            final Matrix4dc shipToWorld = shipTransform.getShipToWorld();
            final Matrix4dc worldToShip = shipTransform.getWorldToShip();

            // Plane in ship-space: n·s = d
            worldToShip.transformDirection(tmp0.set(0.0, 1.0, 0.0), nShip);
            if (!Double.isFinite(nShip.x) || !Double.isFinite(nShip.y) || !Double.isFinite(nShip.z)) continue;
            if (Math.abs(nShip.y) < 1e-6) continue; // Ship is (nearly) vertical; skip.

            worldToShip.transformPosition(tmp0.set(0.0, waterSurfaceY, 0.0), tmp1);
            final double dPlane = nShip.dot(tmp1);

            final int minX = snapshot.getMinX();
            final int minY = snapshot.getMinY();
            final int minZ = snapshot.getMinZ();
            final int sizeX = snapshot.getSizeX();
            final int sizeY = snapshot.getSizeY();
            final int sizeZ = snapshot.getSizeZ();

            final var open = snapshot.getOpen();
            final var waterReachable = snapshot.getWaterReachable();
            if (open == null || waterReachable == null) continue;

            final boolean cameraBelow = cameraPos.y < waterSurfaceY;
            final double depthBias = cameraBelow ? DEPTH_BIAS_ABSOLUTE_BELOW_WATER : DEPTH_BIAS_ABSOLUTE_ABOVE_WATER;

            final int strideY = sizeX;
            final int strideZ = sizeX * sizeY;

            for (int lz = 0; lz < sizeZ; lz++) {
                final int z0i = minZ + lz;
                final double z0 = z0i;
                final double z1 = z0i + 1.0;

                for (int lx = 0; lx < sizeX; lx++) {
                    final int x0i = minX + lx;
                    final double x0 = x0i;
                    final double x1 = x0i + 1.0;

                    // Find the ship-space Y range where the world water plane intersects this (x,z) cell column.
                    // sy = (d - nx*sx - nz*sz) / ny
                    final double sy00 = (dPlane - nShip.x * x0 - nShip.z * z0) / nShip.y;
                    final double sy10 = (dPlane - nShip.x * x1 - nShip.z * z0) / nShip.y;
                    final double sy01 = (dPlane - nShip.x * x0 - nShip.z * z1) / nShip.y;
                    final double sy11 = (dPlane - nShip.x * x1 - nShip.z * z1) / nShip.y;

                    if (!Double.isFinite(sy00) || !Double.isFinite(sy10) || !Double.isFinite(sy01) || !Double.isFinite(sy11)) {
                        continue;
                    }

                    double minSy = Math.min(Math.min(sy00, sy10), Math.min(sy01, sy11));
                    double maxSy = Math.max(Math.max(sy00, sy10), Math.max(sy01, sy11));

                    // The water surface is rendered from the water block *below* the surface. When the surface Y is
                    // exactly on an integer boundary (common for source blocks), flooring would pick the cube above
                    // the surface, which can be solid (e.g., a ship ceiling) and cause us to miss masking the surface.
                    // Bias down to consistently select the cube below the surface in those cases.
                    int yStart = Mth.floor(Math.nextDown(minSy));
                    int yEnd = Mth.floor(Math.nextDown(maxSy));
                    if (yEnd < yStart) {
                        final int t = yStart;
                        yStart = yEnd;
                        yEnd = t;
                    }

                    final int absMinY = minY;
                    final int absMaxY = minY + sizeY - 1;
                    if (yEnd < absMinY || yStart > absMaxY) continue;
                    yStart = Math.max(yStart, absMinY);
                    yEnd = Math.min(yEnd, absMaxY);

                    for (int sy = yStart; sy <= yEnd; sy++) {
                        final int ly = sy - minY;
                        final int idx = lx + sizeX * (ly + sizeY * lz);
                        if (waterReachable.get(idx)) continue;
                        if (!isAirPocketOrAdjacent(open, waterReachable, idx, lx, ly, lz, sizeX, sizeY, sizeZ, strideY, strideZ)) continue;

                        // Slice this ship-space cube [x0,x1]×[sy,sy+1]×[z0,z1] against the world water plane.
                        if (!emitCubeSlice(consumer, poseMatrix, shipToWorld, cameraPos, waterSurfaceY, depthBias,
                            x0i, sy, z0i, tmpCorner, cubeWorld, ptsX, ptsZ, angles, order, cameraBelow)) {
                            continue;
                        }
                    }
                }
            }
        }
    }

    private static Double getTopWaterSurfaceY(final Level level, final long shipId, final long gameTime,
        final AABB shipWorldAabb) {
        final CachedSurface cached = WATER_SURFACE_CACHE.get(shipId);
        if (cached != null && gameTime - cached.lastTick < SURFACE_UPDATE_INTERVAL_TICKS) {
            return cached.y;
        }

        final Double detected = detectTopWaterSurfaceY(level, shipWorldAabb);
        if (detected == null) {
            WATER_SURFACE_CACHE.remove(shipId);
            return null;
        }

        final CachedSurface next = Objects.requireNonNullElseGet(cached, CachedSurface::new);
        next.y = detected;
        next.lastTick = gameTime;
        WATER_SURFACE_CACHE.put(shipId, next);
        return detected;
    }

    /**
     * Detect the world water surface Y (y + height) near the ship.
     */
    private static Double detectTopWaterSurfaceY(final Level level, final AABB shipWorldAabb) {
        final double centerX = (shipWorldAabb.minX + shipWorldAabb.maxX) * 0.5;
        final double centerZ = (shipWorldAabb.minZ + shipWorldAabb.maxZ) * 0.5;

        final int minY = Math.max(level.getMinBuildHeight(), Mth.floor(shipWorldAabb.minY) - 2);
        final int maxY = Math.min(level.getMaxBuildHeight() - 1, Mth.ceil(shipWorldAabb.maxY) + 2);

        // Sample just outside the ship AABB. A center-only probe frequently misses for partially submerged ships.
        final double pad = 1.0;
        final double[] xs = {shipWorldAabb.minX - pad, centerX, shipWorldAabb.maxX + pad};
        final double[] zs = {shipWorldAabb.minZ - pad, centerZ, shipWorldAabb.maxZ + pad};

        final double[] candidates = new double[9];
        int count = 0;

        for (final double x : xs) {
            for (final double z : zs) {
                final Double surface = detectTopWaterSurfaceYColumn(level, x, z, minY, maxY);
                if (surface != null) {
                    candidates[count++] = surface;
                }
            }
        }

        if (count == 0) return null;

        Arrays.sort(candidates, 0, count);
        return candidates[count / 2];
    }

    private static Double detectTopWaterSurfaceYColumn(final Level level, final double x, final double z,
        final int minY, final int maxY) {
        final int bx = Mth.floor(x);
        final int bz = Mth.floor(z);
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(bx, 0, bz);
        final BlockPos.MutableBlockPos posAbove = new BlockPos.MutableBlockPos(bx, 0, bz);
        for (int y = maxY; y >= minY; y--) {
            pos.setY(y);
            final FluidState fluid = level.getFluidState(pos);
            if (!fluid.isEmpty() && fluid.is(Fluids.WATER) && level.isEmptyBlock(posAbove.setY(y + 1))) {
                return y + (double) fluid.getHeight(level, pos);
            }
        }

        return null;
    }

    private static boolean emitCubeSlice(
        final VertexConsumer consumer,
        final org.joml.Matrix4f poseMatrix,
        final Matrix4dc shipToWorld,
        final Vec3 cameraPos,
        final double yPlane,
        final double depthBias,
        final int x0,
        final int y0,
        final int z0,
        final Vector3d tmp,
        final Vector3d[] cubeWorld,
        final double[] ptsX,
        final double[] ptsZ,
        final double[] angles,
        final int[] order,
        final boolean cameraBelow
    ) {
        final double x1 = x0 + 1.0;
        final double y1 = y0 + 1.0;
        final double z1 = z0 + 1.0;

        // Transform cube corners into world-space.
        shipToWorld.transformPosition(tmp.set(x0, y0, z0), cubeWorld[0]);
        shipToWorld.transformPosition(tmp.set(x1, y0, z0), cubeWorld[1]);
        shipToWorld.transformPosition(tmp.set(x1, y1, z0), cubeWorld[2]);
        shipToWorld.transformPosition(tmp.set(x0, y1, z0), cubeWorld[3]);
        shipToWorld.transformPosition(tmp.set(x0, y0, z1), cubeWorld[4]);
        shipToWorld.transformPosition(tmp.set(x1, y0, z1), cubeWorld[5]);
        shipToWorld.transformPosition(tmp.set(x1, y1, z1), cubeWorld[6]);
        shipToWorld.transformPosition(tmp.set(x0, y1, z1), cubeWorld[7]);

        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            final double wy = cubeWorld[i].y;
            minY = Math.min(minY, wy);
            maxY = Math.max(maxY, wy);
        }
        if (yPlane < minY || yPlane > maxY) return false;

        // Collect intersection points with the plane.
        final double eps = 1e-6;
        int count = 0;
        for (final int[] edge : EDGES) {
            final Vector3d a = cubeWorld[edge[0]];
            final Vector3d b = cubeWorld[edge[1]];
            final double ya = a.y;
            final double yb = b.y;

            final boolean crosses =
                (ya < yPlane && yb > yPlane) || (yb < yPlane && ya > yPlane) ||
                    Math.abs(ya - yPlane) < 1e-9 || Math.abs(yb - yPlane) < 1e-9;
            if (!crosses) continue;

            final double denom = (yb - ya);
            if (Math.abs(denom) < 1e-12) continue;

            final double t = (yPlane - ya) / denom;
            if (!Double.isFinite(t) || t < 0.0 || t > 1.0) continue;

            final double ix = a.x + (b.x - a.x) * t;
            final double iz = a.z + (b.z - a.z) * t;

            boolean dup = false;
            for (int i = 0; i < count; i++) {
                if (Math.abs(ix - ptsX[i]) < eps && Math.abs(iz - ptsZ[i]) < eps) {
                    dup = true;
                    break;
                }
            }
            if (dup) continue;

            ptsX[count] = ix;
            ptsZ[count] = iz;
            count++;
        }

        if (count < 3) return false;

        // Sort points around centroid to build a triangle fan.
        double cx = 0.0;
        double cz = 0.0;
        for (int i = 0; i < count; i++) {
            cx += ptsX[i];
            cz += ptsZ[i];
        }
        cx /= count;
        cz /= count;

        for (int i = 0; i < count; i++) {
            angles[i] = Math.atan2(ptsZ[i] - cz, ptsX[i] - cx);
            order[i] = i;
        }

        // Selection sort (count <= 6 in practice).
        for (int i = 0; i < count - 1; i++) {
            int best = i;
            for (int j = i + 1; j < count; j++) {
                if (angles[order[j]] < angles[order[best]]) best = j;
            }
            final int tmpOrder = order[i];
            order[i] = order[best];
            order[best] = tmpOrder;
        }

        // RenderType.waterMask() uses the default cull state (CULL). Our point ordering is CCW in (x,z), which
        // corresponds to a -Y normal in Minecraft's right-handed coordinate system. Flip to face the camera side.
        if (!cameraBelow) {
            for (int i = 0; i < count / 2; i++) {
                final int tmpIdx = order[i];
                order[i] = order[count - 1 - i];
                order[count - 1 - i] = tmpIdx;
            }
        }

        final double vy = (yPlane - cameraPos.y);
        final int base = order[0];

        for (int i = 1; i + 1 < count; i++) {
            final int p1 = order[i];
            final int p2 = order[i + 1];

            // RenderType.waterMask() uses QUADS. Emit a degenerate quad per triangle so we can still fan-triangulate.
            emitVertex(consumer, poseMatrix, ptsX[base], vy, ptsZ[base], cameraPos, depthBias);
            emitVertex(consumer, poseMatrix, ptsX[p1], vy, ptsZ[p1], cameraPos, depthBias);
            emitVertex(consumer, poseMatrix, ptsX[p2], vy, ptsZ[p2], cameraPos, depthBias);
            emitVertex(consumer, poseMatrix, ptsX[p2], vy, ptsZ[p2], cameraPos, depthBias);
        }

        return true;
    }

    private static void emitVertex(final VertexConsumer consumer, final org.joml.Matrix4f poseMatrix,
        final double wx, final double relY, final double wz, final Vec3 cameraPos, final double depthBias) {
        final double rx = (wx - cameraPos.x);
        final double rz = (wz - cameraPos.z);

        final double distSq = rx * rx + relY * relY + rz * rz;
        if (!(distSq > 0.0) || !Double.isFinite(distSq)) {
            consumer.vertex(poseMatrix, 0.0f, 0.0f, 0.0f).endVertex();
            return;
        }

        final double dist = Math.sqrt(distSq);
        final double maxBias = dist * DEPTH_BIAS_MAX_FRACTION_OF_DISTANCE;
        final double absBiasClamped = Math.min(depthBias, maxBias);

        // Push the mask towards the camera along the view ray so its screen-space footprint stays identical.
        final double absScale = (dist - absBiasClamped) / dist;
        final double scale = Math.min(absScale, (double) DEPTH_BIAS_SCALE);

        consumer.vertex(poseMatrix, (float) (rx * scale), (float) (relY * scale), (float) (rz * scale)).endVertex();
    }

    /**
     * Expand the masked volume by 1 block in all directions to prevent water surface triangles from "leaking"
     * through thin/transparent hull blocks (e.g., glass windows) due to world water still existing inside ship blocks.
     */
    private static boolean isAirPocketOrAdjacent(
        final BitSet open,
        final BitSet waterReachable,
        final int idx,
        final int lx,
        final int ly,
        final int lz,
        final int sizeX,
        final int sizeY,
        final int sizeZ,
        final int strideY,
        final int strideZ
    ) {
        // Air pocket cell itself.
        if (open.get(idx)) return true;

        // One-block expansion (Chebyshev distance <= 1) around air pocket cells.
        for (int dz = -1; dz <= 1; dz++) {
            final int nz = lz + dz;
            if (nz < 0 || nz >= sizeZ) continue;
            final int baseZ = idx + dz * strideZ;

            for (int dy = -1; dy <= 1; dy++) {
                final int ny = ly + dy;
                if (ny < 0 || ny >= sizeY) continue;
                final int baseZY = baseZ + dy * strideY;

                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    final int nx = lx + dx;
                    if (nx < 0 || nx >= sizeX) continue;
                    final int nIdx = baseZY + dx;
                    if (open.get(nIdx) && !waterReachable.get(nIdx)) return true;
                }
            }
        }

        return false;
    }
}
