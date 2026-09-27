package dev.p2p.security;

import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** One store per persistent world ID, outside the world backup. Server-thread owned. */
public final class SecurityStore {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public enum Action { BREAK, PLACE, USE, ITEM, ATTACK, ENTITY, HAZARD, AUDIT, ROLLBACK }
    public enum Role { VISITOR, MEMBER, MODERATOR }
    public record Ban(String reason, String actor, String time) {}
    public record Change(long id, String time, UUID actor, String name, String dimension,
                         int x, int y, int z, String before, String after, boolean reversible) {}
    public static final class Data {
        int schema = 1;
        Map<UUID, Ban> bans = new HashMap<>();
        Map<UUID, Role> roles = new HashMap<>();
        Map<UUID, Map<Action, Boolean>> overrides = new HashMap<>();
        boolean locked;
        int backupMinutes = 15;
    }
    private final Path directory;
    private Data data;
    private final ArrayDeque<Change> recent = new ArrayDeque<>();
    private long nextId = 1;
    private boolean healthy;
    private String error = "not loaded";
    public static final int MAX_RECENT = 20_000;

    public SecurityStore(Path directory) { this.directory = directory; }
    public void load() throws IOException {
        healthy = false;
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve("security.json");
            if (Files.exists(file)) {
                JsonObject raw = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                for (String key : List.of("schema","bans","roles","overrides","locked","backupMinutes"))
                    if (!raw.has(key) || raw.get(key).isJsonNull()) throw new IOException("Missing security field: " + key);
                data = JSON.fromJson(raw, Data.class);
            } else data = new Data();
            validate(data);
            recent.clear(); nextId = 1;
            Path history = directory.resolve("blocks.jsonl");
            if (Files.exists(history)) {
                try (var reader = Files.newBufferedReader(history)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Change c = JSON.fromJson(line, Change.class);
                        if (c == null || c.id() < nextId || c.actor() == null || c.before() == null || c.after() == null
                                || c.dimension() == null || c.time() == null) throw new IOException("Invalid block journal");
                        remember(c); nextId = c.id() + 1;
                    }
                }
            }
            if (!Files.exists(file)) save(data);
            healthy = true; error = "";
        } catch (Exception e) { fail(e); throw new IOException("Security data could not be loaded", e); }
    }
    private static void validate(Data d) throws IOException {
        if (d == null || d.schema != 1 || d.bans == null || d.roles == null || d.overrides == null
                || d.backupMinutes < 0 || d.backupMinutes > 1440) throw new IOException("Invalid security configuration");
        if (d.bans.containsKey(null) || d.roles.containsKey(null) || d.overrides.containsKey(null)
                || d.roles.containsValue(null)) throw new IOException("Invalid identity/role");
        for (Ban b : d.bans.values()) if (b == null || b.reason() == null || b.actor() == null || b.time() == null)
            throw new IOException("Invalid ban");
        for (var m : d.overrides.values()) if (m == null || m.containsKey(null) || m.containsValue(null))
            throw new IOException("Invalid permission");
    }
    private void save(Data value) throws IOException { atomicWrite(directory.resolve("security.json"), JSON.toJson(value)); }
    public static void atomicWrite(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".p2ps-", ".tmp");
        try {
            try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(content);
                while (bytes.hasRemaining()) ch.write(bytes);
                ch.force(true);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    private void update(java.util.function.Consumer<Data> mutation) throws IOException {
        requireHealthy();
        Data copy = JSON.fromJson(JSON.toJson(data), Data.class);
        mutation.accept(copy);
        try { validate(copy); save(copy); data = copy; }
        catch (Exception e) { fail(e); throw new IOException("Security update failed", e); }
    }
    public boolean allowed(UUID player, boolean owner, Action action) {
        if (owner) return true;
        if (!healthy || data.locked || data.bans.containsKey(player)) return false;
        Boolean explicit = data.overrides.getOrDefault(player, Map.of()).get(action);
        if (explicit != null) return explicit;
        Role role = data.roles.getOrDefault(player, Role.VISITOR);
        if (action == Action.HAZARD || action == Action.ROLLBACK) return false;
        if (action == Action.AUDIT) return role == Role.MODERATOR;
        return role == Role.MEMBER || role == Role.MODERATOR;
    }
    public void ban(UUID player, String reason, UUID actor) throws IOException {
        if (reason.isBlank() || reason.length() > 200) throw new IOException("Reason must be 1–200 characters");
        audit(actor, "BAN", player + " " + reason);
        update(d -> d.bans.put(player, new Ban(reason, actor.toString(), Instant.now().toString())));
    }
    public void unban(UUID player, UUID actor) throws IOException {
        audit(actor, "UNBAN", player.toString()); update(d -> d.bans.remove(player));
    }
    public void role(UUID player, Role role, UUID actor) throws IOException {
        audit(actor, "ROLE", player + " " + role); update(d -> d.roles.put(player, role));
    }
    public void permit(UUID player, Action action, Boolean allow, UUID actor) throws IOException {
        audit(actor, "PERMIT", player + " " + action + " " + allow);
        update(d -> { var m = d.overrides.computeIfAbsent(player, ignored -> new HashMap<>());
            if (allow == null) m.remove(action); else m.put(action, allow); });
    }
    public void lock(boolean locked, UUID actor) throws IOException {
        audit(actor, "LOCK", Boolean.toString(locked)); update(d -> d.locked = locked);
    }
    public void backupMinutes(int minutes, UUID actor) throws IOException {
        audit(actor, "BACKUP_INTERVAL", Integer.toString(minutes)); update(d -> d.backupMinutes = minutes);
    }
    public Ban ban(UUID id) { return data == null ? null : data.bans.get(id); }
    public Map<UUID, Ban> bans() { return data == null ? Map.of() : Map.copyOf(data.bans); }
    public boolean locked() { return data == null || data.locked; }
    public int backupMinutes() { return data == null ? 0 : data.backupMinutes; }
    public boolean healthy() { return healthy; }
    public String error() { return error; }
    public Path directory() { return directory; }
    public void requireHealthy() throws IOException { if (!healthy) throw new IOException("Security store unavailable: " + error); }
    public void fail(Exception e) { healthy = false; error = e.getClass().getSimpleName() + ": " + e.getMessage(); }
    private void append(String file, Object value) throws IOException {
        requireHealthy();
        try (FileChannel ch = FileChannel.open(directory.resolve(file), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = StandardCharsets.UTF_8.encode(new Gson().toJson(value) + "\n");
            while (buffer.hasRemaining()) ch.write(buffer);
            ch.force(false);
        } catch (IOException e) { fail(e); throw e; }
    }
    /** Audit entries are attempts; operation success is reported only after the state commit. */
    public void audit(UUID actor, String action, String detail) throws IOException {
        append("admin.jsonl", Map.of("time", Instant.now().toString(), "actor", actor.toString(),
                "action", action, "detail", detail));
    }
    public Change record(UUID actor, String name, String dimension, int x, int y, int z,
                         String before, String after, boolean reversible) throws IOException {
        Change c = new Change(nextId, Instant.now().toString(), actor, name, dimension, x, y, z, before, after, reversible);
        append("blocks.jsonl", c); nextId++; remember(c); return c;
    }
    private void remember(Change c) { recent.addLast(c); while (recent.size() > MAX_RECENT) recent.removeFirst(); }
    public List<Change> recent() { return List.copyOf(recent); }
}
