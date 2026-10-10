package dev.dootah.adapters;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.psi.*;

/**
 * Native adapters: ordinary Kotlin, generated at build time and compiled by the application's own
 * Kotlin/Compose compiler. Shared by the Gradle plugin (generation) and the publisher (lowering),
 * so both sides derive the same identity from the same call. No compiler mutation, no reflection.
 *
 * An adapter is a copy of one real call that an eligible scalar hook body makes to an app-owned
 * composable. Arguments that read the hook's data become typed adapter parameters; every other
 * argument is copied verbatim and stays frozen in the APK, and omitted arguments keep their Kotlin
 * defaults. Selection is syntactic and deliberately narrow; anything doubtful produces no adapter.
 */
public final class AdapterGen {
    public static final String INDEX_PACKAGE = AdapterNames.INDEX_PACKAGE, INDEX = AdapterNames.INDEX;
    public static final String METHOD_PREFIX = AdapterNames.METHOD_PREFIX, FACADE_PREFIX = AdapterNames.FACADE_PREFIX,
        REGISTRY_PREFIX = AdapterNames.REGISTRY_PREFIX;
    public static final String ID_PATTERN = "[A-Za-z_][A-Za-z0-9_.]{0,255}#[0-9a-f]{16}";
    /** Lowered structurally by the publisher instead. */
    public static final Set<String> STRUCTURAL = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("Box", "Text")));
    public static final List<String> SCALARS = Collections.unmodifiableList(Arrays.asList("String", "Int", "Boolean", "String?", "Int?", "Boolean?"));
    public static final int MAX_PARAMETERS = 9, MAX_ARGUMENTS = 32;

    private AdapterGen() {}

    public static final class Argument {
        public final String name, frozen, type;
        public final KtExpression expression;
        Argument(String name, String frozen, String type, KtExpression expression) {
            this.name = name; this.frozen = frozen; this.type = type; this.expression = expression;
        }
    }

    public static final class Shape {
        public final String id, callee, method, call;
        public final List<Argument> arguments;
        Shape(String id, String callee, String method, String call, List<Argument> arguments) {
            this.id = id; this.callee = callee; this.method = method; this.call = call; this.arguments = arguments;
        }
        public List<String> types() {
            List<String> types = new ArrayList<>();
            for (Argument a : arguments) if (a.type != null) types.add(a.type);
            return types;
        }
        public String signature() { return String.join(",", types()); }
        /** JVM name the adapter body must invoke (alias imports resolve to the declared name). */
        public String calleeName() { return callee.substring(callee.lastIndexOf('.') + 1); }
    }

    public static final class Result {
        public final String path, packageName, facade, registry;
        public final List<Shape> adapters;
        public final List<String> refused;
        Result(String path, String packageName, String facade, String registry, List<Shape> adapters, List<String> refused) {
            this.path = path; this.packageName = packageName; this.facade = facade; this.registry = registry;
            this.adapters = adapters; this.refused = refused;
        }
        public String qualifiedRegistry() { return packageName.isEmpty() ? registry : packageName + "." + registry; }
        public String qualifiedFacade() { return packageName.isEmpty() ? facade : packageName + "." + facade; }
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }

    /** Module-relative source path with '/' separators; part of every identity in the file. */
    public static String fileHash(String path) { return sha256("dootah-adapter-file-v1|" + path).substring(0, 12); }

    /** Generates the adapters for one source file. Refusals are reported, never thrown. */
    public static Result generate(KtFile file, String path) {
        String pkg = file.getPackageFqName().asString();
        String hash = fileHash(path);
        Map<String, Shape> adapters = new TreeMap<>();
        List<String> refused = new ArrayList<>();
        Set<String> privateNames = privateNames(file);
        for (KtNamedFunction function : hooks(file)) {
            Map<String, String> inputs = inputs(function);
            Set<String> scope = scope(function);
            List<KtCallExpression> calls = new ArrayList<>(PsiTreeUtil.findChildrenOfType(function.getBodyExpression(), KtCallExpression.class));
            if (function.getBodyExpression() instanceof KtCallExpression) calls.add(0, (KtCallExpression) function.getBodyExpression());
            for (KtCallExpression call : calls) {
                if (!candidate(call) || !statementPosition(call, function)) continue;
                try {
                    require(statementInHook(call, function), "Not a direct statement of the hook body (lambda scope or nested expression)");
                    Shape shape = shape(call, file, path, scope, privateNames, e -> dataType(e, inputs));
                    adapters.putIfAbsent(shape.id, shape);
                } catch (IllegalArgumentException e) {
                    refused.add(function.getName() + ": " + call.getCalleeExpression().getText() + ": " + e.getMessage());
                }
            }
        }
        return new Result(path, pkg, FACADE_PREFIX + hash, REGISTRY_PREFIX + hash,
            Collections.unmodifiableList(new ArrayList<>(adapters.values())), Collections.unmodifiableList(refused));
    }

    /** A capitalised call outside the structural vocabulary, written as `Name(...)`. */
    public static boolean candidate(KtCallExpression call) {
        if (!(call.getCalleeExpression() instanceof KtNameReferenceExpression)) return false;
        String name = ((KtNameReferenceExpression) call.getCalleeExpression()).getReferencedName();
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0)) && !STRUCTURAL.contains(name)
            && !(call.getParent() instanceof KtDotQualifiedExpression || call.getParent() instanceof KtSafeQualifiedExpression);
    }

    /** Top-level, non-extension, non-generic composables with at most nine scalar inputs: the hookable form. */
    public static List<KtNamedFunction> hooks(KtFile file) {
        List<String> imports = new ArrayList<>();
        for (KtImportDirective d : file.getImportDirectives()) if (d.getImportPath() != null) imports.add(d.getImportPath().getPathStr());
        List<KtNamedFunction> result = new ArrayList<>();
        for (KtDeclaration declaration : file.getDeclarations()) {
            if (!(declaration instanceof KtNamedFunction)) continue;
            KtNamedFunction f = (KtNamedFunction) declaration;
            if (f.getBodyExpression() == null || f.getReceiverTypeReference() != null || !f.getTypeParameters().isEmpty()
                || f.hasModifier(KtTokens.INLINE_KEYWORD) || f.hasModifier(KtTokens.SUSPEND_KEYWORD)
                || f.getValueParameters().size() > MAX_PARAMETERS || !composable(f, imports)) continue;
            boolean scalar = true;
            for (KtParameter p : f.getValueParameters()) {
                if (p.isVarArg() || p.getTypeReference() == null || !SCALARS.contains(type(p.getTypeReference().getText()))) scalar = false;
            }
            if (scalar) result.add(f);
        }
        return result;
    }

    private static boolean composable(KtNamedFunction f, List<String> imports) {
        for (KtAnnotationEntry a : f.getAnnotationEntries()) {
            String name = a.getTypeReference() == null ? null : a.getTypeReference().getText();
            if ("androidx.compose.runtime.Composable".equals(name) || ("Composable".equals(name) &&
                (imports.contains("androidx.compose.runtime.Composable") || imports.contains("androidx.compose.runtime.*")))) return true;
        }
        return false;
    }

    static String type(String text) { return text.replace("kotlin.", "").replace(" ", ""); }

    public static Map<String, String> inputs(KtNamedFunction function) {
        Map<String, String> inputs = new LinkedHashMap<>();
        for (KtParameter p : function.getValueParameters()) inputs.put(p.getName(), type(p.getTypeReference().getText()));
        return inputs;
    }

    /** Every name the hook body or its parameters bind; an argument naming one of these reads hook data. */
    public static Set<String> scope(KtNamedFunction function) {
        Set<String> scope = new HashSet<>(inputs(function).keySet());
        for (KtNamedDeclaration d : PsiTreeUtil.findChildrenOfType(function.getBodyExpression(), KtNamedDeclaration.class)) {
            if (d.getName() != null) scope.add(d.getName());
        }
        return scope;
    }

    /** File-private top-level names cannot be reached from the generated file. */
    public static Set<String> privateNames(KtFile file) {
        Set<String> names = new HashSet<>();
        for (KtDeclaration d : file.getDeclarations()) {
            if (d instanceof KtNamedDeclaration && d.hasModifier(KtTokens.PRIVATE_KEYWORD) && ((KtNamedDeclaration) d).getName() != null) {
                names.add(((KtNamedDeclaration) d).getName());
            }
        }
        return names;
    }

    /** Written as a statement, not as an argument, condition or receiver. */
    public static boolean statementPosition(KtCallExpression call, KtNamedFunction function) {
        PsiElement parent = call.getParent();
        return parent instanceof KtBlockExpression || parent instanceof KtContainerNodeForControlStructureBody
            || parent instanceof KtWhenEntry || parent == function;
    }

    /** True when every ancestor up to the hook is a block or an if/when branch: no implicit receivers. */
    public static boolean statementInHook(KtCallExpression call, KtNamedFunction function) {
        if (!statementPosition(call, function)) return false;
        for (PsiElement e = call.getParent(); e != null && e != function; e = e.getParent()) {
            if (!(e instanceof KtBlockExpression || e instanceof KtContainerNodeForControlStructureBody || e instanceof KtIfExpression
                || e instanceof KtWhenEntry || e instanceof KtWhenExpression || e instanceof KtContainerNode)) return false;
        }
        return true;
    }

    /** Generation-side data typing: only forms whose type is evident without resolution. */
    static String dataType(KtExpression expression, Map<String, String> inputs) {
        if (expression instanceof KtStringTemplateExpression) return "String";
        if (expression instanceof KtNameReferenceExpression) {
            String type = inputs.get(((KtNameReferenceExpression) expression).getReferencedName());
            require(type != null, "Reads a local value; only hook inputs and string templates become adapter parameters");
            require(!type.endsWith("?"), "Nullable input may be smart-cast at the call site");
            return type;
        }
        throw new IllegalArgumentException("Computed argument from hook data");
    }

    /** Resolves the callee to a qualified name from explicit imports or the file's own package. */
    static String callee(String name, KtFile file) {
        String found = null;
        boolean star = false;
        for (KtImportDirective d : file.getImportDirectives()) {
            if (d.getImportPath() == null) continue;
            if (d.getImportPath().isAllUnder()) { star = true; continue; }
            String visible = d.getAliasName() != null ? d.getAliasName() : d.getImportPath().getImportedName().asString();
            if (visible.equals(name)) {
                require(found == null, "Ambiguous import: " + name);
                found = d.getImportPath().getPathStr();
            }
        }
        if (found != null) return found;
        require(!star, "Wildcard imports require semantic resolution: " + name);
        String pkg = file.getPackageFqName().asString();
        return pkg.isEmpty() ? name : pkg + "." + name;
    }

    /**
     * The identity and typed contract of one call. `dataType` types each argument that reads hook
     * data: generation accepts only evident forms, while the publisher supplies the type of the
     * lowered Logic ABI 1 expression. Both see the same frozen text and therefore the same identity.
     */
    public static Shape shape(KtCallExpression call, KtFile file, String path, Set<String> scope, Set<String> privateNames,
                              Function<KtExpression, String> dataType) {
        require(candidate(call), "Not an app-owned composable call");
        String name = ((KtNameReferenceExpression) call.getCalleeExpression()).getReferencedName();
        require(!scope.contains(name) && !privateNames.contains(name), "Local or file-private callee");
        require(call.getTypeArguments().isEmpty(), "Explicit type arguments");
        require(call.getLambdaArguments().isEmpty(), "Trailing lambda or content slot");
        require(call.getValueArguments().size() <= MAX_ARGUMENTS, "Argument count");
        String callee = callee(name, file);
        List<Argument> arguments = new ArrayList<>();
        StringBuilder identity = new StringBuilder("dootah-adapter-v1|").append(path).append('|').append(callee);
        StringBuilder text = new StringBuilder(name).append('(');
        int parameters = 0;
        for (KtValueArgument argument : call.getValueArguments()) {
            KtExpression expression = argument.getArgumentExpression();
            require(expression != null && argument.getSpreadElement() == null, "Unsupported argument form");
            require(!(expression instanceof KtLambdaExpression) && !(expression instanceof KtCallableReferenceExpression)
                && !(expression instanceof KtThisExpression) && !(expression instanceof KtObjectLiteralExpression)
                && PsiTreeUtil.findChildrenOfAnyType(expression, KtLambdaExpression.class, KtNamedFunction.class,
                    KtObjectLiteralExpression.class, KtThisExpression.class, KtSuperExpression.class,
                    KtCallableReferenceExpression.class, KtReturnExpression.class, KtBreakExpression.class,
                    KtContinueExpression.class).isEmpty(), "Callbacks, receivers and object arguments are not adapter inputs");
            Set<String> names = new HashSet<>();
            if (expression instanceof KtNameReferenceExpression) names.add(((KtNameReferenceExpression) expression).getReferencedName());
            for (KtNameReferenceExpression r : PsiTreeUtil.findChildrenOfType(expression, KtNameReferenceExpression.class)) names.add(r.getReferencedName());
            require(Collections.disjoint(names, privateNames), "Argument reads a file-private declaration");
            String label = argument.isNamed() ? argument.getArgumentName().getAsName().asString() : null;
            String value;
            Argument parsed;
            if (Collections.disjoint(names, scope)) {
                value = expression.getText();
                parsed = new Argument(label, value, null, expression);
                identity.append('|').append(label == null ? "#" + arguments.size() : label).append("=F:").append(value.replaceAll("\\s+", " "));
            } else {
                String type = dataType.apply(expression);
                require(SCALARS.contains(type), "Argument is not a scalar");
                require(++parameters <= MAX_PARAMETERS, "Adapter parameter count");
                value = "a" + (parameters - 1);
                parsed = new Argument(label, null, type, expression);
                identity.append('|').append(label == null ? "#" + arguments.size() : label).append("=P:").append(type);
            }
            arguments.add(parsed);
            if (arguments.size() > 1) text.append(", ");
            text.append(label == null ? value : label + " = " + value);
        }
        String hex = sha256(identity.toString()).substring(0, 16);
        return new Shape(callee + "#" + hex, callee, METHOD_PREFIX + hex, text.append(')').toString(),
            Collections.unmodifiableList(arguments));
    }

    private static String kotlinType(String type) {
        return "kotlin." + type;
    }

    /** One generated file per source file: same package and imports, so frozen text resolves identically. */
    public static String render(KtFile file, Result result) {
        if (result.adapters.isEmpty()) return null;
        StringBuilder out = new StringBuilder();
        out.append("// Generated by Dootah from ").append(result.path).append(". Do not edit.\n");
        out.append("@file:kotlin.jvm.JvmName(\"").append(result.facade).append("\")\n");
        if (!result.packageName.isEmpty()) out.append("package ").append(result.packageName).append("\n");
        out.append('\n');
        for (KtImportDirective d : file.getImportDirectives()) out.append(d.getText().trim()).append('\n');
        out.append('\n');
        for (Shape s : result.adapters) {
            List<String> parameters = new ArrayList<>();
            List<String> types = s.types();
            for (int i = 0; i < types.size(); i++) parameters.add("a" + i + ": " + kotlinType(types.get(i)));
            out.append("@androidx.compose.runtime.Composable\nprivate fun ").append(s.method).append('(')
                .append(String.join(", ", parameters)).append(") {\n    ").append(s.call).append("\n}\n\n");
        }
        out.append("internal object ").append(result.registry).append(" : dev.dootah.runtime.NativeAdapterRegistry {\n");
        out.append("    override val signatures: kotlin.collections.Map<kotlin.String, kotlin.String> = kotlin.collections.mapOf(\n");
        for (Shape s : result.adapters) out.append("        kotlin.Pair(\"").append(s.id).append("\", \"").append(s.signature()).append("\"),\n");
        out.append("    )\n\n    @androidx.compose.runtime.Composable\n");
        out.append("    override fun render(id: kotlin.String, arguments: kotlin.collections.List<kotlin.Any?>) {\n        when (id) {\n");
        for (Shape s : result.adapters) {
            List<String> values = new ArrayList<>();
            List<String> types = s.types();
            for (int i = 0; i < types.size(); i++) values.add("arguments[" + i + "] as " + kotlinType(types.get(i)));
            out.append("            \"").append(s.id).append("\" -> ").append(s.method).append('(').append(String.join(", ", values)).append(")\n");
        }
        out.append("            else -> kotlin.error(\"Unknown native adapter\")\n        }\n    }\n}\n");
        return out.toString();
    }

    /** The single class the SDK links against. Always generated, even when empty. */
    public static String index(List<Result> results) {
        List<String> registries = new ArrayList<>();
        for (Result r : results) if (!r.adapters.isEmpty()) registries.add(r.qualifiedRegistry());
        Collections.sort(registries);
        StringBuilder out = new StringBuilder("// Generated by Dootah. Do not edit.\npackage ").append(INDEX_PACKAGE).append("\n\n");
        out.append("public object ").append(INDEX).append(" {\n    @kotlin.jvm.JvmStatic\n    public fun registry(): kotlin.Any = ");
        if (registries.isEmpty()) out.append("dev.dootah.runtime.NativeAdapterRegistry.Empty\n");
        else out.append("dev.dootah.runtime.NativeAdapterRegistry.of(\n        ").append(String.join(",\n        ", registries)).append(",\n    )\n");
        return out.append("}\n").toString();
    }
}
