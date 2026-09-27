package dev.p2p.security.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.p2p.security.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.*;

@Mixin(ServerPlayerGameMode.class)
public abstract class GameModeMixin {
    @Shadow @Final protected ServerPlayer player;
    private ActorContext.Actor p2ps$actor() { return new ActorContext.Actor(player.getUUID(),player.getName().getString(),player.isCreative()); }
    private SecurityRuntime p2ps$runtime() { return P2PSecurity.runtime(player.level().getServer()); }
    private boolean p2ps$allow(SecurityStore.Action action) {
        var r=p2ps$runtime();
        if(r!=null && r.store.healthy() && r.allowed(player,action)) return true;
        if(r!=null) r.deny(player,"권한 없음 또는 저장 오류: "+action); return false;
    }
    private boolean p2ps$hazard(ItemStack stack) {
        String id=BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return id.equals("minecraft:tnt") || id.equals("minecraft:tnt_minecart") || id.equals("minecraft:end_crystal")
                || id.equals("minecraft:lava_bucket") || id.equals("minecraft:flint_and_steel") || id.equals("minecraft:fire_charge")
                || id.equals("minecraft:respawn_anchor") || id.endsWith("_bed") || id.endsWith("_spawn_egg");
    }
    @WrapMethod(method="destroyBlock")
    private boolean p2ps$break(BlockPos pos, Operation<Boolean> original) {
        var r=p2ps$runtime();
        if(r!=null && r.inspectors.contains(player.getUUID())) { r.inspect(player,pos); return false; }
        if(!p2ps$allow(SecurityStore.Action.BREAK)) return false;
        return ActorContext.run(p2ps$actor(), () -> original.call(pos));
    }
    @WrapMethod(method="useItemOn")
    private InteractionResult p2ps$useBlock(ServerPlayer actor, Level level, ItemStack stack, InteractionHand hand,
                                            BlockHitResult hit, Operation<InteractionResult> original) {
        var r=p2ps$runtime();
        if(r!=null && r.inspectors.contains(player.getUUID())) { r.inspect(player,hit.getBlockPos()); return InteractionResult.FAIL; }
        // Block use and placement are separate grants; both are needed when placing against an interactive block.
        if(!p2ps$allow(SecurityStore.Action.USE)) return InteractionResult.FAIL;
        if(stack.getItem() instanceof BlockItem && !p2ps$allow(SecurityStore.Action.PLACE)) return InteractionResult.FAIL;
        String target=BuiltInRegistries.BLOCK.getKey(level.getBlockState(hit.getBlockPos()).getBlock()).toString();
        if ((p2ps$hazard(stack) || target.equals("minecraft:tnt") || target.equals("minecraft:respawn_anchor") || target.endsWith("_bed"))
                && !p2ps$allow(SecurityStore.Action.HAZARD)) return InteractionResult.FAIL;
        return ActorContext.run(p2ps$actor(), () -> original.call(actor,level,stack,hand,hit));
    }
    @WrapMethod(method="useItem")
    private InteractionResult p2ps$useItem(ServerPlayer actor, Level level, ItemStack stack, InteractionHand hand,
                                           Operation<InteractionResult> original) {
        if(!p2ps$allow(SecurityStore.Action.ITEM) || (p2ps$hazard(stack) && !p2ps$allow(SecurityStore.Action.HAZARD))) return InteractionResult.FAIL;
        return ActorContext.run(p2ps$actor(), () -> original.call(actor,level,stack,hand));
    }
}
