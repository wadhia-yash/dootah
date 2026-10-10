package dev.dootah.adapters;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtPsiFactory;

/**
 * Build-time entry point, run in its own JVM by the Gradle plugin so the parser never shares a
 * classloader with the application's Kotlin Gradle plugin. Parser only: no compilation, FIR or IR.
 * Args: outputDir moduleDir sourceList reportFile enabled
 */
public final class AdapterGenMain {
    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("Usage: outputDir moduleDir sourceList reportFile enabled");
        File out = new File(args[0]);
        Path module = new File(args[1]).toPath().toRealPath();
        List<String> sources = Files.readAllLines(new File(args[2]).toPath(), StandardCharsets.UTF_8);
        boolean enabled = Boolean.parseBoolean(args[4]);
        List<AdapterGen.Result> results = new ArrayList<>();
        List<String> entries = new ArrayList<>();
        Disposable disposable = Disposer.newDisposable();
        try {
            if (enabled) {
                KotlinCoreEnvironment environment = KotlinCoreEnvironment.createForProduction(disposable,
                    new CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES);
                KtPsiFactory factory = new KtPsiFactory(environment.getProject(), false);
                Collections.sort(sources);
                for (String source : sources) {
                    if (source.isBlank()) continue;
                    Path file = new File(source).toPath().toRealPath();
                    // Only module sources: identities are module-relative, as in the retained release record.
                    if (!file.startsWith(module)) continue;
                    String path = module.relativize(file).toString().replace(File.separatorChar, '/');
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    KtFile kt = factory.createFile(file.getFileName().toString(), text);
                    if (PsiTreeUtil.findChildOfType(kt, PsiErrorElement.class) != null) {
                        entries.add(entry(path, 0, List.of("Kotlin syntax not understood; file stays native")));
                        continue;
                    }
                    AdapterGen.Result result = AdapterGen.generate(kt, path);
                    String generated = AdapterGen.render(kt, result);
                    if (generated != null) {
                        File target = new File(out, (result.packageName.isEmpty() ? "" : result.packageName.replace('.', '/') + "/") + result.facade + ".kt");
                        target.getParentFile().mkdirs();
                        Files.writeString(target.toPath(), generated, StandardCharsets.UTF_8);
                    }
                    if (!result.adapters.isEmpty() || !result.refused.isEmpty()) {
                        entries.add(entry(path, result.adapters.size(), result.refused));
                    }
                    results.add(result);
                }
            }
        } finally { Disposer.dispose(disposable); }
        File index = new File(out, AdapterGen.INDEX_PACKAGE.replace('.', '/') + "/" + AdapterGen.INDEX + ".kt");
        index.getParentFile().mkdirs();
        Files.writeString(index.toPath(), AdapterGen.index(results), StandardCharsets.UTF_8);
        int total = 0;
        for (AdapterGen.Result r : results) total += r.adapters.size();
        String report = "{\"schema\":1,\"files\":[" + String.join(",", entries) + "],\"enabled\":" + enabled + ",\"adapters\":" + total + "}\n";
        File reportFile = new File(args[3]);
        reportFile.getParentFile().mkdirs();
        Files.writeString(reportFile.toPath(), report, StandardCharsets.UTF_8);
    }

    private static String entry(String path, int adapters, List<String> refused) {
        StringBuilder report = new StringBuilder();
        report.append("{\"path\":").append(json(path)).append(",\"adapters\":").append(adapters).append(",\"refused\":[");
        for (int i = 0; i < refused.size(); i++) report.append(i == 0 ? "" : ",").append(json(refused.get(i)));
        return report.append("]}").toString();
    }

    private static String json(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}
