package com.example.vigicam;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * PING do painel do controle: o dispositivo "apita e solta partículas no mundo" na cor dele durante 3,2 s
 * (o mesmo tempo do destaque do HTML). O apito é o mesmo beep do painel (remote_ping), repetido a cada segundo.
 */
public final class RemotePings {
    private RemotePings() {}

    private static final int DURATION = 64;

    private static final class Task {
        final RegistryKey<World> dim;
        Vec3d pos;
        @Nullable final UUID drone;
        final DustParticleEffect dust;
        int age;

        Task(RegistryKey<World> dim, Vec3d pos, @Nullable UUID drone, int rgb) {
            this.dim = dim;
            this.pos = pos;
            this.drone = drone;
            this.dust = new DustParticleEffect(new Vector3f(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f), 1.4f);
        }
    }

    private static final List<Task> TASKS = new ArrayList<>();

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(RemotePings::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> TASKS.clear());
    }

    /** Começa o ping em {@code pos}; se for um drone ({@code drone} != null) o apito e as partículas seguem ele. */
    public static void start(ServerWorld world, Vec3d pos, @Nullable UUID drone, int rgb) {
        TASKS.removeIf(t -> t.dim.equals(world.getRegistryKey()) && ((drone != null && drone.equals(t.drone)) || (drone == null && t.pos.squaredDistanceTo(pos) < 1)));
        if (TASKS.size() < 64) TASKS.add(new Task(world.getRegistryKey(), pos, drone, rgb));
    }

    private static void tick(MinecraftServer server) {
        if (TASKS.isEmpty()) return;
        Iterator<Task> it = TASKS.iterator();
        while (it.hasNext()) {
            Task t = it.next();
            ServerWorld w = server.getWorld(t.dim);
            if (w == null || ++t.age > DURATION) {
                it.remove();
                continue;
            }
            if (t.drone != null && w.getEntity(t.drone) instanceof DroneEntity d && !d.isRemoved()) t.pos = d.getPos();
            Vec3d p = t.pos;
            if (t.age % 16 == 1) w.playSound(null, p.x, p.y, p.z, ModSounds.REMOTE_PING, SoundCategory.BLOCKS, 1.0f, 1.0f);
            if (t.age % 2 == 0) {
                double up = w.random.nextDouble() * 2.5;
                w.spawnParticles(t.dust, p.x, p.y + up, p.z, 4, 0.3, 0.2, 0.3, 0.0);
            }
        }
    }
}
