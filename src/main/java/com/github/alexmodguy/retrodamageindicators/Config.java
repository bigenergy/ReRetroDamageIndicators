package com.github.alexmodguy.retrodamageindicators;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class Config {
    private static final Logger LOGGER = LoggerFactory.getLogger("retrodamageindicators-config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("retrodamageindicators.json");
    private static final List<Entry<?>> ENTRIES = new ArrayList<>();
    public static final Config INSTANCE = new Config();

    public final BoolValue damageParticlesEnabled = bool("damage_particles_enabled", true);
    public final DoubleValue damageParticleSize = dbl("damage_particle_size", 1.0, 0.1, 10.0);
    public final BoolValue damageParticleOutline = bool("damage_particle_outline", true);
    public final BoolValue hudIndicatorEnabled = bool("hud_indicator_enabled", true);
    public final DoubleValue maxDistance = dbl("max_distance", 100.0, 3.0, 10000.0);
    public final BoolValue colorblindHealthBar = bool("colorblind_health_bar", false);
    public final BoolValue healthDecimals = bool("health_decimals", true);
    public final BoolValue healthSeperator = bool("health_separator", true);
    public final IntValue hudLingerTime = integer("hud_linger_time", 30, 0, 1200);
    public final DoubleValue hudIndicatorSize = dbl("hud_indicator_size", 0.75, 0.0, 10.0);
    public final DoubleValue hudIndicatorBackgroundOpacity = dbl("hud_indicator_background_opacity", 0.75, 0.0, 10.0);
    public final BoolValue hudIndicatorAlignLeft = bool("hud_indicator_align_left", true);
    public final BoolValue hudIndicatorAlignTop = bool("hud_indicator_align_top", true);
    public final IntValue hudIndicatorPositionX = integer("hud_indicator_position_x", 10, Integer.MIN_VALUE, Integer.MAX_VALUE);
    public final IntValue hudIndicatorPositionY = integer("hud_indicator_position_y", 10, Integer.MIN_VALUE, Integer.MAX_VALUE);
    public final DoubleValue hudEntitySize = dbl("hud_entity_size", 38.0, 0.0, 2000.0);
    public final BoolValue hudNameTextOutline = bool("hud_name_text_outline", false);
    public final BoolValue hudHealthTextOutline = bool("hud_health_text_outline", false);
    public final BoolValue hpBarAnimated = bool("hp_bar_animated", true);
    public final DoubleValue hpBarAnimationSpeed = dbl("hp_bar_animation_speed", 0.15, 0.01, 1.0);
    public final BoolValue damageFlash = bool("damage_flash", true);
    public final IntValue damageFlashDuration = integer("damage_flash_duration", 8, 2, 30);
    public final BoolValue showModSource = bool("show_mod_source", false);
    public final DoubleValue modSourceSize = dbl("mod_source_size", 1.0, 0.1, 5.0);
    public final IntValue modSourceOffsetX = integer("mod_source_offset_x", 0, -500, 500);
    public final IntValue modSourceOffsetY = integer("mod_source_offset_y", 5, -500, 500);
    public final IntValue modSourceColor = integer("mod_source_color", 0xAAAAAA, 0x000000, 0xFFFFFF);
    public final StringListValue oldRenderEntities = stringList("hud_old_render_entities", List.of("alexsmobs:giant_squid"));

    static {
        INSTANCE.load();
    }

    public static class Entry<T> {
        final String key;
        final T defaultValue;
        T value;
        final Supplier<JsonElement> serializer;
        Entry(String key, T defaultValue, Supplier<JsonElement> serializer) {
            this.key = key; this.defaultValue = defaultValue; this.value = defaultValue; this.serializer = serializer;
        }
    }

    public class BoolValue extends Entry<Boolean> {
        BoolValue(String key, boolean def) { super(key, def, null); }
        public Boolean get() { return value; }
        public void set(boolean v) { value = v; save(); }
    }
    public class IntValue extends Entry<Integer> {
        public final int min, max;
        IntValue(String key, int def, int min, int max) { super(key, def, null); this.min = min; this.max = max; }
        public Integer get() { return value; }
        public void set(int v) { value = Math.max(min, Math.min(max, v)); save(); }
    }
    public class DoubleValue extends Entry<Double> {
        public final double min, max;
        DoubleValue(String key, double def, double min, double max) { super(key, def, null); this.min = min; this.max = max; }
        public Double get() { return value; }
        public void set(double v) { value = Math.max(min, Math.min(max, v)); save(); }
    }
    public class StringListValue extends Entry<List<String>> {
        StringListValue(String key, List<String> def) { super(key, def, null); }
        public List<String> get() { return value; }
        public void set(List<String> v) { value = v; save(); }
    }

    private BoolValue bool(String k, boolean d) { BoolValue v = new BoolValue(k, d); ENTRIES.add(v); return v; }
    private IntValue integer(String k, int d, int mn, int mx) { IntValue v = new IntValue(k, d, mn, mx); ENTRIES.add(v); return v; }
    private DoubleValue dbl(String k, double d, double mn, double mx) { DoubleValue v = new DoubleValue(k, d, mn, mx); ENTRIES.add(v); return v; }
    private StringListValue stringList(String k, List<String> d) { StringListValue v = new StringListValue(k, d); ENTRIES.add(v); return v; }

    public void load() {
        if (!Files.exists(CONFIG_PATH)) { save(); return; }
        try {
            String json = Files.readString(CONFIG_PATH);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null) return;
            for (Entry<?> e : ENTRIES) {
                if (!root.has(e.key)) continue;
                JsonElement el = root.get(e.key);
                if (e instanceof BoolValue b) b.value = el.getAsBoolean();
                else if (e instanceof IntValue i) i.value = Math.max(i.min, Math.min(i.max, el.getAsInt()));
                else if (e instanceof DoubleValue d) d.value = Math.max(d.min, Math.min(d.max, el.getAsDouble()));
                else if (e instanceof StringListValue sl) {
                    List<String> list = new ArrayList<>();
                    el.getAsJsonArray().forEach(je -> list.add(je.getAsString()));
                    sl.value = list;
                }
            }
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("Failed to load config", ex);
        }
    }

    public void save() {
        try {
            JsonObject root = new JsonObject();
            for (Entry<?> e : ENTRIES) {
                if (e instanceof BoolValue b) root.addProperty(e.key, b.value);
                else if (e instanceof IntValue i) root.addProperty(e.key, i.value);
                else if (e instanceof DoubleValue d) root.addProperty(e.key, d.value);
                else if (e instanceof StringListValue sl) {
                    com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                    sl.value.forEach(arr::add);
                    root.add(e.key, arr);
                }
            }
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(root));
        } catch (IOException ex) {
            LOGGER.warn("Failed to save config", ex);
        }
    }
}
