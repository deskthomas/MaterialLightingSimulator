import java.util.EnumMap;
import java.util.Map;

final class StudioLighting {
    static final int MAX_INTENSITY_PERCENT = 200;

    record Setting(boolean enabled, int rgb, int intensityPercent) {
        boolean shining() {
            return enabled && intensityPercent > 0 && rgb != 0;
        }

        float power(StudioLight light) {
            return enabled ? light.intensity * intensityPercent / 100.0f : 0.0f;
        }

        float red() {
            return ((rgb >> 16) & 0xff) / 255.0f;
        }

        float green() {
            return ((rgb >> 8) & 0xff) / 255.0f;
        }

        float blue() {
            return (rgb & 0xff) / 255.0f;
        }
    }

    private final Map<StudioLight, Setting> settings;

    private StudioLighting(Map<StudioLight, Setting> settings) {
        this.settings = Map.copyOf(settings);
    }

    static StudioLighting defaults() {
        var settings = new EnumMap<StudioLight, Setting>(StudioLight.class);
        for (StudioLight light : StudioLight.values()) {
            boolean overhead = light == StudioLight.OVERHEAD;
            settings.put(light, new Setting(overhead, overhead ? 0xfff1d6 : 0xffffff, 100));
        }
        return new StudioLighting(settings);
    }

    Setting get(StudioLight light) {
        return settings.get(light);
    }

    StudioLighting with(StudioLight light, Setting setting) {
        if (setting.equals(get(light))) {
            return this;
        }
        var updated = new EnumMap<>(settings);
        updated.put(light, setting);
        return new StudioLighting(updated);
    }
}
