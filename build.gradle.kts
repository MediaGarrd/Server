plugins {
    java
    id("org.springframework.boot") version "4.1.3"
    id("io.spring.dependency-management") version "1.1.7"
}

version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
    implementation("com.github.docker-java:docker-java-core:3.5.1")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:3.5.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
    compileOnly("org.projectlombok:lombok:1.18.48")
	annotationProcessor("org.projectlombok:lombok:1.18.48")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

springBoot {
    buildInfo()
}

tasks.test {
    useJUnitPlatform {
        excludeTags("scale")
    }
}

tasks.register<Test>("scaleTest") {
    group = "verification"
    description = "Builds very large archives under a small heap"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("scale")
    }
    val scratch = layout.buildDirectory.dir("scale-tmp").get().asFile
    maxHeapSize = "64m"
    jvmArgs("-XX:+UseG1GC", "-Xms16m", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=30", "-XX:+ExitOnOutOfMemoryError")
    systemProperty("java.io.tmpdir", scratch.absolutePath)
    doFirst {
        scratch.deleteRecursively()
        scratch.mkdirs()
    }
    doLast {
        scratch.deleteRecursively()
    }
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
    shouldRunAfter(tasks.test)
}
