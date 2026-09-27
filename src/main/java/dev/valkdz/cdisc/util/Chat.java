package dev.valkdz.cdisc.util;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.awt.Color;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Chat {

    private static final Pattern ACTION_BAR_PREFIX = Pattern.compile("(?s)actionbar(?::(\\d{1,3}))?!(.*)");

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

    public static TextComponent suggest(String legacy, String text, String hover) {
        TextComponent component = block(legacy);
        component.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, text));
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

    public static void deliver(Plugin plugin, Player player, String colour, String message) {
        Matcher routed = ACTION_BAR_PREFIX.matcher(message);
        if (!routed.matches()) {
            player.sendMessage(colour + message);
            return;
        }

        String text = colour + routed.group(2);
        int seconds = routed.group(1) == null ? 3 : Math.min(60, Integer.parseInt(routed.group(1)));
        actionBar(player, text);
        // The client keeps an action bar line about 3 s, so a longer one is resent before it fades.
        for (int at = 2; at + 1 < seconds; at += 2) {
            Tasks.entityLater(plugin, player, () -> actionBar(player, text), at * 20L);
        }
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
