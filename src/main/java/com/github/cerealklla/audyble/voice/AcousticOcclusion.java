package com.github.cerealklla.audyble.voice;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Phase 3 -- world/environment-driven occlusion. Deliberately a simple, self-contained sampled
 * raycast rather than integrating an existing acoustic-simulation mod (Sound Physics Remastered /
 * Sound Acoustic Physics, see design-document.md section 19): those are built for vanilla's block/
 * ambient sound engine, and whether their internals are even reachable for *voice* audio (a
 * completely different packet pipeline, routed through SVC rather than vanilla's SoundManager) is
 * an open investigation, not something to guess at here. This gets Phase 3's own success criterion
 * (world geometry audibly affects voice) working on its own, real terms.
 */
public final class AcousticOcclusion {

    /** Step size along the speaker-listener line, in blocks -- finer than this has negligible effect. */
    private static final double SAMPLE_STEP = 0.5;

    /** Above this many distinct solid blocks between speaker and listener, treat as fully muffled. */
    private static final int FULLY_OCCLUDED_BLOCK_COUNT = 5;

    private AcousticOcclusion() {
    }

    /**
     * Counts distinct solid blocks along the straight line between two points.
     *
     * @return the occluding block count, capped at {@link #FULLY_OCCLUDED_BLOCK_COUNT}
     */
    public static int countOccludingBlocks(Level level, Vec3 from, Vec3 to) {
        double distance = from.distanceTo(to);
        if (distance < 0.001) {
            return 0;
        }
        int steps = (int) Math.ceil(distance / SAMPLE_STEP);
        BlockPos lastCounted = null;
        int count = 0;
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            Vec3 point = from.lerp(to, t);
            BlockPos pos = BlockPos.containing(point.x, point.y, point.z);
            if (pos.equals(lastCounted)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && state.isSolidRender()) {
                count++;
                lastCounted = pos;
                if (count >= FULLY_OCCLUDED_BLOCK_COUNT) {
                    return FULLY_OCCLUDED_BLOCK_COUNT;
                }
            }
        }
        return count;
    }

    /** Converts a raw occluding-block count into a base treatment (before gameplay hooks apply). */
    public static VoiceTreatment treatmentFor(int occludingBlocks, float baseMuffleVolumePerBlock,
                                               float baseMuffleCutoffHz) {
        if (occludingBlocks <= 0) {
            return VoiceTreatment.normal();
        }
        float volume = Math.max(0.1f, 1f - (occludingBlocks * baseMuffleVolumePerBlock));
        float cutoff = Math.max(300f, baseMuffleCutoffHz - (occludingBlocks * 150f));
        return VoiceTreatment.muffled(volume, cutoff);
    }
}
