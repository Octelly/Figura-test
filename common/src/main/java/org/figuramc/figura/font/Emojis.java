package org.figuramc.figura.font;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.utils.*;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

public class Emojis {

    private static final Map<String, EmojiContainer> EMOJIS = new HashMap<>();
    private static final Map<String, EmojiContainer> SHORTCUT_LOOKUP = new HashMap<>();

    public static final char DELIMITER = ':';
    public static final char ESCAPE = '\\';

    // listener to load emojis from the resource pack
    public static final FiguraResourceListener RESOURCE_LISTENER = FiguraResourceListener.createResourceListener("emojis", manager -> {
        EMOJIS.clear();

        for (Map.Entry<ResourceLocation, Resource> emojis : manager.listResources("emojis", location -> location.getNamespace().equals(FiguraMod.MOD_ID) && location.getPath().endsWith(".json")).entrySet()) {
            ResourceLocation location = emojis.getKey();
            String[] split = location.getPath().split("/", 2);

            if (split.length <= 1)
                continue;

            String name = split[1].substring(0, split[1].length() - 5);

            // open the resource as json
            try (InputStream stream = emojis.getValue().open()) {
                // add emoji
                JsonObject json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                EmojiContainer container = new EmojiContainer(name, json);
                EMOJIS.put(name, container);
                container.getLookup().getShortcuts().forEach(shortcut -> SHORTCUT_LOOKUP.put(shortcut, container));

                // check for duplicates
                Set<String> set = new HashSet<>();
                for (EmojiContainer emoji : EMOJIS.values()) {
                    for (String s : emoji.getLookup().getNames()) {
                        if (!set.add(s)) {
                            FiguraMod.LOGGER.warn("Duplicate emoji id registered {}", s);
                        }
                    }
                }
            } catch (Exception e) {
                FiguraMod.LOGGER.error("Failed to load {} emojis", name, e);
            }
        }
    });

    public static Collection<String> getCategoryNames() {
        return EMOJIS.keySet();
    }

    public static EmojiContainer getCategory(String key) {
        return EMOJIS.get(key);
    }

    public static boolean hasCategory(String key) {
        return EMOJIS.containsKey(key);
    }

    public static MutableComponent applyEmojis(Component text) {
        Component newText = TextUtils.parseLegacyFormatting(text);
        MutableComponent ret = Component.empty();
        newText.visit((style, string) -> {
            ret.append(convertEmoji(string, style));
            return Optional.empty();
        }, Style.EMPTY);
        return ret;
    }

    private static MutableComponent convertEmoji(String string, Style style) {

        // string lists, every odd index is an emoji
        List<String> strings = new ArrayList<>();

        // temp variables
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        boolean inside = false;

        // iterate over every char
        for (int i = 0; i < string.length(); i++) {
            char c = string.charAt(i);

            // escape sequence
            if (c == ESCAPE) {
                escaped = !escaped;
                // append only if the next char is not the delimiter
                if (i + 1 == string.length() || string.charAt(i + 1) != DELIMITER)
                    current.append(c);
                // delimiter, only if not escaped
            } else if (c == DELIMITER && !escaped) {
                // toggle inside status
                inside = !inside;
                // and also append to the list
                strings.add(current.toString());
                current = new StringBuilder();
                // otherwise just add to the queue
            } else {
                escaped = false;
                current.append(c);

                // space character breaks current emoji parsing
                if (c == ' ' && inside) {
                    inside = false;
                    // removed last appended emoji, as were undoing the parsing of the emoji
                    String removed = strings.remove(strings.size() - 1);
                    // replace current with the removed string, adding back the delimiter and appending the current parsed text
                    current = new StringBuilder(removed).append(DELIMITER).append(current);
                }
            }
        }

        // if the queue is not flushed
        if (current.length() > 0 || inside) {
            String toAdd = current.toString();
            // if we left inside, we want to keep the text within the last index
            // also keep the delimiter since its end was not found
            if (inside) {
                int index = strings.size() - 1;
                String s = strings.get(index) + DELIMITER + toAdd;
                strings.set(index, s);
                // otherwise just add what is remaining
            } else {
                strings.add(toAdd);
            }
        }

        MutableComponent result = Component.empty().withStyle(style);

        // now we parse the list
        for (int i = 0; i < strings.size(); i++) {
            String s = strings.get(i);

            // even: append text
            if (i % 2 == 0) {
                // Find all emoji shortcuts in string
                List<String> shortcuts = SHORTCUT_LOOKUP.keySet().stream().filter(s::contains).toList();

                // Replace all shortcuts
                if (shortcuts.size() > 0) {
                    while(s.length() > 0) {
                        boolean anyFound = false;
                        for (String shortcut : shortcuts) {
                            if (s.startsWith(shortcut)) {
                                s = s.substring(shortcut.length());
                                result.append(SHORTCUT_LOOKUP.get(shortcut).getShortcutComponent(shortcut));
                                anyFound = true;
                                break;
                            }
                        }
                        if (!anyFound) {
                            result.append(String.valueOf(s.charAt(0)));
                            s = s.substring(1);
                        }
                    }
                }
                // Otherwise append as normal
                else {
                    result.append(s);
                }
            }
            // odd: format and append emoji
            else {
                appendEmoji(result, s, Emojis::getEmoji);
            }
        }

        return result;
    }

    private static void appendEmoji(MutableComponent result, String s, Function<String, Component> converter) {
        Component emoji = converter.apply(s);
        if (emoji != null) {
            result.append(emoji);
        } else {
            result.append(DELIMITER + s + DELIMITER);
        }
    }

    public static Component getEmoji(String emojiAlias) {
        for (EmojiContainer container : EMOJIS.values()) {
            Component emoji = container.getEmojiComponent(emojiAlias);
            if (emoji != null) {
                return emoji;
            }
        }
        return null;
    }

    public static EmojiContainer getCategoryByFont(ResourceLocation location) {
        for (EmojiContainer container : EMOJIS.values()) {
            if (location.equals(container.getFont())) {
                return container;
            }
        }
        return null;
    }

    public static Component removeBlacklistedEmojis(Component text) {
        for (EmojiContainer container : EMOJIS.values())
            text = container.blacklist(text);
        return text;
    }

    public static List<String> getMatchingEmojis(String query) {
        if (query.length() == 0 || query.charAt(0) != DELIMITER)
            return List.of();

        String name = query.substring(1);
        List<String> emojis = new ArrayList<>();

        for (EmojiContainer container : EMOJIS.values()) {
            for (String s : container.getLookup().getNames()) {
                if (s.startsWith(name))
                    emojis.add(DELIMITER + s + DELIMITER);
            }
        }

        return emojis;
    }

    public static void tickAnimations() {
        for (EmojiContainer container : EMOJIS.values()) {
            container.tickAnimations();
        }
    }
}
