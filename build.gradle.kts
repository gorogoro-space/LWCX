plugins {
    id("java-library")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://repo.glaremasters.me/repository/towny/")
    maven("https://ci.ender.zone/plugin/repository/everything/")
    maven("https://repo.glaremasters.me/repository/public/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.sk89q.worldedit:worldedit-core:7.1.0") {
        isTransitive = false
    }
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.1.0") {
        isTransitive = false
    }
    compileOnly("com.sk89q.worldguard:worldguard-core:7.0.0") {
        isTransitive = false
    }
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.0") {
        isTransitive = false
    }
    compileOnly("com.palmergames.bukkit.towny:towny:0.98.2.0")
    compileOnly("com.massivecraft:Factions:1.6.9.5-U0.4.9")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    compileOnly("org.apache.commons:commons-lang3:3.19.0")
    compileOnly("com.googlecode.json-simple:json-simple:1.1.1")
    compileOnly("com.google.guava:guava:33.3.1-jre")
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        // 警告はすべて出す(0 件を保つ)
        options.compilerArgs.add("-Xlint:all")
    }
    processResources {
        val version = project.version.toString()
        inputs.property("version", version)
        filesMatching("plugin.yml") {
            expand("version" to version)
        }
    }
    jar {
        archiveFileName.set("LWC-${project.version}.jar")
        manifest {
            attributes("paperweight-mappings-namespace" to "mojang")
        }
        // LWC のライセンス(BSD 2 条項)は、バイナリの配布にも著作権表示と条文を付けることを求めている
        from(files("LICENSE")) {
            into("META-INF")
        }
    }
}
