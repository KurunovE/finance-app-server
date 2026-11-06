import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    java
    jacoco
    id("org.springframework.boot") version "3.5.16"
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
}

group = "com.financetracker"
version = "0.1.0-SNAPSHOT"

repositories { mavenCentral() }

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
kotlin { jvmToolchain(21) }

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.4.21")
    runtimeOnly("org.postgresql:postgresql")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    testAnnotationProcessor("org.projectlombok:lombok")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.mockito:mockito-junit-jupiter")

    // Boot 3.5 / Spring 6.2 support the requested JUnit 5 and standard test slices.
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    testImplementation(enforcedPlatform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.withType<Test>().configureEach {
    jvmArgs("-Xshare:off")
}

// Same test source set, explicit suites for fast checks and Docker-backed checks.
tasks.register<Test>("unitTest") {
    description = "Runs pure unit tests without Docker."
    group = "verification"
    dependsOn(tasks.testClasses)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    include("com/financetracker/unit/**")
}
tasks.register<Test>("integrationTest") {
    description = "Runs PostgreSQL, SMTP, HTTP and migration integration tests."
    group = "verification"
    dependsOn(tasks.testClasses)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    include("com/financetracker/integration/**")
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

// Domain gates span layers after the package refactor; every class remains in the global gate.
val coverageDomains = mapOf(
    "auth" to listOf(
        "controller/auth/**", "service/auth/**", "service/mail/**", "repository/auth/**",
        "model/dto/auth/**", "model/entity/auth/**", "config/Auth*", "config/AccountMail*",
        "config/properties/**", "config/TransactionConfiguration*", "exception/AuthFailure*"
    ),
    "security" to listOf("security/**", "config/HttpSecurityConfiguration*", "config/guard/**", "controller/advice/**", "config/MigrationConfiguration*"),
    "sync" to listOf(
        "controller/sync/**", "service/sync/**", "repository/sync/**", "model/dto/sync/**",
        "model/entity/sync/**", "config/RecoveryBootstrap*", "config/MigrationConfiguration*", "config/TransactionConfiguration*", "model/enums/**", "support/json/**"
    ),
    "finance" to listOf(
        "service/finance/**", "repository/finance/**", "model/entity/finance/**",
        "model/dto/finance/**", "mapper/finance/**", "model/enums/**", "exception/DomainRejection*"
    )
)
val domainCoverage = coverageDomains.map { (domain, patterns) ->
    tasks.register<JacocoCoverageVerification>("verify${domain.replaceFirstChar { it.uppercase() }}Coverage") {
        dependsOn(tasks.test)
        executionData(tasks.test.get())
        classDirectories.setFrom(sourceSets.main.get().output.classesDirs.asFileTree.matching {
            patterns.forEach {
                include("com/financetracker/" + if (it.endsWith("/**")) {
                    "$it/*.class"
                } else {
                    "$it.class"
                })
            }
        })
        sourceDirectories.setFrom(sourceSets.main.get().allSource.srcDirs)
        doFirst { check(!classDirectories.isEmpty) { "Coverage domain $domain must contain production classes" } }
        violationRules {
            rule {
                element = "BUNDLE"
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = "0.80".toBigDecimal()
                }
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestReport, tasks.jacocoTestCoverageVerification)
    dependsOn(domainCoverage)
}

tasks.wrapper {
    gradleVersion = "8.14.4"
    distributionType = Wrapper.DistributionType.BIN
}


// Publish the reviewed contract without maintaining a second copy.
tasks.processResources {
    from("docs/openapi.yaml") {
        into("static/api-docs")
    }
}
