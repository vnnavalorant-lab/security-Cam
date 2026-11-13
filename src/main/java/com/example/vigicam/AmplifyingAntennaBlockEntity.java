package com.example.vigicam;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Guarda o dono da antena (quem a colocou) e emite as partículas de sinal no cliente. */
public class AmplifyingAntennaBlockEntity extends BlockEntity {
    /** Altura da ponta do mastro no modelo: 66 px / 16. */
    public static final double TIP_HEIGHT = 66.0 / 16.0;
    private static final long ACTIVE_TICKS = 30;

    @Nullable private UUID owner;
    private String ownerName = "";
    private long lastLink = Long.MIN_VALUE / 2;

    public AmplifyingAntennaBlockEntity(BlockPos pos, BlockState state) {
        super(VigiCam.ANTENNA_BE, pos, state);
    }

    @Nullable public UUID getOwner() { return owner; }
    public String getOwnerName() { return ownerName; }

    public void setOwner(UUID id, String name) {
        this.owner = id;
        this.ownerName = name;
        markDirty();
    }

    /** Chamado pelo drone enquanto esta antena dá sinal a ele. */
    public void markLinked(long time) { this.lastLink = time; }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        if (owner != null) {
            nbt.putUuid("Owner", owner);
            nbt.putString("OwnerName", ownerName);
        }
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        owner = nbt.containsUuid("Owner") ? nbt.getUuid("Owner") : null;
        ownerName = nbt.getString("OwnerName");
    }

    // ------------------------------------------------------------------ ticks

    public static void serverTick(World world, BlockPos pos, BlockState state, AmplifyingAntennaBlockEntity be) {
        if (world.getTime() % 10 != 0) return;
        boolean active = world.getTime() - be.lastLink <= ACTIVE_TICKS;
        if (state.get(AmplifyingAntennaBlock.ACTIVE) != active) {
            world.setBlockState(pos, state.with(AmplifyingAntennaBlock.ACTIVE, active), 3);
        }
    }

    /** Partículas de sinal (só o cliente chama; o desenho está em SignalParticle). */
    public static void clientTick(World world, BlockPos pos, BlockState state, AmplifyingAntennaBlockEntity be) {
        boolean active = state.get(AmplifyingAntennaBlock.ACTIVE);
        long t = world.getTime();
        double cx = pos.getX() + 0.5, cz = pos.getZ() + 0.5;
        double tipY = pos.getY() + TIP_HEIGHT;
        var rnd = world.random;

        // ondas de rádio: anéis que se expandem a partir da ponta (mais rápidas e maiores com o drone ligado)
        int waveEvery = active ? 8 : 22;
        if (t % waveEvery == 0) {
            world.addParticle(VigiCam.SIGNAL_WAVE, cx, tipY, cz, active ? 1.0 : 0.0, 0, 0);
        }
        // segunda frente de onda, defasada, saindo do conjunto de antenas do meio do mastro
        if ((t + waveEvery / 2) % (waveEvery * 2) == 0) {
            world.addParticle(VigiCam.SIGNAL_WAVE, cx, pos.getY() + 2.55, cz, active ? 1.0 : 0.0, 1, 0);
        }

        // brilho pulsante na ponta
        if (t % 6 == 0) {
            world.addParticle(VigiCam.SIGNAL_GLOW, cx, tipY, cz, 0, 0, 0);
        }

        // "pacotes de dados": faíscas que saem em arco e se dissipam
        int sparks = active ? 2 : (rnd.nextFloat() < 0.35f ? 1 : 0);
        for (int i = 0; i < sparks; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            double sp = 0.05 + rnd.nextDouble() * 0.08;
            world.addParticle(VigiCam.SIGNAL_SPARK,
                    cx + Math.cos(a) * 0.05, tipY - 0.05, cz + Math.sin(a) * 0.05,
                    Math.cos(a) * sp, 0.02 + rnd.nextDouble() * 0.05, Math.sin(a) * sp);
        }
    }
}
