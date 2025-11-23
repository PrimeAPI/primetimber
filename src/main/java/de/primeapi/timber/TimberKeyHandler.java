package de.primeapi.timber;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import io.netty.buffer.ByteBuf;

import java.util.HashSet;
import java.util.Set;

/**
 * Handles networking for the client key binding toggle for timber.
 */
public class TimberKeyHandler {

    public static final ResourceLocation TOGGLE_ID = ResourceLocation.tryParse(PrimeTimber.MOD_ID + ":timber_toggle");

    public record TimberTogglePayload(boolean pressed) implements CustomPacketPayload {
        public static final Type<TimberTogglePayload> TYPE = new Type<>(TOGGLE_ID);
        public static final StreamCodec<ByteBuf, TimberTogglePayload> CODEC = StreamCodec.of((buf, payload) -> buf.writeBoolean(payload.pressed()), buf -> new TimberTogglePayload(buf.readBoolean()));
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static final Set<ServerPlayer> ACTIVE = new HashSet<>();

    public static boolean isActive(ServerPlayer player) {
        return ACTIVE.contains(player);
    }

    public static void setActive(ServerPlayer player, boolean active) {
        if (active) {
            ACTIVE.add(player);
        } else {
            ACTIVE.remove(player);
        }
    }

    public static void registerCodec() {
        // Register client-to-server codec if not already
        PayloadTypeRegistry.playC2S().register(TimberTogglePayload.TYPE, TimberTogglePayload.CODEC);
    }

    public static void registerServerReceiver() {
        // Receiver assumes codec already registered
        ServerPlayNetworking.registerGlobalReceiver(TimberTogglePayload.TYPE, (payload, context) -> {
            context.server().execute(() -> setActive(context.player(), payload.pressed()));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ACTIVE.remove(handler.getPlayer()));
    }
}
