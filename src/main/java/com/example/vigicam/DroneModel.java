package com.example.vigicam;

import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/** Modelo do drone, gerado a partir de drone.bbmodel (65 cubos, 4 rotores). */
public class DroneModel {
    public static final EntityModelLayer LAYER = new EntityModelLayer(new Identifier(VigiCam.MOD_ID, "drone"), "main");

    private final ModelPart root;
    private final ModelPart[] rotors = new ModelPart[4];

    public DroneModel(ModelPart root) {
        this.root = root;
        for (int i = 0; i < 4; i++) rotors[i] = root.getChild("rotor_" + i);
    }

    /** angle em graus: rotação dos rotores (animação "on" do Blockbench: 360° em 0,25s). */
    public void setRotorAngle(float degrees) {
        float rad = (float) Math.toRadians(degrees);
        for (ModelPart r : rotors) r.yaw = rad;
    }

    public void render(MatrixStack matrices, VertexConsumer vc, int light) {
        root.render(matrices, vc, light, OverlayTexture.DEFAULT_UV, 1f, 1f, 1f, 1f);
    }

    public static TexturedModelData getTexturedModelData() {
        ModelData data = new ModelData();
        ModelPartData root = data.getRoot();
        root.addChild("c0", ModelPartBuilder.create().uv(0, 16)
                .cuboid(-3F, -2F, -7F, 6F, 1F, 14F, Dilation.NONE),
                ModelTransform.of(0F, 24F, 0F, 0F, 0F, 0F));
        root.addChild("c1", ModelPartBuilder.create().uv(0, 50)
                .cuboid(-2.18995F, -0.98995F, -7F, 2.2F, 1F, 14F, new Dilation(-0.001F)),
                ModelTransform.of(4.98579F, 22F, 0F, 0F, 0F, -0.47124F));
        root.addChild("c2", ModelPartBuilder.create().uv(32, 50)
                .cuboid(-0.01005F, -0.98995F, -7F, 2.2F, 1F, 14F, new Dilation(-0.001F)),
                ModelTransform.of(-4.98579F, 22F, 0F, 0F, 0F, 0.47124F));
        root.addChild("c3", ModelPartBuilder.create().uv(0, 0)
                .cuboid(-5F, -2F, -7F, 10F, 2F, 14F, Dilation.NONE),
                ModelTransform.of(0F, 22F, 0F, 0F, 0F, 0F));
        root.addChild("c4", ModelPartBuilder.create().uv(70, 74)
                .cuboid(-0.35355F, -1.1F, -2.5F, 2F, 2F, 3F, Dilation.NONE),
                ModelTransform.of(3.35355F, 18.1F, -5F, 0F, 0.2618F, 0F));
        root.addChild("c5", ModelPartBuilder.create().uv(40, 26)
                .cuboid(1.64645F, -1.1F, -2F, 10F, 2F, 2F, Dilation.NONE),
                ModelTransform.of(3.35355F, 18.1F, -5F, 0F, 0.2618F, 0F));
        root.addChild("c6", ModelPartBuilder.create().uv(72, 42)
                .cuboid(6.73528F, -1.35F, -11.14949F, 3F, 2.5F, 3F, Dilation.NONE),
                ModelTransform.of(3.35355F, 18.1F, -5F, 0F, -0.5236F, 0F));
        root.addChild("c7", ModelPartBuilder.create().uv(80, 69)
                .cuboid(7.23528F, -2.6F, -10.64949F, 2F, 1.25F, 2F, Dilation.NONE),
                ModelTransform.of(3.35355F, 18.1F, -5F, 0F, -0.5236F, 0F));
        root.addChild("c8", ModelPartBuilder.create().uv(10, 78)
                .cuboid(7.73528F, 1.15F, -11.14949F, 2F, 4F, 2F, Dilation.NONE),
                ModelTransform.of(3.35355F, 18.1F, -5F, 0F, -0.5236F, 0F));
        root.addChild("c9", ModelPartBuilder.create().uv(46, 77)
                .cuboid(-0.35355F, -1.1F, -0.5F, 2F, 2F, 3F, Dilation.NONE),
                ModelTransform.of(3.35355F, 18.1F, 5F, 0F, -0.2618F, 0F));
        root.addChild("c10", ModelPartBuilder.create().uv(42, 82)
                .cuboid(7.23528F, -2.6F, 8.64949F, 2F, 1.25F, 2F, Dilation.NONE),
                ModelTransform.of(3.35355F, 18.1F, 5F, 0F, 0.5236F, 0F));
        root.addChild("c11", ModelPartBuilder.create().uv(18, 78)
                .cuboid(7.73528F, 1.15F, 9.14949F, 2F, 4F, 2F, Dilation.NONE),
                ModelTransform.of(3.35355F, 18.1F, 5F, 0F, 0.5236F, 0F));
        root.addChild("c12", ModelPartBuilder.create().uv(0, 73)
                .cuboid(6.73528F, -1.35F, 8.14949F, 3F, 2.5F, 3F, Dilation.NONE),
                ModelTransform.of(3.35355F, 18.1F, 5F, 0F, 0.5236F, 0F));
        root.addChild("c13", ModelPartBuilder.create().uv(64, 26)
                .cuboid(1.64645F, -1.1F, 0F, 10F, 2F, 2F, Dilation.NONE),
                ModelTransform.of(3.35355F, 18.1F, 5F, 0F, -0.2618F, 0F));
        root.addChild("c14", ModelPartBuilder.create().uv(56, 77)
                .cuboid(-1.64645F, -1.1F, -0.5F, 2F, 2F, 3F, Dilation.NONE),
                ModelTransform.of(-3.35355F, 18.1F, 5F, 0F, 0.2618F, 0F));
        root.addChild("c15", ModelPartBuilder.create().uv(0, 78)
                .cuboid(-1.64645F, -1.1F, -2.5F, 2F, 2F, 3F, Dilation.NONE),
                ModelTransform.of(-3.35355F, 18.1F, -5F, 0F, -0.2618F, 0F));
        root.addChild("c16", ModelPartBuilder.create().uv(64, 50)
                .cuboid(-11.64645F, -1.1F, 0F, 10F, 2F, 2F, Dilation.NONE),
                ModelTransform.of(-3.35355F, 18.1F, 5F, 0F, 0.2618F, 0F));
        root.addChild("c17", ModelPartBuilder.create().uv(12, 73)
                .cuboid(-9.73528F, -1.35F, 8.14949F, 3F, 2.5F, 3F, Dilation.NONE),
                ModelTransform.of(-3.35355F, 18.1F, 5F, 0F, -0.5236F, 0F));
        root.addChild("c18", ModelPartBuilder.create().uv(26, 78)
                .cuboid(-9.73528F, 1.15F, 9.14949F, 2F, 4F, 2F, Dilation.NONE),
                ModelTransform.of(-3.35355F, 18.1F, 5F, 0F, -0.5236F, 0F));
        root.addChild("c19", ModelPartBuilder.create().uv(50, 82)
                .cuboid(-9.23528F, -2.6F, 8.64949F, 2F, 1.25F, 2F, Dilation.NONE),
                ModelTransform.of(-3.35355F, 18.1F, 5F, 0F, -0.5236F, 0F));
        root.addChild("c20", ModelPartBuilder.create().uv(82, 61)
                .cuboid(-9.23528F, -2.6F, -10.64949F, 2F, 1.25F, 2F, Dilation.NONE),
                ModelTransform.of(-3.35355F, 18.1F, -5F, 0F, 0.5236F, 0F));
        root.addChild("c21", ModelPartBuilder.create().uv(66, 79)
                .cuboid(-9.73528F, 1.15F, -11.14949F, 2F, 4F, 2F, Dilation.NONE),
                ModelTransform.of(-3.35355F, 18.1F, -5F, 0F, 0.5236F, 0F));
        root.addChild("c22", ModelPartBuilder.create().uv(24, 73)
                .cuboid(-9.73528F, -1.35F, -11.14949F, 3F, 2.5F, 3F, Dilation.NONE),
                ModelTransform.of(-3.35355F, 18.1F, -5F, 0F, 0.5236F, 0F));
        root.addChild("c23", ModelPartBuilder.create().uv(64, 54)
                .cuboid(-11.64645F, -1.1F, -2F, 10F, 2F, 2F, Dilation.NONE),
                ModelTransform.of(-3.35355F, 18.1F, -5F, 0F, -0.2618F, 0F));
        root.addChild("c24", ModelPartBuilder.create().uv(48, 0)
                .cuboid(-5F, -4F, -7F, 4F, 4F, 14F, Dilation.NONE),
                ModelTransform.of(3F, 20F, 0F, 0F, 0F, 0F));
        root.addChild("c25", ModelPartBuilder.create().uv(36, 73)
                .cuboid(-5F, -3F, 6F, 4F, 6F, 1F, Dilation.NONE),
                ModelTransform.of(3F, 20F, 1F, 0F, 0F, 0F));
        root.addChild("c26", ModelPartBuilder.create().uv(82, 78)
                .cuboid(-1F, -2F, 6F, 2F, 4F, 1F, Dilation.NONE),
                ModelTransform.of(3F, 20F, 1F, 0F, 0F, 0F));
        root.addChild("c27", ModelPartBuilder.create().uv(0, 83)
                .cuboid(-1F, -2F, 6F, 2F, 4F, 1F, Dilation.NONE),
                ModelTransform.of(-3F, 20F, 1F, 0F, 0F, 0F));
        root.addChild("c28", ModelPartBuilder.create().uv(40, 16)
                .cuboid(-2.22971F, 0.00621F, -0.5F, 2.2F, 1F, 1F, new Dilation(-0.001F)),
                ModelTransform.of(-1.97493F, 16.98289F, 7.5F, 0F, 0F, -0.45379F));
        root.addChild("c29", ModelPartBuilder.create().uv(82, 64)
                .cuboid(0.02971F, 0.00621F, -0.5F, 2.2F, 1F, 1F, new Dilation(-0.001F)),
                ModelTransform.of(1.97493F, 16.98289F, 7.5F, 0F, 0F, 0.45379F));
        root.addChild("c30", ModelPartBuilder.create().uv(10, 84)
                .cuboid(-2.22971F, -1.00621F, -0.5F, 2.2F, 1F, 1F, new Dilation(-0.001F)),
                ModelTransform.of(-1.97493F, 23.01711F, 7.5F, 0F, 0F, 0.45379F));
        root.addChild("c31", ModelPartBuilder.create().uv(84, 14)
                .cuboid(0.02971F, -1.00621F, -0.5F, 2.2F, 1F, 1F, new Dilation(-0.001F)),
                ModelTransform.of(1.97493F, 23.01711F, 7.5F, 0F, 0F, -0.45379F));
        root.addChild("c32", ModelPartBuilder.create().uv(64, 66)
                .cuboid(-5F, -2F, -10F, 4F, 4F, 4F, Dilation.NONE),
                ModelTransform.of(3F, 20.5F, -1F, 0F, 0F, 0F));
        root.addChild("c33", ModelPartBuilder.create().uv(72, 18)
                .cuboid(-5F, -2F, -10F, 4F, 4F, 4F, new Dilation(0.25F)),
                ModelTransform.of(3F, 20.5F, -1F, 0F, 0F, 0F));
        root.addChild("c34", ModelPartBuilder.create().uv(74, 83)
                .cuboid(-0.5F, -1F, -1F, 1F, 2F, 2F, Dilation.NONE),
                ModelTransform.of(2.5F, 20.5F, -9F, -0.7854F, 0F, 0F));
        root.addChild("c35", ModelPartBuilder.create().uv(80, 83)
                .cuboid(-0.5F, -1F, -1F, 1F, 2F, 2F, Dilation.NONE),
                ModelTransform.of(2F, 20.5F, -8F, 0F, 0F, 0F));
        root.addChild("c36", ModelPartBuilder.create().uv(84, 0)
                .cuboid(-0.5F, -1F, -1F, 1F, 2F, 2F, Dilation.NONE),
                ModelTransform.of(-2.5F, 20.5F, -9F, -0.7854F, 0F, 0F));
        root.addChild("c37", ModelPartBuilder.create().uv(84, 4)
                .cuboid(-0.5F, -1F, -1F, 1F, 2F, 2F, Dilation.NONE),
                ModelTransform.of(-2F, 20.5F, -8F, 0F, 0F, 0F));
        root.addChild("c38", ModelPartBuilder.create().uv(80, 66)
                .cuboid(-1F, -0.5F, -1F, 2F, 1F, 2F, Dilation.NONE),
                ModelTransform.of(0F, 18F, -9F, 0F, 0.7854F, 0F));
        root.addChild("c39", ModelPartBuilder.create().uv(64, 58)
                .cuboid(-5F, -5F, -10F, 4F, 3F, 5F, Dilation.NONE),
                ModelTransform.of(3F, 20F, 0F, 0F, 0F, 0F));
        root.addChild("c40", ModelPartBuilder.create().uv(46, 73)
                .cuboid(-5F, -4F, -12F, 4F, 2F, 2F, Dilation.NONE),
                ModelTransform.of(3F, 20F, 0F, 0F, 0F, 0F));
        root.addChild("c41", ModelPartBuilder.create().uv(72, 47)
                .cuboid(-2F, -0.07905F, 0.01627F, 4F, 1F, 2.25F, Dilation.NONE),
                ModelTransform.of(0F, 16.07926F, -11.98478F, 0.45379F, 0F, 0F));
        root.addChild("c42", ModelPartBuilder.create().uv(58, 74)
                .cuboid(-2F, -0.07905F, -2.26627F, 4F, 1F, 2.25F, Dilation.NONE),
                ModelTransform.of(0F, 16.07926F, -3.01522F, -0.45379F, 0F, 0F));
        root.addChild("c43", ModelPartBuilder.create().uv(0, 65)
                .cuboid(-1F, -4F, -12F, 1F, 1F, 7F, Dilation.NONE),
                ModelTransform.of(3F, 20F, 0F, 0F, 0F, 0F));
        root.addChild("c44", ModelPartBuilder.create().uv(48, 65)
                .cuboid(0F, -4F, -12F, 1F, 1F, 7F, Dilation.NONE),
                ModelTransform.of(-3F, 20F, 0F, 0F, 0F, 0F));
        root.addChild("c45", ModelPartBuilder.create().uv(16, 65)
                .cuboid(-0.5F, -0.5F, -3.5F, 1.4F, 1.4F, 7F, new Dilation(-0.001F)),
                ModelTransform.of(-2.29289F, 17F, -8.5F, 0F, 0F, -0.7854F));
        root.addChild("c46", ModelPartBuilder.create().uv(32, 65)
                .cuboid(-0.9F, -0.5F, -3.5F, 1.4F, 1.4F, 7F, new Dilation(-0.001F)),
                ModelTransform.of(2.29289F, 17F, -8.5F, 0F, 0F, 0.7854F));
        root.addChild("c47", ModelPartBuilder.create().uv(72, 30)
                .cuboid(-0.5F, -0.9F, -1.5F, 1F, 1.4F, 5F, new Dilation(-0.001F)),
                ModelTransform.of(2.29289F, 16F, -8.5F, 0F, 0F, -0.7854F));
        root.addChild("c48", ModelPartBuilder.create().uv(72, 36)
                .cuboid(-0.5F, -0.9F, -1.5F, 1F, 1.4F, 5F, new Dilation(-0.001F)),
                ModelTransform.of(-2.29289F, 16F, -8.5F, 0F, 0F, 0.7854F));
        root.addChild("c49", ModelPartBuilder.create().uv(0, 31)
                .cuboid(-4.01525F, 0.01525F, -7F, 4F, 5F, 14F, new Dilation(-0.001F)),
                ModelTransform.of(2F, 15.97843F, 0F, 0F, 0F, -0.63705F));
        root.addChild("c50", ModelPartBuilder.create().uv(74, 79)
                .cuboid(-0.01525F, 0.01525F, 4F, 1F, 1F, 3F, new Dilation(-0.001F)),
                ModelTransform.of(2F, 15.97843F, 0F, 0F, 0F, -0.63705F));
        root.addChild("c51", ModelPartBuilder.create().uv(66, 77)
                .cuboid(-0.01525F, 1.01525F, 6F, 1F, 1F, 1F, new Dilation(-0.001F)),
                ModelTransform.of(2F, 15.97843F, 0F, 0F, 0F, -0.63705F));
        root.addChild("c52", ModelPartBuilder.create().uv(34, 80)
                .cuboid(-0.98475F, 0.01525F, 4F, 1F, 1F, 3F, new Dilation(-0.001F)),
                ModelTransform.of(-2F, 15.97843F, 0F, 0F, 0F, 0.63705F));
        root.addChild("c53", ModelPartBuilder.create().uv(42, 80)
                .cuboid(-0.98475F, 1.01525F, 6F, 1F, 1F, 1F, new Dilation(-0.001F)),
                ModelTransform.of(-2F, 15.97843F, 0F, 0F, 0F, 0.63705F));
        root.addChild("c54", ModelPartBuilder.create().uv(6, 83)
                .cuboid(-1.01525F, 0.01525F, -8F, 1F, 5F, 1F, new Dilation(-0.001F)),
                ModelTransform.of(2F, 15.97843F, 0F, 0F, 0F, -0.63705F));
        root.addChild("c55", ModelPartBuilder.create().uv(84, 8)
                .cuboid(0.01525F, 0.01525F, -8F, 1F, 5F, 1F, new Dilation(-0.001F)),
                ModelTransform.of(-2F, 15.97843F, 0F, 0F, 0F, 0.63705F));
        root.addChild("c56", ModelPartBuilder.create().uv(36, 31)
                .cuboid(0.01525F, 0.01525F, -7F, 4F, 5F, 14F, new Dilation(-0.001F)),
                ModelTransform.of(-2F, 15.97843F, 0F, 0F, 0F, 0.63705F));
        ModelPartData rotor0 = root.addChild("rotor_0", ModelPartBuilder.create(),
                ModelTransform.pivot(15.31026F, 14.95F, -9.23907F));
        rotor0.addChild("c57", ModelPartBuilder.create().uv(80, 72)
                .cuboid(-1F, -0.5F, -1F, 2F, 1F, 2F, Dilation.NONE),
                ModelTransform.of(0F, 0F, 0F, 0F, 0.2618F, 0F));
        rotor0.addChild("c58", ModelPartBuilder.create().uv(40, 18)
                .cuboid(-7F, 0F, -1F, 14F, 0F, 2F, Dilation.NONE),
                ModelTransform.of(0F, 0F, 0F, 0F, 1.0472F, 0F));
        ModelPartData rotor1 = root.addChild("rotor_1", ModelPartBuilder.create(),
                ModelTransform.pivot(-15.31026F, 14.95F, 9.23907F));
        rotor1.addChild("c59", ModelPartBuilder.create().uv(58, 82)
                .cuboid(-1F, -0.5F, -1F, 2F, 1F, 2F, Dilation.NONE),
                ModelTransform.of(0F, 0F, 0F, 0F, 0.2618F, 0F));
        rotor1.addChild("c60", ModelPartBuilder.create().uv(40, 22)
                .cuboid(-7F, 0F, -1F, 14F, 0F, 2F, Dilation.NONE),
                ModelTransform.of(0F, 0F, 0F, 0F, 1.0472F, 0F));
        ModelPartData rotor2 = root.addChild("rotor_2", ModelPartBuilder.create(),
                ModelTransform.pivot(-15.31026F, 14.95F, -9.23907F));
        rotor2.addChild("c61", ModelPartBuilder.create().uv(82, 58)
                .cuboid(-1F, -0.5F, -1F, 2F, 1F, 2F, Dilation.NONE),
                ModelTransform.of(0F, 0F, 0F, 0F, -0.2618F, 0F));
        rotor2.addChild("c62", ModelPartBuilder.create().uv(40, 24)
                .cuboid(-7F, 0F, -1F, 14F, 0F, 2F, Dilation.NONE),
                ModelTransform.of(0F, 0F, 0F, 0F, -1.0472F, 0F));
        ModelPartData rotor3 = root.addChild("rotor_3", ModelPartBuilder.create(),
                ModelTransform.pivot(15.31026F, 14.95F, 9.23907F));
        rotor3.addChild("c63", ModelPartBuilder.create().uv(40, 20)
                .cuboid(-7F, 0F, -1F, 14F, 0F, 2F, Dilation.NONE),
                ModelTransform.of(0F, 0F, 0F, 0F, -1.0472F, 0F));
        rotor3.addChild("c64", ModelPartBuilder.create().uv(80, 75)
                .cuboid(-1F, -0.5F, -1F, 2F, 1F, 2F, Dilation.NONE),
                ModelTransform.of(0F, 0F, 0F, 0F, -0.2618F, 0F));
        return TexturedModelData.of(data, 128, 128);
    }
}
