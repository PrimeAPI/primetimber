package de.primeapi.timber;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class PrimeTimberClient implements ClientModInitializer {
	private static KeyMapping timberKey;
	private static boolean lastSentState = false;
	@Override
	public void onInitializeClient() {
		// Codec registered server-side; no need here.
		// Register keybinding (default SHIFT) under gameplay category.
		timberKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.primetimber.timber",
			GLFW.GLFW_KEY_LEFT_SHIFT,
			KeyMapping.Category.GAMEPLAY
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null) return;
			boolean pressed = timberKey.isDown();
			if (pressed != lastSentState) {
				ClientPlayNetworking.send(new TimberKeyHandler.TimberTogglePayload(pressed));
				lastSentState = pressed;
			}
		});
	}
}