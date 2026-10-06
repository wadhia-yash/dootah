import dev.dootah.publish.SourceAnalyzer
import dev.dootah.publish.LogicLowering
import dev.dootah.portable.PortableProgram
import groovy.json.JsonOutput

// Read the real documented files, not an independent copy of the examples.
def root = new File(args[0])
def inspected = []
new SourceAnalyzer().withCloseable { analyzer ->
    ['baseline', 'ota'].eachWithIndex { variant, index ->
        def file = new File(root, "v2/examples/basic-text/$variant/CheckoutScreen.kt")
        def result = analyzer.inspect('CheckoutScreen.kt', file.text)
        assert result.entries.size() == 1
        def entry = result.entries.first()
        assert entry.id != null : entry.reason
        def lowered = LogicLowering.lower(entry)
        def program = new PortableProgram(JsonOutput.toJson([
            schema:'dootah.portable', runtimeAbi:2, logicAbi:1, runtimeVersion:'docs-example',
            requires:[PortableProgram.LOGIC, PortableProgram.TEXT],
            overrides:[[functionId:entry.id] + lowered]
        ]), 'docs-example')
        def process = new ProcessBuilder('node', '-e', 'process.stdout.write(eval(process.argv[1]))',
            program.source(entry.id, ['Int'], [100])).start()
        def output = process.inputStream.text
        def error = process.errorStream.text
        assert process.waitFor() == 0 : error
        assert program.result(output) == "Discount: ${index == 0 ? 10 : 20}"
        inspected << [skeleton:result.skeleton, id:entry.id]
    }
}
assert inspected[0] == inspected[1] : 'OTA changes frozen source or installed identity'
println 'PASS: exact baseline/OTA Kotlin, stable source skeleton/identity, typed IR, outputs 10/20'
