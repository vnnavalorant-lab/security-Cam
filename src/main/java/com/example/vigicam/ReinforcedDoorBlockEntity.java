package com.example.vigicam;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.UUID;

/**
 * Só existe no bloco central de baixo.
 * <ul>
 *   <li>Servidor: guarda o dono (quem colocou a porta) e a senha de {@link #PIN_LENGTH} dígitos. Só o dono pode trocar a senha,
 *       mas a porta só abre com a senha certa, até para o dono. Se o dono errar {@link #MAX_OWNER_FAILS} vezes seguidas
 *       (esqueceu a senha) abre-se a "janela de biometria": durante {@link #BIOMETRY_WINDOW} ticks o painel de biometria
 *       ({@link BiometryPanelScreen}, leitor de digital) pode liberar a porta. A senha nunca é enviada aos clientes.</li>
 *   <li>Cliente: guarda o quanto a porta já abriu (0 = fechada, 1 = aberta) para animar.</li>
 * </ul>
 */
public class ReinforcedDoorBlockEntity extends BlockEntity {
    public static final int PIN_LENGTH = 4;
    /** Erros seguidos do dono até o sistema fazer a biometria. */
    public static final int MAX_OWNER_FAILS = 3;
    /** Quanto tempo o dono tem para fazer a leitura depois do 3º erro (60 s). */
    public static final int BIOMETRY_WINDOW = 20 * 60;
    /** A leitura leva 1,5 s no painel: o servidor não aceita a conclusão antes de 1 s da abertura da janela. */
    public static final int BIOMETRY_MIN_TICKS = 20;

    /** Quanto o progresso anda por tick: abre/fecha em 10 ticks (0,5 s). */
    private static final float STEP = 0.1f;

    private float prev, cur;
    private boolean started;

    private UUID owner;
    private String pin = "";

    /** Só no servidor e só em memória: erros seguidos do dono e o tick (do mundo) em que a janela de biometria abriu (-1 = fechada). */
    private int ownerFails;
    private long biometryStart = -1;

    public ReinforcedDoorBlockEntity(BlockPos pos, BlockState state) {
        super(VigiCam.REINFORCED_DOOR_BE, pos, state);
    }

    // ------------------------------------------------------------------ dono e senha (servidor)

    public boolean hasOwner() {
        return owner != null;
    }

    @org.jetbrains.annotations.Nullable
    public UUID getOwnerUuid() {
        return owner;
    }

    public boolean isOwner(PlayerEntity player) {
        return owner != null && owner.equals(player.getUuid());
    }

    public void setOwner(PlayerEntity player) {
        owner = player.getUuid();
        markDirty();
    }

    /** Senha válida: exatamente {@link #PIN_LENGTH} dígitos. */
    public static boolean isValidPin(String s) {
        if (s == null || s.length() != PIN_LENGTH) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return true;
    }

    public boolean checkPin(String attempt) {
        return isValidPin(attempt) && attempt.equals(pin);
    }

    public void setPin(String newPin) {
        pin = newPin;
        ownerFails = 0;
        markDirty();
    }

    /** O dono errou a senha. Devolve true quando chegou ao limite (e zera o contador): hora da biometria. */
    public boolean registerOwnerFail() {
        if (++ownerFails < MAX_OWNER_FAILS) return false;
        ownerFails = 0;
        return true;
    }

    public void resetOwnerFails() {
        ownerFails = 0;
    }

    /** Abre a janela de biometria (o dono errou a senha demais). */
    public void startBiometry(long worldTime) {
        biometryStart = worldTime;
    }

    public void clearBiometry() {
        biometryStart = -1;
    }

    /** A janela de biometria está aberta (e ainda não venceu)? */
    public boolean isBiometryPending(long worldTime) {
        return biometryStart >= 0 && worldTime - biometryStart <= BIOMETRY_WINDOW;
    }

    /** O painel pode concluir a leitura agora: janela aberta há pelo menos {@link #BIOMETRY_MIN_TICKS}. */
    public boolean canFinishBiometry(long worldTime) {
        return isBiometryPending(worldTime) && worldTime - biometryStart >= BIOMETRY_MIN_TICKS;
    }

    /** Porta sem senha (recém-colocada ou de um mundo antigo): sorteia uma, assim ninguém abre sem saber. */
    public void ensurePin(net.minecraft.util.math.random.Random random) {
        if (isValidPin(pin)) return;
        pin = String.format("%0" + PIN_LENGTH + "d", random.nextInt((int) Math.pow(10, PIN_LENGTH)));
        markDirty();
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        owner = nbt.containsUuid("Owner") ? nbt.getUuid("Owner") : null;
        pin = nbt.getString("Pin");
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        if (owner != null) nbt.putUuid("Owner", owner);
        nbt.putString("Pin", pin);
    }

    // ------------------------------------------------------------------ animação (cliente)

    public static void clientTick(World world, BlockPos pos, BlockState state, ReinforcedDoorBlockEntity be) {
        float target = state.get(ReinforcedDoorBlock.OPEN) ? 1f : 0f;
        if (!be.started) {   // porta que já estava aberta ao carregar o chunk não faz a animação
            be.prev = be.cur = target;
            be.started = true;
            return;
        }
        be.prev = be.cur;
        if (be.cur < target) be.cur = Math.min(target, be.cur + STEP);
        else if (be.cur > target) be.cur = Math.max(target, be.cur - STEP);
    }

    /** Progresso suavizado entre ticks, 0..1. */
    public float progress(float tickDelta) {
        return MathHelper.lerp(tickDelta, prev, cur);
    }
}
