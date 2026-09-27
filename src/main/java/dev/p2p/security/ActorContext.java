package dev.p2p.security;

import java.util.UUID;
import java.util.function.Supplier;

public final class ActorContext {
    public record Actor(UUID id, String name, boolean creative) {}
    private static final ThreadLocal<Actor> CURRENT = new ThreadLocal<>();
    public static Actor current() { return CURRENT.get(); }
    public static <T> T run(Actor actor, Supplier<T> work) {
        Actor previous = CURRENT.get(); CURRENT.set(actor);
        try { return work.get(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
}
