package ai.resourcepack.pool3d.game;

import org.bukkit.entity.Player;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Drops a click a shooter's client aims at the shooter themselves.
 *
 * <p>While the still stroke camera ({@link StandIn}) is up, the client picks
 * what the crosshair is on from the stand-in's eyes, which sit inside the
 * shooter's own (shrunk) body, and the client only leaves out its camera when
 * it picks. A click then goes to the server as one on the shooter, and the
 * server disconnects anybody who does that ("Cannot interact with self!")
 * before any event could stop it. So a handler sits in the shooter's
 * connection, just before the game's own, and throws those packets away.
 *
 * <p>Netty is not on the API's classpath, so the handler is a proxy of its
 * interface, reached by reflection, as Paper's own (Mojang-named) classes
 * are. Where any of that is missing it does nothing.
 */
public final class SelfClickFilter {

    private static final String NAME = "pool3d_self_click";

    private SelfClickFilter() {
    }

    static void install(Player p) {
        try {
            Object pipeline = pipeline(p);
            if (pipeline == null || call(pipeline, "get", new Class<?>[]{String.class}, NAME) != null) return;
            Class<?> handlerType = Class.forName("io.netty.channel.ChannelInboundHandler", true, pipeline.getClass().getClassLoader());
            Class<?> handlerBase = Class.forName("io.netty.channel.ChannelHandler", true, pipeline.getClass().getClassLoader());
            int self = p.getEntityId();
            InvocationHandler h = (proxy, m, args) -> forward(proxy, m, args, self);
            Object handler = Proxy.newProxyInstance(handlerType.getClassLoader(), new Class<?>[]{handlerType}, h);
            call(pipeline, "addBefore", new Class<?>[]{String.class, String.class, handlerBase}, "packet_handler", NAME, handler);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // without it a click on oneself during a stroke disconnects, as it would anyway
        }
    }

    /** Whether {@code p}'s connection has the filter: for testing from the console. */
    public static boolean installed(Player p) {
        try {
            Object pipeline = pipeline(p);
            return pipeline != null && call(pipeline, "get", new Class<?>[]{String.class}, NAME) != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    static void remove(Player p) {
        try {
            Object pipeline = pipeline(p);
            if (pipeline != null && call(pipeline, "get", new Class<?>[]{String.class}, NAME) != null)
                call(pipeline, "remove", new Class<?>[]{String.class}, NAME);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static Object pipeline(Player p) throws ReflectiveOperationException {
        Object handle = p.getClass().getMethod("getHandle").invoke(p);
        Object listener = handle.getClass().getField("connection").get(handle);
        if (listener == null) return null;
        Object connection = field(listener, "connection");
        Object channel = connection.getClass().getField("channel").get(connection);
        return call(channel, "pipeline", new Class<?>[0]);
    }

    /** Every event goes on to the next handler, but a click on {@code self}. */
    private static Object forward(Object proxy, Method m, Object[] args, int self) throws Throwable {
        String name = m.getName();
        switch (name) {
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == args[0];
            case "toString":
                return NAME;
            case "handlerAdded":
            case "handlerRemoved":
                return null;
            default:
                break;
        }
        Object ctx = args[0];
        if (name.equals("channelRead") && isClickOn(args[1], self)) return null;
        // channelRead(ctx, msg) -> ctx.fireChannelRead(msg), and so on.
        String fire = "fire" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        Method f = FIRE.computeIfAbsent(fire + "/" + (args.length - 1), k -> fireMethod(ctx.getClass(), fire, args.length - 1));
        if (f != null) {
            Object[] rest = new Object[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            f.invoke(ctx, rest);
        }
        return null;
    }

    private static final java.util.Map<String, Method> FIRE = new java.util.concurrent.ConcurrentHashMap<>();

    /** The context's fire method, from the interface that declares it (the context's own class is not public). */
    private static Method fireMethod(Class<?> c, String name, int params) {
        java.util.ArrayDeque<Class<?>> todo = new java.util.ArrayDeque<>(java.util.List.of(c.getInterfaces()));
        for (Class<?> s = c.getSuperclass(); s != null; s = s.getSuperclass()) todo.addAll(java.util.List.of(s.getInterfaces()));
        while (!todo.isEmpty()) {
            Class<?> i = todo.poll();
            for (Method m : i.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == params) return m;
            todo.addAll(java.util.List.of(i.getInterfaces()));
        }
        return null;
    }

    private static boolean isClickOn(Object packet, int self) {
        if (packet == null) return false;
        String type = packet.getClass().getSimpleName();
        if (!type.contains("Interact") && !type.contains("Attack")) return false;
        try {
            return ((Number) packet.getClass().getMethod("entityId").invoke(packet)).intValue() == self;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static Object field(Object o, String name) throws ReflectiveOperationException {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                var f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object call(Object o, String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        Method m = findPublic(o.getClass(), name, types);
        m.setAccessible(true);
        return m.invoke(o, args);
    }

    /** The method as an interface or public class declares it, so it can be called from here. */
    private static Method findPublic(Class<?> c, String name, Class<?>[] types) throws NoSuchMethodException {
        for (Class<?> i : c.getInterfaces()) {
            try {
                return i.getMethod(name, types);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return c.getMethod(name, types);
    }
}
