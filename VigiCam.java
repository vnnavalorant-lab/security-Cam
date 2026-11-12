package com.example.vigicam;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;

public class VigiCam implements ModInitializer {
    public static final String MOD_ID = "vigicam";
    public static final Identifier VIEW_CAMERA = new Identifier(MOD_ID, "view_camera");

    public static final Block SURVEILLANCE_CAMERA = new SurveillanceCameraBlock(
            FabricBlockSettings.create().strength(2.0f).sounds(BlockSoundGroup.METAL).nonOpaque());
    public static final BlockItem SURVEILLANCE_CAMERA_ITEM =
            new BlockItem(SURVEILLANCE_CAMERA, new FabricItemSettings());

    @Override
    public void onInitialize() {
        Identifier id = new Identifier(MOD_ID, "surveillance_camera");
        Registry.register(Registries.BLOCK, id, SURVEILLANCE_CAMERA);
        Registry.register(Registries.ITEM, id, SURVEILLANCE_CAMERA_ITEM);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.REDSTONE)
                .register(entries -> entries.add(SURVEILLANCE_CAMERA_ITEM));
    }
}
