package dev.dootah.publish

import dev.dootah.adapters.AdapterGen
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.lexer.KtTokens
import static dev.dootah.publish.SourceAnalyzer.require

/** Small, closed PSI vocabulary. No compiler mutation or speculative call execution. */
class UiLowering {
    static final Map IMPORTS = [Box:'androidx.compose.foundation.layout.Box', Text:'androidx.compose.material3.Text',
        MaterialTheme:'androidx.compose.material3.MaterialTheme', Modifier:'androidx.compose.ui.Modifier',
        Alignment:'androidx.compose.ui.Alignment', dp:'androidx.compose.ui.unit.dp',
        padding:'androidx.compose.foundation.layout.padding', fillMaxWidth:'androidx.compose.foundation.layout.fillMaxWidth']
    static final Set RESERVED = (IMPORTS.keySet() + ['androidx','String','Int','Boolean','align']) as Set
    static final List STATE_API=['remember','mutableStateOf','getValue','setValue']
    Map entry, constants, adapters, scope=[:]
    List slots=[], state=[], actions=[]
    /** Callback parameter name -> [index: hook parameter position, type: callback token]. */
    Map callbacks=[:]
    AdapterGen.Hook hook
    LogicLowering logic
    int nodes, scalars
    /** Whether the installed baseline negotiated compose.interaction.v1 (state, callbacks, slots, events). */
    boolean interaction

    static Map lower(Map entry, Map constants=[:], Set packageNames=[] as Set, Map adapters=[:], boolean interaction=true) {
        require(entry.id!=null,entry.reason ?: 'Unproven source identity')
        require(entry.container==null && entry.function.receiverTypeReference==null,'Structural UI requires a top-level scalar hook')
        require(!entry.imports.any { it.contains('*') && it!='androidx.compose.runtime.*' },'Wildcard imports require semantic resolution')
        require((packageNames.intersect(RESERVED)).empty,'Shadowed UI symbol in package')
        require(!entry.file.declarations.any { it.name in RESERVED },'Shadowed UI symbol')
        def l=new UiLowering(entry:entry,constants:constants,adapters:adapters,logic:new LogicLowering(entry:entry),interaction:interaction)
        def types=[]
        entry.function.valueParameters.eachWithIndex { p,i ->
            String t=AdapterGen.type(p.typeReference.text)
            require(!(p.name in RESERVED),'Shadowed UI input')
            if(t ==~ AdapterGen.CALLBACK) {
                l.requireInteraction('Callback parameter')
                l.callbacks[p.name]=[index:i,type:t]
            } else {
                require(t in ['String','Int','Boolean','String?','Int?','Boolean?'],'Unsupported UI input')
                l.scope[p.name]=[type:t,node:[op:'input',index:l.scalars++],cost:1]
            }
            types << t
        }
        def body=entry.function.bodyExpression
        if(body instanceof KtBlockExpression) {
            def statements=body.statements
            def declarations=statements.takeWhile { it instanceof KtProperty && it.delegateExpression!=null }
            declarations.each { l.declareState(it) }
            require(statements.size()==declarations.size()+1,'Structural body requires state declarations, then one root statement')
            body=statements.last()
        }
        l.hook=AdapterGen.hook(entry.function,entry.file)
        def tree=l.node(body,0,false)
        def result=[parameters:types, slots:l.slots, tree:tree]
        if(l.state) result.state=l.state
        if(l.actions) result.actions=l.actions
        result
    }
    void requireInteraction(String feature) {
        require(interaction,feature+' requires compose.interaction.v1, which the installed APK does not provide; a new APK is required')
    }
    /** `var x by remember { mutableStateOf(literal) }`: Compose state the native side creates with this literal. */
    void declareState(KtProperty p) {
        requireInteraction('State')
        String type=AdapterGen.stateType(p)
        require(type!=null,'Only var x by remember { mutableStateOf(<Boolean/Int/String literal>) } state is supported')
        STATE_API.each { name ->
            require(('androidx.compose.runtime.'+name in entry.imports || 'androidx.compose.runtime.*' in entry.imports) &&
                !entry.imports.any { it.endsWith('.'+name) && it!='androidx.compose.runtime.'+name } &&
                !entry.file.declarations.any { it.name==name },'Unambiguous androidx.compose.runtime import required: '+name)
        }
        require(!scope.containsKey(p.name) && !callbacks.containsKey(p.name) && !(p.name in RESERVED) && state.size()<8,'State name or budget')
        def literal=((KtCallExpression)((KtCallExpression)p.delegateExpression).lambdaArguments[0].lambdaExpression.bodyExpression.statements[0]).valueArguments[0].argumentExpression
        def initial=type=='Boolean' ? literal.text=='true' : type=='Int' ? Integer.valueOf(literal.text) :
            ((KtStringTemplateExpression)literal).entries.collect { it.text }.join('')
        scope[p.name]=[type:type,node:[op:'input',index:scalars+state.size()],cost:1,state:state.size()]
        state << [type:type,initial:initial]
    }
    void imported(String name) {
        require(IMPORTS[name] in entry.imports && !entry.imports.any {
            it.endsWith('.'+name) && it!=IMPORTS[name]
        },'Explicit unambiguous import required: '+name)
    }
    int slot(KtExpression expression,String type) {
        def value=logic.expr(expression,scope,0)
        require(value.type==type,'UI slot type mismatch')
        require(slots.size()<16 && (!type.startsWith('String') || slots.count { it.type.startsWith('String') }<8),'UI slot budget')
        slots << [type:type,expression:value.node]
        slots.size()-1
    }
    List children(KtExpression expression,int depth,boolean boxScope) {
        require(expression!=null,'Missing UI branch')
        def expressions=expression instanceof KtBlockExpression ? expression.statements : [expression]
        require(expressions.size()<=64,'UI children budget')
        expressions.collect { node(it,depth,boxScope) }
    }
    Map node(KtExpression expression,int depth,boolean boxScope) {
        require(depth<=12 && ++nodes<=64,'UI tree budget')
        if(expression instanceof KtIfExpression) {
            return [type:'if',condition:slot(expression.condition,'Boolean'),
                then:children(expression.then,depth+1,boxScope),
                else:expression.else==null ? [] : children(expression.else,depth+1,boxScope)]
        }
        require(expression instanceof KtCallExpression,'Unsupported structural statement')
        KtCallExpression call=expression
        String name=call.calleeExpression.text
        if(AdapterGen.candidate(call)) return nativeCall(call)
        require(name in ['Box','Text'] && call.typeArguments.empty,'Only Box and Material3 Text supported')
        imported(name)
        def args=arguments(call,name=='Text'?'text':null)
        if(name=='Box') {
            require(args.keySet().every { it in ['modifier','contentAlignment'] } && call.lambdaArguments.size()==1,'Box arguments/content')
            def lambda=call.lambdaArguments.first().lambdaExpression
            require(lambda.functionLiteral.valueParameterList==null && lambda.functionLiteral.arrow==null,'Explicit Box receiver/parameters unsupported')
            return [type:'box',alignment:args.contentAlignment ? alignment(args.contentAlignment) : 'TopStart',
                modifiers:modifiers(args.modifier,boxScope), children:children(lambda.bodyExpression,depth+1,true)]
        }
        require(call.lambdaArguments.empty && args.containsKey('text') &&
            args.keySet().every { it in ['text','modifier','color','style'] },'Unsupported Material3 Text argument')
        [type:'text',text:slot(args.text,'String'),color:theme(args.color,'colorScheme',['secondary','tertiary']),
            style:theme(args.style,'typography',['bodyMedium','labelSmall']),modifiers:modifiers(args.modifier,boxScope)]
    }
    /** An app-owned composable: only a call shape the installed APK generated an adapter for. */
    Map nativeCall(KtCallExpression call) {
        require(AdapterGen.statementInHook(call,entry.function),'Native component call must be a direct statement of the hook body')
        lowerCall(AdapterGen.shape(call,entry.file,entry.path,hook,typer()),0)
    }
    java.util.function.Function typer() { { KtExpression e -> logic.expr(e,scope,0).type } as java.util.function.Function }
    /**
     * One installed call: its data values, the children the update selects for each content slot, and the
     * action bound to each callback argument. A lone trailing slot keeps the earlier `children` form.
     */
    Map lowerCall(AdapterGen.Shape shape,int depth) {
        installed(shape)
        def node=[type:'native',adapter:shape.id,arguments:dataSlots(shape)]
        if(shape.slots.size()==1 && shape.slots[0].name=='' && shape.events.empty) node.children=content(shape.slots[0],shape,depth)
        else if(shape.slots) {
            requireInteraction('Named or multiple content slots')
            node.content=shape.slots.collect { s -> [slot:s.name,children:content(s,shape,depth)] }
        }
        if(shape.events) {
            requireInteraction('Callback argument')
            node.events=shape.events.collect { action(it) }
        }
        node
    }
    void installed(AdapterGen.Shape shape) {
        require(adapters[shape.id]==shape.signature(),'No installed native adapter for this '+shape.callee+' call; a new APK is required')
    }
    List dataSlots(AdapterGen.Shape shape) { shape.arguments.findAll { it.type!=null }.collect { slot(it.expression,it.type) } }
    List content(AdapterGen.Slot contentSlot,AdapterGen.Shape container,int depth) { content(contentSlot.body.statements,contentSlot,container,depth) }
    List content(List statements,AdapterGen.Slot contentSlot,AdapterGen.Shape container,int depth) {
        statements.collect { KtExpression statement ->
            require(++nodes<=64,'UI tree budget')
            if(statement instanceof KtIfExpression) {
                def branch={ KtExpression e -> e==null ? [] : content(e instanceof KtBlockExpression ? e.statements : [e],contentSlot,container,depth) }
                return [type:'if',condition:slot(statement.condition,'Boolean'),then:branch(statement.then),else:branch(statement.else)]
            }
            require(statement instanceof KtCallExpression && AdapterGen.childCandidate(statement),'Unsupported content statement')
            require(depth<AdapterGen.MAX_NESTING,'Native content nesting')
            lowerCall(AdapterGen.childShape(container,contentSlot,statement,entry.file,hook,typer()),depth+1)
        }
    }
    /**
     * A callback argument as an action: ordered `set` steps (state assignments) and `call` steps (installed
     * callbacks), each over the inputs, the state at the event, and the forwarded callback parameter. Later
     * steps read earlier assignments, as the Kotlin lambda does.
     */
    int action(AdapterGen.Event event) {
        require(actions.size()<16,'Action budget')
        def local=new LinkedHashMap(scope)
        def steps=[], parameters=[]
        int input=scalars+state.size()
        def forward={ String type ->
            parameters << type
            [type:type,node:[op:'input',index:input],cost:1]
        }
        if(event.expression instanceof KtNameReferenceExpression) {
            def callback=callbacks[event.expression.getReferencedName()]
            require(callback!=null,'Unknown callback')
            def arguments=dev.dootah.portable.PortableProgram.callbackArguments(callback.type)
            steps << [op:'call',callback:callback.index,arguments:arguments.collect { forward(it).node }]
        } else {
            def lambda=(KtLambdaExpression)event.expression
            def statements=lambda.bodyExpression.statements
            if(event.parameter!=null) {
                require(!local.containsKey(event.parameter) && !callbacks.containsKey(event.parameter),'Shadowed callback parameter')
                local[event.parameter]=forward(parameterType(event.parameter,statements))
            }
            require(statements.size() in 1..8,'Step count')
            statements.each { KtExpression s ->
                if(s instanceof KtBinaryExpression && s.operationToken==KtTokens.EQ) {
                    String name=((KtNameReferenceExpression)s.left).getReferencedName()
                    def target=scope[name]
                    require(target?.state!=null,'Only hook state can be assigned')
                    def value=logic.expr(s.right,local,0)
                    require(value.type==target.type,'State assignment type')
                    steps << [op:'set',state:target.state,value:value.node]
                    local[name]=value
                } else {
                    def call=(KtCallExpression)s
                    def callback=callbacks[call.calleeExpression.text]
                    require(callback!=null && call.lambdaArguments.empty && call.typeArguments.empty,'Only installed callbacks can be invoked')
                    def expected=dev.dootah.portable.PortableProgram.callbackArguments(callback.type)
                    require(call.valueArguments.size()==expected.size() && call.valueArguments.every { !it.argumentName && !it.spreadElement },'Callback arguments')
                    def values=call.valueArguments.withIndex().collect { a,i ->
                        def value=logic.expr(a.argumentExpression,local,0)
                        require(value.type==expected[i],'Callback argument type'); value.node
                    }
                    steps << [op:'call',callback:callback.index,arguments:values]
                }
            }
        }
        actions << [parameters:parameters,steps:steps]
        actions.size()-1
    }
    /** The forwarded parameter's type, evident from a direct use: assigned to state, or passed to a callback. */
    String parameterType(String name,List statements) {
        def types=statements.collectMany { KtExpression s ->
            if(s instanceof KtBinaryExpression && s.right instanceof KtNameReferenceExpression && s.right.getReferencedName()==name)
                return [scope[((KtNameReferenceExpression)s.left).getReferencedName()]?.type]
            if(s instanceof KtCallExpression && callbacks[s.calleeExpression.text]) {
                def expected=dev.dootah.portable.PortableProgram.callbackArguments(callbacks[s.calleeExpression.text].type)
                return s.valueArguments.withIndex().findAll { a,i -> a.argumentExpression instanceof KtNameReferenceExpression &&
                    a.argumentExpression.getReferencedName()==name }.collect { a,i -> expected[i] }
            }
            []
        }.unique()
        require(types.size()==1 && types[0] in ['String','Int','Boolean'],'Callback parameter type is not evident from its use')
        types[0]
    }
    static Map arguments(KtCallExpression call,String first=null) {
        def result=[:]
        call.valueArguments.findAll { !(it instanceof KtLambdaArgument) }.eachWithIndex { a,i ->
            require(!a.spreadElement,'Spread argument unsupported')
            def name=a.argumentName?.asName?.asString()
            if(name==null) { require(i==0 && first!=null,'Named arguments required'); name=first }
            require(!result.containsKey(name),'Duplicate argument')
            result[name]=a.argumentExpression
        }
        result
    }
    String alignment(KtExpression expression) {
        imported('Alignment')
        require(expression.text in dev.dootah.portable.UiTree.ALIGNMENTS.collect { 'Alignment.'+it },'Unsupported alignment')
        expression.text.substring('Alignment.'.length())
    }
    String theme(KtExpression expression,String group,List tokens) {
        if(expression==null) return null
        imported('MaterialTheme')
        String prefix='MaterialTheme.'+group+'.'
        require(expression.text in tokens.collect { prefix+it },'Unsupported theme member token')
        expression.text.substring(prefix.length())
    }
    List modifiers(KtExpression expression,boolean boxScope) {
        if(expression==null) return []
        imported('Modifier')
        def reversed=[]
        while(expression instanceof KtDotQualifiedExpression) {
            require(reversed.size()<8,'Modifier count')
            def call=expression.selectorExpression
            require(call instanceof KtCallExpression && call.typeArguments.empty && call.lambdaArguments.empty,'Unsupported modifier')
            String op=call.calleeExpression.text
            if(op=='fillMaxWidth') {
                imported(op); require(call.valueArguments.empty,'Only default fillMaxWidth fraction supported')
                reversed << [op:op]
            } else if(op=='align') {
                require(boxScope && !entry.imports.any { it.endsWith('.align') },'align requires unambiguous BoxScope member')
                def args=arguments(call,'alignment')
                require(args.keySet()==['alignment'] as Set,'align arguments')
                reversed << [op:op,alignment:alignment(args.alignment)]
            } else if(op=='padding') {
                imported(op)
                def args=arguments(call,'all')
                def names=args.keySet()
                require(!names.empty && (names==['all'] as Set || names.every { it in ['horizontal','vertical'] } ||
                    names.every { it in ['start','top','end','bottom'] }),'Unsupported padding overload')
                def values=args.collectEntries { k,v -> [(k):dimension(v)] }
                reversed << [op:op,start:values.all ?: values.horizontal ?: values.start ?: 0,
                    top:values.all ?: values.vertical ?: values.top ?: 0,
                    end:values.all ?: values.horizontal ?: values.end ?: 0,
                    bottom:values.all ?: values.vertical ?: values.bottom ?: 0]
            } else throw new IllegalArgumentException('Unsupported modifier: '+op)
            expression=expression.receiverExpression
        }
        require(expression instanceof KtNameReferenceExpression && expression.text=='Modifier','Modifier chain root')
        reversed.reverse()
    }
    int dimension(KtExpression expression) {
        if(expression instanceof KtNameReferenceExpression) {
            String name=expression.getReferencedName()
            require(!scope.containsKey(name) && constants.containsKey(name),'Unknown frozen same-package Dp constant')
            def value=constants[name]
            require(!value.privateFile || value.privateFile==entry.file.name,'Private Dp constant from another file')
            require(!entry.imports.any { it.endsWith('.'+name) },'Imported Dp constant unsupported')
            return value.value as int
        }
        imported('dp')
        literalDp(expression)
    }
    static int literalDp(KtExpression expression) {
        require(expression instanceof KtDotQualifiedExpression && expression.selectorExpression.text=='dp' &&
            expression.receiverExpression instanceof KtConstantExpression &&
            expression.receiverExpression.text==~ /0|[1-9][0-9]{0,3}/,'Only bounded integer Dp literals supported')
        int n=Integer.parseInt(expression.receiverExpression.text)
        require(n<=1024,'Dp range'); n
    }
    /** Called only on retained source. Unsupported properties never become constants. */
    static Map constants(KtFile file, Set packageNames) {
        def result=[:]
        if('dp' in packageNames) return result
        def imports=file.importDirectives.collect { it.importPath?.pathStr }
        if(file.importDirectives.any { it.aliasName!=null } || !('androidx.compose.ui.unit.dp' in imports) ||
            imports.any { it.contains('*') || (it.endsWith('.dp') && it!='androidx.compose.ui.unit.dp') }) return result
        file.declarations.findAll { it instanceof KtProperty }.each { KtProperty p ->
            if(p.isVar() || p.hasDelegate() || p.getter!=null || p.setter!=null || p.receiverTypeReference!=null ||
                p.typeReference!=null || !p.annotationEntries.empty ||
                p.modifierList?.text?.trim() && !(p.modifierList.text.trim() in ['internal','private','public'])) return
            try { result[p.name]=[value:literalDp(p.initializer),privateFile:p.hasModifier(KtTokens.PRIVATE_KEYWORD)?file.name:null] }
            catch(IllegalArgumentException ignored) { }
        }
        result
    }
}
