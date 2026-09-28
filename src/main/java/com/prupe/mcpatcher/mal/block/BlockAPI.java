package com.prupe.mcpatcher.mal.block;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import net.coderbot.iris.shaderpack.materialmap.BlockEntry;
import net.coderbot.iris.shaderpack.materialmap.FlatteningMap;
import net.coderbot.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.block.Block;

import com.prupe.mcpatcher.MCPatcherUtils;
import com.prupe.mcpatcher.mal.resource.PropertiesFile;
import com.prupe.mcpatcher.mal.resource.TexturePackAPI;

import cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry;
import cpw.mods.fml.common.registry.GameData;

public class BlockAPI {

    @SuppressWarnings("unchecked")
    BlockAPI(FMLControlledNamespacedRegistry<Block> registry) {
        File outputFile = new File("blocks17.txt");
        if (outputFile.isFile()) {
            PrintStream ps = null;
            try {
                ps = new PrintStream(outputFile);
                String[] nameList = new String[4096];
                for (String name17 : (Set<String>) registry.getKeys()) {
                    Object block = registry.getObject(name17);
                    if (block != null) {
                        int id = registry.getIDForObject(block);
                        if (id >= 0 && id < nameList.length) {
                            nameList[id] = name17;
                        }
                    }
                }
                for (int id = 0; id < nameList.length; id++) {
                    if (nameList[id] != null) {
                        ps.printf("canonicalIdByName.put(\"%s\", %d);\n", nameList[id], id);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                MCPatcherUtils.close(ps);
            }
        }
    }

    public static String getBlockName(Block block) {
        return block == null ? "(null)" : block.getUnlocalizedName();
    }

    public static List<Block> getAllBlocks() {
        List<Block> blocks = new ArrayList<>();
        for (Block block : GameData.getBlockRegistry()
            .typeSafeIterable()) {
            if (block != null && !blocks.contains(block)) {
                blocks.add(block);
            }
        }
        return blocks;
    }

    public static List<BlockStateMatcher> createMatchers(PropertiesFile source, String matchString) {
        Map<String, String> propertyMap = new HashMap<>();
        String namespace = null;
        String blockName = null;
        StringBuilder metadata = new StringBuilder();
        StringBuilder metaString = new StringBuilder();
        for (String s : matchString.split("\\s*:\\s*")) {
            if (s.isEmpty()) {
                continue;
            }
            boolean appendThis = false;
            String[] tokens = s.split("\\s*=\\s*", 2);
            if (blockName == null) {
                blockName = s;
            } else if (tokens.length == 2) {
                propertyMap.put(tokens[0], tokens[1]);
                appendThis = true;
            } else if (namespace == null && !s.matches("\\d[-, 0-9]*")) {
                namespace = blockName;
                blockName = s;
            } else if (s.matches("\\d[-, 0-9]*")) {
                metadata.append(' ')
                    .append(s);
                appendThis = true;
            } else {
                source.warning("invalid token '%s' in %s", source, s, matchString);
                return Collections.emptyList();
            }
            if (appendThis) {
                metaString.append(':');
                metaString.append(s);
            }
        }

        if (MCPatcherUtils.isNullOrEmpty(namespace)) {
            namespace = source.getResource().getResourceDomain();
        }
        if (MCPatcherUtils.isNullOrEmpty(blockName)) {
            source.warning("cannot parse namespace/block name from %s", matchString);
            return Collections.emptyList();
        }
        final Block block = GameData.getBlockRegistry()
            .getRaw(namespace + ':' + blockName);
        if (block != null) {
            return Collections.singletonList(
                new BlockStateMatcher(
                    metaString.toString(),
                    block,
                    metadata.toString()
                        .trim()));
        }
        if (!TexturePackAPI.DEFAULT_NAMESPACE.equals(namespace)) {
            source.fine("unknown block %s:%s", namespace, blockName);
            return Collections.emptyList();
        }
        final List<BlockEntry> entries = FlatteningMap.toLegacy(blockName, propertyMap);
        if (entries == null) {
            source.fine("unknown block %s:%s", namespace, blockName);
            return Collections.emptyList();
        }
        if (!propertyMap.isEmpty() && entries.equals(FlatteningMap.toLegacy(blockName, Collections.emptyMap()))) {
            source.fine("block %s has no 1.7.10 equivalent for %s, ignoring this match", blockName, propertyMap);
            return Collections.emptyList();
        }
        final String explicitMetadata = metadata.toString()
            .trim();
        final List<BlockStateMatcher> matchers = new ArrayList<>(entries.size());
        for (BlockEntry entry : entries) {
            final NamespacedId id = entry.id();
            final Block legacy = GameData.getBlockRegistry()
                .getRaw(id.getNamespace() + ':' + id.getName());
            final String entryMetadata = intersectMetadata(explicitMetadata, entry.metas());
            if (entryMetadata == null) {
                continue;
            }
            matchers.add(new BlockStateMatcher(metaString.toString(), legacy, entryMetadata));
        }
        if (matchers.isEmpty()) {
            source.fine("unknown block %s:%s", namespace, blockName);
        }
        return matchers;
    }

    public static List<BlockStateMatcher> createOreDictMatchers(PropertiesFile source, String oreTag) {
        List<ItemStack> stacks = OreDictionary.getOres(oreTag);
        if(stacks.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Block, Set<Integer>> byBlock = new LinkedHashMap<>();
        Set<Block> wildcardBlocks = new HashSet<>();

        for (ItemStack stack : stacks) {
            Block block = Block.getBlockFromItem(stack.getItem());
            if (block == null || block == Blocks.air) {
                continue;
            }
            int meta = stack.getItemDamage();
            if (meta == OreDictionary.WILDCARD_VALUE) {
                wildcardBlocks.add(block);
            } else {
                byBlock.computeIfAbsent(block, b -> new TreeSet<>()).add(meta);
            }
        }

        List<BlockStateMatcher> result = new ArrayList<>();
        for (Block block : wildcardBlocks) {
            result.add(new BlockStateMatcher("[ore:"+oreTag+"]", block, ""));
        }
        for (Map.Entry<Block, Set<Integer>> e : byBlock.entrySet()) {
            if (wildcardBlocks.contains(e.getKey())) {
                continue;
            }
            String metaList = e.getValue().stream().map(String::valueOf).collect(Collectors.joining(" "));
            result.add(new BlockStateMatcher("[ore:" + oreTag + "]", e.getKey(), metaList));
        }
        return result;
    }

    private static String intersectMetadata(String explicitMetadata, Set<Integer> entryMetas) {
        if (MCPatcherUtils.isNullOrEmpty(explicitMetadata)) {
            if (entryMetas.isEmpty()) {
                return "";
            }
            final StringBuilder sb = new StringBuilder();
            for (int meta : entryMetas) {
                sb.append(' ')
                    .append(meta);
            }
            return sb.toString()
                .trim();
        }
        final StringBuilder sb = new StringBuilder();
        for (int meta : MCPatcherUtils.parseIntegerList(explicitMetadata, 0, 15)) {
            if (entryMetas.isEmpty() || entryMetas.contains(meta)) {
                sb.append(' ')
                    .append(meta);
            }
        }
        return sb.length() == 0 ? null
            : sb.toString()
                .trim();
    }

}
