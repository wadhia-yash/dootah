package dev.dootah.publish

import dev.dootah.identity.FunctionIdentity
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

/** A closed syntactic subset, not speculative Kotlin symbol resolution. */
class SourceAnalyzer implements Closeable {
    private final disposable = Disposer.newDisposable()
    private final environment = KotlinCoreEnvironment.createForProduction(disposable,
        new CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES)
    private final factory = new KtPsiFactory(environment.project, false)
    private static final Map TYPES = [Boolean:'Z', Byte:'B', Char:'C', Short:'S', Int:'I',
        Float:'F', Long:'J', Double:'D', String:'Ljava/lang/String;']

    static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason)
    }

    Map inspect(String path, String source) {
        require(source.length() <= 1_000_000, 'Source exceeds parser bound')
        def file = factory.createFile(new File(path).name, source)
        require(PsiTreeUtil.findChildOfType(file, PsiErrorElement) == null, "Kotlin syntax error: $path")
        def functions = []
        collect(file.declarations, null, functions)
        def imports = file.importDirectives.collect { it.importPath?.pathStr }
        def entries = functions.findAll { item ->
            item.function.annotationEntries.any {
                def name = it.typeReference?.text
                name == 'androidx.compose.runtime.Composable' || (name == 'Composable' &&
                    ('androidx.compose.runtime.Composable' in imports || 'androidx.compose.runtime.*' in imports))
            }
        }.collect { item ->
            KtNamedFunction f = item.function
            def result = [name:f.name, start:f.bodyExpression?.textRange?.startOffset,
                end:f.bodyExpression?.textRange?.endOffset, body:f.bodyExpression?.text,
                function:f, container:item.container, file:file, imports:imports]
            try { result.putAll(identity(file, f, item.container, imports)) }
            catch (IllegalArgumentException e) { result.reason = e.message }
            result
        }
        // Anything outside a declared composable body must match the installed snapshot.
        // This freezes imports, types, defaults, properties, ordinary functions, and call sites.
        String skeleton = source
        entries.findAll { it.start != null }.sort { -it.start }.each {
            skeleton = skeleton.substring(0, it.start) + '<DOOTAH-BODY>' + skeleton.substring(it.end)
        }
        def hazards = PsiTreeUtil.findChildrenOfType(file, KtNamedDeclaration).findAll {
            it.name in ['androidx', 'BasicText', 'Composable']
        }*.name
        [skeleton:skeleton, entries:entries, hazards:hazards]
    }

    private static void collect(List declarations, KtClass container, List result) {
        declarations.each { d ->
            if (d instanceof KtNamedFunction) result << [function:d, container:container]
            else if (d instanceof KtClass) {
                // Nested, companion, object and local forms are deliberately not inferred.
                d.declarations.findAll { it instanceof KtNamedFunction }.each {
                    result << [function:it, container:d]
                }
            }
        }
    }

    private static Map identity(KtFile file, KtNamedFunction f, KtClass owner, List imports) {
        require(file.annotationEntries.empty, 'File annotations/JVM facade overrides unsupported')
        require(!file.importDirectives.any { it.aliasName != null }, 'Import aliases require semantic resolution')
        require(file.name ==~ /[A-Za-z_][A-Za-z0-9_]*\.kt/, 'Unrecognized JVM facade name')
        require(f.name ==~ /[A-Za-z_][A-Za-z0-9_]*/, 'Escaped/mangled name unsupported')
        require(f.typeParameters.empty && !f.hasModifier(KtTokens.SUSPEND_KEYWORD) &&
            !f.hasModifier(KtTokens.INLINE_KEYWORD) && !f.hasModifier(KtTokens.OVERRIDE_KEYWORD),
            'Generic/inline/suspend/override declaration remains native')
        require(f.annotationEntries.size() == 1, 'Additional function annotations unsupported')
        require(f.typeReference == null || f.typeReference.text in ['Unit','kotlin.Unit'], 'Value return unsupported')
        require(f.bodyExpression != null, 'No body')
        if (owner != null) {
            require(owner.parent instanceof KtFile && !owner.isInterface() && owner.typeParameters.empty &&
                owner.superTypeListEntries.empty && owner.annotationEntries.empty &&
                !owner.hasModifier(KtTokens.INNER_KEYWORD) && !owner.hasModifier(KtTokens.DATA_KEYWORD) &&
                !f.hasModifier(KtTokens.INTERNAL_KEYWORD), 'Uncertain member JVM form remains native')
        }
        def refs = (f.receiverTypeReference == null ? [] : [f.receiverTypeReference]) + f.valueParameters.collect { it.typeReference }
        require(refs.size() <= 9 && f.valueParameters.every {
            !it.isVarArg() && it.annotationEntries.empty && it.name != 'androidx'
        }, 'Unsupported parameter form')
        def descriptors = refs.collect { ref ->
            require(ref != null, 'Parameter type required')
            String name = ref.text
            boolean nullable = name.endsWith('?')
            String base = nullable ? name[0..-2] : name
            base = base.startsWith('kotlin.') ? base.substring(7) : base
            require(TYPES.containsKey(base) && (!nullable || base in ['String','Int','Boolean']), 'Only primitive/nonboxed or String parameters supported')
            require(!imports.any { it != "kotlin.$base" && (it.endsWith(".$base") || it.contains(' as ')) }, 'Ambiguous type import')
            require(!file.declarations.any { it.name == base }, 'Shadowed builtin type')
            nullable && base == 'Int' ? 'Ljava/lang/Integer;' : nullable && base == 'Boolean' ? 'Ljava/lang/Boolean;' : TYPES[base]
        }
        String descriptor = '(' + descriptors.join('') + 'Landroidx/compose/runtime/Composer;I' +
            (f.valueParameters.any { it.hasDefaultValue() } ? 'I' : '') + ')V'
        String pkg = file.packageFqName.asString().replace('.', '/')
        String className = owner == null ? file.name[0..-4] + 'Kt' : owner.name
        String jvmOwner = (pkg ? pkg + '/' : '') + className
        String nullability = refs.collect { it.text.endsWith('?') ? '?' : '!' }.join('')
        [owner:jvmOwner, method:f.name, descriptor:descriptor, isStatic:owner == null,
            extension:f.receiverTypeReference != null, nullability:nullability,
            id:FunctionIdentity.of(jvmOwner, f.name, descriptor, owner == null,
                f.receiverTypeReference != null, null, nullability)]
    }

    Map lower(Map entry) {
        require(entry.id != null, entry.reason ?: 'Unproven identity')
        require(!entry.imports.any { it?.endsWith('.androidx') ||
            (it?.contains('*') && it != 'androidx.compose.runtime.*') ||
            (it?.endsWith('.BasicText') && it != 'androidx.compose.foundation.text.BasicText') },
            'Ambiguous rendering import')
        require(entry.container == null || entry.container.declarations.every {
            !(it instanceof KtProperty) || it.name != 'androidx'
        }, 'Shadowed package root')
        require(!entry.file.declarations.any { it.name in ['androidx', 'BasicText'] }, 'Shadowed rendering symbol')
        // All branches are checked, even when a constant condition makes one unreachable.
        return tree(entry.function.bodyExpression, [:], 0, entry)
    }

    private static Map tree(KtExpression expression, Map constants, int depth, Map entry) {
        require(depth < 12, 'Tree nesting exceeds bound')
        if (expression instanceof KtBlockExpression) {
            def statements = expression.statements
            require(statements.size() in 1..16, 'Expected bounded constants followed by one text expression')
            def scope = new LinkedHashMap(constants)
            statements.dropRight(1).each { s ->
                require(s instanceof KtProperty && s.modifierList == null && !s.isVar() && !s.hasDelegate() && s.typeReference == null &&
                    s.annotationEntries.empty && s.name != 'androidx' && !scope.containsKey(s.name) &&
                    !entry.function.valueParameters.any { it.name == s.name }, 'Only untyped immutable local constants supported')
                scope[s.name] = value(s.initializer, scope)
            }
            return tree(statements.last(), scope, depth + 1, entry)
        }
        if (expression instanceof KtIfExpression) {
            def condition = value(expression.condition, constants)
            require(condition instanceof Boolean && expression.else != null, 'Only constant Boolean if/else supported')
            def yes = tree(expression.then, constants, depth + 1, entry)
            def no = tree(expression.else, constants, depth + 1, entry)
            return condition ? yes : no
        }
        KtCallExpression call
        if (expression instanceof KtDotQualifiedExpression) {
            require(expression.receiverExpression.text == 'androidx.compose.foundation.text', 'Unsupported qualified call')
            call = expression.selectorExpression instanceof KtCallExpression ? expression.selectorExpression : null
        } else if (expression instanceof KtCallExpression) {
            require(expression.calleeExpression.text == 'BasicText', 'Only BasicText matches the installed renderer; Material Text and other calls remain native')
            require('androidx.compose.foundation.text.BasicText' in entry.imports &&
                !entry.imports.any { it?.contains('*') && it != 'androidx.compose.runtime.*' }, 'BasicText requires an unambiguous explicit import')
            call = expression
        }
        require(call != null && call.calleeExpression.text == 'BasicText' && call.typeArguments.empty &&
            call.lambdaArguments.empty && call.valueArguments.size() == 1, 'Only Compose BasicText(text) matches the installed renderer; other calls remain native')
        def argument = call.valueArguments.first()
        require(!argument.spreadElement && (!argument.argumentName || argument.argumentName.asName.asString() == 'text'), 'Only the text argument supported')
        def text = value(argument.argumentExpression, constants)
        require(text instanceof String && !text.isBlank() && text.length() <= 256, 'Text must be a nonblank constant String of at most 256 UTF-16 units')
        [type:'text', value:[type:'string', value:text]]
    }

    private static Object value(KtExpression expression, Map constants) {
        if (expression instanceof KtParenthesizedExpression) return value(expression.expression, constants)
        if (expression instanceof KtStringTemplateExpression) {
            return expression.entries.collect { e ->
                if (e instanceof KtLiteralStringTemplateEntry) return e.text
                if (e instanceof KtEscapeStringTemplateEntry) {
                    require(e.text ==~ /\\([tbnr'"\\$]|u[0-9a-fA-F]{4})/, 'Unsupported or invalid String escape')
                    return e.unescapedValue
                }
                throw new IllegalArgumentException('String interpolation/parameter/state access is not installed')
            }.join('')
        }
        if (expression instanceof KtConstantExpression) {
            if (expression.text == 'true') return true
            if (expression.text == 'false') return false
            if (expression.text ==~ /0|[1-9][0-9]{0,8}/) return Integer.valueOf(expression.text)
        }
        if (expression instanceof KtNameReferenceExpression && constants.containsKey(expression.getReferencedName())) {
            return constants[expression.getReferencedName()]
        }
        throw new IllegalArgumentException('Only literal String/Boolean/Int and local constant references supported; parameters/state stay native')
    }

    @Override void close() { Disposer.dispose(disposable) }
}
