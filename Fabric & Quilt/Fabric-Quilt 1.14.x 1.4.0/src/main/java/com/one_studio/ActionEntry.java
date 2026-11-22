package com.one_studio;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.Element;
import net.minecraft.item.ItemStack;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Clickable row used by the config screen (Cloth Config 2.1.8 / MC 1.14 API,
 * which still renders plain strings and has no MatrixStack-based render).
 */
public class ActionEntry extends AbstractConfigListEntry<Void> {
    private final String label;
    private final Runnable action;
    private final ItemStack icon;

    private int lastX;
    private int lastY;
    private int lastWidth;
    private int lastHeight;

    public ActionEntry(String label, Runnable action) {
        this(label, ItemStack.EMPTY, action);
    }

    public ActionEntry(String label, ItemStack icon, Runnable action) {
        super(label, false);
        this.label = label;
        this.icon = icon;
        this.action = action;
    }

    @Override
    public Void getValue() {
        return null;
    }

    @Override
    public void save() {
    }

    @Override
    public Optional<Void> getDefaultValue() {
        return Optional.empty();
    }

    @Override
    public int getItemHeight() {
        return 24;
    }

    @Override
    public List<? extends Element> children() {
        return Collections.emptyList();
    }

    @Override
    public void render(int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean isHovered, float delta) {
        this.lastX = x;
        this.lastY = y;
        this.lastWidth = entryWidth;
        this.lastHeight = entryHeight;

        boolean hover = mouseX >= x && mouseX < x + entryWidth && mouseY >= y && mouseY < y + entryHeight;
        DrawableHelper.fill(x, y, x + entryWidth, y + entryHeight, hover ? 0xFF3A3A3A : 0xFF262626);
        DrawableHelper.fill(x + 1, y + 1, x + entryWidth - 1, y + entryHeight - 1, hover ? 0xFF4A4A4A : 0xFF333333);

        if (!icon.isEmpty()) {
            MinecraftClient.getInstance().getItemRenderer().renderGuiItemIcon(icon, x + 4, y + (entryHeight - 16) / 2);
        }

        MinecraftClient client = MinecraftClient.getInstance();
        int color = hover ? 0xFFFFFFA0 : 0xFFFFFFFF;
        client.textRenderer.draw(label, x + entryWidth / 2f - client.textRenderer.getStringWidth(label) / 2f, y + (entryHeight - 8) / 2f, color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0
                && mouseX >= lastX && mouseX < lastX + lastWidth
                && mouseY >= lastY && mouseY < lastY + lastHeight) {
            action.run();
            return true;
        }
        return false;
    }
}
