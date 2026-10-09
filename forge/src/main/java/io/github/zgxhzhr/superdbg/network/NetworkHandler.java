package io.github.zgxhzhr.superdbg.network;

import io.github.zgxhzhr.superdbg.Constants;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 模组网络通道注册中心。
 */
public final class NetworkHandler {

    /** 协议版本，服务端与客户端不一致时拒绝连接。 */
    private static final String PROTOCOL_VERSION = "17";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Constants.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private NetworkHandler() {
    }

    /**
     * 注册所有网络包。在模组入口调用一次。
     */
    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(
                id++,
                UpdatePotionEffectPacket.class,
                UpdatePotionEffectPacket::encode,
                UpdatePotionEffectPacket::decode,
                UpdatePotionEffectPacket::handle
        );
        CHANNEL.registerMessage(
                id++,
                OpenEditorPacket.class,
                OpenEditorPacket::encode,
                OpenEditorPacket::decode,
                OpenEditorPacket::handle
        );
        CHANNEL.registerMessage(
                id++,
                OpenItemEditorPacket.class,
                OpenItemEditorPacket::encode,
                OpenItemEditorPacket::decode,
                OpenItemEditorPacket::handle
        );
        CHANNEL.registerMessage(
                id++,
                SubmitItemEditorPacket.class,
                SubmitItemEditorPacket::encode,
                SubmitItemEditorPacket::decode,
                SubmitItemEditorPacket::handle
        );
        CHANNEL.registerMessage(
                id++,
                OpenEntityEditorPacket.class,
                OpenEntityEditorPacket::encode,
                OpenEntityEditorPacket::decode,
                OpenEntityEditorPacket::handle
        );
        CHANNEL.registerMessage(
                id++,
                SubmitEntityEditorPacket.class,
                SubmitEntityEditorPacket::encode,
                SubmitEntityEditorPacket::decode,
                SubmitEntityEditorPacket::handle
        );
        CHANNEL.registerMessage(
                id++,
                OpenGameRuleEditorPacket.class,
                OpenGameRuleEditorPacket::encode,
                OpenGameRuleEditorPacket::decode,
                OpenGameRuleEditorPacket::handle
        );
        CHANNEL.registerMessage(
                id++,
                SubmitGameRuleEditorPacket.class,
                SubmitGameRuleEditorPacket::encode,
                SubmitGameRuleEditorPacket::decode,
                SubmitGameRuleEditorPacket::handle
        );
        CHANNEL.registerMessage(
                id++,
                RequestGameRuleEditorPacket.class,
                RequestGameRuleEditorPacket::encode,
                RequestGameRuleEditorPacket::decode,
                RequestGameRuleEditorPacket::handle
        );
        CHANNEL.registerMessage(
                id++,
                QuickActionPacket.class,
                QuickActionPacket::encode,
                QuickActionPacket::decode,
                QuickActionPacket::handle
        );
        CHANNEL.registerMessage(
                id++,
                RequestDimensionsPacket.class,
                RequestDimensionsPacket::encode,
                RequestDimensionsPacket::decode,
                RequestDimensionsPacket::handle
        );
        CHANNEL.registerMessage(
                id++,
                DimensionListPacket.class,
                DimensionListPacket::encode,
                DimensionListPacket::decode,
                DimensionListPacket::handle
        );
        CHANNEL.registerMessage(
                id++,
                EntityClearPacket.class,
                EntityClearPacket::encode,
                EntityClearPacket::decode,
                EntityClearPacket::handle
        );
        CHANNEL.registerMessage(
                id++,
                EntityClearResultPacket.class,
                EntityClearResultPacket::encode,
                EntityClearResultPacket::decode,
                EntityClearResultPacket::handle
        );
        CHANNEL.registerMessage(
                id++,
                GiveItemPacket.class,
                GiveItemPacket::encode,
                GiveItemPacket::decode,
                GiveItemPacket::handle
        );
        CHANNEL.registerMessage(
                id++,
                SyncRenderNamePacket.class,
                SyncRenderNamePacket::encode,
                SyncRenderNamePacket::decode,
                SyncRenderNamePacket::handle
        );
        CHANNEL.registerMessage(
                id++,
                SetFoxSlabModelPacket.class,
                SetFoxSlabModelPacket::encode,
                SetFoxSlabModelPacket::decode,
                SetFoxSlabModelPacket::handle
        );
        CHANNEL.registerMessage(
                id++,
                SetRenderNamePacket.class,
                SetRenderNamePacket::encode,
                SetRenderNamePacket::decode,
                SetRenderNamePacket::handle
        );
        CHANNEL.registerMessage(
                id++,
                SetPseudoCreativePacket.class,
                SetPseudoCreativePacket::encode,
                SetPseudoCreativePacket::decode,
                SetPseudoCreativePacket::handle
        );
        CHANNEL.registerMessage(
                id++,
                AddPotionEffectPacket.class,
                AddPotionEffectPacket::encode,
                AddPotionEffectPacket::decode,
                AddPotionEffectPacket::handle
        );
    }
}
