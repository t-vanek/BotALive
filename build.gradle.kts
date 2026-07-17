/*
 * BotAlive – kořenový build skript.
 *
 * Společná konfigurace pro všechny moduly: Java 25 toolchain (vyžadováno Paper API 26.1),
 * repozitáře a kvalita kompilace (parametry, UTF-8, varování).
 */
plugins {
    java
}

allprojects {
    group = "dev.botalive"
    version = "1.0.0"
}

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
        maven("https://repo.opencollab.dev/maven-releases/") {
            name = "opencollab-releases"
        }
        maven("https://repo.opencollab.dev/maven-snapshots/") {
            name = "opencollab-snapshots"
        }
        maven("https://jitpack.io") {
            name = "jitpack"
            // Jen VaultAPI – ať JitPack nestíní ostatní závislosti.
            content { includeGroup("com.github.MilkBowl") }
        }
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            // Minecraft 26.1 / Paper API běží na Javě 25.
            languageVersion.set(JavaLanguageVersion.of(25))
        }
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
        // -parameters kvůli čitelným názvům parametrů v reflexi a debuggeru.
        options.compilerArgs.addAll(listOf("-parameters"))
    }

    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
