package dev.p2p.security;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public final class SecurityRuntime {
    private static final Set<String> SIMPLE = Set.of("minecraft:air", "minecraft:stone", "minecraft:cobblestone",
            "minecraft:dirt", "minecraft:oak_planks", "minecraft:glass", "minecraft:bricks", "minecraft:obsidian",
            "minecraft:netherrack", "minecraft:iron_block", "minecraft:gold_block", "minecraft:diamond_block");
    public final MinecraftServer server;
    public final SecurityStore store;
    public final UUID worldId;
    private final Path world;
    private final Path backups;
    private long revision;
    private long nextBackup;
    private String backupStatus = "아직 백업 없음";
    private final Map<UUID, Pending> plans = new HashMap<>();
    private final Map<UUID, Undo> undo = new HashMap<>();
    private final Map<UUID, Long> messages = new HashMap<>();
    public final Set<UUID> inspectors = new HashSet<>();
    private record Pending(String token, long revision, long expires, RollbackPlan.Preview preview) {}
    private record Undo(long revision, List<RollbackPlan.Edit> edits) {}

    public SecurityRuntime(MinecraftServer server) throws IOException {
        this.server = server;
        world = server.getWorldPath(LevelResource.ROOT).toRealPath();
        Path marker = world.resolve("p2p_security_world_id.txt");
        if (!Files.exists(marker)) SecurityStore.atomicWrite(marker, UUID.randomUUID().toString());
        try { worldId = UUID.fromString(Files.readString(marker).trim()); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid persistent world ID", e); }
        Path base = FabricLoader.getInstance().getConfigDir().resolve("p2p_security/worlds").resolve(worldId.toString());
        store = new SecurityStore(base);
        backups = FabricLoader.getInstance().getGameDir().resolve("p2p_security_backups").resolve(worldId.toString());
        store.load(); resetBackupTimer();
    }
    public boolean owner(NameAndId profile) { return server.isSingleplayerOwner(profile); }
    public boolean owner(ServerPlayer p) { return p != null && owner(p.nameAndId()); }
    public boolean owner(CommandSourceStack source) { return owner(source.getPlayer()); }
    public boolean allowed(ServerPlayer p, SecurityStore.Action action) {
        return p != null && store.allowed(p.getUUID(), owner(p), action);
    }
    public boolean allowed(CommandSourceStack s, SecurityStore.Action action) { return allowed(s.getPlayer(), action); }
    public Component loginDenied(NameAndId profile) {
        if (owner(profile)) return null;
        if (!store.healthy()) return Component.literal("P2P_Security: 보안 저장소 오류. 방장에게 문의하세요.");
        var ban = store.ban(profile.id());
        if (ban != null) return Component.literal("P2P_Security: 영구 차단 — " + ban.reason());
        if (store.locked()) return Component.literal("P2P_Security: 방이 긴급 잠금 상태입니다.");
        return null;
    }
    public void deny(ServerPlayer p, String message) {
        long now = System.nanoTime();
        if (now - messages.getOrDefault(p.getUUID(), 0L) > 2_000_000_000L) {
            p.sendSystemMessage(Component.literal("[P2P_Security] " + message)); messages.put(p.getUUID(), now);
        }
    }
    public void disconnect(ServerPlayer p) { messages.remove(p.getUUID()); inspectors.remove(p.getUUID()); plans.remove(p.getUUID()); undo.remove(p.getUUID()); }
    public static boolean simple(BlockState state) {
        return !state.hasBlockEntity() && state.getFluidState().isEmpty()
                && SIMPLE.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }
    public void changed(ServerLevel level, BlockPos pos, BlockState before, BlockState after, ActorContext.Actor actor) {
        if (before.equals(after)) return;
        revision++;
        try {
            store.record(actor.id(), actor.name(), level.dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ(),
                    BlockStateParser.serialize(before), BlockStateParser.serialize(after),
                    actor.creative() && simple(before) && simple(after));
        } catch (IOException e) { P2PSecurity.LOG.error("Block audit failed; guest changes are now disabled", e); }
    }
    public void inspect(ServerPlayer p, BlockPos pos) {
        if (!allowed(p, SecurityStore.Action.AUDIT)) { inspectors.remove(p.getUUID()); deny(p, "조사 권한 없음"); return; }
        String dimension = p.level().dimension().identifier().toString();
        var found = store.recent().reversed().stream().filter(c -> c.dimension().equals(dimension)
                && c.x() == pos.getX() && c.y() == pos.getY() && c.z() == pos.getZ()).limit(8).toList();
        p.sendSystemMessage(Component.literal("[P2PS] " + pos.toShortString() + " 최근 " + found.size() + "건 (조회 창: 최근 20,000건)"));
        found.forEach(c -> p.sendSystemMessage(Component.literal(format(c))));
    }
    public static String format(SecurityStore.Change c) {
        return "#" + c.id() + " " + c.time() + " " + c.name() + " [" + c.actor() + "] "
                + c.x() + "," + c.y() + "," + c.z() + " " + c.before() + " → " + c.after()
                + (c.reversible() ? "" : " (복구 미지원)");
    }
    private ServerLevel level(RollbackPlan.Position p) {
        for (var level : server.getAllLevels()) if (level.dimension().identifier().toString().equals(p.dimension())) return level;
        return null;
    }
    private String read(RollbackPlan.Position p) {
        var level = level(p); BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
        if (level == null || !level.hasChunkAt(pos)) return null;
        BlockState state = level.getBlockState(pos);
        if (!simple(state)) return null;
        return BlockStateParser.serialize(state);
    }
    public String preview(ServerPlayer by, UUID target, int minutes, int radius) throws IOException {
        store.requireHealthy();
        Instant since = Instant.now().minusSeconds(minutes * 60L);
        BlockPos center = by.blockPosition(); String dimension = by.level().dimension().identifier().toString();
        var all = store.recent();
        var selected = all.stream().filter(c -> c.actor().equals(target) && Instant.parse(c.time()).isAfter(since)
                && c.dimension().equals(dimension) && Math.abs((long)c.x()-center.getX()) <= radius
                && Math.abs((long)c.y()-center.getY()) <= radius && Math.abs((long)c.z()-center.getZ()) <= radius).toList();
        if (selected.size() > 200) throw new IOException("한 번에 최대 200개 이벤트. 기간/반경을 줄이세요.");
        // Conservatively protect a position if another player edited it after any selected event,
        // even if the current block happens to equal the original after-state (ABA case).
        Map<RollbackPlan.Position, Long> first = new HashMap<>();
        selected.forEach(c -> first.merge(RollbackPlan.Position.of(c), c.id(), Math::min));
        Set<RollbackPlan.Position> touched = new HashSet<>();
        all.stream().filter(c -> !c.actor().equals(target)).forEach(c -> {
            var pos = RollbackPlan.Position.of(c);
            if (c.id() > first.getOrDefault(pos, Long.MAX_VALUE)) touched.add(pos);
        });
        var result = RollbackPlan.preview(selected, pos -> touched.contains(pos) ? null : read(pos));
        String token = UUID.randomUUID().toString();
        plans.put(by.getUUID(), new Pending(token, revision, System.nanoTime() + 60_000_000_000L, result));
        return "미리보기: 적용 " + result.edits().size() + ", 충돌 " + result.conflicts() + ", 미지원 " + result.unsupported()
                + " (최근 20,000건 한정). 60초 내 /p2ps apply " + token;
    }
    public String apply(ServerPlayer by, String token) throws Exception {
        Pending pending = plans.remove(by.getUUID());
        if (pending == null || !pending.token().equals(token) || System.nanoTime() > pending.expires()
                || pending.revision() != revision) throw new IOException("미리보기 만료 또는 월드 변경. preview를 다시 실행하세요.");
        return applyEdits(by, pending.preview().edits(), false);
    }
    public String undo(ServerPlayer by) throws Exception {
        var saved = undo.get(by.getUUID());
        if (saved == null) throw new IOException("이 세션에서 취소할 복구 없음");
        if (saved.revision() != revision) throw new IOException("복구 후 다른 변경이 기록되어 취소를 중단했습니다.");
        String result = applyEdits(by, RollbackPlan.inverse(saved.edits()), true);
        undo.remove(by.getUUID());
        return result;
    }
    private String applyEdits(ServerPlayer by, List<RollbackPlan.Edit> edits, boolean undoing) throws Exception {
        store.requireHealthy();
        if (edits.isEmpty()) return "적용할 변경 없음";
        // Full snapshot first; abort rather than perform a destructive change without recovery data.
        backup();
        String job = UUID.randomUUID().toString();
        SecurityStore.atomicWrite(store.directory().resolve("job-" + job + ".json"), new Gson().toJson(edits));
        store.audit(by.getUUID(), undoing ? "UNDO_BEGIN" : "ROLLBACK_BEGIN", job);
        List<RollbackPlan.Edit> applied = new ArrayList<>(); int skipped = 0;
        Set<RollbackPlan.Position> blocked = new HashSet<>();
        try {
            for (var e : edits) {
                if (blocked.contains(e.position()) || !Objects.equals(read(e.position()), e.expected())) {
                    skipped++; blocked.add(e.position()); continue;
                }
                ServerLevel level = level(e.position());
                var state = BlockStateParser.parseForBlock(level.registryAccess().lookupOrThrow(Registries.BLOCK), e.replacement(), false).blockState();
                if (!simple(state)) { skipped++; blocked.add(e.position()); continue; }
                boolean ok = level.setBlock(new BlockPos(e.position().x(),e.position().y(),e.position().z()), state, 2);
                if (ok && Objects.equals(read(e.position()), e.replacement())) { applied.add(e); revision++; }
                else { skipped++; blocked.add(e.position()); }
            }
        } finally {
            if (!undoing && !applied.isEmpty()) undo.put(by.getUUID(), new Undo(revision, List.copyOf(applied)));
            SecurityStore.atomicWrite(store.directory().resolve("job-" + job + "-result.json"), new Gson().toJson(applied));
        }
        store.audit(by.getUUID(), "ROLLBACK_END", job + " applied=" + applied.size() + " skipped=" + skipped);
        return "적용 " + applied.size() + ", 충돌/건너뜀 " + skipped + ". 작업 ID " + job;
    }
    public Path backup() throws IOException {
        store.requireHealthy();
        // Runs on the server thread. Flush async chunk writes before reading world files.
        server.saveEverything(false, true, true);
        try {
            Path result = BackupService.create(world, backups);
            backupStatus = "성공: " + result.getFileName(); return result;
        } catch (IOException e) { backupStatus = "실패: " + e.getMessage(); throw e; }
        finally { resetBackupTimer(); }
    }
    public void resetBackupTimer() { nextBackup = System.nanoTime() + Math.max(1, store.backupMinutes()) * 60_000_000_000L; }
    public void tick() {
        if (store.healthy() && store.backupMinutes() > 0 && System.nanoTime() >= nextBackup) {
            try { backup(); }
            catch (IOException e) { P2PSecurity.LOG.error("Automatic backup failed", e);
                for (var p : server.getPlayerList().getPlayers()) if (owner(p)) p.sendSystemMessage(Component.literal("[P2PS] " + backupStatus)); }
        }
    }
    public String status() {
        return "P2P_Security 0.1.0-alpha.1 | 자체 구현, CP/LP 불필요 | 저장=" + (store.healthy() ? "정상" : store.error())
                + " | 잠금=" + store.locked() + " | 밴=" + store.bans().size() + " | 로그=" + store.recent().size()
                + " | 자동백업=" + store.backupMinutes() + "분 | " + backupStatus;
    }
}
