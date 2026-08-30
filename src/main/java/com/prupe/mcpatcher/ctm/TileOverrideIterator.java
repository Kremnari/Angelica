package com.prupe.mcpatcher.ctm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.util.IIcon;

import com.prupe.mcpatcher.mal.block.BlockStateMatcher;

import jss.notfine.config.MCPatcherForgeConfig;

abstract public class TileOverrideIterator implements Iterator<TileOverride> {

    private final Map<Block, List<BlockStateMatcher>> allBlockOverrides;
    private final Map<String, List<TileOverride>> allTileOverrides;

    protected IIcon currentIcon;

    private List<BlockStateMatcher> blockOverrides;
    private List<TileOverride> tileOverrides;
    private final Set<TileOverride> skipOverrides = new HashSet<>();

    private RenderBlockState renderBlockState;
    private int blockPos;
    private int iconPos;
    private boolean foundNext;
    private TileOverride nextOverride;
    private TileOverride lastMatchedOverride;

    protected TileOverrideIterator(Map<Block, List<BlockStateMatcher>> allBlockOverrides,
        Map<String, List<TileOverride>> allTileOverrides) {
        this.allBlockOverrides = allBlockOverrides;
        this.allTileOverrides = allTileOverrides;
    }

     void clear() {
        currentIcon = null;
        blockOverrides = null;
        tileOverrides = null;
        nextOverride = null;
        lastMatchedOverride = null;
        skipOverrides.clear();
    }

    private void resetForNextPass() {
        blockOverrides = null;
        tileOverrides = allTileOverrides.get(currentIcon.getIconName());
        blockPos = 0;
        iconPos = 0;
        foundNext = false;
    }

    @Override
    public boolean hasNext() {
        if (foundNext) {
            return true;
        }
        if (tileOverrides != null) {
            while (iconPos < tileOverrides.size()) {
                if (checkOverride(tileOverrides.get(iconPos++))) {
                    renderBlockState.setFilter(null);
                    return true;
                }
            }
        }
        if (blockOverrides != null) {
            while (blockPos < blockOverrides.size()) {
                BlockStateMatcher matcher = blockOverrides.get(blockPos++);
                if (renderBlockState.match(matcher) && checkOverride((TileOverride) matcher.getData())) {
                    renderBlockState.setFilter(matcher);
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public TileOverride next() {
        if (!foundNext) {
            throw new IllegalStateException("next called before hasNext() == true");
        }
        foundNext = false;
        return nextOverride;
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException("remove not supported");
    }

    private boolean checkOverride(TileOverride override) {
        if (override != null && !override.isDisabled() && !skipOverrides.contains(override)) {
            foundNext = true;
            nextOverride = override;
            return true;
        } else {
            return false;
        }
    }

    public TileOverride go(RenderBlockState renderBlockState, IIcon origIcon) {
        CTMUtils.clearCurrentCompact();
        this.renderBlockState = renderBlockState;
        renderBlockState.setFilter(null);
        currentIcon = origIcon;
        blockOverrides = allBlockOverrides.get(renderBlockState.getBlock());
        tileOverrides = allTileOverrides.get(origIcon.getIconName());
        blockPos = 0;
        iconPos = 0;
        foundNext = false;
        nextOverride = null;
        lastMatchedOverride = null;
        skipOverrides.clear();

        pass: for (int pass = 0; pass < MCPatcherForgeConfig.ConnectedTextures.maxRecursion; pass++) {
            while (hasNext()) {
                TileOverride override = next();
                IIcon newIcon = getTile(override, renderBlockState, origIcon);
                if (newIcon != null) {
                    if (override instanceof TileOverrideImpl.CTMCompact compact) {
                        CTMUtils.setCurrentCompact(
                            collectCompactLayers(compact, renderBlockState, origIcon),
                            renderBlockState);
                    }
                    lastMatchedOverride = override;
                    skipOverrides.add(override);
                    currentIcon = newIcon;
                    resetForNextPass();
                    continue pass;
                }
            }
            break;
        }
        return lastMatchedOverride;
    }

    // Collector: given the CTMCompact override that just won the normal priority walk, look ahead through the
    // remainder of the same sorted candidate list for other CTMCompact overrides on this same block/state with a
    // distinct renderLayer, so they can all be painted together as one composited stack. This is a read-only
    // lookahead -- it never touches blockPos/skipOverrides/currentIcon, so the surrounding pass/recursion logic
    // (which is for tile-chained overrides, not layering) behaves exactly as it did before renderLayer existed.
    private TileOverrideImpl.CTMCompact[] collectCompactLayers(TileOverrideImpl.CTMCompact primary,
        RenderBlockState renderBlockState, IIcon origIcon) {
        List<TileOverrideImpl.CTMCompact> layers = new ArrayList<>();
        layers.add(primary);
        if (blockOverrides != null) {
            BlockStateMatcher primaryFilter = renderBlockState.getFilter();
            Set<Integer> claimedLayers = new HashSet<>();
            claimedLayers.add(primary.getRenderLayer());
            try {
                for (int i = blockPos; i < blockOverrides.size(); i++) {
                    BlockStateMatcher matcher = blockOverrides.get(i);
                    if (!(matcher.getData() instanceof TileOverrideImpl.CTMCompact candidate)) {
                        continue;
                    }
                    if (candidate.isDisabled() || claimedLayers.contains(candidate.getRenderLayer())) {
                        continue;
                    }
                    if (!renderBlockState.match(matcher)) {
                        continue;
                    }
                    renderBlockState.setFilter(matcher);
                    if (getTile(candidate, renderBlockState, origIcon) != null) {
                        claimedLayers.add(candidate.getRenderLayer());
                        layers.add(candidate);
                    }
                }
            } finally {
                // Restore the filter to the primary's matcher -- go() is about to snapshot renderBlockState via
                // CTMUtils.setCurrentCompact, and that snapshot must reflect the primary match, not whichever
                // sibling we last probed.
                renderBlockState.setFilter(primaryFilter);
            }
        }
        layers.sort(Comparator.comparingInt(TileOverrideImpl.CTMCompact::getRenderLayer));
        return layers.toArray(new TileOverrideImpl.CTMCompact[0]);
    }

    public IIcon getIcon() {
        return currentIcon;
    }

    abstract protected IIcon getTile(TileOverride override, RenderBlockState renderBlockState, IIcon origIcon);

    public static final class IJK extends TileOverrideIterator {

        IJK(Map<Block, List<BlockStateMatcher>> blockOverrides, Map<String, List<TileOverride>> tileOverrides) {
            super(blockOverrides, tileOverrides);
        }

        @Override
        protected IIcon getTile(TileOverride override, RenderBlockState renderBlockState, IIcon origIcon) {
            return override.getTileWorld(renderBlockState, origIcon);
        }
    }

    public static final class Metadata extends TileOverrideIterator {

        Metadata(Map<Block, List<BlockStateMatcher>> blockOverrides, Map<String, List<TileOverride>> tileOverrides) {
            super(blockOverrides, tileOverrides);
        }

        @Override
        protected IIcon getTile(TileOverride override, RenderBlockState renderBlockState, IIcon origIcon) {
            return override.getTileHeld(renderBlockState, origIcon);
        }
    }
}
