plugins {
    java
}

group = "ma.darija"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// Build with JDK 25. The plugin's own code targets Java 21 so it can show a clear
// error on old servers, but the Discord voice encryption library (JDAVE) needs
// the SERVER to run on Java 25.
java {
    // Allow Java 25 libraries (JDAVE) even though our own code targets Java 21
    disableAutoTargetJvm()
}

val jdaVersion = "6.5.0"
val jdaveVersion = "0.1.8"

// Everything in "bundle" is packed inside the plugin jar.
val bundle: Configuration by configurations.creating {
    isCanBeResolved = false
    isCanBeConsumed = false
}

dependencies {
    // Provided by the Paper server at runtime
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    // Discord bot library + voice encryption (DAVE)
    bundle("net.dv8tion:JDA:$jdaVersion") {
        exclude(group = "org.slf4j") // Paper already ships SLF4J
    }
    bundle("club.minnced:jdave-api:$jdaveVersion")
    bundle("club.minnced:jdave-native-linux-x86-64:$jdaveVersion")
    bundle("club.minnced:jdave-native-linux-aarch64:$jdaveVersion")
    bundle("club.minnced:jdave-native-win-x86-64:$jdaveVersion")

    // MP3 decoder for the text-to-speech audio
    bundle("javazoom:jlayer:1.0.1")
}

configurations.implementation.get().extendsFrom(bundle)

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

// Build one "fat" jar with all libraries inside, ready to drop into /plugins
tasks.jar {
    archiveFileName.set("DarijaVoiceBot-${project.version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
        exclude("module-info.class", "META-INF/versions/*/module-info.class")
        exclude("META-INF/MANIFEST.MF")
    }
    manifest {
        attributes("Enable-Native-Access" to "ALL-UNNAMED")
    }
}
