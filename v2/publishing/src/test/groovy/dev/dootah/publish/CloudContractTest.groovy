package dev.dootah.publish

import dev.dootah.portable.PortableProgram
import groovy.json.JsonSlurper
import org.junit.Test
import static org.junit.Assert.*

class CloudContractTest {
    @org.junit.Rule public org.junit.rules.TemporaryFolder temp = new org.junit.rules.TemporaryFolder()
    private static Map contract(List functions) {
        [schema:2, irAbi:2, dispatchAbi:2, capabilities:new JsonSlurper().parseText(PortableProgram.contract().toString()), functions:functions]
    }
    @Test void typesAreDerivedFromInstalledDescriptorsAndNullability() {
        def function = [id:'dth1:' + 'a' * 64, installed:true, extension:false,
            descriptor:'(Ljava/lang/String;ILjava/lang/Boolean;Landroidx/compose/runtime/Composer;II)V', nullable:'?!?']
        def result = Contract.cloudContract(contract([function]))
        assertEquals(['String?', 'Int', 'Boolean?'], result.functions[function.id])
        assertEquals(['capabilities', 'functions'] as Set, result.keySet())
        assertEquals(result, Contract.cloudContract(contract([function])))
    }
    @Test void removedAndUnsupportedHooksNeverExpandLogicAbi() {
        def good = [id:'good', installed:true, extension:false, descriptor:'(Landroidx/compose/runtime/Composer;I)V', nullable:'']
        def unsupported = good + [id:'float', descriptor:'(FLandroidx/compose/runtime/Composer;I)V', nullable:'!']
        assertEquals(['good'] as Set, Contract.cloudContract(contract([good, unsupported, good + [id:'removed', installed:false], good + [id:'ext', extension:true]])).functions.keySet())
        assertThrows(IllegalArgumentException) { Contract.cloudContract(contract([unsupported])) }
    }
    @Test void mismatchedAbiAndDuplicateIdentitiesFailClosed() {
        def f = [id:'good', installed:true, descriptor:'(Landroidx/compose/runtime/Composer;I)V', nullable:'']
        assertThrows(IllegalArgumentException) { Contract.cloudContract(contract([f]) + [schema:1]) }
        def bad = contract([f]); bad.capabilities.logicAbi = 2
        assertThrows(IllegalArgumentException) { Contract.cloudContract(bad) }
        assertThrows(IllegalArgumentException) { Contract.cloudContract(contract([f, f])) }
    }

    @Test void boundedVariantSourcesDetectNewFilesWithoutScanningProjectTrees() {
        def root = temp.newFolder()
        def source = new File(root, 'src/main/kotlin'); source.mkdirs()
        new File(source, 'Screen.kt').text = 'fun Screen() {}'
        new File(root, 'unrelated.kt').text = 'not part of this variant'
        def config = [sourceRoot:root.path, sourceRoots:['src/main/kotlin'], sourcePaths:['src/main/kotlin/Screen.kt']]
        assertEquals(config.sourcePaths as Set, Contract.currentSources(config).keySet())
        new File(source, 'Extra.kt').text = 'fun Extra() {}'
        assertEquals(2, Contract.currentSources(config).size())
        assertThrows(IllegalArgumentException) { Contract.currentSources(config + [sourceRoots:['../outside']]) }
    }
}
