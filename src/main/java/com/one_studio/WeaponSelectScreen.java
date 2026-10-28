package com.one_studio;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class WeaponSelectScreen extends Screen {
    private static final int PAGE_SIZE = 7;
    private static final int ROW_HEIGHT = 18;
    private static final int ROW_GAP = 2;
    private static final int ROW_TOP = 68;

    private final Screen parent;
    private final Screen configParent;
    private final List<String> allEntries = new ArrayList<>();
    private final List<String> filtered = new ArrayList<>();
    private EditBox searchBox;
    private String searchText = "";
    private boolean suppressResponder = false;
    private int page = 0;

    public WeaponSelectScreen(Screen parent, Screen configParent) {
        super(Component.translatable("title.midnight-assist.weapon_select"));
        this.parent = parent;
        this.configParent = configParent;
        buildEntries();
    }

    private void buildEntries() {
        allEntries.clear();

        allEntries.add("big:item");
        allEntries.add("big:block");

        for (String key : MidnightAssistConfig.weaponCategoryKeys()) {
            allEntries.add("cat:" + key);
        }

        List<Item> items = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            items.add(item);
        }
        items.sort(Comparator.comparing(item -> Component.translatable(item.getDescriptionId()).getString()));

        for (Item item : items) {
            allEntries.add("item:" + BuiltInRegistries.ITEM.getKey(item));
        }
    }

    @Override
    protected void init() {
        int centerX = width / 2;

        searchBox = new EditBox(font, centerX - 120, 30, 240, 18, Component.translatable("search.midnight-assist.weapon_select"));
        searchBox.setHint(Component.translatable("search.midnight-assist.weapon_select"));
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

            String entry = filtered.get(index);
            Component label = entryLabel(entry);

            addRenderableWidget(Button.builder(label, b -> selectEntry(entry))
                .bounds(centerX - 170, y, 340, ROW_HEIGHT).build());

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

    private Component entryLabel(String entry) {
        if (entry.startsWith("cat:")) {
            String key = entry.substring(4);
            return Component.translatable("text.midnight-assist.category_entry",
                Component.translatable("option.midnight-assist.weapon_category." + key));
        }
        if (entry.startsWith("big:")) {
            String key = entry.substring(4);
            return Component.translatable("text.midnight-assist.big_category_entry",
                Component.translatable("option.midnight-assist.big_category." + key));
        }
        String id = entry.substring(5);
        for (Item item : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.getKey(item).toString().equals(id)) {
                return Component.translatable(item.getDescriptionId());
            }
        }
        return Component.literal(id);
    }

    private void selectEntry(String entry) {
        String kind;
        String key;
        if (entry.startsWith("cat:")) {
            kind = "category";
            key = entry.substring(4);
        } else if (entry.startsWith("big:")) {
            kind = "big";
            key = entry.substring(4);
        } else {
            kind = "item";
            key = entry.substring(5);
        }

        ConfigData data = MidnightAssistConfig.getConfigData();
        WeaponProfile profile = null;
        for (WeaponProfile existing : data.weaponProfiles) {
            if (existing.kind.equals(kind) && existing.key.equals(key)) {
                profile = existing;
                break;
            }
        }

        if (profile == null) {
            profile = new WeaponProfile();
            profile.kind = kind;
            profile.key = key;
            data.weaponProfiles.add(profile);
            MidnightAssistConfig.INSTANCE.save();
        }

        Screen configScreen = MidnightAssistModMenu.createConfigScreen(configParent);
        Minecraft.getInstance().setScreen(
            MidnightAssistModMenu.createWeaponProfileScreen(profile, configScreen, configParent));
    }

    private void refreshFilter() {
        String query = searchText.trim().toLowerCase(Locale.ROOT);
        filtered.clear();

        for (String entry : allEntries) {
            if (query.isEmpty() || matchesQuery(entry, query)) {
                filtered.add(entry);
            }
        }
    }

    private boolean matchesQuery(String entry, String query) {
        if (entry.toLowerCase(Locale.ROOT).contains(query)) return true;
        return entryLabel(entry).getString().toLowerCase(Locale.ROOT).contains(query);
    }

    private void clampPage() {
        int pages = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFFFF);

        if (filtered.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("text.midnight-assist.no_results"), width / 2, height / 2, 0xFFA0A0A0);
            return;
        }

        int pages = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        graphics.drawCenteredString(font, Component.translatable("text.midnight-assist.page", page + 1, pages), width / 2, height - 44, 0xFFA0A0A0);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
