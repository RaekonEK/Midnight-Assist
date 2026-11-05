package com.one_studio;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

public class ActionEntry extends AbstractConfigListEntry<Void> {
    private final Runnable action;
    private final ItemStack icon;

    private int lastX;
    private int lastY;
    private int lastWidth;
    private int lastHeight;

    public ActionEntry(Component name, Runnable action) {
        this(name, ItemStack.EMPTY, action);
    }

    public ActionEntry(Component name, ItemStack icon, Runnable action) {
        super(name, false);
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
    public List<? extends NarratableEntry> narratables() {
        return List.of();
    }

    @Override
    public List<? extends GuiEventListener> children() {
        return List.of();
    }

    @Override
    public void render(PoseStack graphics, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean isHovered, float delta) {
        this.lastX = x;
        this.lastY = y;
        this.lastWidth = entryWidth;
        this.lastHeight = entryHeight;

        boolean hover = mouseX >= x && mouseX < x + entryWidth && mouseY >= y && mouseY < y + entryHeight;
        if (hover) {
            GuiComponent.fill(graphics, x + 1, y + 1, x + entryWidth - 1, y + entryHeight - 1, 0x33FFFFFF);
        }

        if (!icon.isEmpty()) {
            Minecraft.getInstance().getItemRenderer().renderAndDecorateItem(icon, x + 4, y + (entryHeight - 16) / 2);
        }

        Font font = Minecraft.getInstance().font;
        font.draw(graphics, getFieldName(), x + entryWidth / 2f - font.width(getFieldName()) / 2f, y + (entryHeight - 8) / 2, hover ? 0xFFFFFFA0 : 0xFFFFFFFF);
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
