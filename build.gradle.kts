plugins {
    kotlin("jvm") version "1.9.23"
    application
}

version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
}

application {
    mainClass.set("MainKt")
}
