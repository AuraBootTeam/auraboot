package com.auraboot.framework.meta.contribution;

import com.auraboot.framework.meta.dto.PageSchemaDTO;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure, fail-closed materializer for base-authorized page contributions. */
@Component
public class PageSchemaContributionComposer {

    public static final String SLOTS_EXTENSION_KEY = "contributionSlots";

    private static final Comparator<PageSchemaContribution> CONTRIBUTION_ORDER =
            Comparator.comparingInt(PageSchemaContribution::priority).reversed()
                    .thenComparing(PageSchemaContribution::contributorId)
                    .thenComparing(PageSchemaContribution::id);

    /**
     * Applies active contributions to a deep copy of {@code basePage}. An empty list is a strict
     * no-op and returns the original instance. Invalid authorization or payload data throws before
     * the caller can expose a partially composed page.
     */
    public PageSchemaDTO compose(PageSchemaDTO basePage, List<PageSchemaContribution> contributions) {
        Objects.requireNonNull(basePage, "basePage must not be null");
        Objects.requireNonNull(contributions, "contributions must not be null");
        if (contributions.isEmpty()) {
            return basePage;
        }

        Map<String, Slot> slots = parseSlots(basePage);
        validateContributionIdentities(contributions);

        PageSchemaDTO composed = copyPage(basePage);
        List<PageSchemaContribution> ordered = contributions.stream().sorted(CONTRIBUTION_ORDER).toList();
        validateBlockPayloadIdentities(composed, ordered);
        for (PageSchemaContribution contribution : ordered) {
            apply(composed, slots, contribution);
        }
        return composed;
    }

    private Map<String, Slot> parseSlots(PageSchemaDTO page) {
        Object rawSlots = page.getExtension() == null ? null : page.getExtension().get(SLOTS_EXTENSION_KEY);
        if (!(rawSlots instanceof List<?> slotList)) {
            throw invalid("base page does not declare extension." + SLOTS_EXTENSION_KEY);
        }
        Map<String, Slot> slots = new LinkedHashMap<>();
        for (Object rawSlot : slotList) {
            if (!(rawSlot instanceof Map<?, ?> map)) {
                throw invalid("slot declaration must be an object");
            }
            String id = text(map.get("id"), "slot.id");
            PageSchemaContributionKind kind = enumValue(PageSchemaContributionKind.class, map.get("kind"), "slot.kind");
            Object rawAnchor = map.get("anchor");
            if (!(rawAnchor instanceof Map<?, ?> anchor)) {
                throw invalid("slot " + id + " must declare anchor");
            }
            AnchorTarget target = enumValue(AnchorTarget.class, anchor.get("target"), "slot.anchor.target");
            String blockId = text(anchor.get("blockId"), "slot.anchor.blockId");
            String tabKey = optionalText(anchor.get("tabKey"));
            if (target == AnchorTarget.TAB_BLOCKS && !StringUtils.hasText(tabKey)) {
                throw invalid("slot " + id + " TAB_BLOCKS anchor requires tabKey");
            }
            if (kind == PageSchemaContributionKind.BLOCK && target != AnchorTarget.TAB_BLOCKS) {
                throw invalid("slot " + id + " BLOCK kind requires TAB_BLOCKS target");
            }
            if (kind == PageSchemaContributionKind.ACTION && target == AnchorTarget.TAB_BLOCKS) {
                throw invalid("slot " + id + " ACTION kind requires an action target");
            }
            if (slots.putIfAbsent(id, new Slot(id, kind, target, blockId, tabKey)) != null) {
                throw invalid("duplicate slot id: " + id);
            }
        }
        return slots;
    }

    private void validateContributionIdentities(List<PageSchemaContribution> contributions) {
        Set<String> ids = new HashSet<>();
        for (PageSchemaContribution contribution : contributions) {
            if (contribution == null) {
                throw invalid("contribution must not be null");
            }
            requireText(contribution.id(), "contribution.id");
            requireText(contribution.contributorId(), "contribution.contributorId");
            requireText(contribution.slotId(), "contribution.slotId");
            Objects.requireNonNull(contribution.kind(), "contribution.kind must not be null");
            Objects.requireNonNull(contribution.payload(), "contribution.payload must not be null");
            if (!ids.add(contribution.id())) {
                throw invalid("duplicate contribution id: " + contribution.id());
            }
        }
    }

    private void validateBlockPayloadIdentities(PageSchemaDTO page, List<PageSchemaContribution> contributions) {
        Set<String> blockIds = new HashSet<>();
        collectBlockIds(page.getBlocks(), blockIds, "base page");
        for (PageSchemaContribution contribution : contributions) {
            if (contribution.kind() == PageSchemaContributionKind.BLOCK) {
                text(contribution.payload().get("id"), "contribution.payload.id");
                text(contribution.payload().get("blockType"), "contribution.payload.blockType");
                collectBlockIds(contribution.payload(), blockIds,
                        "contribution payload " + contribution.id());
            } else {
                text(contribution.payload().get("code"), "contribution.payload.code");
            }
        }
    }

    private void apply(PageSchemaDTO page, Map<String, Slot> slots, PageSchemaContribution contribution) {
        Slot slot = slots.get(contribution.slotId());
        if (slot == null) {
            throw invalid("missing slot: " + contribution.slotId());
        }
        if (slot.kind != contribution.kind()) {
            throw invalid("contribution " + contribution.id() + " kind does not match slot " + slot.id);
        }
        Map<String, Object> block = findBlock(page.getBlocks(), slot.blockId);
        if (block == null) {
            throw invalid("missing anchor block: " + slot.blockId);
        }

        switch (slot.target) {
            case TAB_BLOCKS -> appendToTab(block, slot, contribution.payload());
            case BLOCK_ACTIONS -> appendAction(block, "actions", contribution.payload(), slot);
            case SUB_TABLE_ACTIONS -> {
                Object rawSubTable = block.get("subTable");
                if (!(rawSubTable instanceof Map<?, ?>)) {
                    throw invalid("missing subTable anchor on block: " + slot.blockId);
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> subTable = (Map<String, Object>) rawSubTable;
                appendAction(subTable, "actions", contribution.payload(), slot);
            }
            case SUB_TABLE_TOOLBAR_ACTIONS -> {
                Object rawSubTable = block.get("subTable");
                if (!(rawSubTable instanceof Map<?, ?>)) {
                    throw invalid("missing subTable anchor on block: " + slot.blockId);
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> subTable = (Map<String, Object>) rawSubTable;
                appendAction(subTable, "toolbarActions", contribution.payload(), slot);
            }
        }
    }

    private void appendToTab(Map<String, Object> block, Slot slot, Map<String, Object> payload) {
        Object rawTabs = block.get("tabs");
        if (!(rawTabs instanceof List<?> tabs)) {
            throw invalid("anchor block " + slot.blockId + " has no tabs");
        }
        for (Object rawTab : tabs) {
            if (rawTab instanceof Map<?, ?> tab && Objects.equals(slot.tabKey, tab.get("key"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> writableTab = (Map<String, Object>) tab;
                append(writableTab, "blocks", payload, slot);
                return;
            }
        }
        throw invalid("missing anchor tab: " + slot.tabKey);
    }

    private void append(Map<String, Object> owner, String collection, Map<String, Object> payload, Slot slot) {
        Object raw = owner.get(collection);
        if (raw == null) {
            owner.put(collection, new ArrayList<>(List.of(deepCopyMap(payload))));
            return;
        }
        if (!(raw instanceof List<?> list)) {
            throw invalid("anchor collection is not a list for slot: " + slot.id);
        }
        @SuppressWarnings("unchecked")
        List<Object> writable = (List<Object>) list;
        writable.add(deepCopyMap(payload));
    }

    private void appendAction(Map<String, Object> owner, String collection,
                              Map<String, Object> payload, Slot slot) {
        String code = text(payload.get("code"), "contribution.payload.code");
        Object raw = owner.get(collection);
        if (raw instanceof List<?> actions) {
            boolean duplicate = actions.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .anyMatch(action -> Objects.equals(code, action.get("code")));
            if (duplicate) {
                throw invalid("duplicate action code in target collection: " + code);
            }
        }
        append(owner, collection, payload, slot);
    }

    private Map<String, Object> findBlock(List<?> blocks, String blockId) {
        if (blocks == null) {
            return null;
        }
        for (Object rawBlock : blocks) {
            if (!(rawBlock instanceof Map<?, ?> rawMap)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> block = (Map<String, Object>) rawMap;
            if (Objects.equals(blockId, block.get("id"))) {
                return block;
            }
            Map<String, Object> nested = findBlock(asList(block.get("blocks")), blockId);
            if (nested != null) {
                return nested;
            }
            nested = findBlock(asList(block.get("children")), blockId);
            if (nested != null) {
                return nested;
            }
            Object rawTabs = block.get("tabs");
            if (rawTabs instanceof List<?> tabs) {
                for (Object rawTab : tabs) {
                    if (rawTab instanceof Map<?, ?> tab) {
                        nested = findBlock(asList(tab.get("blocks")), blockId);
                        if (nested != null) {
                            return nested;
                        }
                    }
                }
            }
        }
        return null;
    }

    private void collectBlockIds(Object node, Set<String> blockIds, String context) {
        if (node instanceof List<?> list) {
            list.forEach(item -> collectBlockIds(item, blockIds, context));
        } else if (node instanceof Map<?, ?> map) {
            if (map.containsKey("blockType") && map.get("id") instanceof String id && !blockIds.add(id)) {
                throw invalid("duplicate id in " + context + ": " + id);
            }
            collectBlockIds(map.get("blocks"), blockIds, context);
            collectBlockIds(map.get("children"), blockIds, context);
            collectBlockIds(map.get("tabs"), blockIds, context);
            collectBlockIds(map.get("subTable"), blockIds, context);
        }
    }

    private PageSchemaDTO copyPage(PageSchemaDTO source) {
        PageSchemaDTO copy = new PageSchemaDTO();
        BeanUtils.copyProperties(source, copy);
        copy.setBlocks(deepCopyList(source.getBlocks()));
        return copy;
    }

    private List<Object> deepCopyList(List<?> source) {
        if (source == null) {
            return null;
        }
        List<Object> copy = new ArrayList<>(source.size());
        source.forEach(value -> copy.add(deepCopy(value)));
        return copy;
    }

    private Map<String, Object> deepCopyMap(Map<String, Object> source) {
        @SuppressWarnings("unchecked")
        Map<String, Object> copy = (Map<String, Object>) deepCopy(source);
        return copy;
    }

    private Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(String.valueOf(key), deepCopy(nested)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(nested -> copy.add(deepCopy(nested)));
            return copy;
        }
        return value;
    }

    private List<?> asList(Object value) {
        return value instanceof List<?> list ? list : null;
    }

    private String text(Object value, String field) {
        String text = optionalText(value);
        if (!StringUtils.hasText(text)) {
            throw invalid(field + " must not be blank");
        }
        return text;
    }

    private String optionalText(Object value) {
        return value instanceof String text ? text : null;
    }

    private void requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw invalid(field + " must not be blank");
        }
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, Object value, String field) {
        String text = text(value, field);
        try {
            return Enum.valueOf(type, text.replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid("unsupported " + field + ": " + text);
        }
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid page contribution contract: " + message);
    }

    private enum AnchorTarget {
        TAB_BLOCKS,
        BLOCK_ACTIONS,
        SUB_TABLE_ACTIONS,
        SUB_TABLE_TOOLBAR_ACTIONS
    }

    private record Slot(String id, PageSchemaContributionKind kind, AnchorTarget target,
                        String blockId, String tabKey) {
    }
}
