plugins {
    id("fabric-loom") version "1.14-SNAPSHOT"
}

// Versions - the same ones the Meteor addon template uses for 1.21.11.
val minecraftVersion = "1.21.11"
val yarnMappings = "1.21.11+build.3"
val loaderVersion = "0.18.2"
val meteorVersion = "1.21.11-SNAPSHOT"

group = "arsonist"
version = "1.6.0"

base {
    archivesName = "auto-spear-v7"
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
}

dependencies {
    // Fabric
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")

    // Meteor
    modImplementation("meteordevelopment:meteor-client:$meteorVersion")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to minecraftVersion
        )

        inputs.properties(propertyMap)

        filteringCharset = "UTF-8"

        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 21
    }
}
