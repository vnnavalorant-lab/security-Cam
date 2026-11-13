package com.example.vigicam;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Registro (lado servidor) das antenas amplificadoras carregadas em cada dimensão.
 * O drone consulta aqui se existe uma antena do próprio piloto por perto.
 */
public final class AntennaNetwork {
    private AntennaNetwork() {}

    /** Raio (blocos) em que a antena é "reconhecida": vale a distância até o drone OU até o piloto. */
    public static final double DETECT_RADIUS = 64.0;
    /** Alcance extra de sinal (blocos) que uma antena do dono dá ao drone. */
    public static final double RANGE_BONUS = 50.0;

    private static final Map<RegistryKey<World>, Set<AmplifyingAntennaBlockEntity>> ANTENNAS = new HashMap<>();

    public static void init() {
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((be, world) -> {
            if (be instanceof AmplifyingAntennaBlockEntity ant) add(world, ant);
        });
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((be, world) -> {
            if (be instanceof AmplifyingAntennaBlockEntity ant) remove(world, ant);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ANTENNAS.clear());
    }

    private static void add(ServerWorld world, AmplifyingAntennaBlockEntity ant) {
        ANTENNAS.computeIfAbsent(world.getRegistryKey(),
                k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(ant);
    }

    private static void remove(ServerWorld world, AmplifyingAntennaBlockEntity ant) {
        Set<AmplifyingAntennaBlockEntity> set = ANTENNAS.get(world.getRegistryKey());
        if (set != null) set.remove(ant);
    }

    /**
     * Antena do jogador {@code owner} mais próxima de {@code a} (drone) ou de {@code b} (piloto),
     * dentro de {@link #DETECT_RADIUS}; null se não houver.
     */
    @Nullable
    public static AmplifyingAntennaBlockEntity find(ServerWorld world, UUID owner, Vec3d a, Vec3d b) {
        Set<AmplifyingAntennaBlockEntity> set = ANTENNAS.get(world.getRegistryKey());
        if (set == null || set.isEmpty()) return null;
        double max = DETECT_RADIUS * DETECT_RADIUS;
        AmplifyingAntennaBlockEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (AmplifyingAntennaBlockEntity ant : set) {
            if (ant.isRemoved() || !owner.equals(ant.getOwner())) continue;
            Vec3d c = Vec3d.ofCenter(ant.getPos());
            double d = Math.min(c.squaredDistanceTo(a), c.squaredDistanceTo(b));
            if (d <= max && d < bestD) {
                bestD = d;
                best = ant;
            }
        }
        return best;
    }
}
