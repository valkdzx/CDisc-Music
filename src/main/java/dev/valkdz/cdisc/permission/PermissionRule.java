package dev.valkdz.cdisc.permission;

import org.bukkit.command.CommandSender;
import org.bukkit.permissions.PermissionDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PermissionRule {

    private final List<String> entries;

    private PermissionRule(List<String> entries) {
        this.entries = entries;
    }

    public static PermissionRule parse(Object raw) {
        List<String> entries = new ArrayList<>();

        if (raw instanceof Iterable<?> many) {
            for (Object one : many) {
                String entry = clean(one);
                if (entry != null) entries.add(entry);
            }
        } else {
            String entry = clean(raw);
            if (entry != null) entries.add(entry);
        }
        return new PermissionRule(List.copyOf(entries));
    }

    private static String clean(Object raw) {
        if (raw == null) return null;
        String text = String.valueOf(raw).trim();
        return text.isEmpty() ? null : text;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public boolean test(CommandSender sender) {
        for (String entry : entries) {
            if (matches(sender, entry)) return true;
        }
        return false;
    }

    private static boolean matches(CommandSender sender, String entry) {
        boolean negated = entry.startsWith("!");
        String subject = negated ? entry.substring(1).trim() : entry;

        PermissionDefault word = word(subject);
        boolean held = word == null ? sender.hasPermission(subject) : switch (word) {
            case TRUE -> true;
            case FALSE -> false;
            case OP -> sender.isOp();
            case NOT_OP -> !sender.isOp();
        };

        return negated != held;
    }

    public PermissionDefault asDefault() {
        if (entries.size() != 1) return null;

        String only = entries.get(0);
        if (only.startsWith("!")) {
            PermissionDefault word = word(only.substring(1).trim());
            if (word == PermissionDefault.OP) return PermissionDefault.NOT_OP;
            if (word == PermissionDefault.NOT_OP) return PermissionDefault.OP;
            if (word == PermissionDefault.TRUE) return PermissionDefault.FALSE;
            if (word == PermissionDefault.FALSE) return PermissionDefault.TRUE;
            return null;
        }
        return word(only);
    }

    private static PermissionDefault word(String entry) {
        return switch (entry.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "all", "everyone" -> PermissionDefault.TRUE;
            case "false", "no", "none", "nobody" -> PermissionDefault.FALSE;
            case "op", "operator", "ops" -> PermissionDefault.OP;
            case "notop", "not-op", "not_op" -> PermissionDefault.NOT_OP;
            default -> null;
        };
    }

    @Override
    public String toString() {
        return entries.size() == 1 ? entries.get(0) : String.join(", ", entries);
    }
}
