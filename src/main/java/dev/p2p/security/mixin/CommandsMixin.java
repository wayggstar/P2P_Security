package dev.p2p.security.mixin;

import com.mojang.brigadier.ParseResults;
import dev.p2p.security.P2PSecurity;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
public abstract class CommandsMixin {
    @Inject(method="performCommand", at=@At("HEAD"), cancellable=true)
    private void p2ps$commands(ParseResults<CommandSourceStack> parse, String raw, CallbackInfo ci) {
        var source=parse.getContext().getSource(); var r=P2PSecurity.runtime(source.getServer());
        if (source.getPlayer()!=null && source.getServer().isSingleplayerOwner(source.getPlayer().nameAndId())) return;
        // Alpha intentionally allows only our individually permission-checked command tree.
        // Checking parsed nodes rejects namespace tricks and indirect /execute entrypoints.
        var nodes=parse.getContext().getNodes();
        boolean ownTree=!nodes.isEmpty() && nodes.getFirst().getNode().getName().equals("p2ps");
        if (r!=null && r.store.healthy() && !r.store.locked() && source.getPlayer()!=null
                && r.store.ban(source.getPlayer().getUUID())==null && ownTree) return;
        source.sendFailure(Component.literal("P2P_Security alpha: 방장 외 일반/OP/간접 명령어 실행 제한"));
        ci.cancel();
    }
}
