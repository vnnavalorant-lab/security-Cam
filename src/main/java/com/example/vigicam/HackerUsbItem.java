package com.example.vigicam;

import net.minecraft.block.BlockState;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Pendrive Hacker: clique direito numa Antena Amplificadora abre o painel do puzzle ({@link HackPanelScreen}).
 * Vencendo, o jogador manda em tudo que o dono da antena colocou por {@link HackManager#DURATION_SECONDS} segundos.
 * O pendrive não se gasta.
 */
public class HackerUsbItem extends Item {
    public HackerUsbItem(Settings settings) { super(settings); }

    /** Segurando o pendrive em qualquer uma das mãos? */
    public static boolean holds(PlayerEntity p) {
        return p.getMainHandStack().getItem() instanceof HackerUsbItem || p.getOffHandStack().getItem() instanceof HackerUsbItem;
    }

    /** Cobre o clique com Shift (o bloco não recebe o clique) e a mão que não é a do pendrive. */
    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        World w = ctx.getWorld();
        BlockPos pos = ctx.getBlockPos();
        BlockState state = w.getBlockState(pos);
        if (!(state.getBlock() instanceof AmplifyingAntennaBlock)) return ActionResult.PASS;
        if (!w.isClient && ctx.getPlayer() instanceof ServerPlayerEntity sp) {
            HackManager.openPanel(sp, pos.down(state.get(AmplifyingAntennaBlock.PART)));
        }
        return ActionResult.success(w.isClient);
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.translatable("item.vigicam.pendrive_hacker.tooltip").styled(s -> s.withColor(0x55FF55)));
        tooltip.add(Text.translatable("item.vigicam.pendrive_hacker.tooltip2").styled(s -> s.withColor(0x777777)));
    }
}
