package com.example.vigicam;

import net.minecraft.client.item.ModelPredicateProviderRegistry;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

/**
 * (Drone e câmera são 3D em todos os contextos e não passam por aqui.) O controle remoto tem dois modelos: o sprite (inventário, chão, moldura) e um modelo "na mão" (builtin/entity),
 * escolhido por um override com o predicado "vigicam:hand". O predicado só vale 1 durante a chamada
 * feita por {@link #forHand}, que o ItemRendererMixin usa quando o item é desenhado na mão.
 */
public final class HandModels {
    private HandModels() {}

    private static final Identifier PREDICATE = new Identifier(VigiCam.MOD_ID, "hand");
    private static boolean asking;

    public static void register() {
        for (Item item : new Item[]{VigiCam.REMOTE_ITEM}) {
            ModelPredicateProviderRegistry.register(item, PREDICATE, (stack, world, entity, seed) -> asking ? 1f : 0f);
        }
    }

    public static boolean isOurs(ItemStack stack) {
        Item i = stack.getItem();
        return i == VigiCam.REMOTE_ITEM;
    }

    public static boolean isFirstPerson(ModelTransformationMode m) {
        return m == ModelTransformationMode.FIRST_PERSON_LEFT_HAND || m == ModelTransformationMode.FIRST_PERSON_RIGHT_HAND;
    }

    public static boolean isHand(ModelTransformationMode m) {
        return isFirstPerson(m) || m == ModelTransformationMode.THIRD_PERSON_LEFT_HAND
                || m == ModelTransformationMode.THIRD_PERSON_RIGHT_HAND;
    }

    /** @return o modelo "na mão" do item, ou null se não houver. */
    public static BakedModel forHand(BakedModel sprite, ItemStack stack) {
        asking = true;
        try {
            BakedModel m = sprite.getOverrides().apply(sprite, stack, null, null, 0);
            return (m != null && m != sprite && m.isBuiltin()) ? m : null;
        } finally {
            asking = false;
        }
    }
}
