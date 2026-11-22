package com.one_studio;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.EntityType;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;

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
    private TextFieldWidget searchBox;
    private String searchText = "";
    private boolean suppressListener = false;
    private int page = 0;

    public EntityListScreen(Screen parent) {
        super(new TranslatableText("title.midnight-assist.entity_list"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = width / 2;

        searchBox = new TextFieldWidget(font, centerX - 120, 30, 240, 18, "");
        searchBox.setMaxLength(64);
        suppressListener = true;
        searchBox.setText(searchText);
        suppressListener = false;
        searchBox.setChangedListener(value -> {
            if (suppressListener) return;
            searchText = value;
            page = 0;
            init(minecraft, width, height);
        });
        addButton(searchBox);

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
            Text name = new TranslatableText(type.getTranslationKey());

            String label = (enabled ? "\u2714 " : "\u2718 ") + name.asFormattedString();
            addButton(new ButtonWidget(centerX - 170, y, 230, ROW_HEIGHT, label, b -> {
                MidnightAssistConfig.getConfigData().enabledEntities.put(id, !enabled);
                init(minecraft, width, height);
            }));

            String star = priority ? "\u2605" : "\u2606";
            addButton(new ButtonWidget(centerX + 66, y, 24, ROW_HEIGHT, star, b -> {
                List<String> priorities = MidnightAssistConfig.getConfigData().priorityEntities;
                if (priorities.contains(id)) {
                    priorities.remove(id);
                } else {
                    priorities.add(id);
                }
                init(minecraft, width, height);
            }));

            y += ROW_HEIGHT + ROW_GAP;
        }

        int bottomY = height - 28;
        addButton(new ButtonWidget(centerX - 170, bottomY, 70, 20, new TranslatableText("button.midnight-assist.prev").asFormattedString(), b -> {
            if (page > 0) {
                page--;
                init(minecraft, width, height);
            }
        }));

        addButton(new ButtonWidget(centerX + 100, bottomY, 70, 20, new TranslatableText("button.midnight-assist.next").asFormattedString(), b -> {
            if ((page + 1) * PAGE_SIZE < filtered.size()) {
                page++;
                init(minecraft, width, height);
            }
        }));

        addButton(new ButtonWidget(centerX - 55, bottomY, 110, 20, new TranslatableText("button.midnight-assist.done").asFormattedString(), b -> onClose()));
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
        return new TranslatableText(type.getTranslationKey()).asFormattedString().toLowerCase(Locale.ROOT).contains(query);
    }

    private void clampPage() {
        int pages = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTick) {
        super.render(mouseX, mouseY, partialTick);

        String titleText = title.asFormattedString();
        font.draw(titleText, width / 2f - font.getStringWidth(titleText) / 2f, 12, 0xFFFFFFFF);

        if (filtered.isEmpty()) {
            String noResults = new TranslatableText("text.midnight-assist.no_results").asFormattedString();
            font.draw(noResults, width / 2f - font.getStringWidth(noResults) / 2f, height / 2, 0xFFA0A0A0);
            return;
        }

        String targetColumn = new TranslatableText("text.midnight-assist.target_column").asFormattedString();
        font.draw(targetColumn, width / 2 - 170, ROW_TOP - 11, 0xFF808080);

        String priorityColumn = new TranslatableText("text.midnight-assist.priority_column").asFormattedString();
        font.draw(priorityColumn, width / 2 + 69, ROW_TOP - 11, 0xFF808080);

        int pages = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        String pageText = new TranslatableText("text.midnight-assist.page", page + 1, pages).asFormattedString();
        font.draw(pageText, width / 2f - font.getStringWidth(pageText) / 2f, height - 44, 0xFFA0A0A0);
    }

    @Override
    public void onClose() {
        MinecraftClient.getInstance().openScreen(parent);
    }
}
