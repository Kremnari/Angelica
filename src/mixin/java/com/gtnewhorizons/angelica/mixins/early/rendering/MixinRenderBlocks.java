package com.gtnewhorizons.angelica.mixins.early.rendering;

import com.gtnewhorizons.angelica.api.ExtCeleritasRenderBlocks;
import com.gtnewhorizons.angelica.common.BlockError;
import com.gtnewhorizons.angelica.loading.AngelicaClientTweaker;
import com.gtnewhorizons.angelica.proxy.ClientProxy;
import com.gtnewhorizons.angelica.rendering.StateAwareTessellator;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.prupe.mcpatcher.ctm.CTMUtils;
import com.prupe.mcpatcher.ctm.RenderBlockState;
import com.prupe.mcpatcher.ctm.TileOverrideImpl;
import cpw.mods.fml.client.registry.RenderingRegistry;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.coderbot.iris.Iris;
import net.coderbot.iris.block_rendering.BlockRenderingSettings;
import net.minecraft.block.Block;
import net.minecraft.block.BlockGrass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.common.util.ForgeDirection;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderBlocks.class)
public abstract class MixinRenderBlocks implements ExtCeleritasRenderBlocks {
    @Shadow
    public abstract boolean renderStandardBlockWithColorMultiplier(Block p_147736_1_, int p_147736_2_, int p_147736_3_, int p_147736_4_, float p_147736_5_, float p_147736_6_, float p_147736_7_);

    @Unique
    private static final ObjectOpenHashSet<String> isbrhExceptionCache = new ObjectOpenHashSet<>();

    @Unique
    private static final Object2IntOpenHashMap<Class<? extends Exception>> exceptionErrorBlockMap = new Object2IntOpenHashMap<>();

    static {
        exceptionErrorBlockMap.put(NullPointerException.class, 0);
        exceptionErrorBlockMap.put(ArrayIndexOutOfBoundsException.class, 1);
    }

    @Unique
    private boolean isRenderingByType = false;

    private boolean applyingCeleritasAO = false;

    @Inject(method = "renderBlockByRenderType", at = @At("HEAD"))
    private void renderingByTypeEnable(CallbackInfoReturnable<Boolean> ci) {
        CTMUtils.clearCurrentCompact();
        this.isRenderingByType = true;
    }

    @Inject(method = "renderBlockByRenderType", at = @At("TAIL"))
    private void renderingByTypeDisable(CallbackInfoReturnable<Boolean> ci) {
        CTMUtils.clearCurrentCompact();
        this.isRenderingByType = false;
    }

    /**
     * This mixin and the one below(wrapRenderWorldBlockDeobfuscated) achieve the same goal. The goal is to wrap ISBRH rendering in a try/catch
     * to ignore NPE, as mods commonly like to not null-guard the tile entity casting, and Sodium introduces a race condition where when a block
     * is broken, the TE can be removed from the world before the render thread gets to it, but the block data is deep copied to the thread, so
     * it still tries to render the block.

     * The reason there's two mixins to the same thing for this, is because FMLRenderAccessLibrary is an old Forge remnant of Optifine compat
     * whereby they provided this class for Optifine to be able to access Forge's rendering methods. For some reason, in a deobfuscated environment,
     * that class lives in net.minecraft.src.FMLRenderAccessLibrary. However in an obfuscated prod environment, it gets moved into the root unnamed
     * package. So basically, only one of these two redirects will actually end up getting applied, and the other will fail, based on what environment
     * you're running in.
     */
    @Redirect(
        method = "renderBlockByRenderType",
        at = @At(
            value = "INVOKE",
            target = "LFMLRenderAccessLibrary;renderWorldBlock(Lnet/minecraft/client/renderer/RenderBlocks;Lnet/minecraft/world/IBlockAccess;IIILnet/minecraft/block/Block;I)Z",
            remap = false
        ),
        expect = 0
    )
    private boolean wrapRenderWorldBlockObfuscated(RenderBlocks rb, IBlockAccess world, int x, int y, int z, Block block, int modelId) {
        return handleISBRHException(rb, world, x, y, z, block, modelId);
    }

    @Redirect(
        method = "renderBlockByRenderType",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/src/FMLRenderAccessLibrary;renderWorldBlock(Lnet/minecraft/client/renderer/RenderBlocks;Lnet/minecraft/world/IBlockAccess;IIILnet/minecraft/block/Block;I)Z",
            remap = false
        ),
        expect = 0
    )
    private boolean wrapRenderWorldBlockDeobfuscated(RenderBlocks rb, IBlockAccess world, int x, int y, int z, Block block, int modelId) {
        return handleISBRHException(rb, world, x, y, z, block, modelId);
    }

    /**
     * @author embeddedt
     * @reason When vanilla would render with AO, hijack the rendering logic and render using flat lighting instead.
     */
    @Inject(method = { "renderStandardBlockWithAmbientOcclusion", "renderStandardBlockWithAmbientOcclusionPartial" }, at = @At("HEAD"), cancellable = true)
    private void handleCeleritasAo(Block block, int x, int y, int z, float r, float g, float b, CallbackInfoReturnable<Boolean> cir) {
        if (angelica$shouldApplyCeleritasAO()) {
            this.applyingCeleritasAO = true;
            try {
                cir.setReturnValue(this.renderStandardBlockWithColorMultiplier(block, x, y, z, r, g, b));
            } finally {
                this.applyingCeleritasAO = false;
            }
        }
    }

    @Override
    public boolean angelica$shouldApplyCeleritasAO() {
        return (this.isRenderingByType && Minecraft.isAmbientOcclusionEnabled() && ClientProxy.options().quality.useCeleritasSmoothLighting) ||
            (Iris.enabled && BlockRenderingSettings.INSTANCE.shouldUseSeparateAo());
    }

    @Override
    public void angelica$setApplyingCeleritasAO(boolean val) {
        this.applyingCeleritasAO = val;
    }

    /**
     * Widen the grass identity check ({@code block != Blocks.grass}) to cover any BlockGrass subclass
     * (e.g. BOP's loamy/sandy/silty grass). When the block being rendered IS a BlockGrass, we return
     * it in place of {@code Blocks.grass} so the reference comparison evaluates to {@code false},
     * giving it the same "no color multiplier on sides/bottom" treatment as vanilla grass.
     */
    @ModifyExpressionValue(method = "renderStandardBlockWithColorMultiplier",
        at = @At(value = "FIELD", target = "Lnet/minecraft/init/Blocks;grass:Lnet/minecraft/block/BlockGrass;", opcode = Opcodes.GETSTATIC))
    private BlockGrass angelica$widenGrassCheck(BlockGrass grassBlock, @Local(argsOnly = true, ordinal = 0) Block block) {
        return (block instanceof BlockGrass bg && block == ClientProxy.bopGrass) ? bg : grassBlock;
    }

    /* Disable diffuse when celeritas AO is in use */
    @ModifyExpressionValue(method = "renderStandardBlockWithColorMultiplier", at = @At(value = "CONSTANT", args = "floatValue=0.5", ordinal = 0))
    private float noBottomDiffuse(float original) {
        return this.applyingCeleritasAO ? 1.0f : original;
    }

    @ModifyExpressionValue(method = "renderStandardBlockWithColorMultiplier", at = @At(value = "CONSTANT", args = "floatValue=0.6", ordinal = 0))
    private float noXDiffuse(float original) {
        return this.applyingCeleritasAO ? 1.0f : original;
    }

    @ModifyExpressionValue(method = "renderStandardBlockWithColorMultiplier", at = @At(value = "CONSTANT", args = "floatValue=0.8", ordinal = 0))
    private float noZDiffuse(float original) {
        return this.applyingCeleritasAO ? 1.0f : original;
    }

    @SuppressWarnings("deprecation")
    private boolean handleISBRHException(RenderBlocks rb, IBlockAccess world, int x, int y, int z, Block block, int modelId) {
        try {
            return RenderingRegistry.instance().renderWorldBlock(rb, world, x, y, z, block, modelId);
        } catch (Exception e) {
            CTMUtils.clearCurrentCompact();
            // Render Error Block
            int meta = exceptionErrorBlockMap.getOrDefault(e.getClass(), 0);
            rb.overrideBlockTexture = BlockError.icons[exceptionErrorBlockMap.getOrDefault(e.getClass(), 0)];
            rb.renderStandardBlock(ClientProxy.blockError, x, y, z);
            rb.overrideBlockTexture = null;

            // Check if we've already caught the exception for this block and log it if we haven't
            String key = block.getUnlocalizedName() + ":" + meta;
            if (isbrhExceptionCache.add(key)) {
                AngelicaClientTweaker.LOGGER.warn("Caught an exception during ISBRH rendering for {} at position {}, {}, {} with renderer ID {}", block.getUnlocalizedName(), x, y, z, modelId, e);
            }
        }
        return false;
    }

    @ModifyExpressionValue(method = { "renderStandardBlockWithColorMultiplier" },
        at = @At(value = "FIELD", opcode = Opcodes.GETFIELD, target = "Lnet/minecraft/client/renderer/RenderBlocks;renderAllFaces:Z"))
    private boolean applyAOBrightness(boolean original, @Local(ordinal = 0) Tessellator tessellator) {
        ((StateAwareTessellator)tessellator).angelica$setAppliedAo(this.applyingCeleritasAO);
        return original;
    }

    @Inject(method = { "renderStandardBlockWithColorMultiplier" },
        at = @At("RETURN"))
    private void resetAOFlag(Block p_147736_1_, int p_147736_2_, int p_147736_3_, int p_147736_4_, float p_147736_5_, float p_147736_6_, float p_147736_7_, CallbackInfoReturnable<Boolean> cir, @Local(ordinal = 0) Tessellator tessellator) {
        ((StateAwareTessellator)tessellator).angelica$setAppliedAo(false);
    }

    @Shadow
    public IBlockAccess blockAccess;

    @Unique
    private void angelica$handleCompactCtmFace(IIcon icon, ForgeDirection direction, CallbackInfo ci) {
        CTMUtils.CTMCompactContext ctx = CTMUtils.getCurrentCompact();
        if (ctx == null) {
            return;
        }
        TileOverrideImpl.CTMCompact[] compacts = ctx.compacts();
        RenderBlockState renderBlockState = ctx.renderBlockState();
        CTMUtils.clearCurrentCompact();
        if (this.blockAccess == null || renderBlockState.getBlockAccess() == null) {
            return;
        }

        RenderBlocks rb = (RenderBlocks) (Object) this;
        int face = direction.ordinal();

        if (rb.hasOverrideBlockTexture()) {
            // Something else (vanilla's own block-damage/mining-crack overlay via renderBlockUsingTexture, or
            // any other caller that forces a specific icon via setOverrideBlockTexture) wants this exact face
            // drawn with that specific icon, not whatever CTM would normally paint here. CTM's own icon
            // selection still runs and still populates CURRENT_COMPACT regardless of overrideBlockTexture --
            // vanilla always computes the "normal" icon first and only substitutes overrideBlockTexture
            // afterward (e.g. RenderBlocks.renderFaceYNeg's own "if (hasOverrideBlockTexture()) icon =
            // overrideBlockTexture;" as its first statement) -- so this context is genuinely fresh here, not
            // stale. Defer entirely, for every layer in the stack, before touching renderMinX/Y/Z or
            // renderMaxX/Y/Z at all -- matching how classic method=ctm already behaves here by construction,
            // and how the single-layer compact bridge handles it (see ctm-compact-override-texture-fix).
            return;
        }

        // renderLayer=0 (compacts[0]) is the floor: it alone decides whether this face is CTM-managed at all.
        // If it has nothing to paint for this neighbor state, we leave ci uncancelled so vanilla paints the
        // plain icon, and we don't attempt the higher layers -- they're overlays on top of the floor, not a
        // replacement for it.
        //
        // The topmost layer (the one actually facing the camera) is painted at the block's true, unnudged
        // bounds; every layer beneath it is pushed inward instead, one ULP further per step down the stack.
        // That keeps the visually-foremost surface exactly where anything else that reasons about "the block's
        // real bounds" expects it to be (e.g. the vanilla block-damage overlay's own glPolygonOffset, or the
        // selection outline) -- rather than the old scheme, which left the floor at the true bounds and pushed
        // the overlay outward past it. Net relative spacing between layers is identical either way; only the
        // anchor point moves. Implemented as a pre-nudge-inward-then-walk-back-out so the array itself never
        // needs reversing: nextUp/nextDown are exact inverses, so undoing one inward step per remaining layer
        // lands the last layer back on precisely the original bounds.
        int extraLayers = compacts.length - 1;
        if (extraLayers == 0) {
            if (compacts[0].getProcessor().processFace(rb, renderBlockState, icon, face)) {
                ci.cancel();
            }
            return;
        }

        double minX = rb.renderMinX, minY = rb.renderMinY, minZ = rb.renderMinZ;
        double maxX = rb.renderMaxX, maxY = rb.renderMaxY, maxZ = rb.renderMaxZ;
        for (int i = 0; i < extraLayers; i++) {
            angelica$nudgeFaceInward(rb, face);
        }
        if (compacts[0].getProcessor().processFace(rb, renderBlockState, icon, face)) {
            for (int i = 1; i < compacts.length; i++) {
                angelica$nudgeFaceOutward(rb, face);
                compacts[i].getProcessor().processFace(rb, renderBlockState, icon, face);
            }
            ci.cancel();
        } else {
            // Floor had nothing to paint -- restore the bounds we pre-nudged so vanilla's own subsequent
            // rendering sees exactly the bounds it originally set up, untouched.
            rb.setRenderBounds(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    @Unique
    private static void angelica$nudgeFaceOutward(RenderBlocks rb, int face) {
        switch (face) {
            case 0 -> rb.renderMinY = Math.nextDown(rb.renderMinY); // Y-
            case 1 -> rb.renderMaxY = Math.nextUp(rb.renderMaxY);   // Y+
            case 2 -> rb.renderMinZ = Math.nextDown(rb.renderMinZ); // Z-
            case 3 -> rb.renderMaxZ = Math.nextUp(rb.renderMaxZ);   // Z+
            case 4 -> rb.renderMinX = Math.nextDown(rb.renderMinX); // X-
            case 5 -> rb.renderMaxX = Math.nextUp(rb.renderMaxX);   // X+
        }
    }

    @Unique
    private static void angelica$nudgeFaceInward(RenderBlocks rb, int face) {
        // Exact inverse of angelica$nudgeFaceOutward, one ULP toward the block's interior instead of away from it.
        switch (face) {
            case 0 -> rb.renderMinY = Math.nextUp(rb.renderMinY);   // Y-
            case 1 -> rb.renderMaxY = Math.nextDown(rb.renderMaxY); // Y+
            case 2 -> rb.renderMinZ = Math.nextUp(rb.renderMinZ);   // Z-
            case 3 -> rb.renderMaxZ = Math.nextDown(rb.renderMaxZ); // Z+
            case 4 -> rb.renderMinX = Math.nextUp(rb.renderMinX);   // X-
            case 5 -> rb.renderMaxX = Math.nextDown(rb.renderMaxX); // X+
        }
    }

    @Inject(method = "renderFaceYNeg", at = @At("HEAD"), cancellable = true)
    private void compactCtm_onRenderFaceYNeg(Block block, double x, double y, double z, IIcon icon, CallbackInfo ci) {
        angelica$handleCompactCtmFace(icon, ForgeDirection.DOWN, ci);
    }

    @Inject(method = "renderFaceYPos", at = @At("HEAD"), cancellable = true)
    private void compactCtm_onRenderFaceYPos(Block block, double x, double y, double z, IIcon icon, CallbackInfo ci) {
        angelica$handleCompactCtmFace(icon, ForgeDirection.UP, ci);
    }

    @Inject(method = "renderFaceZNeg", at = @At("HEAD"), cancellable = true)
    private void compactCtm_onRenderFaceZNeg(Block block, double x, double y, double z, IIcon icon, CallbackInfo ci) {
        angelica$handleCompactCtmFace(icon, ForgeDirection.NORTH, ci);
    }

    @Inject(method = "renderFaceZPos", at = @At("HEAD"), cancellable = true)
    private void compactCtm_onRenderFaceZPos(Block block, double x, double y, double z, IIcon icon, CallbackInfo ci) {
        angelica$handleCompactCtmFace(icon, ForgeDirection.SOUTH, ci);
    }

    @Inject(method = "renderFaceXNeg", at = @At("HEAD"), cancellable = true)
    private void compactCtm_onRenderFaceXNeg(Block block, double x, double y, double z, IIcon icon, CallbackInfo ci) {
        angelica$handleCompactCtmFace(icon, ForgeDirection.WEST, ci);
    }

    @Inject(method = "renderFaceXPos", at = @At("HEAD"), cancellable = true)
    private void compactCtm_onRenderFaceXPos(Block block, double x, double y, double z, IIcon icon, CallbackInfo ci) {
        angelica$handleCompactCtmFace(icon, ForgeDirection.EAST, ci);
    }

    @Inject(method = "renderStandardBlock(Lnet/minecraft/block/Block;III)Z", at = @At("RETURN"))
    private void compactCtm_resetAfterBlock(Block block, int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        CTMUtils.clearCurrentCompact();
    }

    @Inject(method = "renderBlockAsItem", at = @At("HEAD"))
    private void compactCtm_resetBeforeItem(Block block, int meta, float brightness, CallbackInfo ci) {
        CTMUtils.clearCurrentCompact();
    }
}
