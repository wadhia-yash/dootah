package dev.dootah.publish

import javax.xml.parsers.DocumentBuilderFactory
import static dev.dootah.publish.SourceAnalyzer.require

class Main {
    static void main(String[] args) {
        try {
            if (args.length == 1 && args[0] == 'capabilities') { println(dev.dootah.portable.PortableProgram.contract()); return }
            require(args.length == 3, 'Usage: analyzer import|analyze config.json output.json')
            def configFile = new File(args[1])
            def config = Contract.read(configFile)
            def output = new File(args[2])
            def result
            if (args[0] == 'import') {
                def manifest = readManifest(new File(config.apk))
                result = Contract.create(new File(config.apk), new File(config.classes),
                    Contract.read(new File(config.classHashes)), Contract.sources(new File(config.sourceRoot), config.sourcePaths as List), manifest)
            } else if (args[0] == 'cloud-contract') {
                def contract = Contract.read(new File(config.contract))
                Contract.validate(contract, config)
                result = Contract.cloudContract(contract)
            } else {
                require(args[0] == 'analyze', 'Unknown command')
                def contract = Contract.read(new File(config.contract))
                Contract.validate(contract, config)
                result = Contract.analyze(contract, Contract.currentSources(config))
            }
            output.parentFile.mkdirs()
            output.setText(Contract.json(result) + '\n', 'UTF-8')
        } catch (Exception e) {
            System.err.println('Dootah refused: ' + e.message)
            if (System.getenv('DOOTAH_DIAGNOSTICS')) e.printStackTrace()
            System.exit(1)
        }
    }

    static Map readManifest(File apk) {
        def factory = DocumentBuilderFactory.newInstance()
        factory.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
        factory.namespaceAware = true
        def xml
        new net.dongliu.apk.parser.ApkFile(apk).withCloseable { xml = it.manifestXml }
        def doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes('UTF-8')))
        def metadata = [:]
        def entries = doc.getElementsByTagName('meta-data')
        for (int i = 0; i < entries.length; i++) {
            def element = entries.item(i)
            metadata[element.getAttributeNS('http://schemas.android.com/apk/res/android', 'name')] =
                element.getAttributeNS('http://schemas.android.com/apk/res/android', 'value')
        }
        def headers = new groovy.json.JsonSlurper().parseText(metadata['expo.modules.updates.UPDATES_CONFIGURATION_REQUEST_HEADERS_KEY'])
        def result = [packageName:doc.documentElement.getAttribute('package'),
            runtimeVersion:metadata['expo.modules.updates.EXPO_RUNTIME_VERSION'],
            updateUrl:metadata['expo.modules.updates.EXPO_UPDATE_URL'],
            appId:headers['expo-app-id'], channel:headers['expo-channel-name']]
        require(result.values().every { it instanceof String && !it.empty }, 'APK manifest has no literal installed release target')
        result
    }
}
