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

    /** Alcance normal do sinal do piloto (blocos). */
    public static final double BASE_RANGE = DroneEntity.RANGE;
    /** Blocos extras que a antena dá: o drone voa até BASE_RANGE + RANGE_BONUS de distância DA ANTENA. */
    public static final double RANGE_BONUS = 50.0;
    /** Raio de cobertura da antena, contado a partir dela. */
    public static final double COVERAGE = BASE_RANGE + RANGE_BONUS;
    /** Folga extra na busca, para a antena não "piscar" na borda enquanto o drone é contido. */
    private static final double FIND_MARGIN = 6.0;

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

    /** Antena do jogador {@code owner} mais próxima do ponto {@code p} (o drone), dentro de {@link #COVERAGE}; null se não houver. */
    @Nullable
    public static AmplifyingAntennaBlockEntity find(ServerWorld world, UUID owner, Vec3d p) {
        Set<AmplifyingAntennaBlockEntity> set = ANTENNAS.get(world.getRegistryKey());
        if (set == null || set.isEmpty()) return null;
        double max = (COVERAGE + FIND_MARGIN) * (COVERAGE + FIND_MARGIN);
        AmplifyingAntennaBlockEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (AmplifyingAntennaBlockEntity ant : set) {
            if (ant.isRemoved() || !owner.equals(ant.getOwner())) continue;
            double d = Vec3d.ofCenter(ant.getPos()).squaredDistanceTo(p);
            if (d <= max && d < bestD) {
                bestD = d;
                best = ant;
            }
        }
        return best;
    }
}
