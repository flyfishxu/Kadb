import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import javax.inject.Inject
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

@CacheableTask
abstract class DownloadVerifiedArchive : DefaultTask() {
    @get:Input abstract val sourceUrl: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val archiveFile: RegularFileProperty

    private fun checksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    @TaskAction fun download() {
        val archive = archiveFile.get().asFile
        archive.parentFile.mkdirs()
        val lockFile = archive.resolveSibling("${archive.name}.lock").toPath()
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                if (archive.isFile && checksum(archive) == sha256.get()) return
                val temporary = archive.resolveSibling("${archive.name}.download")
                val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(30)).build()
                val request = HttpRequest.newBuilder(URI(sourceUrl.get()))
                    .timeout(Duration.ofMinutes(5)).GET().build()
                try {
                    logger.lifecycle("Downloading verified native dependency: ${archive.name}")
                    val response = client.send(request, HttpResponse.BodyHandlers.ofFile(temporary.toPath(),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))
                    check(response.statusCode() == 200) { "Download failed with HTTP ${response.statusCode()}: ${sourceUrl.get()}" }
                    check(checksum(temporary) == sha256.get()) { "SHA-256 mismatch for ${archive.name}" }
                    Files.move(temporary.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    temporary.delete()
                }
            }
        }
    }
}

@DisableCachingByDefault(because = "Expands a locally cached, verified upstream archive")
abstract class ExtractNativeArchive : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val archiveFile: RegularFileProperty
    @get:OutputDirectory abstract val destinationDirectory: DirectoryProperty
    @get:Inject abstract val execOperations: ExecOperations

    @TaskAction fun extract() {
        val destination = destinationDirectory.get().asFile
        destination.deleteRecursively()
        destination.mkdirs()
        // macOS tar preserves the CMake bundle's executable permissions and symbolic links.
        execOperations.exec {
            commandLine("/usr/bin/tar", "-xzf", archiveFile.get().asFile,
                "-C", destination, "--strip-components=1")
        }.assertNormalExitValue()
    }
}

@DisableCachingByDefault(because = "CMake builds incrementally using the local Xcode SDK/toolchain")
abstract class BuildIosNative : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bridgeDirectory: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val boringSslDirectory: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val cmakeExecutable: RegularFileProperty
    @get:Input abstract val sdk: Property<String>
    @get:Input abstract val deploymentTarget: Property<String>
    @get:Input abstract val architecture: Property<String>
    @get:Input abstract val configuration: Property<String>
    @get:LocalState abstract val cmakeBuildDirectory: DirectoryProperty
    @get:OutputFile abstract val nativeLibrary: RegularFileProperty
    @get:OutputFile abstract val sslLibrary: RegularFileProperty
    @get:OutputFile abstract val cryptoLibrary: RegularFileProperty
    @get:Inject abstract val execOperations: ExecOperations

    @TaskAction fun build() {
        check(System.getProperty("os.name").startsWith("Mac")) { "The iOS backend requires macOS and Xcode" }
        val cmake = cmakeExecutable.get().asFile
        check(cmake.canExecute()) { "CMake is not executable: $cmake" }
        val buildDirectory = cmakeBuildDirectory.get().asFile
        execOperations.exec {
            commandLine(cmake, "-S", bridgeDirectory.get().asFile, "-B", buildDirectory,
                "-G", "Unix Makefiles", "-DBORINGSSL_SOURCE=${boringSslDirectory.get().asFile}",
                "-DCMAKE_SYSTEM_NAME=iOS", "-DCMAKE_OSX_SYSROOT=${sdk.get()}",
                "-DCMAKE_OSX_ARCHITECTURES=${architecture.get()}",
                "-DCMAKE_OSX_DEPLOYMENT_TARGET=${deploymentTarget.get()}",
                "-DCMAKE_BUILD_TYPE=${configuration.get()}", "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
                "-DCMAKE_MACOSX_BUNDLE=OFF")
        }.assertNormalExitValue()
        execOperations.exec {
            commandLine(cmake, "--build", buildDirectory, "--target", "kadb_native", "--parallel",
                Runtime.getRuntime().availableProcessors().coerceAtMost(8).toString())
        }.assertNormalExitValue()
        listOf(nativeLibrary, sslLibrary, cryptoLibrary).forEach { output ->
            val destination = output.get().asFile
            destination.parentFile.mkdirs()
            Files.copy(buildDirectory.resolve("lib/${destination.name}").toPath(), destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

val boringSslRevision = "5b83e5d1abf0ae0033ae52ab579d906b67633b2c"
val cmakeVersion = "3.31.8"
val nativeRoot = layout.buildDirectory.dir("native")
val downloadBoringSsl = tasks.register<DownloadVerifiedArchive>("downloadIosBoringSsl") {
    sourceUrl.set("https://codeload.github.com/google/boringssl/tar.gz/$boringSslRevision")
    sha256.set("4117ae02de040d4469284673ed63a14e648af7c3974aa9e85bcef2975a9c9ea8")
    archiveFile.set(nativeRoot.map { it.file("downloads/boringssl.tar.gz") })
}
val prepareBoringSsl = tasks.register<ExtractNativeArchive>("prepareIosBoringSsl") {
    archiveFile.set(downloadBoringSsl.flatMap { it.archiveFile })
    destinationDirectory.set(nativeRoot.map { it.dir("boringssl-$boringSslRevision") })
}

val cmakeOverride = providers.environmentVariable("CMAKE").orNull?.trim()?.takeIf { it.isNotEmpty() }
val pathDirectories = providers.environmentVariable("PATH").getOrElse("").split(File.pathSeparator)
val externalCmake = when {
    cmakeOverride != null && (cmakeOverride.contains('/') || cmakeOverride.contains('\\')) -> file(cmakeOverride)
    else -> pathDirectories.asSequence().map { File(it, cmakeOverride ?: "cmake") }
        .firstOrNull { it.canExecute() && it.isFile } ?: cmakeOverride?.let { file(it) }
}
val cmakeBinary = if (externalCmake != null) {
    layout.file(provider { externalCmake })
} else {
    val downloadCmake = tasks.register<DownloadVerifiedArchive>("downloadIosCmake") {
        sourceUrl.set("https://github.com/Kitware/CMake/releases/download/v$cmakeVersion/cmake-$cmakeVersion-macos-universal.tar.gz")
        sha256.set("d1449f969c54d5c00886d5b643340d493dfb3c81cb39ee29b35453395c11ebf7")
        archiveFile.set(nativeRoot.map { it.file("downloads/cmake.tar.gz") })
    }
    val prepareCmake = tasks.register<ExtractNativeArchive>("prepareIosCmake") {
        archiveFile.set(downloadCmake.flatMap { it.archiveFile })
        destinationDirectory.set(nativeRoot.map { it.dir("cmake-$cmakeVersion-macos-universal") })
    }
    prepareCmake.flatMap { it.destinationDirectory.file("CMake.app/Contents/bin/cmake") }
}

mapOf("iosArm64" to "iphoneos", "iosSimulatorArm64" to "iphonesimulator").forEach { (target, sdkName) ->
    tasks.register<BuildIosNative>("buildNative${target.replaceFirstChar(Char::uppercaseChar)}") {
        group = "build"
        description = "Build the pinned native ADB backend for $target"
        bridgeDirectory.set(layout.projectDirectory.dir("src/nativeInterop"))
        boringSslDirectory.set(prepareBoringSsl.flatMap { it.destinationDirectory })
        cmakeExecutable.set(cmakeBinary)
        sdk.set(sdkName)
        deploymentTarget.set("15.0")
        architecture.set("arm64")
        configuration.set("Release")
        cmakeBuildDirectory.set(nativeRoot.map { it.dir("$target/cmake") })
        nativeLibrary.set(nativeRoot.map { it.file("$target/libkadb_native.a") })
        sslLibrary.set(nativeRoot.map { it.file("$target/libssl.a") })
        cryptoLibrary.set(nativeRoot.map { it.file("$target/libcrypto.a") })
    }
}
