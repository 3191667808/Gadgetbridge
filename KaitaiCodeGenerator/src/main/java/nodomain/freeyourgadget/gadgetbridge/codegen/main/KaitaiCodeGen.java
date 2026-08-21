package nodomain.freeyourgadget.gadgetbridge.codegen.main;

import java.io.IOException;

import nodomain.freeyourgadget.gadgetbridge.codegen.duml.DumlCodeGen;

public class KaitaiCodeGen {
    public static void main(final String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: KaitaiCodeGen <schemaDir> <generatedOutDir>");
        }

        DumlCodeGen.main(args);
    }
}
