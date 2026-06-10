package com.ToasterStudios.jenga.network;

import com.ToasterStudios.jenga.JengaMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client → server packet carrying the player's right-mouse-button state
 * (pressed = true / released = false). Used by the "hold to drag" mode
 * (lazy-drag toggle off): server starts the grab on right-click via the
 * normal {@code UseBlockCallback} and ends it when this packet reports the
 * button has been released.
 */
public record RightHoldPayload(boolean held) implements CustomPayload {

    public static final CustomPayload.Id<RightHoldPayload> ID =
        new CustomPayload.Id<>(Identifier.of(JengaMod.MOD_ID, "right_hold"));

    public static final PacketCodec<RegistryByteBuf, RightHoldPayload> CODEC =
        PacketCodec.tuple(
            PacketCodecs.BOOL, RightHoldPayload::held,
            RightHoldPayload::new
        );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
