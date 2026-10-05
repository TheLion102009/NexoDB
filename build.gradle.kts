plugins {
    kotlin("jvm") version "2.5.0-Beta1"
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")

    // Nur noetig, wenn du spaeter direkt gegen die Nexo-API kompilieren willst.
    // NexoDB braucht das NICHT - der Nexo-Hook laeuft komplett ueber Reflection,
    // damit das Plugin bei jedem Nexo-Update weiter baut und laedt.
    // maven("https://repo.nexomc.com/releases")
}

// Standard ist die AELTESTE unterstuetzte API: gegen 1.21.4 gebaute Plugins laufen auch
// auf 26.2, umgekehrt nicht. paper-api 26.2 waere Java-25-Bytecode und wuerde auf einem
// 1.21.4-Server (Java 21) gar nicht erst laden.
// Leaf ist ein Paper-Fork mit identischer API - es braucht nichts Zusaetzliches.
//
// Kompatibilitaetscheck gegen das obere Ende der Spanne (erzeugt KEIN Release-Jar,
// sondern prueft nur, ob alle benutzten APIs dort noch existieren):
//   ./gradlew compileKotlin -PpaperApi=26.2.build.+ -PjavaVersion=25
val paperApiVersion = (findProperty("paperApi") as String?) ?: "1.21.4-R0.1-SNAPSHOT"
val javaVersion = (findProperty("javaVersion") as String?)?.toInt() ?: 21

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

    // compileOnly("com.nexomc:nexo:1.28.0")

    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.mongodb:mongodb-driver-sync:5.12.0")
    implementation("com.github.ben-manes.caffeine:caffeine:3.3.0")
}

kotlin {
    // Java 21 = Minimum von Minecraft 1.21.4. Neuere Server (26.x laeuft auf Java 25)
    // fuehren Java-21-Bytecode problemlos aus.
    jvmToolchain(javaVersion)
}

// Basis-Package fuer alle geshadeten Bibliotheken.
val relocationBase = "the.lion.nexoDB.libs"

tasks {
    build {
        dependsOn(shadowJar)
    }

    jar {
        // Das "duenne" Jar enthaelt weder den MongoDB-Treiber noch die Relocations. Landet es
        // in plugins/, scheitert der Start mit NoClassDefFoundError: com/mongodb/... - deshalb
        // wird es gar nicht erst gebaut. "gradlew jar" baut stattdessen das Shadow-Jar, sodass
        // in build/libs immer nur das eine, lauffaehige Jar liegt.
        enabled = false
        dependsOn(shadowJar)

        // Verhindert, dass beide Tasks denselben Dateinamen als Output deklarieren.
        archiveClassifier.set("dev")
    }

    shadowJar {
        // Ergebnis: build/libs/NexoDB-<version>.jar -> direkt in plugins/ kopierbar.
        archiveClassifier.set("")

        // MongoDB nutzt java.util.ServiceLoader (z.B. fuer DNS-Provider).
        mergeServiceFiles()

        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        exclude("META-INF/maven/**")
        exclude("module-info.class")

        // Relocation ist Pflicht: andere Plugins auf dem Server bringen haeufig
        // eigene (aeltere) MongoDB-Treiber oder Kotlin-Versionen mit.
        relocate("com.mongodb", "$relocationBase.mongodb")
        relocate("org.bson", "$relocationBase.bson")
        relocate("com.github.benmanes.caffeine", "$relocationBase.caffeine")
        relocate("org.checkerframework", "$relocationBase.checkerframework")
        relocate("com.google.errorprone", "$relocationBase.errorprone")
        relocate("org.jspecify", "$relocationBase.jspecify")

        // Kotlin-Stdlib relocaten vermeidet Konflikte mit anderen Kotlin-Plugins.
        // Falls du jemals Kotlin-Reflection nutzt, diese Zeile entfernen.
        relocate("kotlin", "$relocationBase.kotlin")
        relocate("org.jetbrains.annotations", "$relocationBase.jbannotations")
        relocate("org.intellij.lang.annotations", "$relocationBase.intellijannotations")
    }

    runServer {
        // Zum Gegentesten auf das andere Ende der Spanne einfach auf "1.21.4" aendern.
        minecraftVersion("26.2")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to version, "description" to project.description)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }
}
