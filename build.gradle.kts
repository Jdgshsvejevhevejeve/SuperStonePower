plugins {
    java
}

group = "me.bogeyman.stonepowers"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

val resourcePackZip = tasks.register<Zip>("resourcePack") {
    archiveFileName.set("StonePowers-ResourcePack.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from("resourcepack")
}

tasks.named("assemble") {
    dependsOn(resourcePackZip)
}
