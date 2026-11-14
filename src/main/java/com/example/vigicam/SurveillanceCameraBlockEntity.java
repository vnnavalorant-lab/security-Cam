package com.example.vigicam;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Existe para o BlockEntityRenderer desenhar (e animar) o bloco, para tocar o som ambiente da câmera
 * e para guardar o dono (quem colocou), usado pelo Pendrive Hacker ({@link HackManager}).
 */
public class SurveillanceCameraBlockEntity extends BlockEntity {
    @Nullable private UUID owner;
    private String ownerName = "";

    public SurveillanceCameraBlockEntity(BlockPos pos, BlockState state) {
        super(VigiCam.CAMERA_BE, pos, state);
    }

    /** Dono (quem colocou); null em câmeras de mundos antigos: essas continuam livres para todos. */
    @Nullable public UUID getOwner() { return owner; }
    public String getOwnerName() { return ownerName; }

    public void setOwner(UUID id, String name) {
        this.owner = id;
        this.ownerName = name;
        markDirty();
    }

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

    /** Só o cliente chama: mantém o som da câmera em looping. */
    public static void clientTick(World world, BlockPos pos, BlockState state, SurveillanceCameraBlockEntity be) {
        VigiSounds.cameraTick(pos, world.getTime());
    }
}
