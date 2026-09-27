package dev.p2p.security.mixin;

import dev.p2p.security.P2PSecurity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.net.SocketAddress;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Shadow @Final private MinecraftServer server;
    @Inject(method="canPlayerLogin", at=@At("HEAD"), cancellable=true)
    private void p2ps$login(SocketAddress address, NameAndId profile, CallbackInfoReturnable<Component> cir) {
        var r=P2PSecurity.runtime(server);
        Component denied = r == null ? (server.isSingleplayerOwner(profile) ? null : Component.literal("P2P_Security 초기화 실패")) : r.loginDenied(profile);
        if(denied!=null) cir.setReturnValue(denied);
    }
}
