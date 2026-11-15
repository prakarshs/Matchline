plugins {
    java
    application
}

group = "dev.matchline"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "dev.matchline.bootstrap.MatchlineApplication"
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}


// JMH is opt-in: regular `test` / `build` do not compile or execute benchmarks.
// An isolated source set avoids overhead/dependencies in the core and HTTP server.
val jmh by sourceSets.creating {
    java.srcDir("src/jmh/java")
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}

configurations[jmh.implementationConfigurationName].extendsFrom(configurations["implementation"])

dependencies {
    add(jmh.implementationConfigurationName, "org.openjdk.jmh:jmh-core:1.37")
    add(jmh.annotationProcessorConfigurationName, "org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Run isolated JMH benchmarks; pass JMH args via --args='...'."
    dependsOn(tasks.named(jmh.classesTaskName))
    mainClass.set("org.openjdk.jmh.Main")
    classpath = jmh.runtimeClasspath
    jvmArgs("-Dfile.encoding=UTF-8")
}
