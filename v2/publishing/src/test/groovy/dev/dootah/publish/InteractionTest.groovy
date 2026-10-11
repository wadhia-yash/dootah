package dev.dootah.publish

import dev.dootah.adapters.AdapterGen
import dev.dootah.portable.PortableProgram
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.Test
import static org.junit.Assert.*

/**
 * Compose state and installed callbacks: the adapter-proof dialog fixture's hook, lowered from an ordinary
 * source edit into a portable artifact that the JVM proof (DialogInteractionTest) renders and drives.
 */
class InteractionTest {
    static final File PROOF = new File(System.getProperty('repository'), 'v2/android-sdk/tests/adapter-proof/dialog')
    static final String PATH = 'src/main/kotlin/dev/dootah/proof/dialog/Screens.kt'
    static final File ARTIFACT = new File(PROOF, 'src/test/resources/interaction.json')
    static String installed() { new File(PROOF, PATH).getText('UTF-8') }

    static Map adapters(String source = installed()) {
        new SourceAnalyzer().withCloseable { a -> AdapterGen.generate(a.inspect(PATH, source).file, PATH).signatures() }
    }
    static Map lower(String source, Map adapters = adapters(), boolean interaction = true) {
        new SourceAnalyzer().withCloseable { a ->
            def entry = a.inspect(PATH, source).entries.find { it.name == 'DeleteDialog' }
            [functionId: entry.id] + UiLowering.lower(entry, [:], [] as Set, adapters, interaction)
        }
    }
    static Map artifact(Map override) {
        [schema: 'dootah.portable', runtimeAbi: 3, logicAbi: 1, runtimeVersion: 'dialog-fixture',
            requires: Contract.requirements([override]), overrides: [override]]
    }
    static PortableProgram program(Map artifact) { new PortableProgram(JsonOutput.toJson(artifact), 'dialog-fixture') }
    static String node(String source) {
        def process = new ProcessBuilder('node', '-e', 'process.stdout.write(eval(process.argv[1]))', source).start()
        def out = process.inputStream.text, err = process.errorStream.text
        assertEquals(err, 0, process.waitFor())
        out
    }
    static void refused(String reason, Closure c) {
        try { c(); fail('Must refuse') } catch (IllegalArgumentException e) { assertTrue(e.message, e.message.contains(reason)) }
    }
    static final String EDIT = 'mutableStateOf(false)'

    @Test void anOrdinaryStateEditLowersToAnArtifactOverTheInstalledDialog() {
        def override = lower(installed().replace(EDIT, 'mutableStateOf(true)'))
        assertEquals(['()->Unit', '(Boolean)->Unit'], override.parameters)
        assertEquals([[type: 'Boolean', initial: true]], override.state)
        // Every native call is an installed shape: the dialog, its slots' children, and their own content.
        assertEquals(adapters().keySet(), collect(override.tree).keySet())
        def json = artifact(override)
        assertEquals([PortableProgram.INTERACTION, PortableProgram.NATIVE, PortableProgram.LOGIC], json.requires)
        def p = program(json)
        String id = override.functionId
        assertEquals(['()->Unit', '(Boolean)->Unit'], p.parameters(id))
        assertEquals(['Boolean'], p.inputTypes(id))
        assertEquals([true], p.interaction(id).initials)
        // Render: the checkbox reads the state; actions: toggle (set), remove (call, call), dismiss (call x2).
        def result = p.renderResult(id, node(p.source(id, [true])))
        assertEquals([true], result.slots)
        assertEquals([[parameters: ['Boolean'], steps: [[op: 'set', state: 0, value: [op: 'input', index: 1]]]],
                      [parameters: [], steps: [[op: 'call', callback: 0, arguments: []]]],
                      [parameters: [], steps: [[op: 'call', callback: 1, arguments: [[op: 'input', index: 0]]], [op: 'call', callback: 0, arguments: []]]],
                      [parameters: [], steps: [[op: 'call', callback: 0, arguments: []]]]] as Set, override.actions as Set)
        int remove = override.actions.findIndexOf { it.steps.size() == 2 }
        assertEquals([false], p.actionResult(id, remove, node(p.actionSource(id, remove, [false]))))
        int toggle = override.actions.findIndexOf { it.parameters == ['Boolean'] }
        assertEquals([false], p.actionResult(id, toggle, node(p.actionSource(id, toggle, [true, false]))))
        if (System.getenv('DOOTAH_REGENERATE')) ARTIFACT.setText(JsonOutput.prettyPrint(JsonOutput.toJson(json)) + '\n', 'UTF-8')
        assertEquals(ARTIFACT.path, new JsonSlurper().parse(ARTIFACT), new JsonSlurper().parseText(JsonOutput.toJson(json)))
    }

    /** Later steps read earlier assignments in the same callback, as the Kotlin lambda does. */
    @Test void actionsAreSequentialOverOneEventSnapshot() {
        def source = installed().replace('onDelete(removeEverything)', 'removeEverything = !removeEverything\n                onDelete(removeEverything)')
        assertNotEquals(installed(), source)
        def override = lower(source)
        def action = override.actions.find { it.steps.size() == 3 }
        assertEquals(['set', 'call', 'call'], action.steps*.op)
        assertEquals([op: 'not', args: [[op: 'input', index: 0]]], action.steps[1].arguments[0])
        def p = program(artifact(override))
        int index = override.actions.indexOf(action)
        assertEquals([true, true], p.actionResult(override.functionId, index, node(p.actionSource(override.functionId, index, [false]))))
    }

    @Test void unsupportedShapesAndProgramsAreRefused() {
        // A callback that takes two parameters, is nullable, or is a composable lambda: no hook, no adapters.
        ['(Boolean, Int) -> Unit', '(() -> Unit)?', '@Composable () -> Unit', 'suspend () -> Unit', '(Long) -> Unit'].each { type ->
            assertTrue(type, adapters(installed().replace('onDelete: (Boolean) -> Unit', 'onDelete: ' + type)).isEmpty())
        }
        // Callback bodies may only assign hook state and invoke the hook's callbacks; anything else needs a new APK.
        refused('neither content nor a supported callback') { lower(installed().replace('onDismissRequest()\n', 'println("x")\n')) }
        refused('neither content nor a supported callback') { lower(installed().replace('onCheckedChange = { removeEverything = it }', 'onCheckedChange = { onDismissRequest = it }')) }
        refused('Callback argument type') { lower(installed().replace('onDelete(removeEverything)', 'onDelete(1)')) }
        refused('State assignment type') { lower(installed().replace('onDelete(removeEverything)', 'removeEverything = 1\n                onDelete(removeEverything)')) }
        // Dropping the read of the callback's own parameter is also a different installed shape.
        refused('No installed native adapter') { lower(installed().replace('removeEverything = it', 'removeEverything = true')) }
        // A callback that newly reads its parameter is a different installed shape.
        refused('No installed native adapter') { lower(installed().replace('Action(onClick = {\n', 'Action(onClick = { x ->\n')) }
        // A baseline that did not negotiate compose.interaction.v1 never receives state or callbacks.
        refused('requires compose.interaction.v1') { lower(installed(), adapters(), false) }
        // The device grammar refuses malformed actions independently of the publisher.
        def good = artifact(lower(installed()))
        [{ it.overrides[0].actions[0].steps[0].value = [op: 'literal', type: 'String', value: 'x'] },
         { it.overrides[0].actions[1].steps[0].callback = 5 },
         { it.overrides[0].actions[0].parameters = ['Boolean', 'Int'] },
         { it.overrides[0].state[0].initial = 'yes' },
         { it.overrides[0].tree.events = [99] },
         { it.overrides[0].parameters[0] = '(Boolean,Int)->Unit' },
         { it.requires.remove(PortableProgram.INTERACTION) }].each { change ->
            def bad = new JsonSlurper().parseText(JsonOutput.toJson(good))
            change(bad)
            try { program(bad); fail('Malformed interaction accepted: ' + JsonOutput.toJson(bad.overrides[0].actions)) }
            catch (IllegalArgumentException | org.json.JSONException expected) { }
        }
        // An action result must match its step types.
        def p = program(good)
        String id = good.overrides[0].functionId
        int toggle = good.overrides[0].actions.findIndexOf { it.parameters == ['Boolean'] }
        refused('Boolean value') { p.actionResult(id, toggle, '["true"]') }
        refused('Action result count') { p.actionResult(id, toggle, '[true,false]') }
    }

    @Test void capabilityDocumentsAreNegotiatedNotSilentlyBroken() {
        assertTrue(PortableProgram.negotiate(PortableProgram.contract()))
        assertFalse(PortableProgram.negotiate(PortableProgram.previousContract()))
        refused('Unknown installed capability contract') {
            def other = PortableProgram.contract(); other.put('runtimeAbi', 2); PortableProgram.negotiate(other)
        }
        assertFalse(PortableProgram.previousContract().getJSONObject('capabilities').has(PortableProgram.INTERACTION))
    }

    /** Kotlin gives callback parameters a generic JVM signature; the contract accepts exactly the one the tokens imply. */
    @Test void callbackHooksCarryExactlyKotlinsGenericSignature() {
        // As compiled by kotlinc for HistoryDeleteDialog(onDismissRequest: () -> Unit, onDelete: (Boolean) -> Unit).
        String descriptor = '(Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function1;Landroidx/compose/runtime/Composer;I)V'
        assertEquals('(Lkotlin/jvm/functions/Function0<Lkotlin/Unit;>;Lkotlin/jvm/functions/Function1<-Ljava/lang/Boolean;Lkotlin/Unit;>;Landroidx/compose/runtime/Composer;I)V',
            SourceAnalyzer.genericSignature(descriptor, ['()->Unit', '(Boolean)->Unit']))
        assertEquals('(Ljava/lang/String;Lkotlin/jvm/functions/Function1<-Ljava/lang/Integer;Lkotlin/Unit;>;Landroidx/compose/runtime/Composer;I)V',
            SourceAnalyzer.genericSignature('(Ljava/lang/String;Lkotlin/jvm/functions/Function1;Landroidx/compose/runtime/Composer;I)V', ['String', '(Int)->Unit']))
        assertNull(SourceAnalyzer.genericSignature('(Ljava/lang/String;ILandroidx/compose/runtime/Composer;I)V', ['String', 'Int']))
        // Tokens and descriptor must agree on every callback.
        refused('Callback descriptor mismatch') { SourceAnalyzer.genericSignature(descriptor, ['()->Unit', '()->Unit']) }
        refused('Callback descriptor mismatch') { SourceAnalyzer.genericSignature(descriptor, ['()->Unit']) }
    }

    /** An inline composable container (foundation Column) leaves no call, only kotlinc's inlined-body marker. */
    @Test void inlinedComposablesAreRecognisedAndAbsentOnesRefused() {
        def method = { Closure body ->
            def m = new org.objectweb.asm.tree.MethodNode(org.objectweb.asm.Opcodes.ACC_STATIC, 'lambda', '()V', null, null)
            body(m); m
        }
        def start = new org.objectweb.asm.tree.LabelNode(), end = new org.objectweb.asm.tree.LabelNode()
        def inlined = method { m ->
            m.instructions.add(start); m.instructions.add(end)
            m.localVariables = [new org.objectweb.asm.tree.LocalVariableNode('$i$f$Column', 'I', null, start, end, 1)]
        }
        def called = method { m -> m.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC,
            'androidx/compose/material3/TextKt', 'Text-Nvy7gAk', '(Ljava/lang/String;Landroidx/compose/runtime/Composer;I)V')) }
        assertTrue(Contract.invokes([inlined], 'Column'))
        assertTrue(Contract.invokes([called], 'Text'))
        assertFalse(Contract.invokes([inlined, called], 'Row'))
        assertFalse(Contract.invokes([inlined], 'Col'))
    }

    static Map collect(Map node, Map into = [:]) {
        if (node.type == 'native') into[node.adapter] = true
        (node.children ?: []).each { collect(it, into) }
        (node.content ?: []).each { slot -> slot.children.each { collect(it, into) } }
        (node.then ?: []).each { collect(it, into) }
        (node.else ?: []).each { collect(it, into) }
        into
    }
}
