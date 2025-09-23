plugins {
    `maven-publish`
    signing
}

publishing {
    // Configure all publications
    publications.withType<MavenPublication> {
        // Stub javadoc.jar artifact
        artifact(tasks.register("${name}JavadocJar", Jar::class) {
            archiveClassifier.set("javadoc")
            archiveAppendix.set(this@withType.name)
        })

        // Provide artifacts information required by Maven Central
        pom {
            name.set("Kadb")
            description.set("A modern and versatile Kotlin Multiplatform ADB client library that simplifies the interaction with Android devices.")
            url.set("https://github.com/flyfishxu/Kadb.git")

            licenses {
                license {
                    name.set("Apache License 2.0")
                    url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("flyfishxu")
                    name.set("Flyfish Xu")
                    organization.set("Flyfish Studio")
                    email.set("flyfishxu@outlook.com")
                }
            }
            scm {
                connection.set("scm:git:git://github.com/flyfishxu/Kadb.git")
                developerConnection.set("scm:git:ssh://github.com/flyfishxu/Kadb.git")
                url.set("https://github.com/flyfishxu/Kadb.git")
            }
        }
    }

    repositories {
        maven {
            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2")
            credentials {
                username = project.findProperty("sonatypeUsername") as String? ?: ""
                password = project.findProperty("sonatypePassword") as String? ?: ""
            }
        }
        mavenLocal()
    }
}

signing {
    // useGpgCmd()
    // sign(publishing.publications)
}