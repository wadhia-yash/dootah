package dev.dootah.gradle

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations

/**
 * Generates ordinary Kotlin native adapters (and the one index class the SDK links against) into a
 * generated source directory that the application's normal Kotlin/Compose compilation consumes.
 * The parser runs in a separate JVM; nothing is loaded into the build's Kotlin plugin classloader.
 */
@CacheableTask
abstract class DootahGenerateAdapters extends DefaultTask {
    static final String MAIN = 'dev.dootah.adapters.AdapterGenMain'

    @InputFiles @PathSensitive(PathSensitivity.RELATIVE) abstract ConfigurableFileCollection getSources()
    @Classpath abstract ConfigurableFileCollection getGeneratorClasspath()
    @Input abstract Property<Boolean> getEnabledAdapters()
    @Internal abstract DirectoryProperty getModuleDirectory()
    @OutputDirectory abstract DirectoryProperty getOutputDirectory()
    @OutputFile abstract RegularFileProperty getReport()
    @Inject abstract ExecOperations getExecOperations()

    @TaskAction void generate() {
        def out = outputDirectory.get().asFile
        out.deleteDir()
        out.mkdirs()
        def list = new File(temporaryDir, 'sources.txt')
        list.setText(sources.files.findAll { it.isFile() && it.name.endsWith('.kt') }*.absolutePath.sort().join('\n'), 'UTF-8')
        execOperations.javaexec {
            it.classpath = generatorClasspath
            it.mainClass.set(MAIN)
            it.args(out.absolutePath, moduleDirectory.get().asFile.absolutePath, list.absolutePath,
                report.get().asFile.absolutePath, enabledAdapters.get().toString())
        }
    }
}
