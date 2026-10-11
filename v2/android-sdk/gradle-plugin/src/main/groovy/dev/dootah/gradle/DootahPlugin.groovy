package dev.dootah.gradle

import groovy.json.JsonOutput
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.GradleException
import java.security.cert.CertificateFactory
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact

class DootahExtension {
    String appId
    String updateUrl
    // Optional local endpoint for debuggable variants only; release keeps updateUrl.
    String debugUpdateUrl
    String channel = 'development'
    String runtimeVersion
    File publicCertificate
    String keyId = 'main'
    boolean allowLocalHttp = false
    boolean composeDispatch = true
    // Acceptance/fixture builds: fail instead of warn when a variant has no OTA entry points.
    boolean requireHooks = false
    // Build-generated adapters for app-owned composables called from hookable functions. Disabling
    // still generates the empty index the SDK links against.
    boolean nativeAdapters = true
}

class DootahPlugin implements Plugin<Project> {
    static final String SDK_VERSION = DootahPlugin.getResource('sdk-version.txt').getText('UTF-8').trim()
    // Parser used for adapter generation; the same version as the publisher's analyzer.
    static final String PARSER = 'org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.20'

    void apply(Project project) {
        def config = project.extensions.create('dootah', DootahExtension)
        project.pluginManager.withPlugin('com.android.application') {
            def android = project.extensions.getByName('android')
            project.dependencies.add('implementation', "dev.dootah:runtime-v2:$SDK_VERSION")
            android.packaging.jniLibs.useLegacyPackaging = false
            // Same shared-runtime collision policy as the pinned RN Gradle plugin.
            android.packaging.jniLibs.pickFirsts.addAll(['**/libc++_shared.so', '**/libfbjni.so'])
            android.defaultConfig.ndk.abiFilters.add('arm64-v8a')
            def parser = project.configurations.create('dootahAdapterParser') {
                canBeConsumed = false
                canBeResolved = true
                visible = false
                attributes {
                    attribute(org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE, project.objects.named(org.gradle.api.attributes.Usage, org.gradle.api.attributes.Usage.JAVA_RUNTIME))
                    attribute(org.gradle.api.attributes.Category.CATEGORY_ATTRIBUTE, project.objects.named(org.gradle.api.attributes.Category, org.gradle.api.attributes.Category.LIBRARY))
                    attribute(org.gradle.api.attributes.java.TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                        project.objects.named(org.gradle.api.attributes.java.TargetJvmEnvironment, org.gradle.api.attributes.java.TargetJvmEnvironment.STANDARD_JVM))
                }
            }
            project.dependencies.add('dootahAdapterParser', PARSER)
            def components = project.extensions.getByName('androidComponents')
            components.finalizeDsl {
                validate(config)
                if (android.defaultConfig.ndk.abiFilters.any { it != 'arm64-v8a' }) {
                    throw new GradleException('This Dootah SDK build supports arm64-v8a only')
                }
                def headers = JsonOutput.toJson(['expo-app-id': config.appId, 'expo-channel-name': config.channel])
                def certificate = config.publicCertificate.text
                android.defaultConfig.manifestPlaceholders.putAll([
                    dootah_update_url: config.updateUrl,
                    dootah_runtime_version: config.runtimeVersion,
                    dootah_request_headers: headers,
                    dootah_certificate: certificate,
                    dootah_signing_metadata: JsonOutput.toJson([keyid: config.keyId, alg: 'rsa-v1_5-sha256'])
                ])
            }
            components.onVariants(components.selector().all()) { variant ->
                String variantUrl = variant.debuggable && config.debugUpdateUrl ? config.debugUpdateUrl : config.updateUrl
                variant.manifestPlaceholders.put('dootah_update_url', variantUrl)
                // ABI splits bypass the NDK filter; never emit non-arm64 APKs lacking Dootah native libraries.
                requireArm64Outputs(variant.outputs.collectMany { it.filters.findAll { f -> f.filterType.name() == 'ABI' }*.identifier })
                if (config.composeDispatch) {
                    variant.instrumentation.transformClassesWith(ComposeEntryFactory, InstrumentationScope.PROJECT) {}
                    variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS)
                    registerHookReport(project, variant, config)
                }
                def embeddedIdentity = JsonOutput.toJson([config.appId, variantUrl, config.channel, config.runtimeVersion,
                    config.publicCertificate.text, config.keyId, variant.applicationId.get(),
                    android.defaultConfig.versionCode, SDK_VERSION])
                def task = project.tasks.register("generate${variant.name.capitalize()}DootahAssets", DootahAssets) {
                    identity.set(embeddedIdentity)
                    localHttp.set(variantUrl.startsWith('http:'))
                    debuggable.set(variant.debuggable)
                    outputDirectory.set(project.layout.buildDirectory.dir("generated/dootah/${variant.name}/assets"))
                }
                variant.sources.assets.addGeneratedSourceDirectory(task) { it.outputDirectory }
                def adapters = registerAdapters(project, variant, config, parser)
                registerRetention(project, variant, config, adapters)
            }
        }
    }

    /** The generator's own code location: a jar when published, a classes directory in tests. */
    static File generatorLocation() {
        new File(dev.dootah.identity.FunctionIdentity.protectionDomain.codeSource.location.toURI())
    }

    static File adapterDirectory(Project project, variant) {
        project.layout.buildDirectory.dir("generated/dootah/${variant.name}/adapters").get().asFile
    }

    static registerAdapters(Project project, variant, DootahExtension config, parser) {
        def task = project.tasks.register("generate${variant.name.capitalize()}DootahAdapters", DootahGenerateAdapters) {
            // Static roots only: generated roots (including this task's output) are never re-read.
            [variant.sources.java, variant.sources.kotlin].findAll { it != null }.each { dirs ->
                sources.from(dirs.static.map { roots -> roots.collect { dir -> dir.asFileTree.matching { include '**/*.kt' } } })
            }
            generatorClasspath.from(generatorLocation(), parser)
            enabledAdapters.set(project.providers.provider { config.nativeAdapters })
            moduleDirectory.set(project.layout.projectDirectory)
            outputDirectory.set(adapterDirectory(project, variant))
            report.set(project.layout.buildDirectory.file("outputs/dootah/${variant.name}/adapters.json"))
        }
        // A generated *java* root: the Kotlin Android plugin compiles .kt files found there, while
        // KGP 2.1 does not consume AGP's generated `sources.kotlin` roots.
        variant.sources.java.addGeneratedSourceDirectory(task) { it.outputDirectory }
        task
    }

    static void registerRetention(Project project, variant, DootahExtension config, adapters = null) {
        def kotlin = project.extensions.findByName('kotlin')
        def kotlinPlugin = project.plugins.findPlugin('org.jetbrains.kotlin.android')
        String kotlinVersion = kotlinPlugin?.hasProperty('pluginVersion') ? kotlinPlugin.pluginVersion :
            (kotlin?.hasProperty('coreLibrariesVersion') ? kotlin.coreLibrariesVersion : 'unknown')
        def agp = project.plugins.findPlugin('com.android.application').class.classLoader
            .loadClass('com.android.Version').getField('ANDROID_GRADLE_PLUGIN_VERSION').get(null).toString()
        def modulePath = project.layout.projectDirectory.asFile.toPath()
        // Generated adapters are derived from the retained sources; they are recorded as classes, not sources.
        def generated = adapterDirectory(project, variant).toPath()
        def task = project.tasks.register("dootahRetain${variant.name.capitalize()}Release", DootahRetainRelease) {
            apkDirectory.set(variant.artifacts.get(SingleArtifact.APK.INSTANCE))
            loader.set(variant.artifacts.getBuiltArtifactsLoader())
            sourceRoot.set(project.layout.projectDirectory)
            // Both Java and Kotlin source roots may contain Kotlin; the file collection deduplicates them.
            [variant.sources.java, variant.sources.kotlin].findAll { it != null }.each { dirs ->
                def roots = dirs.all.map { all -> all.findAll { !it.asFile.toPath().startsWith(generated) } }
                sourceFiles.from(roots.map { all -> all.collect { dir ->
                    dir.asFileTree.matching { include '**/*.kt' }
                } })
                sourceRoots.addAll(roots.map { all -> all.collect { dir -> modulePath.relativize(dir.asFile.toPath()).toString() } })
            }
            metadata.set([variant: variant.name, applicationId: variant.applicationId.get(), appId: config.appId,
                channel: config.channel, runtimeVersion: config.runtimeVersion, sdkVersion: SDK_VERSION,
                kotlinVersion: kotlinVersion, agpVersion: agp, gradleVersion: project.gradle.gradleVersion])
            outputDirectory.set(project.layout.buildDirectory.dir("outputs/dootah/${variant.name}/retained"))
        }
        variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT).use(task)
            .toGet(ScopedArtifact.CLASSES.INSTANCE, { it.classJars }, { it.classDirectories })
    }

    // Normal builds warn on zero entry points; requireHooks makes it a build failure.
    static void registerHookReport(Project project, variant, DootahExtension config) {
        def name = variant.name.capitalize()
        def report = project.tasks.register("report${name}DootahHooks", DootahHookReport) {
            variantName.set(variant.name)
            requireHooks.set(config.requireHooks) // DSL is final in onVariants
            it.report.set(project.layout.buildDirectory.file("outputs/dootah/${variant.name}/hooks.json"))
        }
        variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT).use(report)
            .toGet(ScopedArtifact.CLASSES.INSTANCE, { it.classJars }, { it.classDirectories })
        def consumers = ['assemble', 'bundle', 'install'].collect { it + name } as Set<String>
        project.tasks.matching { it.name in consumers }.configureEach { it.dependsOn(report) }
    }

    static void requireArm64Outputs(Collection<String> abiOutputs) {
        if (abiOutputs.any { it != 'arm64-v8a' }) {
            throw new GradleException("This Dootah SDK build supports arm64-v8a only; ABI split outputs ${abiOutputs.unique()} are unsupported")
        }
    }

    static void validate(DootahExtension config) {
        for (field in ['appId', 'updateUrl', 'channel', 'runtimeVersion', 'keyId']) {
            if (!config."$field"?.trim()) throw new GradleException("dootah.$field is required")
        }
        UUID.fromString(config.appId)
        if (config.debugUpdateUrl) validateUrl(config.debugUpdateUrl, config.allowLocalHttp)
        validateUrl(config.updateUrl, config.allowLocalHttp)
        if (!config.publicCertificate?.isFile()) throw new GradleException('dootah.publicCertificate is required')
        def pem = config.publicCertificate.text
        if (pem.contains('PRIVATE KEY') || !pem.contains('BEGIN CERTIFICATE')) throw new GradleException('Only a public X.509 certificate is allowed')
        def cert = CertificateFactory.getInstance('X.509').generateCertificate(new ByteArrayInputStream(pem.bytes))
        if (cert.publicKey.algorithm != 'RSA') throw new GradleException('Dootah requires an RSA signing certificate')
    }

    static void validateUrl(String value, boolean local) {
        def uri = new URI(value)
        if (uri.scheme != 'https' && !(local && uri.scheme == 'http' && uri.host in ['127.0.0.1', 'localhost', '10.0.2.2'])) {
            throw new GradleException('Dootah requires HTTPS; allowLocalHttp permits only local test origins')
        }
        if (!uri.host || uri.userInfo || uri.fragment) throw new GradleException('Invalid Dootah update URL')
    }
}
