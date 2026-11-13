package com.example.vigicam;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.particle.DefaultParticleType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.text.Text;
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
    public static final Identifier VIEW_STATE = new Identifier(MOD_ID, "view_state");   // cliente -> servidor: "estou nas câmeras/drone"
    // servidor -> clientes próximos: quem está pilotando (para as mãos nos controles)
    public static final Identifier PILOT_SYNC = new Identifier(MOD_ID, "pilot_sync");
    // servidor -> cliente: o drone perdeu o sinal (sai da visão do drone)
    public static final Identifier SIGNAL_LOST = new Identifier(MOD_ID, "signal_lost");

    public static final Block SURVEILLANCE_CAMERA = new SurveillanceCameraBlock(
            FabricBlockSettings.create().strength(2.0f).sounds(BlockSoundGroup.METAL).nonOpaque());
    public static final BlockItem SURVEILLANCE_CAMERA_ITEM =
            new BlockItem(SURVEILLANCE_CAMERA, new FabricItemSettings());

    public static final Block AMPLIFYING_ANTENNA = new AmplifyingAntennaBlock(
            FabricBlockSettings.create().strength(3.0f).sounds(BlockSoundGroup.METAL).nonOpaque()
                    .luminance(state -> state.get(AmplifyingAntennaBlock.PART) != 0 ? 0 : (state.get(AmplifyingAntennaBlock.ACTIVE) ? 9 : 4)));
    public static final BlockItem AMPLIFYING_ANTENNA_ITEM =
            new BlockItem(AMPLIFYING_ANTENNA, new FabricItemSettings());

    // partículas do sinal da antena (desenhadas no cliente por SignalParticle)
    public static final DefaultParticleType SIGNAL_WAVE = FabricParticleTypes.simple();
    public static final DefaultParticleType SIGNAL_GLOW = FabricParticleTypes.simple();
    public static final DefaultParticleType SIGNAL_SPARK = FabricParticleTypes.simple();

    public static final Item DRONE_ITEM = new DroneItem(new FabricItemSettings().maxCount(16));
    public static final Item REMOTE_ITEM = new RemoteItem(new FabricItemSettings().maxCount(1));

    public static EntityType<DroneEntity> DRONE;
    public static BlockEntityType<SurveillanceCameraBlockEntity> CAMERA_BE;
    public static BlockEntityType<AmplifyingAntennaBlockEntity> ANTENNA_BE;

    @Override
    public void onInitialize() {
        org.slf4j.LoggerFactory.getLogger("VigiCam").info("VigiCam - feito pela equipe Scute60");
        ModSounds.init();
        Identifier id = new Identifier(MOD_ID, "surveillance_camera");
        Registry.register(Registries.BLOCK, id, SURVEILLANCE_CAMERA);
        Registry.register(Registries.ITEM, id, SURVEILLANCE_CAMERA_ITEM);
        CAMERA_BE = Registry.register(Registries.BLOCK_ENTITY_TYPE, id,
                FabricBlockEntityTypeBuilder.create(SurveillanceCameraBlockEntity::new, SURVEILLANCE_CAMERA).build());
        Identifier antennaId = new Identifier(MOD_ID, "amplifying_antenna");
        Registry.register(Registries.BLOCK, antennaId, AMPLIFYING_ANTENNA);
        Registry.register(Registries.ITEM, antennaId, AMPLIFYING_ANTENNA_ITEM);
        ANTENNA_BE = Registry.register(Registries.BLOCK_ENTITY_TYPE, antennaId,
                FabricBlockEntityTypeBuilder.create(AmplifyingAntennaBlockEntity::new, AMPLIFYING_ANTENNA).build());
        Registry.register(Registries.PARTICLE_TYPE, new Identifier(MOD_ID, "signal_wave"), SIGNAL_WAVE);
        Registry.register(Registries.PARTICLE_TYPE, new Identifier(MOD_ID, "signal_glow"), SIGNAL_GLOW);
        Registry.register(Registries.PARTICLE_TYPE, new Identifier(MOD_ID, "signal_spark"), SIGNAL_SPARK);
        AntennaNetwork.init();
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
            entries.add(AMPLIFYING_ANTENNA_ITEM);
        });

        // --- rede: cliente -> servidor ---
        ServerPlayNetworking.registerGlobalReceiver(DRONE_INPUT, (server, player, handler, buf, sender) -> {
            float forward = buf.readFloat(), strafe = buf.readFloat(), vertical = buf.readFloat();
            float yaw = buf.readFloat(), pitch = buf.readFloat();
            server.execute(() -> DroneControl.update(player, forward, strafe, vertical, yaw, pitch));
        });
        ServerPlayNetworking.registerGlobalReceiver(DRONE_RELEASE, (server, player, handler, buf, sender) ->
                server.execute(() -> {
                    DroneEntity d = DroneControl.get(player.getUuid());
                    DroneControl.release(player.getUuid());
                    if (d != null && d.getHome() != null) {
                        player.sendMessage(Text.literal("§eDrone retornando à base..."), true);
                    }
                }));
        ServerPlayNetworking.registerGlobalReceiver(CYCLE_DEVICE, (server, player, handler, buf, sender) ->
                server.execute(() -> RemoteItem.cycle(player)));

        ServerPlayNetworking.registerGlobalReceiver(VIEW_STATE, (server, player, handler, buf, sender) -> {
            boolean on = buf.readBoolean();
            server.execute(() -> broadcastPilot(player, on));
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            DroneControl.release(handler.getPlayer().getUuid());
            broadcastPilot(handler.getPlayer(), false);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> DroneControl.clear());
    }

    /** Avisa os jogadores que enxergam este jogador se ele está (ou não) usando câmera/drone. */
    public static void broadcastPilot(ServerPlayerEntity p, boolean on) {
        for (ServerPlayerEntity t : PlayerLookup.tracking(p)) {
            PacketByteBuf b = PacketByteBufs.create();
            b.writeUuid(p.getUuid());
            b.writeBoolean(on);
            ServerPlayNetworking.send(t, PILOT_SYNC, b);
        }
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

    public static void sendSignalLost(ServerPlayerEntity sp) {
        ServerPlayNetworking.send(sp, SIGNAL_LOST, PacketByteBufs.create());
    }

    public static void sendViewDrone(ServerPlayerEntity sp, int entityId, int index, int count) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(entityId);
        buf.writeInt(index);
        buf.writeInt(count);
        ServerPlayNetworking.send(sp, VIEW_DRONE, buf);
    }
}
