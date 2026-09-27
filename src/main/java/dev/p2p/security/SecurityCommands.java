package dev.p2p.security;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.commands.Commands.*;

public final class SecurityCommands {

    private interface Work {
        String run(CommandSourceStack s, SecurityRuntime r) throws Exception;
    }

    private static int run(
            CommandContext<CommandSourceStack> ctx,
            Work work
    ) {
        var source = ctx.getSource();
        var r = P2PSecurity.runtime(source.getServer());

        if (r == null) {
            source.sendFailure(
                    Component.literal(
                            "P2P_Security 초기화 실패. 서버 로그를 확인하세요."
                    )
            );
            return 0;
        }

        try {
            String message = work.run(source, r);

            source.sendSuccess(
                    () -> Component.literal(message),
                    false
            );

            return 1;

        } catch (Exception e) {
            source.sendFailure(
                    Component.literal("[P2PS] " + e.getMessage())
            );

            return 0;
        }
    }

    private static boolean owner(CommandSourceStack s) {
        var r = P2PSecurity.runtime(s.getServer());
        return r != null && r.owner(s);
    }

    private static boolean allowed(
            CommandSourceStack s,
            SecurityStore.Action action
    ) {
        var r = P2PSecurity.runtime(s.getServer());
        return r != null && r.allowed(s, action);
    }

    /*
     * UUID 또는 현재 접속 중인 플레이어 이름을 UUID로 변환.
     *
     * 예:
     *   /p2ps role Steve member
     *   /p2ps role 12345678-1234-1234-1234-123456789abc member
     *
     * 둘 다 사용 가능.
     */
    private static UUID uuid(CommandContext<CommandSourceStack> c) {
        String input = StringArgumentType.getString(c, "uuid");

        // 먼저 UUID인지 확인
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException ignored) {
        }

        // UUID가 아니라면 현재 접속 중인 플레이어 이름으로 검색
        for (var player :
                c.getSource()
                        .getServer()
                        .getPlayerList()
                        .getPlayers()) {

            if (player.getName()
                    .getString()
                    .equalsIgnoreCase(input)) {

                return player.getUUID();
            }
        }

        throw new IllegalArgumentException(
                "플레이어를 찾을 수 없습니다: " + input
        );
    }

    /*
     * 플레이어 이름 Tab Completion.
     *
     * 실제 입력되는 값:
     *   Steve
     *
     * Tooltip:
     *   UUID: xxxxxxxx-xxxx-....
     */
    private static CompletableFuture<Suggestions> suggestPlayers(
            CommandContext<CommandSourceStack> ctx,
            SuggestionsBuilder builder
    ) {
        for (var player :
                ctx.getSource()
                        .getServer()
                        .getPlayerList()
                        .getPlayers()) {

            builder.suggest(
                    player.getName().getString(),
                    Component.literal(
                            "UUID: " + player.getUUID()
                    )
            );
        }

        return builder.buildFuture();
    }

    private static UUID actor(CommandSourceStack s) throws Exception {
        return s.getPlayerOrException().getUUID();
    }

    private static void protectOwner(
            SecurityRuntime r,
            UUID target
    ) {
        var profile = r.server.getSingleplayerProfile();

        if (profile != null && profile.id().equals(target)) {
            throw new IllegalArgumentException(
                    "방장 계정은 제재/역할 변경 대상이 아닙니다."
            );
        }
    }

    public static void register(
            CommandDispatcher<CommandSourceStack> d
    ) {

        var root = literal("p2ps")
                .executes(c -> run(
                        c,
                        (s, r) ->
                                "status, players, role, permit, ban, unban, " +
                                        "bans, lock, inspect, lookup, preview, apply, " +
                                        "undo, backup, autobackup"
                ));

        /*
         * STATUS
         */
        root.then(
                literal("status")
                        .requires(SecurityCommands::owner)
                        .executes(c -> run(
                                c,
                                (s, r) -> r.status()
                        ))
        );

        /*
         * PLAYERS
         */
        root.then(
                literal("players")
                        .requires(SecurityCommands::owner)
                        .executes(c -> run(c, (s, r) -> {

                            for (var p :
                                    r.server
                                            .getPlayerList()
                                            .getPlayers()) {

                                s.sendSuccess(
                                        () -> Component.literal(
                                                p.getName().getString()
                                                        + " "
                                                        + p.getUUID()
                                        ),
                                        false
                                );
                            }

                            return "플레이어 이름 또는 UUID를 사용할 수 있습니다.";
                        }))
        );

        /*
         * ROLE
         *
         * /p2ps role <player|uuid> visitor
         * /p2ps role <player|uuid> member
         * /p2ps role <player|uuid> moderator
         */
        root.then(
                literal("role")
                        .requires(SecurityCommands::owner)

                        .then(
                                argument(
                                        "uuid",
                                        StringArgumentType.word()
                                )

                                        .suggests(
                                                SecurityCommands::suggestPlayers
                                        )

                                        .then(
                                                argument(
                                                        "role",
                                                        StringArgumentType.word()
                                                )

                                                        .suggests(
                                                                (c, b) ->
                                                                        net.minecraft.commands
                                                                                .SharedSuggestionProvider
                                                                                .suggest(
                                                                                        List.of(
                                                                                                "visitor",
                                                                                                "member",
                                                                                                "moderator"
                                                                                        ),
                                                                                        b
                                                                                )
                                                        )

                                                        .executes(c -> run(
                                                                c,
                                                                (s, r) -> {

                                                                    var id = uuid(c);

                                                                    protectOwner(r, id);

                                                                    r.store.role(
                                                                            id,
                                                                            SecurityStore.Role
                                                                                    .valueOf(
                                                                                            StringArgumentType
                                                                                                    .getString(
                                                                                                            c,
                                                                                                            "role"
                                                                                                    )
                                                                                                    .toUpperCase(
                                                                                                            Locale.ROOT
                                                                                                    )
                                                                                    ),
                                                                            actor(s)
                                                                    );

                                                                    return "역할 저장 완료";
                                                                }
                                                        ))
                                        )
                        )
        );

        /*
         * PERMIT
         *
         * /p2ps permit <player|uuid> BREAK deny
         */
        root.then(
                literal("permit")
                        .requires(SecurityCommands::owner)

                        .then(
                                argument(
                                        "uuid",
                                        StringArgumentType.word()
                                )

                                        .suggests(
                                                SecurityCommands::suggestPlayers
                                        )

                                        .then(
                                                argument(
                                                        "action",
                                                        StringArgumentType.word()
                                                )

                                                        .suggests(
                                                                (c, b) ->
                                                                        net.minecraft.commands
                                                                                .SharedSuggestionProvider
                                                                                .suggest(
                                                                                        Arrays.stream(
                                                                                                SecurityStore.Action.values()
                                                                                        ).map(
                                                                                                Enum::name
                                                                                        ),
                                                                                        b
                                                                                )
                                                        )

                                                        .then(
                                                                argument(
                                                                        "decision",
                                                                        StringArgumentType.word()
                                                                )

                                                                        .suggests(
                                                                                (c, b) ->
                                                                                        net.minecraft.commands
                                                                                                .SharedSuggestionProvider
                                                                                                .suggest(
                                                                                                        List.of(
                                                                                                                "allow",
                                                                                                                "deny",
                                                                                                                "unset"
                                                                                                        ),
                                                                                                        b
                                                                                                )
                                                                        )

                                                                        .executes(c -> run(
                                                                                c,
                                                                                (s, r) -> {

                                                                                    var id = uuid(c);

                                                                                    protectOwner(
                                                                                            r,
                                                                                            id
                                                                                    );

                                                                                    String decision =
                                                                                            StringArgumentType
                                                                                                    .getString(
                                                                                                            c,
                                                                                                            "decision"
                                                                                                    );

                                                                                    Boolean allow =
                                                                                            switch (decision) {

                                                                                                case "allow" ->
                                                                                                        true;

                                                                                                case "deny" ->
                                                                                                        false;

                                                                                                case "unset" ->
                                                                                                        null;

                                                                                                default ->
                                                                                                        throw new IllegalArgumentException(
                                                                                                                "allow/deny/unset"
                                                                                                        );
                                                                                            };

                                                                                    r.store.permit(
                                                                                            id,
                                                                                            SecurityStore.Action
                                                                                                    .valueOf(
                                                                                                            StringArgumentType
                                                                                                                    .getString(
                                                                                                                            c,
                                                                                                                            "action"
                                                                                                                    )
                                                                                                                    .toUpperCase(
                                                                                                                            Locale.ROOT
                                                                                                                    )
                                                                                                    ),
                                                                                            allow,
                                                                                            actor(s)
                                                                                    );

                                                                                    return "권한 저장 완료";
                                                                                }
                                                                        ))
                                                        )
                                        )
                        )
        );

        /*
         * BAN
         *
         * /p2ps ban <player|uuid> <reason>
         */
        root.then(
                literal("ban")
                        .requires(SecurityCommands::owner)

                        .then(
                                argument(
                                        "uuid",
                                        StringArgumentType.word()
                                )

                                        .suggests(
                                                SecurityCommands::suggestPlayers
                                        )

                                        .then(
                                                argument(
                                                        "reason",
                                                        StringArgumentType.greedyString()
                                                )

                                                        .executes(c -> run(
                                                                c,
                                                                (s, r) -> {

                                                                    UUID id = uuid(c);

                                                                    protectOwner(
                                                                            r,
                                                                            id
                                                                    );

                                                                    r.store.ban(
                                                                            id,
                                                                            StringArgumentType
                                                                                    .getString(
                                                                                            c,
                                                                                            "reason"
                                                                                    ),
                                                                            actor(s)
                                                                    );

                                                                    var p =
                                                                            r.server
                                                                                    .getPlayerList()
                                                                                    .getPlayer(id);

                                                                    if (p != null) {
                                                                        p.connection.disconnect(
                                                                                r.loginDenied(
                                                                                        p.nameAndId()
                                                                                )
                                                                        );
                                                                    }

                                                                    return "영구 차단 저장 완료. "
                                                                            + "방 재개설 후에도 적용됩니다.";
                                                                }
                                                        ))
                                        )
                        )
        );

        /*
         * UNBAN
         */
        root.then(
                literal("unban")
                        .requires(SecurityCommands::owner)

                        .then(
                                argument(
                                        "uuid",
                                        StringArgumentType.word()
                                )

                                        .suggests(
                                                SecurityCommands::suggestPlayers
                                        )

                                        .executes(c -> run(
                                                c,
                                                (s, r) -> {

                                                    r.store.unban(
                                                            uuid(c),
                                                            actor(s)
                                                    );

                                                    return "차단 해제 저장 완료";
                                                }
                                        ))
                        )
        );

        /*
         * BAN LIST
         */
        root.then(
                literal("bans")
                        .requires(SecurityCommands::owner)

                        .executes(c -> run(
                                c,
                                (s, r) -> {

                                    r.store.bans()
                                            .entrySet()
                                            .stream()
                                            .limit(50)
                                            .forEach(e ->
                                                    s.sendSuccess(
                                                            () ->
                                                                    Component.literal(
                                                                            e.getKey()
                                                                                    + " "
                                                                                    + e.getValue()
                                                                                    .reason()
                                                                    ),
                                                            false
                                                    )
                                            );

                                    return "총 "
                                            + r.store.bans().size()
                                            + "건 (최대 50건 표시)";
                                }
                        ))
        );

        /*
         * EMERGENCY LOCK
         */
        root.then(
                literal("lock")
                        .requires(SecurityCommands::owner)

                        .then(
                                argument(
                                        "enabled",
                                        BoolArgumentType.bool()
                                )

                                        .executes(c -> run(
                                                c,
                                                (s, r) -> {

                                                    boolean locked =
                                                            BoolArgumentType
                                                                    .getBool(
                                                                            c,
                                                                            "enabled"
                                                                    );

                                                    r.store.lock(
                                                            locked,
                                                            actor(s)
                                                    );

                                                    if (locked) {

                                                        for (var p :
                                                                List.copyOf(
                                                                        r.server
                                                                                .getPlayerList()
                                                                                .getPlayers()
                                                                )) {

                                                            if (!r.owner(p)) {

                                                                p.connection.disconnect(
                                                                        Component.literal(
                                                                                "P2P_Security 긴급 잠금"
                                                                        )
                                                                );
                                                            }
                                                        }
                                                    }

                                                    return "긴급 잠금=" + locked;
                                                }
                                        ))
                        )
        );

        /*
         * INSPECT
         */
        root.then(
                literal("inspect")
                        .requires(
                                s -> allowed(
                                        s,
                                        SecurityStore.Action.AUDIT
                                )
                        )

                        .executes(c -> run(
                                c,
                                (s, r) -> {

                                    UUID id = actor(s);

                                    if (!r.inspectors.remove(id)) {

                                        r.inspectors.add(id);

                                        return "조사 켜짐: "
                                                + "블록을 좌/우클릭하세요.";
                                    }

                                    return "조사 꺼짐";
                                }
                        ))
        );

        /*
         * LOOKUP
         */
        root.then(
                literal("lookup")
                        .requires(
                                s -> allowed(
                                        s,
                                        SecurityStore.Action.AUDIT
                                )
                        )

                        .then(
                                argument(
                                        "uuid",
                                        StringArgumentType.word()
                                )

                                        .suggests(
                                                SecurityCommands::suggestPlayers
                                        )

                                        .executes(c -> run(
                                                c,
                                                (s, r) -> {

                                                    UUID id = uuid(c);

                                                    var found =
                                                            r.store
                                                                    .recent()
                                                                    .reversed()
                                                                    .stream()
                                                                    .filter(
                                                                            e ->
                                                                                    e.actor()
                                                                                            .equals(id)
                                                                    )
                                                                    .limit(15)
                                                                    .toList();

                                                    found.forEach(
                                                            e ->
                                                                    s.sendSuccess(
                                                                            () ->
                                                                                    Component.literal(
                                                                                            SecurityRuntime
                                                                                                    .format(e)
                                                                                    ),
                                                                            false
                                                                    )
                                                    );

                                                    return "최근 "
                                                            + found.size()
                                                            + "건. 조회 범위는 "
                                                            + "최근 20,000개 이벤트입니다.";
                                                }
                                        ))
                        )
        );

        /*
         * ROLLBACK PREVIEW
         *
         * /p2ps preview <player|uuid> <minutes> <radius>
         */
        root.then(
                literal("preview")
                        .requires(
                                s -> allowed(
                                        s,
                                        SecurityStore.Action.ROLLBACK
                                )
                        )

                        .then(
                                argument(
                                        "uuid",
                                        StringArgumentType.word()
                                )

                                        .suggests(
                                                SecurityCommands::suggestPlayers
                                        )

                                        .then(
                                                argument(
                                                        "minutes",
                                                        IntegerArgumentType.integer(
                                                                1,
                                                                1440
                                                        )
                                                )

                                                        .then(
                                                                argument(
                                                                        "radius",
                                                                        IntegerArgumentType.integer(
                                                                                1,
                                                                                32
                                                                        )
                                                                )

                                                                        .executes(c -> run(
                                                                                c,
                                                                                (s, r) ->
                                                                                        r.preview(
                                                                                                s.getPlayerOrException(),
                                                                                                uuid(c),
                                                                                                IntegerArgumentType
                                                                                                        .getInteger(
                                                                                                                c,
                                                                                                                "minutes"
                                                                                                        ),
                                                                                                IntegerArgumentType
                                                                                                        .getInteger(
                                                                                                                c,
                                                                                                                "radius"
                                                                                                        )
                                                                                        )
                                                                        ))
                                                        )
                                        )
                        )
        );

        /*
         * APPLY ROLLBACK
         */
        root.then(
                literal("apply")
                        .requires(
                                s -> allowed(
                                        s,
                                        SecurityStore.Action.ROLLBACK
                                )
                        )

                        .then(
                                argument(
                                        "token",
                                        StringArgumentType.word()
                                )

                                        .executes(c -> run(
                                                c,
                                                (s, r) ->
                                                        r.apply(
                                                                s.getPlayerOrException(),
                                                                StringArgumentType
                                                                        .getString(
                                                                                c,
                                                                                "token"
                                                                        )
                                                        )
                                        ))
                        )
        );

        /*
         * UNDO
         */
        root.then(
                literal("undo")
                        .requires(
                                s -> allowed(
                                        s,
                                        SecurityStore.Action.ROLLBACK
                                )
                        )

                        .executes(c -> run(
                                c,
                                (s, r) ->
                                        r.undo(
                                                s.getPlayerOrException()
                                        )
                        ))
        );

        /*
         * BACKUP
         */
        root.then(
                literal("backup")
                        .requires(SecurityCommands::owner)

                        .executes(c -> run(
                                c,
                                (s, r) ->
                                        "백업 완료: " + r.backup()
                        ))
        );

        /*
         * AUTO BACKUP
         */
        root.then(
                literal("autobackup")
                        .requires(SecurityCommands::owner)

                        .then(
                                argument(
                                        "minutes",
                                        IntegerArgumentType.integer(
                                                0,
                                                1440
                                        )
                                )

                                        .executes(c -> run(
                                                c,
                                                (s, r) -> {

                                                    r.store.backupMinutes(
                                                            IntegerArgumentType
                                                                    .getInteger(
                                                                            c,
                                                                            "minutes"
                                                                    ),
                                                            actor(s)
                                                    );

                                                    r.resetBackupTimer();

                                                    return "자동 백업 간격 저장 완료 (0=끔)";
                                                }
                                        ))
                        )
        );

        d.register(root);
    }
}