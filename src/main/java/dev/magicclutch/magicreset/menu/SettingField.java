package dev.magicclutch.magicreset.menu;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * One config.yml setting shown in the menu. The input name is derived from the path, the label from
 * {@code menu.field.<path>} in messages.yml.
 *
 * @param path      config path, e.g. {@code timer.mode}
 * @param kind      how it is edited
 * @param min       smallest value (numbers)
 * @param max       largest value (numbers)
 * @param step      slider step (numbers)
 * @param options   allowed values (choices), stored as written here
 * @param maxLength text length limit
 * @param validator returns a messages.yml key describing the problem, or {@code null} if the text is fine
 */
record SettingField(
    String path,
    Kind kind,
    int min,
    int max,
    int step,
    List<String> options,
    int maxLength,
    @Nullable Function<String, @Nullable String> validator
) {

    enum Kind {
        /** On/off switch. */
        BOOL,
        /** Slider. */
        INT,
        /** Pick one value. */
        CHOICE,
        /** Free text. */
        TEXT,
        /** Comma separated numbers stored as a YAML list. */
        INT_LIST,
        /** "rule=value, rule=value" stored as a YAML map. */
        MAP
    }

    static SettingField bool(String path) {
        return new SettingField(path, Kind.BOOL, 0, 0, 0, List.of(), 0, null);
    }

    static SettingField number(String path, int min, int max, int step) {
        return new SettingField(path, Kind.INT, min, max, step, List.of(), 0, null);
    }

    static SettingField choice(String path, List<String> options) {
        return new SettingField(path, Kind.CHOICE, 0, 0, 0, List.copyOf(options), 0, null);
    }

    static SettingField choice(String path, Enum<?>[] values) {
        return choice(path, Arrays.stream(values).map(Enum::name).toList());
    }

    static SettingField text(String path, int maxLength, @Nullable Function<String, @Nullable String> validator) {
        return new SettingField(path, Kind.TEXT, 0, 0, 0, List.of(), maxLength, validator);
    }

    static SettingField intList(String path) {
        return new SettingField(path, Kind.INT_LIST, 0, 0, 0, List.of(), 256, null);
    }

    static SettingField map(String path) {
        return new SettingField(path, Kind.MAP, 0, 0, 0, List.of(), 512, null);
    }

    /** Dialog input names may only contain letters, digits and '_'. */
    String inputName() {
        return this.path.replace('.', '_').replace('-', '_');
    }

    String labelKey() {
        return "field." + this.path;
    }

    /** messages.yml key of a choice, e.g. {@code menu.option.first-input}. */
    static String optionKey(String value) {
        return "option." + value.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
