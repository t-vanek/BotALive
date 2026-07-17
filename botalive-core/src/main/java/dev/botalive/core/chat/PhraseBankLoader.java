package dev.botalive.core.chat;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Načítání jazykových souborů frází ({@code lang/<kód>.yml}).
 *
 * <p>Vrstvení (každá vrstva přepisuje jen to, co sama definuje – neúplný
 * překlad nikdy botům nevezme řeč):</p>
 * <ol>
 *   <li>vestavěná čeština ({@code lang/cs.yml} v jaru) – vždy kompletní,
 *       úplnost hlídá unit test,</li>
 *   <li>vestavěný soubor zvoleného jazyka (je-li přibalen, např. {@code en}),</li>
 *   <li>uživatelský soubor {@code plugins/BotAlive/lang/<kód>.yml} – vlastní
 *       překlady i úpravy; nové jazyky se přidávají prostě novým souborem.</li>
 * </ol>
 *
 * <p>Rozbité regulární výrazy ve vzorech se hlásí a spadnou na předchozí
 * vrstvu; neznámý jazyk (bez vestavěného i uživatelského souboru) spadne
 * na češtinu s varováním.</p>
 */
public final class PhraseBankLoader {

    private static final Logger LOG = LoggerFactory.getLogger(PhraseBankLoader.class);

    /** Jazyk vestavěné, vždy kompletní banky. */
    public static final String BUILT_IN_LANGUAGE = "cs";

    /** Jazyky přibalené v jaru (šablony pro vlastní překlady). */
    public static final List<String> BUNDLED_LANGUAGES = List.of("cs", "en");

    private PhraseBankLoader() {
    }

    /**
     * Načte banku frází pro daný jazyk.
     *
     * @param dataFolder datová složka pluginu ({@code plugins/BotAlive})
     * @param language   kód jazyka z konfigurace ({@code chat.language})
     * @return banka frází (nikdy {@code null}, nikdy neúplná)
     */
    public static PhraseBank load(File dataFolder, String language) {
        String code = language == null ? BUILT_IN_LANGUAGE
                : language.toLowerCase(Locale.ROOT).trim();
        PhraseBank bank = builtIn();

        boolean found = code.equals(BUILT_IN_LANGUAGE);
        if (!found) {
            ConfigurationSection bundled = readBundled(code);
            if (bundled != null) {
                bank = overlay(bundled, bank, "vestavěný lang/" + code + ".yml");
                found = true;
            }
        }
        File userFile = new File(new File(dataFolder, "lang"), code + ".yml");
        if (userFile.isFile()) {
            ConfigurationSection user = readFile(userFile);
            if (user != null) {
                bank = overlay(user, bank, userFile.getPath());
                found = true;
            }
        }
        if (!found) {
            LOG.warn("Jazyk frází '{}' nemá vestavěný ani uživatelský soubor "
                    + "(lang/{}.yml) – boti budou mluvit česky.", code, code);
        }
        return bank;
    }

    /**
     * Vyexportuje přibalené jazykové soubory do datové složky (jen chybějící),
     * aby měl admin šablony k úpravě a překladu.
     *
     * @param dataFolder datová složka pluginu
     */
    public static void exportDefaults(File dataFolder) {
        File langDir = new File(dataFolder, "lang");
        if (!langDir.isDirectory() && !langDir.mkdirs()) {
            LOG.warn("Nelze vytvořit složku {}", langDir);
            return;
        }
        for (String code : BUNDLED_LANGUAGES) {
            File target = new File(langDir, code + ".yml");
            if (target.exists()) {
                continue;
            }
            try (InputStream in = resource(code)) {
                if (in != null) {
                    Files.copy(in, target.toPath());
                }
            } catch (Exception e) {
                LOG.warn("Export lang/{}.yml selhal: {}", code, e.toString());
            }
        }
    }

    /**
     * Vestavěná čeština – poslední záchranná vrstva.
     *
     * @return kompletní banka z přibaleného {@code lang/cs.yml}
     * @throws IllegalStateException při poškozeném jaru (hlídá unit test)
     */
    static PhraseBank builtIn() {
        ConfigurationSection root = readBundled(BUILT_IN_LANGUAGE);
        if (root == null) {
            throw new IllegalStateException("V jaru chybí vestavěný lang/cs.yml");
        }
        ConfigurationSection phrases = root.getConfigurationSection("phrases");
        EnumMap<PhraseCategory, List<String>> lists = new EnumMap<>(PhraseCategory.class);
        for (PhraseCategory category : PhraseCategory.values()) {
            List<String> list = phrases == null
                    ? List.of() : phrases.getStringList(category.key());
            lists.put(category, list); // úplnost vynutí konstruktor PhraseBank
        }
        java.util.Map<String, Pattern> patterns = new java.util.LinkedHashMap<>();
        for (String key : PhraseBank.PATTERN_KEYS) {
            patterns.put(key, requirePattern(root, key));
        }
        return new PhraseBank(lists, patterns);
    }

    /** Překryje fallback banku hodnotami definovanými v {@code root}. */
    private static PhraseBank overlay(ConfigurationSection root, PhraseBank fallback,
                                      String sourceName) {
        ConfigurationSection phrases = root.getConfigurationSection("phrases");
        EnumMap<PhraseCategory, List<String>> lists = new EnumMap<>(PhraseCategory.class);
        for (PhraseCategory category : PhraseCategory.values()) {
            List<String> list = phrases == null
                    ? List.of() : phrases.getStringList(category.key());
            lists.put(category, list.isEmpty() ? fallback.list(category) : list);
        }
        java.util.Map<String, Pattern> patterns = new java.util.LinkedHashMap<>();
        for (String key : PhraseBank.PATTERN_KEYS) {
            patterns.put(key, overlayPattern(root, key, fallback.pattern(key), sourceName));
        }
        return new PhraseBank(lists, patterns);
    }

    /** Vzor z vrstvy, při absenci/chybě vzor z fallbacku. */
    private static Pattern overlayPattern(ConfigurationSection root, String key,
                                          Pattern fallback, String sourceName) {
        String raw = root.getString("patterns." + key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Pattern.compile(raw, PhraseBank.PATTERN_FLAGS);
        } catch (PatternSyntaxException e) {
            LOG.warn("Neplatný vzor patterns.{} v {}: {} – používám předchozí vrstvu.",
                    key, sourceName, e.getDescription());
            return fallback;
        }
    }

    /** Povinný vzor vestavěné vrstvy. */
    private static Pattern requirePattern(ConfigurationSection root, String key) {
        String raw = root.getString("patterns." + key);
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Vestavěný lang/cs.yml nemá patterns." + key);
        }
        return Pattern.compile(raw, PhraseBank.PATTERN_FLAGS);
    }

    /** Přibalený jazykový soubor z classpath ({@code null} pokud neexistuje). */
    private static ConfigurationSection readBundled(String code) {
        try (InputStream in = resource(code)) {
            if (in == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOG.warn("Čtení přibaleného lang/{}.yml selhalo: {}", code, e.toString());
            return null;
        }
    }

    /** Uživatelský soubor z disku ({@code null} + varování při chybě parsování). */
    private static ConfigurationSection readFile(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            return yaml;
        } catch (Exception e) {
            LOG.warn("Jazykový soubor {} nejde načíst ({}) – přeskočen.",
                    file, e.getMessage());
            return null;
        }
    }

    private static InputStream resource(String code) {
        return PhraseBankLoader.class.getClassLoader()
                .getResourceAsStream("lang/" + code + ".yml");
    }
}
