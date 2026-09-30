plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

extra["httpclient5.version"] = "5.4.4"
extra["httpcore5.version"] = "5.3.4"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.xerial:sqlite-jdbc:3.44.0.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.xerial:sqlite-jdbc:3.44.0.0")
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

tasks.test {
    useJUnitPlatform()
}
