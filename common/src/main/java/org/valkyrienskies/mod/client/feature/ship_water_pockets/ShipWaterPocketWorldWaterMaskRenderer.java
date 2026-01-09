package org.valkyrienskies.mod.client.feature.ship_water_pockets;

import com.mojang.blaze3d.systems.RenderSystem;
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
    private static final double DEPTH_BIAS_ABSOLUTE_BELOW_WATER = 1.5;
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

        private final org.joml.Matrix4f projection = new org.joml.Matrix4f();
        private final org.joml.Matrix4f invProjection = new org.joml.Matrix4f();

        private final org.joml.Matrix4f modelViewPose = new org.joml.Matrix4f();
        private final org.joml.Matrix4f invModelViewPose = new org.joml.Matrix4f();

        private float projM00;
        private float projM01;
        private float projM02;
        private float projM03;
        private float projM10;
        private float projM11;
        private float projM12;
        private float projM13;
        private float projM20;
        private float projM21;
        private float projM22;
        private float projM23;
        private float projM30;
        private float projM31;
        private float projM32;
        private float projM33;

        private float invProjM00;
        private float invProjM01;
        private float invProjM02;
        private float invProjM03;
        private float invProjM10;
        private float invProjM11;
        private float invProjM12;
        private float invProjM13;
        private float invProjM20;
        private float invProjM21;
        private float invProjM22;
        private float invProjM23;
        private float invProjM30;
        private float invProjM31;
        private float invProjM32;
        private float invProjM33;

        private float mvpM00;
        private float mvpM01;
        private float mvpM02;
        private float mvpM10;
        private float mvpM11;
        private float mvpM12;
        private float mvpM20;
        private float mvpM21;
        private float mvpM22;
        private float mvpTX;
        private float mvpTY;
        private float mvpTZ;

        private float invMvpM00;
        private float invMvpM01;
        private float invMvpM02;
        private float invMvpM10;
        private float invMvpM11;
        private float invMvpM12;
        private float invMvpM20;
        private float invMvpM21;
        private float invMvpM22;
        private float invMvpTX;
        private float invMvpTY;
        private float invMvpTZ;

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

        // Reusable temporaries to avoid allocations.
        final RenderTemps temps = RENDER_TEMPS.get();

        final var poseMatrix = poseStack.last().pose();

        // Vanilla view bobbing is folded into the projection matrix (see GameRenderer#bobView). To keep the mask
        // perfectly stable with view bobbing on/off (and with/without shader pipelines that may implement bobbing
        // differently), we treat ProjectionMat as part of the transform when applying the depth bias.
        temps.projection.set(RenderSystem.getProjectionMatrix());
        temps.invProjection.set(temps.projection).invert();

        temps.modelViewPose.set(RenderSystem.getModelViewMatrix()).mul(poseMatrix);
        temps.invModelViewPose.set(temps.modelViewPose).invert();

        temps.projM00 = temps.projection.m00();
        temps.projM01 = temps.projection.m01();
        temps.projM02 = temps.projection.m02();
        temps.projM03 = temps.projection.m03();
        temps.projM10 = temps.projection.m10();
        temps.projM11 = temps.projection.m11();
        temps.projM12 = temps.projection.m12();
        temps.projM13 = temps.projection.m13();
        temps.projM20 = temps.projection.m20();
        temps.projM21 = temps.projection.m21();
        temps.projM22 = temps.projection.m22();
        temps.projM23 = temps.projection.m23();
        temps.projM30 = temps.projection.m30();
        temps.projM31 = temps.projection.m31();
        temps.projM32 = temps.projection.m32();
        temps.projM33 = temps.projection.m33();

        temps.invProjM00 = temps.invProjection.m00();
        temps.invProjM01 = temps.invProjection.m01();
        temps.invProjM02 = temps.invProjection.m02();
        temps.invProjM03 = temps.invProjection.m03();
        temps.invProjM10 = temps.invProjection.m10();
        temps.invProjM11 = temps.invProjection.m11();
        temps.invProjM12 = temps.invProjection.m12();
        temps.invProjM13 = temps.invProjection.m13();
        temps.invProjM20 = temps.invProjection.m20();
        temps.invProjM21 = temps.invProjection.m21();
        temps.invProjM22 = temps.invProjection.m22();
        temps.invProjM23 = temps.invProjection.m23();
        temps.invProjM30 = temps.invProjection.m30();
        temps.invProjM31 = temps.invProjection.m31();
        temps.invProjM32 = temps.invProjection.m32();
        temps.invProjM33 = temps.invProjection.m33();

        temps.mvpM00 = temps.modelViewPose.m00();
        temps.mvpM01 = temps.modelViewPose.m01();
        temps.mvpM02 = temps.modelViewPose.m02();
        temps.mvpM10 = temps.modelViewPose.m10();
        temps.mvpM11 = temps.modelViewPose.m11();
        temps.mvpM12 = temps.modelViewPose.m12();
        temps.mvpM20 = temps.modelViewPose.m20();
        temps.mvpM21 = temps.modelViewPose.m21();
        temps.mvpM22 = temps.modelViewPose.m22();
        temps.mvpTX = temps.modelViewPose.m30();
        temps.mvpTY = temps.modelViewPose.m31();
        temps.mvpTZ = temps.modelViewPose.m32();

        temps.invMvpM00 = temps.invModelViewPose.m00();
        temps.invMvpM01 = temps.invModelViewPose.m01();
        temps.invMvpM02 = temps.invModelViewPose.m02();
        temps.invMvpM10 = temps.invModelViewPose.m10();
        temps.invMvpM11 = temps.invModelViewPose.m11();
        temps.invMvpM12 = temps.invModelViewPose.m12();
        temps.invMvpM20 = temps.invModelViewPose.m20();
        temps.invMvpM21 = temps.invModelViewPose.m21();
        temps.invMvpM22 = temps.invModelViewPose.m22();
        temps.invMvpTX = temps.invModelViewPose.m30();
        temps.invMvpTY = temps.invModelViewPose.m31();
        temps.invMvpTZ = temps.invModelViewPose.m32();

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
                        if (!emitCubeSlice(consumer, poseMatrix, temps, shipToWorld, cameraPos, waterSurfaceY, depthBias,
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
        final RenderTemps temps,
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
            emitVertex(consumer, poseMatrix, temps, ptsX[base], vy, ptsZ[base], cameraPos, depthBias);
            emitVertex(consumer, poseMatrix, temps, ptsX[p1], vy, ptsZ[p1], cameraPos, depthBias);
            emitVertex(consumer, poseMatrix, temps, ptsX[p2], vy, ptsZ[p2], cameraPos, depthBias);
            emitVertex(consumer, poseMatrix, temps, ptsX[p2], vy, ptsZ[p2], cameraPos, depthBias);
        }

        return true;
    }

    private static void emitVertex(final VertexConsumer consumer, final org.joml.Matrix4f poseMatrix,
        final RenderTemps temps, final double wx, final double relY, final double wz, final Vec3 cameraPos,
        final double depthBias) {
        final float px = (float) (wx - cameraPos.x);
        final float py = (float) relY;
        final float pz = (float) (wz - cameraPos.z);

        // Apply the depth bias in clip-space Z (after projection) so its screen-space footprint stays identical.
        // This avoids the subtle "breathing" you get when view bobbing adds a translation (vanilla folds bobbing into
        // the projection matrix, so scaling in view space doesn't perfectly preserve NDC X/Y).
        final float vx = temps.mvpM00 * px + temps.mvpM10 * py + temps.mvpM20 * pz + temps.mvpTX;
        final float vy = temps.mvpM01 * px + temps.mvpM11 * py + temps.mvpM21 * pz + temps.mvpTY;
        final float vz = temps.mvpM02 * px + temps.mvpM12 * py + temps.mvpM22 * pz + temps.mvpTZ;

        final double distSq = (double) vx * vx + (double) vy * vy + (double) vz * vz;
        if (!(distSq > 0.0) || !Double.isFinite(distSq)) {
            consumer.vertex(poseMatrix, px, py, pz).endVertex();
            return;
        }

        final double dist = Math.sqrt(distSq);
        final double maxBias = dist * DEPTH_BIAS_MAX_FRACTION_OF_DISTANCE;
        final double absBiasClamped = Math.min(depthBias, maxBias);

        final double absScale = (dist - absBiasClamped) / dist;
        final float scale = (float) Math.min(absScale, (double) DEPTH_BIAS_SCALE);
        final float vScaledX = vx * scale;
        final float vScaledY = vy * scale;
        final float vScaledZ = vz * scale;

        // Clip-space (unbiased) position.
        final float clipX = temps.projM00 * vx + temps.projM10 * vy + temps.projM20 * vz + temps.projM30;
        final float clipY = temps.projM01 * vx + temps.projM11 * vy + temps.projM21 * vz + temps.projM31;
        final float clipZ = temps.projM02 * vx + temps.projM12 * vy + temps.projM22 * vz + temps.projM32;
        final float clipW = temps.projM03 * vx + temps.projM13 * vy + temps.projM23 * vz + temps.projM33;

        // Clip-space Z/W after scaling along the view ray (used only to compute the desired depth).
        final float clipScaledZ = temps.projM02 * vScaledX + temps.projM12 * vScaledY + temps.projM22 * vScaledZ + temps.projM32;
        final float clipScaledW = temps.projM03 * vScaledX + temps.projM13 * vScaledY + temps.projM23 * vScaledZ + temps.projM33;

        if (!(clipW != 0.0f) || !(clipScaledW != 0.0f)) {
            consumer.vertex(poseMatrix, px, py, pz).endVertex();
            return;
        }

        // Keep NDC X/Y identical by only changing clip-space Z (keep X/Y/W untouched).
        final float ndcZScaled = clipScaledZ / clipScaledW;
        final float biasedClipZ = ndcZScaled * clipW;

        // Back-transform from clip -> view -> local coordinates.
        final float viewX = temps.invProjM00 * clipX + temps.invProjM10 * clipY + temps.invProjM20 * biasedClipZ + temps.invProjM30 * clipW;
        final float viewY = temps.invProjM01 * clipX + temps.invProjM11 * clipY + temps.invProjM21 * biasedClipZ + temps.invProjM31 * clipW;
        final float viewZ = temps.invProjM02 * clipX + temps.invProjM12 * clipY + temps.invProjM22 * biasedClipZ + temps.invProjM32 * clipW;
        final float viewW = temps.invProjM03 * clipX + temps.invProjM13 * clipY + temps.invProjM23 * biasedClipZ + temps.invProjM33 * clipW;

        if (!(viewW != 0.0f) || !Float.isFinite(viewW)) {
            consumer.vertex(poseMatrix, px, py, pz).endVertex();
            return;
        }

        final float localX = temps.invMvpM00 * viewX + temps.invMvpM10 * viewY + temps.invMvpM20 * viewZ + temps.invMvpTX * viewW;
        final float localY = temps.invMvpM01 * viewX + temps.invMvpM11 * viewY + temps.invMvpM21 * viewZ + temps.invMvpTY * viewW;
        final float localZ = temps.invMvpM02 * viewX + temps.invMvpM12 * viewY + temps.invMvpM22 * viewZ + temps.invMvpTZ * viewW;

        final float invW = 1.0f / viewW;
        final float preX = localX * invW;
        final float preY = localY * invW;
        final float preZ = localZ * invW;

        consumer.vertex(poseMatrix, preX, preY, preZ).endVertex();
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
