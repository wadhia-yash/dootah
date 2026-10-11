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
    Map entry, constants, adapters, scope=[:]
    List slots=[]
    LogicLowering logic
    int nodes

    static Map lower(Map entry, Map constants=[:], Set packageNames=[] as Set, Map adapters=[:]) {
        require(entry.id!=null,entry.reason ?: 'Unproven source identity')
        require(entry.container==null && entry.function.receiverTypeReference==null,'Structural UI requires a top-level scalar hook')
        require(!entry.imports.any { it.contains('*') && it!='androidx.compose.runtime.*' },'Wildcard imports require semantic resolution')
        require((packageNames.intersect(RESERVED)).empty,'Shadowed UI symbol in package')
        require(!entry.file.declarations.any { it.name in RESERVED },'Shadowed UI symbol')
        def l=new UiLowering(entry:entry,constants:constants,adapters:adapters,logic:new LogicLowering(entry:entry))
        def types=[]
        entry.function.valueParameters.eachWithIndex { p,i ->
            String t=p.typeReference.text.replace('kotlin.','')
            require(t in ['String','Int','Boolean','String?','Int?','Boolean?'] && !(p.name in RESERVED),'Unsupported/shadowed UI input')
            types << t; l.scope[p.name]=[type:t,node:[op:'input',index:i],cost:1]
        }
        def body=entry.function.bodyExpression
        if(body instanceof KtBlockExpression) {
            require(body.statements.size()==1,'Structural body requires one root statement')
            body=body.statements.first()
        }
        [parameters:types, slots:l.slots, tree:l.node(body,0,false)]
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
        KtNamedFunction function=entry.function
        require(AdapterGen.statementInHook(call,function),'Native component call must be a direct statement of the hook body')
        def names=AdapterGen.scope(function), hidden=AdapterGen.privateNames(entry.file)
        def typer={ KtExpression e -> logic.expr(e,scope,0).type } as java.util.function.Function
        if(call.lambdaArguments.empty) {
            def shape=AdapterGen.shape(call,entry.file,entry.path,names,hidden,typer)
            installed(shape)
            return [type:'native',adapter:shape.id,arguments:dataSlots(shape)]
        }
        // A container: its installed content lambda renders the children this update selects and orders.
        def shape=AdapterGen.containerShape(call,entry.file,entry.path,names,hidden,typer)
        installed(shape)
        def arguments=dataSlots(shape)
        [type:'native',adapter:shape.id,arguments:arguments,
            children:content(AdapterGen.contentBody(call).statements,shape,names,hidden,typer)]
    }
    void installed(AdapterGen.Shape shape) {
        require(adapters[shape.id]==shape.signature(),'No installed native adapter for this '+shape.callee+' call; a new APK is required')
    }
    List dataSlots(AdapterGen.Shape shape) { shape.arguments.findAll { it.type!=null }.collect { slot(it.expression,it.type) } }
    List content(List statements,AdapterGen.Shape container,Set names,Set hidden,typer) {
        statements.collect { KtExpression statement ->
            require(++nodes<=64,'UI tree budget')
            if(statement instanceof KtIfExpression) {
                def branch={ KtExpression e -> e==null ? [] : content(e instanceof KtBlockExpression ? e.statements : [e],container,names,hidden,typer) }
                return [type:'if',condition:slot(statement.condition,'Boolean'),then:branch(statement.then),else:branch(statement.else)]
            }
            require(statement instanceof KtCallExpression && AdapterGen.childCandidate(statement),'Unsupported content statement')
            def child=AdapterGen.childShape(container,statement,entry.file,names,hidden,typer)
            installed(child)
            [type:'native',adapter:child.id,arguments:dataSlots(child)]
        }
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
