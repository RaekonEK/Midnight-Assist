package com.one_studio;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.registry.Registry;

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
    private TextFieldWidget searchBox;
    private String searchText = "";
    private boolean suppressListener = false;
    private int page = 0;

    public WeaponSelectScreen(Screen parent, Screen configParent) {
        super(new TranslatableText("title.midnight-assist.weapon_select"));
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
        for (Item item : Registry.ITEM) {
            items.add(item);
        }
        items.sort(Comparator.comparing(item -> new TranslatableText(item.getTranslationKey()).asFormattedString()));

        for (Item item : items) {
            allEntries.add("item:" + Registry.ITEM.getId(item));
        }
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

            String entry = filtered.get(index);
            Text label = entryLabel(entry);

            addButton(new ButtonWidget(centerX - 170, y, 340, ROW_HEIGHT, label.asFormattedString(), b -> selectEntry(entry)));

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

    private Text entryLabel(String entry) {
        if (entry.startsWith("cat:")) {
            String key = entry.substring(4);
            return new TranslatableText("text.midnight-assist.category_entry",
                new TranslatableText("option.midnight-assist.weapon_category." + key));
        }
        if (entry.startsWith("big:")) {
            String key = entry.substring(4);
            return new TranslatableText("text.midnight-assist.big_category_entry",
                new TranslatableText("option.midnight-assist.big_category." + key));
        }
        String id = entry.substring(5);
        for (Item item : Registry.ITEM) {
            if (Registry.ITEM.getId(item).toString().equals(id)) {
                return new TranslatableText(item.getTranslationKey());
            }
        }
        return new LiteralText(id);
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
        MinecraftClient.getInstance().openScreen(
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
        return entryLabel(entry).asFormattedString().toLowerCase(Locale.ROOT).contains(query);
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

        int pages = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        String pageText = new TranslatableText("text.midnight-assist.page", page + 1, pages).asFormattedString();
        font.draw(pageText, width / 2f - font.getStringWidth(pageText) / 2f, height - 44, 0xFFA0A0A0);
    }

    @Override
    public void onClose() {
        MinecraftClient.getInstance().openScreen(parent);
    }
}
