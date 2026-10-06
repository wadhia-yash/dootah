package dev.dootah.publish

import org.jetbrains.kotlin.psi.*
import static dev.dootah.publish.SourceAnalyzer.require

/** Closed PSI grammar. Locals and acyclic local helper calls are expanded into typed expressions. */
class LogicLowering {
    static final Map OPS = ['+':'add','-':'sub','*':'mul','/':'div','%':'rem','<':'lt','<=':'le','>':'gt','>=':'ge',
        '==':'eq','!=':'ne','&&':'and','||':'or']
    int nodes
    Map helpers = [:]
    Set active = [] as Set
    Map entry

    static Map lower(Map entry) {
        def lowering = new LogicLowering(entry:entry)
        require(entry.function.receiverTypeReference == null, 'Extension input remains native in logic ABI 1')
        require(!entry.imports.any { it?.contains('*') && it != 'androidx.compose.runtime.*' }, 'Wildcard imports require semantic resolution')
        require(!entry.file.declarations.any { it.name in ['androidx','BasicText','String','Int','Boolean'] }, 'Shadowed builtin/rendering name')
        require(entry.container == null || !entry.container.declarations.any { it.name in ['androidx','BasicText','String','Int','Boolean'] }, 'Shadowed member symbol')
        def scope=[:]
        def types=[]
        entry.function.valueParameters.eachWithIndex { p,i ->
            def t=p.typeReference.text.replace('kotlin.','')
            require(t in ['String','Int','Boolean','String?','Int?','Boolean?'], 'Unsupported portable parameter')
            types << t; scope[p.name]=[type:t,node:[op:'input',index:i],cost:1]
        }
        def result=lowering.render(entry.function.bodyExpression,scope,0)
        require(result.type=='String','Renderer requires String')
        [parameters:types,expression:result.node]
    }
    Map render(KtExpression e,Map scope,int depth) {
        require(depth<=20,'Nesting')
        if(e instanceof KtBlockExpression) return block(e,scope,depth,true)
        if(e instanceof KtIfExpression) {
            require(e.else != null,'Missing else')
            return operation('if',[expr(e.condition,scope,depth+1),render(e.then,scope,depth+1),render(e.else,scope,depth+1)])
        }
        KtCallExpression call
        if(e instanceof KtDotQualifiedExpression) {
            require(e.receiverExpression.text=='androidx.compose.foundation.text','Unknown qualified call')
            require(!scope.containsKey('androidx'),'Shadowed package root')
            call=e.selectorExpression instanceof KtCallExpression?e.selectorExpression:null
        } else if(e instanceof KtCallExpression) {
            require('androidx.compose.foundation.text.BasicText' in entry.imports &&
                !entry.imports.any { it?.endsWith('.BasicText') && it!='androidx.compose.foundation.text.BasicText' },'Explicit BasicText import required')
            require(!scope.containsKey('BasicText') && !helpers.containsKey('BasicText'),'Shadowed renderer')
            call=e
        }
        require(call!=null && call.calleeExpression.text=='BasicText' && call.typeArguments.empty && call.lambdaArguments.empty && call.valueArguments.size()==1,'Only BasicText(text) rendering installed')
        def arg=call.valueArguments.first()
        require(!arg.spreadElement && (!arg.argumentName || arg.argumentName.asName.asString()=='text'),'Unsupported render argument')
        expr(arg.argumentExpression,scope,depth+1)
    }
    Map block(KtBlockExpression e,Map outer,int depth,boolean rendering) {
        require(e.statements.size() in 1..16,'Block size')
        def scope=new LinkedHashMap(outer)
        def saved=new LinkedHashMap(helpers)
        def initializers=[]
        try {
            e.statements.dropRight(1).each { s ->
                require(s instanceof KtProperty || s instanceof KtNamedFunction, 'Only immutable locals/helpers')
                require(s.name != 'androidx' && !scope.containsKey(s.name) && !helpers.containsKey(s.name),'Local shadowing unsupported')
                if(s instanceof KtNamedFunction) {
                    require(s.modifierList==null && s.typeParameters.empty && s.receiverTypeReference==null && s.typeReference!=null,'Helper form')
                    require(s.valueParameters.every { !it.hasDefaultValue() && !it.isVarArg() && it.typeReference!=null && it.annotationEntries.empty },'Helper parameters')
                    helpers[s.name]=[function:s,scope:new LinkedHashMap(scope)]
                } else {
                    require(s instanceof KtProperty && !s.isVar() && !s.hasDelegate() && s.modifierList==null,'Only immutable locals/helpers')
                    def declared=s.typeReference?.text?.replace('kotlin.','')
                    def value=expr(s.initializer,scope,depth+1,declared)
                    require(declared==null || declared==value.type,'Local type mismatch')
                    scope[s.name]=value
                    initializers << value
                }
            }
            def last=e.statements.last()
            if(!rendering && last instanceof KtReturnExpression) {require(last.getTargetLabel()==null,'Labelled return');last=last.returnedExpression}
            def result=rendering?render(last,scope,depth+1):expr(last,scope,depth+1)
            return initializers.empty ? result : operation('seq',initializers+[result])
        } finally {helpers=saved}
    }
    Map expr(KtExpression e,Map scope,int depth,String expected=null) {
        require(e!=null && depth<=20 && ++nodes<=512,'Expression/depth budget')
        if(e instanceof KtParenthesizedExpression) return expr(e.expression,scope,depth+1,expected)
        if(e instanceof KtBlockExpression) return block(e,scope,depth+1,false)
        if(e instanceof KtConstantExpression) {
            if(e.text in ['true','false']) return literal('Boolean',e.text=='true')
            if(e.text=='null') {require(expected?.endsWith('?'),'Null requires explicit nullable type');return literal(expected,null)}
            require(e.text==~ /0|[1-9][0-9]{0,9}/,'Only decimal Int literals')
            long v=Long.parseLong(e.text);require(v<=Integer.MAX_VALUE,'Int range');return literal('Int',(int)v)
        }
        if(e instanceof KtStringTemplateExpression) {
            require(!e.text.startsWith('"""'),'Raw strings remain native')
            def parts=e.entries.collect { p ->
                if(p instanceof KtLiteralStringTemplateEntry) return literal('String',p.text)
                if(p instanceof KtEscapeStringTemplateEntry) return literal('String',p.unescapedValue)
                if(p instanceof KtStringTemplateEntryWithExpression) return operation('string',[expr(p.expression,scope,depth+1)])
                throw new IllegalArgumentException('String entry')
            }
            return parts.inject(literal('String','')) {a,b -> operation('concat',[a,b])}
        }
        if(e instanceof KtNameReferenceExpression) {require(scope.containsKey(e.getReferencedName()),'Unknown value');return scope[e.getReferencedName()]}
        if(e instanceof KtPrefixExpression) {
            if(e.operationReference.text=='-' && e.baseExpression.text=='2147483648') return literal('Int',Integer.MIN_VALUE)
            require(e.operationReference.text in ['!','-'],'Unary operation')
            return operation(e.operationReference.text=='!'?'not':'neg',[expr(e.baseExpression,scope,depth+1)])
        }
        if(e instanceof KtBinaryExpression) {
            def token=e.operationReference.text;require(OPS.containsKey(token),'Binary operation')
            def left,right
            if(e.left.text=='null') {right=expr(e.right,scope,depth+1);left=expr(e.left,scope,depth+1,right.type)}
            else {left=expr(e.left,scope,depth+1);right=expr(e.right,scope,depth+1,left.type)}
            if(token=='+' && left.type=='String') return operation('concat',[left,operation('string',[right])])
            return operation(OPS[token],[left,right])
        }
        if(e instanceof KtIfExpression) {
            require(e.else!=null,'Missing else')
            return operation('if',[expr(e.condition,scope,depth+1),expr(e.then,scope,depth+1,expected),expr(e.else,scope,depth+1,expected)])
        }
        if(e instanceof KtWhenExpression) {
            require(e.entries.size() in 2..8 && e.entries.last().isElse() && !e.entries.dropRight(1).any {it.isElse()},'Bounded exhaustive when requires final else')
            def subject=e.subjectExpression==null?null:expr(e.subjectExpression,scope,depth+1)
            def result=expr(e.entries.last().expression,scope,depth+1,expected)
            e.entries.dropRight(1).reverseEach { branch ->
                require(branch.conditions.size()==1 && branch.conditions.first() instanceof KtWhenConditionWithExpression,'Single value/Boolean when conditions only')
                def test=expr(branch.conditions.first().expression,scope,depth+1,subject?.type)
                if(subject!=null)test=operation('eq',[subject,test])
                result=operation('if',[test,expr(branch.expression,scope,depth+1,expected),result])
            }
            return result
        }
        if(e instanceof KtCallExpression) {
            String name=e.calleeExpression.text
            require(helpers.containsKey(name) && !active.contains(name) && active.size()<8 && e.typeArguments.empty && e.lambdaArguments.empty,'Unknown or recursive helper')
            def h=helpers[name], f=h.function
            require(e.valueArguments.size()==f.valueParameters.size(),'Helper arity')
            def values=e.valueArguments.collect {a -> require(!a.spreadElement && !a.argumentName,'Helper positional arguments only');expr(a.argumentExpression,scope,depth+1)}
            def local=new LinkedHashMap(h.scope)
            f.valueParameters.eachWithIndex {p,i ->require(values[i].type==p.typeReference.text.replace('kotlin.',''),'Helper input type');local[p.name]=values[i]}
            active.add(name)
            try {def result=expr(f.bodyExpression,local,depth+1,f.typeReference.text.replace('kotlin.',''));require(result.type==f.typeReference.text.replace('kotlin.',''),'Helper return type');return values.empty?result:operation('seq',values+[result])}
            finally {active.remove(name)}
        }
        throw new IllegalArgumentException('Unsupported expression: '+e.class.simpleName)
    }
    static Map literal(String type,Object value) {[type:type,node:[op:'literal',type:type,value:value],cost:1]}
    static Map operation(String op,List a) {
        int cost=1+a.sum{it.cost}
        require(cost<=512,'Expanded operation budget')
        def type
        switch(op) {
            case 'seq':type=a.last().type;break
            case 'string':type='String';break
            case 'concat':require(a.every{it.type=='String'},'String operands');type='String';break
            case 'if':require(a[0].type=='Boolean' && a[1].type==a[2].type,'Branch types');type=a[1].type;break
            case ['not','and','or']:require(a.every{it.type=='Boolean'},'Boolean operands');type='Boolean';break
            case ['eq','ne']:require(a[0].type.replace('?','')==a[1].type.replace('?',''),'Equality types');type='Boolean';break
            default:require(a.every{it.type=='Int'},'Int operands');type=op in ['lt','le','gt','ge']?'Boolean':'Int'
        }
        [type:type,node:[op:op,args:a*.node],cost:cost]
    }
}
