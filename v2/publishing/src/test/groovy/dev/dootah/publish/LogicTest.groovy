package dev.dootah.publish

import dev.dootah.portable.PortableProgram
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.Test
import static org.junit.Assert.*

class LogicTest {
    static final String ID='dth1:'+'a'*64
    static Map lower(String body,String parameters='price: Int') {
        String source="package demo\nimport androidx.compose.runtime.Composable\n@Composable fun Screen($parameters) { $body }"
        new SourceAnalyzer().withCloseable { a -> LogicLowering.lower(a.inspect('Demo.kt',source).entries.first()) }
    }
    static Map artifact(Map lowered) { [schema:'dootah.portable',runtimeAbi:2,logicAbi:1,runtimeVersion:'test',
        requires:[PortableProgram.LOGIC,PortableProgram.TEXT],overrides:[[functionId:ID]+lowered]] }
    static PortableProgram program(Map value) {new PortableProgram(JsonOutput.toJson(value),'test')}
    static void refused(Closure c) {try {c();fail('Must refuse')}catch(IllegalArgumentException expected){}}
    static String execute(String body,List values=[100],String parameters='price: Int') {
        def p=program(artifact(lower(body,parameters)))
        def process=new ProcessBuilder('node','-e','process.stdout.write(eval(process.argv[1]))',p.source(ID,p.parameters(ID),values)).start()
        String output=process.inputStream.getText('UTF-8'),error=process.errorStream.getText('UTF-8')
        assertEquals(error,0,process.waitFor())
        p.result(output)
    }
    @Test void businessRuleComposesLocalHelperAndTypedInput() {
        assertEquals('Discount: 20',execute('fun discount(p: Int): Int = p * 20 / 100\n val amount = discount(price); androidx.compose.foundation.text.BasicText("Discount: $amount")'))
        assertEquals('Eligible',execute('val eligible = price >= 100 && price < 200; val label = if (eligible) "Eligible" else "Not eligible"; androidx.compose.foundation.text.BasicText(label)'))
        assertEquals('medium',execute('val label = when { price < 50 -> "small"; price < 200 -> "medium"; else -> "large" }; androidx.compose.foundation.text.BasicText(label)'))
    }
    @Test void kotlinIntOverflowDivisionAndNullableValues() {
        assertEquals('-2147483648',execute('val n = price + 1; androidx.compose.foundation.text.BasicText("$n")',[Integer.MAX_VALUE]))
        assertEquals('-3',execute('val n = price / 3; androidx.compose.foundation.text.BasicText("$n")',[-10]))
        assertEquals('null',execute('val label: String? = null; androidx.compose.foundation.text.BasicText("$label")'))
        assertEquals('empty',execute('val s = if (label == null) "empty" else "present"; androidx.compose.foundation.text.BasicText(s)',[null],'label: String?'))
    }
    @Test void unsafeAndUnknownSourceRefuses() {
        ['val x = System.currentTimeMillis(); androidx.compose.foundation.text.BasicText("$x")',
         'val x = price.toString(); androidx.compose.foundation.text.BasicText(x)',
         'fun loop(x: Int): Int = loop(x)\n val n = loop(price); androidx.compose.foundation.text.BasicText("$n")',
         'var x = 0; androidx.compose.foundation.text.BasicText("$x")',
         'val x = if (true) price else unknown(); androidx.compose.foundation.text.BasicText("$x")',
         'androidx.compose.material3.Text("x")'].each { body -> refused {program(artifact(lower(body)))} }
    }
    @Test void unusedLocalsStillExecuteAndExponentialExpansionRefuses() {
        def p=program(artifact(lower('val unused = price / 0; androidx.compose.foundation.text.BasicText("safe")')))
        def process=new ProcessBuilder('node','-e','eval(process.argv[1])',p.source(ID,['Int'],[1])).start()
        process.inputStream.text;process.errorStream.text
        assertNotEquals(0,process.waitFor())
        String body='val x0 = price; '+(1..12).collect {"val x$it = x${it-1} + x${it-1}; "}.join('')+'androidx.compose.foundation.text.BasicText("$x12")'
        refused {lower(body)}
    }
    @Test void runtimeRejectsCapabilitiesVersionsAbiAndUnknownNativeOperations() {
        def original=artifact(lower('androidx.compose.foundation.text.BasicText("hello")'))
        [ {it.requires=[]}, {it.requires[0]='logic.pure.v2'}, {it.runtimeAbi=99}, {it.logicAbi=99},
          {it.runtimeVersion='stale'}, {it.overrides[0].expression=[op:'invokeNative',args:[]]},
          {it.overrides[0].expression=[op:'eval',args:[]]}, {it.extra='NativeModules'} ].each { mutate ->
            def changed=new JsonSlurper().parseText(JsonOutput.toJson(original));mutate(changed);refused {program(changed)}
        }
    }
    @Test void typedBoundaryRejectsObjectsCoercionRangeNullAndWrongInstalledTypes() {
        def p=program(artifact(lower('androidx.compose.foundation.text.BasicText("$price")')))
        [['100'],[true],[null],[2147483648L],[new Object()],[1.0d]].each {bad -> refused{p.source(ID,['Int'],bad)}}
        refused {p.source(ID,['String'],['100'])}
        refused {p.result('{"native":"object"}')}
        refused {p.result('"'+('x'*257)+'"')}
        refused {p.result('"\\ud800"')}
    }
    @Test void runtimeBoundsMalformedProgramsBeforeExecution() {
        def ir=artifact(lower('androidx.compose.foundation.text.BasicText("hello")'))
        refused {new PortableProgram(JsonOutput.toJson(ir)+';eval(1)','test')}
        def full=[op:'literal',type:'String',value:'x']
        10.times {full=[op:'concat',args:[full,full]]}
        ir.overrides[0].expression=full
        refused {program(ir)}
        refused {new PortableProgram('['*10000+'0'+']'*10000,'test')}
        refused {new PortableProgram(' '*65537,'test')}
        def p=program(artifact(lower('androidx.compose.foundation.text.BasicText("$label")','label: String')))
        refused {p.source(ID,['String'],['x'*257])}
    }
    @Test void installedContractAssetMatchesSharedValidation() {
        def path=new File(System.getProperty('repository'),'v2/android-sdk/runtime/src/main/assets/dootah-capabilities.json')
        assertEquals(new JsonSlurper().parseText(PortableProgram.contract().toString()),new JsonSlurper().parse(path))
    }
    @Test void sourceAndPublisherRetainVersionOneCompatibility() {
        assertEquals([type:'text',value:[type:'string',value:'old']],new SourceAnalyzer().withCloseable { a ->
            a.lower(a.inspect('Demo.kt','import androidx.compose.runtime.Composable\n@Composable fun Screen(){ androidx.compose.foundation.text.BasicText("old") }').entries.first())
        })
    }
}
