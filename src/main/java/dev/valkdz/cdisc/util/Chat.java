package dev.valkdz.cdisc.util;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.entity.Player;

import java.awt.Color;

public final class Chat {

    private Chat() {
    }

    public static BaseComponent[] of(String legacy) {
        return TextComponent.fromLegacyText(legacy == null ? "" : legacy);
    }

    public static TextComponent block(String legacy) {
        return new TextComponent(of(legacy));
    }

    public static TextComponent link(String legacy, String url, String hover) {
        TextComponent component = block(legacy);
        component.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        if (hover != null) {
            component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(of(hover))));
        }
        return component;
    }

    public static TextComponent command(String legacy, String command, String hover) {
        TextComponent component = block(legacy);
        component.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
        if (hover != null) {
            component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(of(hover))));
        }
        return component;
    }

    public static TextComponent copyable(String legacy, String value, String hover) {
        TextComponent component = block(legacy);
        component.setClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, value));
        if (hover != null) {
            component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(of(hover))));
        }
        return component;
    }

    public static void send(Player player, BaseComponent... components) {
        player.spigot().sendMessage(components);
    }

    public static void actionBar(Player player, BaseComponent... components) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, components);
    }

    public static void actionBar(Player player, String legacy) {
        actionBar(player, of(legacy));
    }

    public static void actionBar(Player player, String message, int red, int green, int blue) {
        TextComponent component = new TextComponent(message == null ? " " : message);
        component.setColor(ChatColor.of(new Color(clamp(red), clamp(green), clamp(blue))));
        actionBar(player, component);
    }

    public static void clearActionBar(Player player) {
        actionBar(player, new TextComponent(" "));
    }

    private static int clamp(int channel) {
        return Math.max(0, Math.min(255, channel));
    }
}
