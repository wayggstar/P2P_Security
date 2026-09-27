package dev.p2p.security.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.p2p.security.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Level.class)
public abstract class LevelMixin {
    @WrapMethod(method="setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")
    private boolean p2ps$record(BlockPos pos, BlockState state, int flags, int depth, Operation<Boolean> original) {
        var actor=ActorContext.current();
        if(actor==null || !((Object)this instanceof ServerLevel level)) return original.call(pos,state,flags,depth);
        var runtime=P2PSecurity.runtime(level.getServer());
        if(runtime==null || !runtime.store.healthy()) return false;
        BlockPos immutable=pos.immutable(); BlockState before=level.getBlockState(pos);
        boolean changed=original.call(pos,state,flags,depth);
        if(changed) runtime.changed(level,immutable,before,level.getBlockState(immutable),actor);
        return changed;
    }
}
