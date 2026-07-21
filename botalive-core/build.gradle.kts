/*
 * botalive-core – implementace pluginu BotAlive.
 *
 * Výsledkem je "fat jar" (shadowJar) s relokovanými závislostmi, aby nedocházelo
 * ke konfliktům s knihovnami, které bundluje samotný Paper server (Netty, Gson,
 * Adventure, SLF4J) nebo jiné pluginy.
 *
 * Relokační strategie:
 *  - MCProtocolLib + jeho tranzitivní závislosti (Netty 4.2, cloudburst math/nbt,
 *    fastutil, MinecraftAuth) -> dev.botalive.libs.* ; server má vlastní verze
 *    těchto knihoven a střet verzí by způsobil nedefinované chování.
 *  - HikariCP a Caffeine -> dev.botalive.libs.* ; běžně je bundlují i jiné pluginy.
 *  - sqlite-jdbc a postgresql se NErelokují: sqlite-jdbc načítá nativní knihovny
 *    podle cesty balíčku a relokace by ji rozbila; JDBC ovladače jsou navíc
 *    registrované přes java.sql.DriverManager, kde kolize nehrozí.
 *  - Gson, Adventure a SLF4J se nebundlují vůbec – poskytuje je server.
 */
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

dependencies {
    api(project(":botalive-api"))

    compileOnly(libs.paper.api)
    // Vault (ekonomika) je volitelná runtime závislost (softdepend) – třídy
    // dodává plugin Vault, my proti API jen kompilujeme.
    compileOnly(libs.vaultapi) {
        exclude(group = "org.bukkit", module = "bukkit")
    }

    implementation(libs.mcprotocollib) {
        // Server poskytuje vlastní (novější) Adventure a Gson – nebundlovat.
        // Výjimka: adventure-text-serializer-json-legacy-impl (NBTLegacyHoverEventSerializer)
        // Paper 26.1 už nedodává (má jen interní io.papermc.paper.adventure.providers verzi),
        // ale MCProtocolLib DefaultComponentSerializer ho staticky vyžaduje → musí se bundlovat.
        exclude(group = "net.kyori", module = "adventure-text-serializer-gson")
        exclude(group = "com.google.code.gson", module = "gson")
        exclude(group = "org.slf4j", module = "slf4j-api")
        exclude(group = "org.checkerframework", module = "checker-qual")
    }
    implementation(libs.hikaricp) {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation(libs.caffeine) {
        exclude(group = "org.checkerframework", module = "checker-qual")
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }
    // Plný fastutil: pathfinder (AStarPathfinder) používá Long2ObjectOpenHashMap,
    // který rozsekaný com.nukkitx.fastutil od MCProtocolLib neobsahuje. Bez tohoto
    // se relokovaná třída v jaru nevyskytuje → NoClassDefFoundError za běhu.
    implementation(libs.fastutil)
    implementation(libs.sqlite.jdbc)
    implementation(libs.postgresql)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Testy potřebují Paper typy (Material, BlockData) na classpath.
    testImplementation(libs.paper.api)
    testImplementation(libs.vaultapi) {
        exclude(group = "org.bukkit", module = "bukkit")
    }
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("BotAlive")
    archiveClassifier.set("")

    // Relokace do interního namespace pluginu.
    val libs = "dev.botalive.libs"
    relocate("org.geysermc.mcprotocollib", "$libs.mcprotocollib")
    relocate("io.netty", "$libs.netty")
    relocate("org.cloudburstmc", "$libs.cloudburstmc")
    relocate("it.unimi.dsi.fastutil", "$libs.fastutil")
    relocate("net.raphimc", "$libs.raphimc")
    relocate("net.lenni0451", "$libs.lenni0451")
    relocate("com.zaxxer.hikari", "$libs.hikari")
    relocate("com.github.benmanes.caffeine", "$libs.caffeine")

    mergeServiceFiles()

    // Metadata a nativní knihovny Netty, které po relokaci nemají smysl –
    // relokované Netty nativní transporty nenajde a korektně spadne na NIO.
    exclude("META-INF/maven/**")
    exclude("META-INF/proguard/**")
    exclude("META-INF/native/**")
    exclude("META-INF/native-image/**")

    // Minimalizace je záměrně VYPNUTÁ. Bundlované knihovny (MCProtocolLib, Netty,
    // Caffeine, fastutil, cloudburstmc, MinecraftAuth, HikariCP…) načítají značnou
    // část tříd reflexí, přes lambda metafactory nebo Class.forName() podle jména,
    // případně přes hustě provázané specializované kolekce. Statická analýza shadow
    // minimize tyto vazby nevidí a třídy odstraní → NoClassDefFoundError/
    // ClassNotFoundException až za běhu (Adventure NBTLegacyHoverEventSerializer,
    // Caffeine cache varianty typu SSMSW, fastutil Long2ObjectOpenHashMap…).
    // Ušetřené místo (~20 MB) nestojí za tuto křehkost; korektnost má přednost.
}

// plugin.yml drží 'api-version' (major.minor Minecraftu) a 'version' pluginu. Obojí
// plníme při buildu z jediného zdroje – version catalogu (paperApiVersion) a Gradle
// verze projektu – ať upgrade Minecraftu znamená změnu na jednom místě, ne editaci
// resource souboru. Filtrujeme jen plugin.yml; ostatní resources (config.yml, lang/*)
// se nesmí procházet Groovy šablonou (obsahují '$'). Viz docs/UPGRADING.md.
tasks.processResources {
    filteringCharset = "UTF-8"
    val tokens = mapOf(
        "version" to project.version.toString(),
        "apiVersion" to libs.versions.paperApiVersion.get(),
    )
    inputs.properties(tokens)
    filesMatching("plugin.yml") {
        expand(tokens)
    }
}

tasks.named("build") {
    dependsOn("shadowJar")
}
