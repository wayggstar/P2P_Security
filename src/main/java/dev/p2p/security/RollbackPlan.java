package dev.p2p.security;

import java.util.*;

/** Pure planner. Newest change first, including chained edits to the same position. */
public final class RollbackPlan {
    public record Position(String dimension, int x, int y, int z) {
        public static Position of(SecurityStore.Change c) { return new Position(c.dimension(), c.x(), c.y(), c.z()); }
    }
    public record Edit(Position position, String expected, String replacement) {}
    public record Preview(List<Edit> edits, int conflicts, int unsupported) {}
    public static Preview preview(List<SecurityStore.Change> changes, java.util.function.Function<Position,String> read) {
        Map<Position,String> simulated = new HashMap<>();
        List<Edit> edits = new ArrayList<>();
        Set<Position> blocked = new HashSet<>();
        int conflicts = 0, unsupported = 0;
        for (var c : changes.reversed()) {
            var p = Position.of(c);
            if (!c.reversible()) { unsupported++; blocked.add(p); continue; }
            String current = simulated.computeIfAbsent(p, read);
            if (blocked.contains(p) || !Objects.equals(current, c.after())) { conflicts++; blocked.add(p); continue; }
            edits.add(new Edit(p, current, c.before())); simulated.put(p, c.before());
        }
        return new Preview(List.copyOf(edits), conflicts, unsupported);
    }
    public static List<Edit> inverse(List<Edit> applied) {
        return applied.reversed().stream().map(e -> new Edit(e.position(), e.replacement(), e.expected())).toList();
    }
}
