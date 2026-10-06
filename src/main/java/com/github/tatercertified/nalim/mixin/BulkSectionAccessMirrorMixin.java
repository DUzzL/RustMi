package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.collision.CubeMirror;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * OreFeature writes through {@link BulkSectionAccess} straight into LevelChunkSection,
 * bypassing LevelChunk.setBlockState and with it the collision-mirror hook. On a live level
 * ({@code /place feature}, datapack ores) that would leave a stale snapshot, so every section
 * the access touched is dropped when it closes; the next collision read rebuilds it.
 */
@Mixin(BulkSectionAccess.class)
public abstract class BulkSectionAccessMirrorMixin {

    @Shadow @Final private LevelAccessor level;
    @Shadow @Final private Long2ObjectMap<LevelChunkSection> acquiredSections;

    @Inject(method = "close", at = @At("HEAD"))
    private void rustmi$dropTouchedMirrors(CallbackInfo ci) {
        for (LongIterator it = this.acquiredSections.keySet().iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            // Same lookup getSection used to acquire the section — already loaded.
            ChunkAccess chunk = this.level.getChunk(SectionPos.x(key), SectionPos.z(key));
            if (chunk instanceof ImposterProtoChunk imposter) {
                chunk = imposter.getWrapped();
            }
            if (chunk instanceof CubeMirror mirror) {
                mirror.oxi$invalidateSection(chunk.getSectionIndexFromSectionY(SectionPos.y(key)));
            }
        }
    }
}
