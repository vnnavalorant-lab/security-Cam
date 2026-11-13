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
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
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

    @Nullable private UUID controller;
    private Vec3d vel = Vec3d.ZERO;

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
    }

    public boolean isPowered() { return this.dataTracker.get(POWERED); }
    private void setPowered(boolean v) { this.dataTracker.set(POWERED, v); }

    @Nullable public UUID getController() { return controller; }
    public void setController(UUID id) { this.controller = id; setPowered(true); }
    public void clearController() { this.controller = null; }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {}

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {}

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
            vel = vel.lerp(new Vec3d(0, -LAND_SPEED, 0), ACCEL);   // sem piloto: pousa devagar
        } else {
            vel = new Vec3d(vel.x * 0.5, Math.max(vel.y - 0.04, -1.0), vel.z * 0.5);   // desligado: cai
        }

        if (pilot != null) {   // limite de alcance do sinal
            Vec3d off = this.getPos().subtract(pilot.getPos());
            double dist = off.length();
            if (dist > RANGE) {
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
            setPowered(false);
            vel = Vec3d.ZERO;
        }
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
