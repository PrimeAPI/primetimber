package de.primeapi.timber.mixin.client;

import de.primeapi.timber.TreePreview;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "render", at = @At("TAIL"))
	private void primetimber$outline(float tickDelta, long nanoTime, boolean renderLevel, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return;
		List<BlockPos> blocks = TreePreview.getPreviewBlocks();
		if (blocks.isEmpty()) return;
		int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
		for (BlockPos p : blocks) {
			int x=p.getX(), y=p.getY(), z=p.getZ();
			if (x<minX) minX=x; if (y<minY) minY=y; if (z<minZ) minZ=z;
			if (x>maxX) maxX=x; if (y>maxY) maxY=y; if (z>maxZ) maxZ=z;
		}
		maxX += 1; maxY += 1; maxZ += 1; // encompass blocks fully
		// Debug: log bbox size occasionally
		if (mc.level.getGameTime() % 40 == 0) {
			de.primeapi.timber.PrimeTimber.LOGGER.debug("Timber preview bbox: ({} {} {}) to ({} {} {}) count {}", minX,minY,minZ,maxX,maxY,maxZ, blocks.size());
		}
		double camX = mc.gameRenderer.getMainCamera().getPosition().x;
		double camY = mc.gameRenderer.getMainCamera().getPosition().y;
		double camZ = mc.gameRenderer.getMainCamera().getPosition().z;
		var bufferSource = mc.renderBuffers().bufferSource();
		var vc = bufferSource.getBuffer(net.minecraft.client.renderer.RenderType.lines());
		float r = TreePreview.canChopAll() ? 0f : 1f;
		float g = TreePreview.canChopAll() ? 1f : 0f;
		float b = 0f;
		float a = 1.0f; // full alpha
		int ri=(int)(r*255), gi=(int)(g*255), bi=(int)(b*255), ai=(int)(a*255);
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glLineWidth(2f);
		java.util.function.BiConsumer<double[], double[]> edge = (s,e)->{
			vc.addVertex((float)(s[0]-camX),(float)(s[1]-camY),(float)(s[2]-camZ)).setColor(ri,gi,bi,ai);
			vc.addVertex((float)(e[0]-camX),(float)(e[1]-camY),(float)(e[2]-camZ)).setColor(ri,gi,bi,ai);
		};
		edge.accept(new double[]{minX,minY,minZ}, new double[]{maxX,minY,minZ});
		edge.accept(new double[]{minX,minY,minZ}, new double[]{minX,maxY,minZ});
		edge.accept(new double[]{minX,minY,minZ}, new double[]{minX,minY,maxZ});
		edge.accept(new double[]{maxX,maxY,maxZ}, new double[]{minX,maxY,maxZ});
		edge.accept(new double[]{maxX,maxY,maxZ}, new double[]{maxX,minY,maxZ});
		edge.accept(new double[]{maxX,maxY,maxZ}, new double[]{maxX,maxY,minZ});
		edge.accept(new double[]{minX,maxY,minZ}, new double[]{minX,maxY,maxZ});
		edge.accept(new double[]{minX,maxY,minZ}, new double[]{maxX,maxY,minZ});
		edge.accept(new double[]{minX,minY,maxZ}, new double[]{minX,maxY,maxZ});
		edge.accept(new double[]{minX,minY,maxZ}, new double[]{maxX,minY,maxZ});
		edge.accept(new double[]{maxX,minY,minZ}, new double[]{maxX,minY,maxZ});
		edge.accept(new double[]{maxX,minY,minZ}, new double[]{maxX,maxY,minZ});
		bufferSource.endBatch(net.minecraft.client.renderer.RenderType.lines());
		GL11.glEnable(GL11.GL_DEPTH_TEST);
	}
}
