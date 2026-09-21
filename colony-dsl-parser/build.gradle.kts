plugins {
    kotlin("jvm") version "2.2.21"
    antlr
}

repositories { mavenCentral() }

dependencies {
    antlr("org.antlr:antlr4:4.13.2")
    implementation("org.antlr:antlr4-runtime:4.13.2")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(17) }

tasks.generateGrammarSource {
    arguments = arguments + listOf("-visitor", "-no-listener", "-package", "colony.parser")
}

tasks.compileKotlin { dependsOn(tasks.generateGrammarSource) }
tasks.compileTestKotlin { dependsOn(tasks.generateTestGrammarSource) }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.test { useJUnitPlatform() }
