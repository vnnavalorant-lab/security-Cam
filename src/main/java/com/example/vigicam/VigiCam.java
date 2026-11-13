package com.example.vigicam;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.block.Block;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public class VigiCam implements ModInitializer {
    public static final String MOD_ID = "vigicam";

    // servidor -> cliente
    public static final Identifier VIEW_CAMERA = new Identifier(MOD_ID, "view_camera");
    public static final Identifier VIEW_DRONE = new Identifier(MOD_ID, "view_drone");
    // cliente -> servidor
    public static final Identifier DRONE_INPUT = new Identifier(MOD_ID, "drone_input");
    public static final Identifier DRONE_RELEASE = new Identifier(MOD_ID, "drone_release");
    public static final Identifier CYCLE_DEVICE = new Identifier(MOD_ID, "cycle_device");

    public static final Block SURVEILLANCE_CAMERA = new SurveillanceCameraBlock(
            FabricBlockSettings.create().strength(2.0f).sounds(BlockSoundGroup.METAL).nonOpaque());
    public static final BlockItem SURVEILLANCE_CAMERA_ITEM =
            new BlockItem(SURVEILLANCE_CAMERA, new FabricItemSettings());

    public static final Item DRONE_ITEM = new DroneItem(new FabricItemSettings().maxCount(16));
    public static final Item REMOTE_ITEM = new RemoteItem(new FabricItemSettings().maxCount(1));

    public static EntityType<DroneEntity> DRONE;

    @Override
    public void onInitialize() {
        Identifier id = new Identifier(MOD_ID, "surveillance_camera");
        Registry.register(Registries.BLOCK, id, SURVEILLANCE_CAMERA);
        Registry.register(Registries.ITEM, id, SURVEILLANCE_CAMERA_ITEM);
        Registry.register(Registries.ITEM, new Identifier(MOD_ID, "drone"), DRONE_ITEM);
        Registry.register(Registries.ITEM, new Identifier(MOD_ID, "remote"), REMOTE_ITEM);

        DRONE = Registry.register(Registries.ENTITY_TYPE, new Identifier(MOD_ID, "drone"),
                FabricEntityTypeBuilder.<DroneEntity>create(SpawnGroup.MISC, DroneEntity::new)
                        .dimensions(EntityDimensions.fixed(0.8f, 0.4f))
                        .trackRangeBlocks(160)
                        .trackedUpdateRate(1)
                        .build());

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.REDSTONE).register(entries -> {
            entries.add(SURVEILLANCE_CAMERA_ITEM);
            entries.add(DRONE_ITEM);
            entries.add(REMOTE_ITEM);
        });

        // --- rede: cliente -> servidor ---
        ServerPlayNetworking.registerGlobalReceiver(DRONE_INPUT, (server, player, handler, buf, sender) -> {
            float forward = buf.readFloat(), strafe = buf.readFloat(), vertical = buf.readFloat();
            float yaw = buf.readFloat(), pitch = buf.readFloat();
            server.execute(() -> DroneControl.update(player, forward, strafe, vertical, yaw, pitch));
        });
        ServerPlayNetworking.registerGlobalReceiver(DRONE_RELEASE, (server, player, handler, buf, sender) ->
                server.execute(() -> DroneControl.release(player.getUuid())));
        ServerPlayNetworking.registerGlobalReceiver(CYCLE_DEVICE, (server, player, handler, buf, sender) ->
                server.execute(() -> RemoteItem.cycle(player)));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                DroneControl.release(handler.getPlayer().getUuid()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> DroneControl.clear());
    }

    // --- rede: servidor -> cliente ---
    /** index/count: posição do dispositivo na lista do controle remoto (-1/0 = sem lista). */
    public static void sendViewCamera(ServerPlayerEntity sp, BlockPos pos, float yaw, int index, int count) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeFloat(yaw);
        buf.writeInt(index);
        buf.writeInt(count);
        ServerPlayNetworking.send(sp, VIEW_CAMERA, buf);
    }

    public static void sendViewDrone(ServerPlayerEntity sp, int entityId, int index, int count) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(entityId);
        buf.writeInt(index);
        buf.writeInt(count);
        ServerPlayNetworking.send(sp, VIEW_DRONE, buf);
    }
}
