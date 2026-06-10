package com.ToasterStudios.jenga.network;

import com.ToasterStudios.jenga.JengaMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client → server packet carrying the player's "drag distance modifier"
 * key state (held = true / released = false). The user binds this key in
 * Controls; when it's held while scrolling, scroll changes drag distance
 * instead of yaw.
 */
public record ModifierKeyPayload(boolean held) implements CustomPayload {

    public static final CustomPayload.Id<ModifierKeyPayload> ID =
        new CustomPayload.Id<>(Identifier.of(JengaMod.MOD_ID, "modifier_key"));

    public static final PacketCodec<RegistryByteBuf, ModifierKeyPayload> CODEC =
        PacketCodec.tuple(
            PacketCodecs.BOOL, ModifierKeyPayload::held,
            ModifierKeyPayload::new
        );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
