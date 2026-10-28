package com.one_studio;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class EntityListScreen extends Screen {
    private static final int PAGE_SIZE = 7;
    private static final int ROW_HEIGHT = 18;
    private static final int ROW_GAP = 2;
    private static final int ROW_TOP = 68;

    private final Screen parent;
    private final List<String> filtered = new ArrayList<>();
    private EditBox searchBox;
    private String searchText = "";
    private boolean suppressResponder = false;
    private int page = 0;

    public EntityListScreen(Screen parent) {
        super(Component.translatable("title.midnight-assist.entity_list"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = width / 2;

        searchBox = new EditBox(font, centerX - 120, 30, 240, 18, Component.translatable("search.midnight-assist.entity_list"));
        searchBox.setHint(Component.translatable("search.midnight-assist.entity_list"));
        searchBox.setMaxLength(64);
        suppressResponder = true;
        searchBox.setValue(searchText);
        suppressResponder = false;
        searchBox.setResponder(value -> {
            if (suppressResponder) return;
            searchText = value;
            page = 0;
            rebuildWidgets();
        });
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox);

        refreshFilter();
        clampPage();

        int startIndex = page * PAGE_SIZE;
        int y = ROW_TOP;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = startIndex + i;
            if (index >= filtered.size()) break;

            String id = filtered.get(index);
            EntityType<?> type = MidnightAssistConfig.getEntityType(id);
            if (type == null) continue;

            ConfigData data = MidnightAssistConfig.getConfigData();
            boolean enabled = data.enabledEntities.getOrDefault(id, false);
            boolean priority = data.priorityEntities.contains(id);
            Component name = Component.translatable(type.getDescriptionId());

            Component label = Component.literal((enabled ? "\u2714 " : "\u2718 ") + name.getString());
            addRenderableWidget(Button.builder(label, b -> {
                MidnightAssistConfig.getConfigData().enabledEntities.put(id, !enabled);
                rebuildWidgets();
            }).bounds(centerX - 170, y, 230, ROW_HEIGHT).build());

            Component star = Component.literal(priority ? "\u2605" : "\u2606");
            addRenderableWidget(Button.builder(star, b -> {
                List<String> priorities = MidnightAssistConfig.getConfigData().priorityEntities;
                if (priorities.contains(id)) {
                    priorities.remove(id);
                } else {
                    priorities.add(id);
                }
                rebuildWidgets();
            }).bounds(centerX + 66, y, 24, ROW_HEIGHT).build());

            y += ROW_HEIGHT + ROW_GAP;
        }

        int bottomY = height - 28;
        addRenderableWidget(Button.builder(Component.translatable("button.midnight-assist.prev"), b -> {
            if (page > 0) {
                page--;
                rebuildWidgets();
            }
        }).bounds(centerX - 170, bottomY, 70, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("button.midnight-assist.next"), b -> {
            if ((page + 1) * PAGE_SIZE < filtered.size()) {
                page++;
                rebuildWidgets();
            }
        }).bounds(centerX + 100, bottomY, 70, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("button.midnight-assist.done"), b -> onClose())
            .bounds(centerX - 55, bottomY, 110, 20).build());
    }

    private void refreshFilter() {
        String query = searchText.trim().toLowerCase(Locale.ROOT);
        filtered.clear();

        List<String> ids = new ArrayList<>(MidnightAssistConfig.getConfigData().enabledEntities.keySet());
        ids.remove("minecraft:player");
        Collections.sort(ids);

        for (String id : ids) {
            if (query.isEmpty() || matchesQuery(id, query)) {
                filtered.add(id);
            }
        }
    }

    private boolean matchesQuery(String id, String query) {
        if (id.toLowerCase(Locale.ROOT).contains(query)) return true;
        EntityType<?> type = MidnightAssistConfig.getEntityType(id);
        if (type == null) return false;
        return Component.translatable(type.getDescriptionId()).getString().toLowerCase(Locale.ROOT).contains(query);
    }

    private void clampPage() {
        int pages = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);

        extractor.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);

        if (filtered.isEmpty()) {
            extractor.centeredText(font, Component.translatable("text.midnight-assist.no_results"), width / 2, height / 2, 0xFFA0A0A0);
            return;
        }

        extractor.text(font, Component.translatable("text.midnight-assist.target_column"), width / 2 - 170, ROW_TOP - 11, 0xFF808080);
        extractor.text(font, Component.translatable("text.midnight-assist.priority_column"), width / 2 + 69, ROW_TOP - 11, 0xFF808080);

        int pages = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        extractor.centeredText(font, Component.translatable("text.midnight-assist.page", page + 1, pages), width / 2, height - 44, 0xFFA0A0A0);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }
}
