package dev.p2p.security;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.event.player.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import org.slf4j.*;

public final class P2PSecurity implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("P2P_Security");
    private static SecurityRuntime active;
    public static SecurityRuntime runtime(MinecraftServer server) { return active != null && active.server == server ? active : null; }
    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            active = null;
            try { active = new SecurityRuntime(server); }
            catch (Exception e) { LOG.error("Cannot initialize security; guest login will be refused", e); }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> active = null);
        ServerTickEvents.END_SERVER_TICK.register(server -> { var r = runtime(server); if (r != null) r.tick(); });
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> SecurityCommands.register(dispatcher));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var r = runtime(server);
            if (r == null) {
                if (!server.isSingleplayerOwner(handler.player.nameAndId())) handler.disconnect(Component.literal("P2P_Security 초기화 실패"));
                else handler.player.sendSystemMessage(Component.literal("[P2PS] 초기화 실패. 로그를 확인하세요."));
                return;
            }
            Component denied = r.loginDenied(handler.player.nameAndId());
            if (denied != null) { handler.disconnect(denied); return; }
            handler.player.sendSystemMessage(Component.literal(r.owner(handler.player) ? r.status()
                    : "[P2PS] 방 보호가 적용됩니다. 기본 방문자는 건축·상호작용이 제한됩니다."));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> { var r = runtime(server); if (r != null) r.disconnect(handler.player); });
        AttackEntityCallback.EVENT.register((player, level, hand, target, hit) -> check(player, SecurityStore.Action.ATTACK));
        UseEntityCallback.EVENT.register((player, level, hand, target, hit) -> check(player, SecurityStore.Action.ENTITY));
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (player instanceof ServerPlayer p) {
                var r=runtime(level.getServer());
                if(r!=null && r.inspectors.contains(p.getUUID())) { r.inspect(p,pos); return InteractionResult.FAIL; }
            }
            return check(player, SecurityStore.Action.BREAK);
        });
        // GameMode mixins enforce break/use on the server even when client-side callbacks are skipped.
        LOG.info("P2P_Security alpha: independent implementation; no CoreProtect or LuckPerms dependency");
    }
    private static InteractionResult check(net.minecraft.world.entity.player.Player p, SecurityStore.Action action) {
        if (!(p instanceof ServerPlayer player)) return InteractionResult.PASS;
        var r = runtime(player.level().getServer());
        if (r != null && r.allowed(player, action)) return InteractionResult.PASS;
        if (r != null) r.deny(player, "권한 없음: " + action);
        return InteractionResult.FAIL;
    }
}
