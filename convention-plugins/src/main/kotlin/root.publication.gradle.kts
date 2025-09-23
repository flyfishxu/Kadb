plugins {
    id("io.github.gradle-nexus.publish-plugin")
}

allprojects {
    group = "com.flyfishxu"
    version = "1.3.1"
}

nexusPublishing {
    // Configure maven central repository
    repositories {
        sonatype {
            nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
            snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))
        }
    }
}
