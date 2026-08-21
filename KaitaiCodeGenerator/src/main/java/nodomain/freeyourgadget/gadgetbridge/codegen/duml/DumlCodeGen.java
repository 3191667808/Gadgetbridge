package nodomain.freeyourgadget.gadgetbridge.codegen.duml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiLintException;
import nodomain.freeyourgadget.gadgetbridge.codegen.kaitai.KaitaiLinter;

/**
 * Reads every {@code .ksy} file under the DUML schema directory, lints it
 * (generic checks via {@code codegen.kaitai.KaitaiLinter}, then DUML's own
 * cross-file cmd/cmdSet/direction collision check), and emits one Kotlin
 * source file per schema plus a small aggregator combining their decoder
 * maps by cmdSet. The actual byte-layout-to-Kotlin logic lives in the
 * generic {@code codegen.kaitai} package; this class and the rest of
 * {@code service.devices.dji.duml.codegen} only add DUML's dispatch wiring
 * on top (see {@link DumlSchemaParser}, {@link DumlKotlinEmitter}).
 *
 * <p>Usage: {@code DumlCodeGen <schemaDir> <generatedOutDir>}.
 */
public final class DumlCodeGen {
    public static void main(final String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: DumlCodeGen <schemaDir> <generatedOutDir>");
        }
        final Path schemaDir = Path.of(args[0]).resolve("duml");
        final Path outDir = Path.of(args[1]).resolve("nodomain/freeyourgadget/gadgetbridge/service/devices/dji/duml/messages");

        final Map<String, DumlSchema> schemasByFile = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(schemaDir)) {
            for (final Path file : files.filter(p -> p.toString().endsWith(".ksy")).sorted().toList()) {
                final String fileName = file.getFileName().toString();
                final Map<String, Object> raw = loadYaml(file);
                final DumlSchema schema = DumlSchemaParser.parse(fileName, raw);
                KaitaiLinter.lint(fileName, schema.generic());
                schemasByFile.put(fileName, schema);
            }
        }
        if (schemasByFile.isEmpty()) {
            throw new IllegalStateException("no .ksy files found under " + schemaDir);
        }
        DumlLinter.lintNoCrossFileCollisions(schemasByFile);

        Files.createDirectories(outDir);
        final List<DumlSchema> emitted = new ArrayList<>();
        for (final var e : schemasByFile.entrySet()) {
            final DumlSchema schema = e.getValue();
            final String source = DumlKotlinEmitter.emitSchemaFile(schema);
            Files.writeString(outDir.resolve("Generated" + schema.kotlinClassName() + ".kt"), source);
            emitted.add(schema);
        }

        Files.writeString(outDir.resolve("GeneratedDumlDecoders.kt"), DumlKotlinEmitter.emitAggregator(emitted));
        System.out.println("DumlCodeGen: generated " + (emitted.size() + 1) + " file(s) into " + outDir);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(final Path file) throws IOException {
        final Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        try (InputStream in = Files.newInputStream(file)) {
            final Object loaded = yaml.load(in);
            if (!(loaded instanceof Map)) {
                throw new KaitaiLintException(file.getFileName().toString(), "<root>", "expected a YAML mapping at the document root");
            }
            return (Map<String, Object>) loaded;
        }
    }
}
