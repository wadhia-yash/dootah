package dev.dootah.publish

import dev.dootah.portable.PortableProgram
import dev.dootah.portable.UiTree
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.Test
import static org.junit.Assert.*

class StructuralUiTest {
    @org.junit.Rule public org.junit.rules.TemporaryFolder temp = new org.junit.rules.TemporaryFolder()
    static final String ID=LogicTest.ID
    static final String IMPORTS='''package demo
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
'''
    static final String TITLE='''Text(text = title, color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(horizontal = HeaderPadding), style = MaterialTheme.typography.bodyMedium)'''
    static final String LABEL='''Text(text = "NEW", color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.align(Alignment.CenterEnd).padding(horizontal = HeaderPadding), style = MaterialTheme.typography.labelSmall)'''
    static String box(String children) { 'Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp, top = 14.dp)) { '+children+' }' }
    static String source(String body) { IMPORTS+'@Composable fun Header(title: String) { '+body+' }' }
    static Map lower(String body) {
        new SourceAnalyzer().withCloseable { a ->
            UiLowering.lower(a.inspect('Header.kt',source(body)).entries.first(),[HeaderPadding:[value:16]],['Header'] as Set)
        }
    }
    static Map artifact(Map lowered) {
        def overrides=[[functionId:ID]+lowered]
        [schema:'dootah.portable',runtimeAbi:3,logicAbi:1,runtimeVersion:'test',requires:Contract.requirements(overrides),overrides:overrides]
    }
    static PortableProgram program(Map ir) { LogicTest.program(ir) }
    static String run(PortableProgram p,String title='Theme') {
        def process=new ProcessBuilder('node','-e','process.stdout.write(eval(process.argv[1]))',p.source(ID,['String'],[title])).start()
        def out=process.inputStream.text,err=process.errorStream.text
        assertEquals(err,0,process.waitFor()); out
    }
    static UiTree.Result execute(String body,String title='Theme') {
        def p=program(artifact(lower(body))); p.renderResult(ID,run(p,title))
    }
    static void refused(Closure c) { LogicTest.refused(c) }
    @Test void instrumentedFixtureIsExactlyTheAnalyzerOutputOfOrdinarySource() {
        def root=new File(System.getProperty('repository'))
        new SourceAnalyzer().withCloseable { a ->
            def inspected=a.inspect('Header.kt',new File(root,'v2/publishing/src/test/resources/structural/Header.kt').text)
            def names=['HeaderPadding','Header'] as Set
            def lowered=UiLowering.lower(inspected.entries.first(),UiLowering.constants(inspected.file,names),names)
            def expected=artifact(lowered)
            expected.runtimeVersion='structural-fixture'
            expected.overrides[0].functionId=inspected.entries.first().id
            assertEquals(expected,new JsonSlurper().parse(new File(root,'v2/android-sdk/runtime/src/androidTest/assets/header.json')))
        }
    }
    @Test void baselineTreeRetainsStructureOrderAndThemeTokens() {
        def result=execute(box(TITLE))
        assertEquals('box',result.root.kind)
        assertEquals('CenterStart',result.root.alignment)
        assertEquals(['fillMaxWidth','padding'],result.root.modifiers*.op)
        assertEquals(14,result.root.modifiers[1].top)
        assertEquals(8,result.root.modifiers[1].bottom)
        def text=result.root.children.first()
        assertEquals('secondary',text.color); assertEquals('bodyMedium',text.style)
        assertEquals(16,text.modifiers.first().start); assertEquals(16,text.modifiers.first().end)
        assertEquals(['Theme'],result.slots)
    }
    @Test void addRemoveAndNestChildren() {
        def added=execute(box(TITLE+'\n'+LABEL))
        assertEquals(2,added.root.children.size())
        assertEquals(['Theme','NEW'],added.slots)
        assertEquals(['align','padding'],added.root.children[1].modifiers*.op)
        assertEquals('CenterEnd',added.root.children[1].modifiers.first().alignment)
        assertEquals('tertiary',added.root.children[1].color)
        assertEquals('labelSmall',added.root.children[1].style)
        assertEquals(['NEW'],execute(box(LABEL)).slots)
        assertTrue(execute(box('')).root.children.empty)
        assertEquals('box',execute(box('Box { '+TITLE+' }')).root.children.first().kind)
    }
    @Test void conditionsEvaluateOnlyActiveBranchSlots() {
        String body=box(TITLE+'\nif (title == "Theme") { '+LABEL+' }')
        assertEquals(['Theme',true,'NEW'],execute(body).slots)
        assertEquals('Display',execute(body,'Display').slots[0])
        assertEquals(false,execute(body,'Display').slots[1])
        def p=program(artifact(lower(box('if (title == "Theme") { Text("ok") } else { Text("${1 / 0}") }'))))
        assertEquals('ok',p.renderResult(ID,run(p)).slots[1])
        refused { p.renderResult(ID,'[true,"ok","forged inactive"]') }
    }
    @Test void unsupportedKotlinIsRefusedIncludingDeadBranches() {
        ['Text("x", maxLines = 1)', 'Text("x", style = MaterialTheme.typography.headlineLarge)',
         'Text("x", color = MaterialTheme.colorScheme.active)', 'Text("x", modifier = Modifier.width(4.dp))',
         'Text("x", modifier = Modifier.padding(1.dp, 2.dp))', 'Text("x", modifier = Modifier.padding(all = 1.dp, top = 2.dp))',
         'Text("x", modifier = Modifier.padding(MaterialTheme.padding.small))', 'Text("x", modifier = Modifier.padding(-1.dp))',
         'Text("x", modifier = Modifier.padding(1.5.dp))', 'Icon()', 'val x = "x"; Text(x)',
         'if (false) { Icon() } else { Text("ok") }', 'Text(title.uppercase())', 'Box { it -> Text("x") }'].each { bad ->
            refused { program(artifact(lower(box(bad)))) }
        }
        refused { lower('Text("x", modifier = Modifier.align(Alignment.CenterEnd))') }
    }
    @Test void shadowedImportsAndInputsAreRefused() {
        [source(box(TITLE)).replace('import androidx.compose.material3.Text','import other.Text'),
         source(box(TITLE)).replace('import androidx.compose.material3.Text','import androidx.compose.material3.*'),
         source(box(TITLE)).replace('title: String','Modifier: String'),
         source(box(TITLE))+'\nval Text = 1',
         source(box(TITLE))+'\nval dp = 1'].each { text ->
            refused { new SourceAnalyzer().withCloseable { a -> UiLowering.lower(a.inspect('Header.kt',text).entries.first()) } }
        }
    }
    @Test void frozenConstantsAndWholePublicationRefusal() {
        new SourceAnalyzer().withCloseable { a ->
            def src=source(box(TITLE)), entry=a.inspect('Header.kt',src).entries.first()
            def sources=['Header.kt':src,'Spacing.kt':'package demo\nimport androidx.compose.ui.unit.dp\ninternal val HeaderPadding = 16.dp']
            def contract=[schema:3,irAbi:3,dispatchAbi:2,runtimeVersion:'test',sources:sources,
                functions:[[installed:true,path:'Header.kt',id:entry.id,owner:entry.owner,descriptor:entry.descriptor]]]
            def result=Contract.analyze(contract,sources+['Header.kt':source(box(TITLE+'\n'+LABEL))])
            assertFalse(result.refused); assertEquals(1,result.ir.overrides.size())
            assertNotNull(new PortableProgram(JsonOutput.toJson(result.ir),'test'))
            ['internal var HeaderPadding = 16.dp','internal val HeaderPadding get() = 16.dp',
             'internal val HeaderPadding = compute()', 'internal val HeaderPadding by lazy { 16.dp }'].each { decl ->
                def changed=sources+['Spacing.kt':'package demo\nimport androidx.compose.ui.unit.dp\n'+decl]
                def c=contract+[sources:changed]
                assertTrue(Contract.analyze(c,changed+['Header.kt':source(box(TITLE+'\n'+LABEL))]).refused)
            }
            refused { Contract.analyze(contract,sources+['Spacing.kt':sources['Spacing.kt'].replace('16.dp','20.dp')]) }
            refused { Contract.analyze(contract,sources+['Header.kt':source(box(TITLE))+'\nimport androidx.compose.foundation.layout.Row']) }
            assertTrue(Contract.analyze(contract,sources+['Header.kt':source(box(TITLE+'\nIcon()'))]).refused)
        }
    }
    @Test void malformedTreesSlotsTokensAndAbiRefuseBeforeRendering() {
        def ir=artifact(lower(box(TITLE+'\n'+LABEL)))
        [ {it.runtimeAbi=2}, {it.logicAbi=2}, {it.requires.remove(PortableProgram.LAYOUT)},
          {it.requires << 'native.invoke'}, {it.overrides[0].tree.extra=true},
          {it.overrides[0].tree.type='command'}, {it.overrides[0].tree.alignment='CenterVertically'},
          {it.overrides[0].tree.children[0].text=99}, {it.overrides[0].slots[0].type='Int'},
          {it.overrides[0].tree.children[0].color='surface.copy'},
          {it.overrides[0].tree.children[0].style='unknown'},
          {it.overrides[0].tree.modifiers[1].top=1025}, {it.overrides[0].tree.modifiers[1].top=1.5},
          {it.overrides[0].tree.modifiers << [op:'align',alignment:'Center']},
          {it.overrides[0].tree.children[1].text=0},
          {it.overrides[0].slots << [type:'String',expression:[op:'literal',type:'String',value:'unused']]},
          {it.overrides[0].slots[0].expression=[op:'invokeNative',args:[]]} ].each { mutate ->
            def copy=new JsonSlurper().parseText(JsonOutput.toJson(ir)); mutate(copy); refused { program(copy) }
        }
    }
    @Test void allInstalledAlignmentsAndPaddingOverloadsResolveWithoutReordering() {
        UiTree.ALIGNMENTS.each { alignment ->
            def result=execute('Box(contentAlignment = Alignment.'+alignment+') { Text(title, modifier = Modifier.align(Alignment.'+alignment+').padding(start = 1.dp, top = 2.dp, end = 3.dp, bottom = 4.dp)) }')
            assertEquals(alignment,result.root.alignment)
            assertEquals(alignment,result.root.children[0].modifiers[0].alignment)
            def p=result.root.children[0].modifiers[1]
            assertEquals([1,2,3,4],[p.start,p.top,p.end,p.bottom])
        }
        def all=execute(box('Text(title, modifier = Modifier.padding(4.dp))')).root.children[0].modifiers[0]
        assertEquals([4,4,4,4],[all.start,all.top,all.end,all.bottom])
        def edges=execute(box('Text(title, modifier = Modifier.padding(horizontal = 2.dp, vertical = 3.dp))')).root.children[0].modifiers[0]
        assertEquals([2,3,2,3],[edges.start,edges.top,edges.end,edges.bottom])
    }
    @Test void oversizedArtifactsTreesModifiersAndSlotsRefuse() {
        refused { program(artifact(lower(box((1..9).collect { 'Text("x")' }.join('\n'))))) }
        refused { lower(box('Text("x", modifier = Modifier'+'.padding(1.dp)'*9+')')) }
        refused { lower('Box { '*14+'Text("x")'+' }'*14) }
        def empty=[type:'box',alignment:'TopStart',modifiers:[],children:[]]
        def ir=artifact([parameters:[],slots:[],tree:empty+[children:(1..64).collect { empty }]])
        refused { program(ir) }
        refused {new PortableProgram(' '*65537,'test')}
        refused {new PortableProgram('['*10000+'0'+']'*10000,'test')}
    }
    @Test void cloudUsesTheSameTreeValidatorAndInstalledScalarSignature() {
        def output=temp.newFolder()
        def cp=[PortableProgram.protectionDomain.codeSource.location.path,org.json.JSONObject.protectionDomain.codeSource.location.path].join(File.pathSeparator)
        def compiler=javax.tools.ToolProvider.systemJavaCompiler
        assertNotNull(compiler)
        assertEquals(0,compiler.run(null,null,null,'-cp',cp,'-d',output.path,
            new File(System.getProperty('repository'),'v2/cloud/Validate.java').path))
        def valid=artifact(lower(box(TITLE)))
        def caps=new JsonSlurper().parseText(PortableProgram.contract().toString())
        def input=[runtime:'test',artifact:valid,contract:[capabilities:caps,functions:[(ID):['String']]]]
        def check={ Map request, boolean accepted ->
            def process=new ProcessBuilder(new File(System.getProperty('java.home'),'bin/java').path,
                '-cp',output.path+File.pathSeparator+cp,'Validate').start()
            process.outputStream.withWriter('UTF-8') { it.write(JsonOutput.toJson(request)) }
            process.inputStream.text; process.errorStream.text
            assertEquals(accepted,process.waitFor()==0)
        }
        check(input,true)
        check(input+[artifact:valid+[runtimeAbi:2]],false)
        check(input+[contract:[capabilities:caps,functions:[(ID):['Int']]]],false)
        check(input+[contract:[capabilities:caps+[runtimeAbi:2],functions:[(ID):['String']]]],false)
        def corrupt=new JsonSlurper().parseText(JsonOutput.toJson(valid))
        corrupt.overrides[0].tree.children[0].type='invokeNative'
        check(input+[artifact:corrupt],false)
    }
    @Test void worstCaseEscapedResultFitsUnchangedSandboxLimit() {
        def p=program(artifact(lower(box((1..8).collect { 'if (title == title) { Text(title) }' }.join('\n')))))
        String raw=run(p,'\u0001'*256)
        assertTrue(raw.getBytes('UTF-8').length<UiTree.MAX_RESULT_BYTES)
        assertEquals(16,p.renderResult(ID,raw).slots.size())
        ['['*10000+']'*10000,'[{}]','[true]','[]','["x"] trailing','["\\ud800"]', ' '*16385].each { bad ->
            refused { p.renderResult(ID,bad) }
        }
        refused {p.source(ID,['String'],[new Object()])}
        refused {p.source(ID,['Int'],[1])}
    }
}
