package com.example.vigicam;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Drone pilotado à distância. Quem pilota enxerga pela câmera dele (com os shaders de visão noturna/térmica). */
public class DroneEntity extends Entity {
    public static final double RANGE = 96.0;          // distância máxima do piloto
    private static final double H_SPEED = 0.35;       // blocos/tick (~7 m/s)
    private static final double V_SPEED = 0.22;
    private static final double LAND_SPEED = 0.08;
    private static final double ACCEL = 0.18;
    private static final long INPUT_TIMEOUT = 40;     // ticks sem input -> solta o controle

    private static final TrackedData<Boolean> POWERED =
            DataTracker.registerData(DroneEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    /** Alcance de sinal atual (96, ou 96 + 50 com antena do piloto por perto): o cliente usa na barra de sinal. */
    private static final TrackedData<Float> SIGNAL_RANGE =
            DataTracker.registerData(DroneEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private static final double RETURN_SPEED = 0.25;   // velocidade de volta à base (blocos/tick)

    @Nullable private UUID controller;
    private Vec3d vel = Vec3d.ZERO;
    private boolean amplified;   // servidor: há uma antena do piloto dando sinal

    // base: onde o drone foi colocado no chão pela última vez
    @Nullable private Vec3d home;
    // estado do retorno automático (servidor)
    private boolean landing, gaveUp;
    private double cruiseY = -1;
    private int cruiseBoost, stuckCount;
    @Nullable private Vec3d stuckPos;

    // cliente: interpolação e inclinação visual
    private int lerpSteps;
    private double lerpX, lerpY, lerpZ;
    private float lerpYaw, lerpPitch;
    public float leanForward, leanSide;

    public DroneEntity(EntityType<DroneEntity> type, World world) {
        super(type, world);
    }

    @Override
    protected void initDataTracker() {
        this.dataTracker.startTracking(POWERED, false);
        this.dataTracker.startTracking(SIGNAL_RANGE, (float) RANGE);
    }

    /** Alcance de sinal em vigor (blocos): {@link #RANGE}, mais o bônus da antena quando ela está dando sinal. */
    public float getSignalRange() { return this.dataTracker.get(SIGNAL_RANGE); }
    public boolean isAmplified() { return getSignalRange() > RANGE + 1.0f; }

    public boolean isPowered() { return this.dataTracker.get(POWERED); }
    private void setPowered(boolean v) { this.dataTracker.set(POWERED, v); }

    @Nullable public UUID getController() { return controller; }
    public void setController(UUID id) {
        this.controller = id;
        if (home == null && this.isOnGround()) home = this.getPos();   // drone antigo/sem base: usa onde está
        resetReturn();
        setPowered(true);
    }
    public void clearController() {
        this.controller = null;
        this.amplified = false;
        this.dataTracker.set(SIGNAL_RANGE, (float) RANGE);
        resetReturn();
    }

    @Nullable public Vec3d getHome() { return home; }
    public void setHome(Vec3d h) { this.home = h; }

    private void resetReturn() {
        landing = false;
        gaveUp = false;
        cruiseY = -1;
        cruiseBoost = 0;
        stuckCount = 0;
        stuckPos = null;
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        if (nbt.getBoolean("HasHome")) home = new Vec3d(nbt.getDouble("HomeX"), nbt.getDouble("HomeY"), nbt.getDouble("HomeZ"));
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        if (home != null) {
            nbt.putBoolean("HasHome", true);
            nbt.putDouble("HomeX", home.x);
            nbt.putDouble("HomeY", home.y);
            nbt.putDouble("HomeZ", home.z);
        }
    }

    @Override
    public Packet<ClientPlayPacketListener> createSpawnPacket() {
        return new EntitySpawnS2CPacket(this);
    }

    @Override
    public boolean canHit() { return !this.isRemoved(); }

    @Override
    public Box getVisibilityBoundingBox() { return this.getBoundingBox().expand(0.6); }

    @Override
    public ItemStack getPickBlockStack() { return new ItemStack(VigiCam.DRONE_ITEM); }

    @Override
    public void remove(RemovalReason reason) {
        if (!this.getWorld().isClient && controller != null) DroneControl.release(controller);
        super.remove(reason);
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (this.getWorld().isClient || this.isRemoved()) return true;
        boolean creative = source.getAttacker() instanceof PlayerEntity p && p.getAbilities().creativeMode;
        if (!creative) this.dropStack(new ItemStack(VigiCam.DRONE_ITEM));
        this.discard();
        return true;
    }

    @Override
    public ActionResult interact(PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        boolean client = this.getWorld().isClient;

        if (stack.getItem() instanceof RemoteItem) {
            if (!client && player instanceof ServerPlayerEntity sp) {
                RemoteItem.Link link = RemoteItem.Link.drone(sp.getServerWorld(), this);
                if (sp.isSneaking()) RemoteItem.toggleLink(sp, stack, link);
                else RemoteItem.connectTo(sp, stack, link);
            }
            return ActionResult.success(client);
        }
        if (stack.isEmpty()) {
            if (!client && player instanceof ServerPlayerEntity sp) {
                if (sp.isSneaking()) {
                    if (!sp.getAbilities().creativeMode) sp.getInventory().offerOrDrop(new ItemStack(VigiCam.DRONE_ITEM));
                    this.discard();
                } else {
                    DroneControl.begin(sp, this, -1, 0);   // mão vazia: pilotar direto
                }
            }
            return ActionResult.success(client);
        }
        return ActionResult.PASS;
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        super.tick();
        if (this.getWorld().isClient) clientTick();
        else serverTick();
    }

    private void serverTick() {
        ServerPlayerEntity pilot = null;
        DroneControl.Input in = null;
        if (controller != null) {
            pilot = ((ServerWorld) this.getWorld()).getServer().getPlayerManager().getPlayer(controller);
            in = DroneControl.input(controller);
            if (pilot == null || pilot.isRemoved() || pilot.getWorld() != this.getWorld() || in == null
                    || this.getWorld().getTime() - in.time() > INPUT_TIMEOUT) {
                DroneControl.release(controller);
                controller = null;
                pilot = null;
                in = null;
            }
        }

        boolean powered = isPowered();
        if (in != null) {
            double r = Math.toRadians(in.yaw());
            Vec3d fwd = new Vec3d(-Math.sin(r), 0, Math.cos(r));
            Vec3d right = new Vec3d(-Math.cos(r), 0, -Math.sin(r));
            Vec3d horiz = fwd.multiply(in.forward()).add(right.multiply(in.strafe()));
            if (horiz.lengthSquared() > 1.0) horiz = horiz.normalize();
            Vec3d target = new Vec3d(horiz.x * H_SPEED, in.vertical() * V_SPEED, horiz.z * H_SPEED);
            vel = vel.lerp(target, ACCEL);
            this.setYaw(in.yaw());
            this.setPitch(in.pitch());
        } else if (powered) {
            vel = vel.lerp(returnVelocity(), ACCEL);   // sem piloto: volta para a base e pousa
        } else {
            vel = new Vec3d(vel.x * 0.5, Math.max(vel.y - 0.04, -1.0), vel.z * 0.5);   // desligado: cai
        }

        if (pilot != null) {   // antena do piloto por perto: +50 blocos de alcance
            if (this.age % 5 == 0) updateAntenna(pilot);
            double range = RANGE + (amplified ? AntennaNetwork.RANGE_BONUS : 0.0);
            Vec3d off = this.getPos().subtract(pilot.getPos());
            double dist = off.length();
            if (dist > range + SIGNAL_LOSS_MARGIN) {
                // a antena deixou de cobrir o drone (piloto se afastou / antena destruída): sinal perdido.
                // O retorno à base não depende do sinal, então ele volta sozinho de onde estiver.
                loseSignal(pilot);
                pilot = null;
                in = null;
            } else if (dist > range) {   // limite de alcance do sinal
                Vec3d out = off.normalize();
                double v = vel.dotProduct(out);
                if (v > 0) vel = vel.subtract(out.multiply(v));
            }
        }

        Vec3d before = this.getPos();
        this.move(MovementType.SELF, vel);
        Vec3d moved = this.getPos().subtract(before);
        double x = Math.abs(moved.x) < Math.abs(vel.x) - 1.0E-4 ? 0 : vel.x;
        double y = Math.abs(moved.y) < Math.abs(vel.y) - 1.0E-4 ? 0 : vel.y;
        double z = Math.abs(moved.z) < Math.abs(vel.z) - 1.0E-4 ? 0 : vel.z;
        vel = new Vec3d(x, y, z);
        this.fallDistance = 0f;

        if (in == null && powered && this.isOnGround()) {
            boolean atHome = home == null || landing || gaveUp
                    || Math.hypot(home.x - this.getX(), home.z - this.getZ()) < 1.0;
            if (atHome) {
                setPowered(false);
                vel = Vec3d.ZERO;
                resetReturn();
            }
        }
    }

    private static final double SIGNAL_LOSS_MARGIN = 6.0;   // tolerância além do alcance antes de perder o sinal

    /** Procura uma antena DO PILOTO perto do drone ou do piloto e ajusta o alcance. */
    private void updateAntenna(ServerPlayerEntity pilot) {
        ServerWorld sw = (ServerWorld) this.getWorld();
        AmplifyingAntennaBlockEntity ant = AntennaNetwork.find(sw, pilot.getUuid(), this.getPos(), pilot.getPos());
        boolean now = ant != null;
        if (now) ant.markLinked(sw.getTime());
        if (now != amplified) {
            amplified = now;
            this.dataTracker.set(SIGNAL_RANGE, (float) (RANGE + (now ? AntennaNetwork.RANGE_BONUS : 0.0)));
            // só avisa quando já está além do alcance normal (evita spam perto do piloto)
            double dist = this.getPos().distanceTo(pilot.getPos());
            if (now) pilot.sendMessage(Text.literal("§aAntena conectada §7· alcance +"
                    + (int) AntennaNetwork.RANGE_BONUS + " blocos"), true);
            else if (dist > RANGE) pilot.sendMessage(Text.literal("§eSinal da antena perdido"), true);
        }
    }

    private void loseSignal(ServerPlayerEntity pilot) {
        DroneControl.release(pilot.getUuid());   // limpa o controle; o drone passa a voltar para a base
        controller = null;
        amplified = false;
        this.dataTracker.set(SIGNAL_RANGE, (float) RANGE);
        pilot.sendMessage(Text.literal("§cSinal perdido §7· drone retornando à base"), false);
        VigiCam.sendSignalLost(pilot);
    }

    /**
     * Retorno seguro: sobe até uma altitude acima do relevo da rota, voa até cima da base e desce na vertical.
     * Se ficar preso (teto, parede), tenta subir mais; se mesmo assim não sair do lugar, faz um pouso de emergência.
     */
    private Vec3d returnVelocity() {
        Vec3d pos = this.getPos();
        if (gaveUp || home == null) return new Vec3d(0, -LAND_SPEED, 0);

        double dx = home.x - pos.x, dz = home.z - pos.z;
        double horiz = Math.hypot(dx, dz);
        if (horiz < 0.6) landing = true; else if (horiz > 3.0) landing = false;
        if (landing) return new Vec3d(dx * 0.2, -LAND_SPEED, dz * 0.2);   // desce centralizando na base

        if (cruiseY < 0 || this.age % 10 == 0) cruiseY = computeCruise(pos, horiz);
        double dy = cruiseY - pos.y;
        double vy = MathHelper.clamp(dy * 0.25, -V_SPEED, V_SPEED);
        double sh = dy > 1.0 ? 0.0 : Math.max(0.08, Math.min(RETURN_SPEED, horiz * 0.08));   // só avança na altitude de cruzeiro
        double nx = dx / horiz, nz = dz / horiz;

        if (sh > 0) {   // aponta o nariz para onde está indo
            float target = (float) Math.toDegrees(Math.atan2(-dx, dz));
            this.setYaw(this.getYaw() + MathHelper.clamp(MathHelper.wrapDegrees(target - this.getYaw()), -8f, 8f));
        }
        this.setPitch(this.getPitch() * 0.9f);

        if (this.age % 20 == 0) {   // detecta se travou
            if (stuckPos != null && pos.squaredDistanceTo(stuckPos) < 0.25) {
                stuckCount++;
                if (stuckCount >= 4) gaveUp = true;
                else { cruiseBoost = Math.min(cruiseBoost + 4, 12); cruiseY = -1; }
            } else {
                stuckCount = 0;
            }
            stuckPos = pos;
        }
        return new Vec3d(nx * sh, vy, nz * sh);
    }

    /** Altitude segura: acima do ponto mais alto (com folga lateral) entre o drone e a base. */
    private double computeCruise(Vec3d pos, double horiz) {
        World w = this.getWorld();
        if (w.getDimension().hasCeiling()) return Math.max(home.y + 1.5, pos.y);   // Nether etc.: sem mapa de altura útil
        ServerWorld sw = (ServerWorld) w;
        int n = (int) Math.min(64, Math.ceil(horiz / 2.0) + 1);
        int top = (int) Math.floor(home.y);
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n;
            int bx = (int) Math.floor(pos.x + (home.x - pos.x) * t);
            int bz = (int) Math.floor(pos.z + (home.z - pos.z) * t);
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    int x = bx + ox, z = bz + oz;
                    if (!sw.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) continue;
                    top = Math.max(top, sw.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z));
                }
            }
        }
        return Math.min(top + 2.5 + cruiseBoost, sw.getTopY() - 2);
    }

    private void clientTick() {
        if (lerpSteps > 0) {
            double nx = this.getX() + (lerpX - this.getX()) / lerpSteps;
            double ny = this.getY() + (lerpY - this.getY()) / lerpSteps;
            double nz = this.getZ() + (lerpZ - this.getZ()) / lerpSteps;
            float ny2 = this.getYaw() + MathHelper.wrapDegrees(lerpYaw - this.getYaw()) / lerpSteps;
            float np = this.getPitch() + (lerpPitch - this.getPitch()) / lerpSteps;
            lerpSteps--;
            this.setPosition(nx, ny, nz);
            this.setYaw(ny2);
            this.setPitch(np);
        }
        // inclinação visual conforme o movimento
        double dx = this.getX() - this.prevX, dz = this.getZ() - this.prevZ;
        double r = Math.toRadians(this.getYaw());
        double fwd = -Math.sin(r) * dx + Math.cos(r) * dz;
        double side = -Math.cos(r) * dx - Math.sin(r) * dz;
        float tf = (float) MathHelper.clamp(fwd * 60.0, -20.0, 20.0);
        float ts = (float) MathHelper.clamp(side * 60.0, -20.0, 20.0);
        leanForward += (tf - leanForward) * 0.3f;
        leanSide += (ts - leanSide) * 0.3f;
    }

    @Override
    public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch,
                                               int interpolationSteps, boolean interpolate) {
        this.lerpX = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.lerpYaw = yaw;
        this.lerpPitch = pitch;
        this.lerpSteps = Math.max(1, interpolationSteps - 1);
    }
}
